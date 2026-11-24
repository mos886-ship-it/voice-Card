import Foundation

/// 錄音資料（對應 Android `MeetingStore`）：Application Support/meetings/<id>/
///   meta.json、segments/<seq>.m4a、audio.m4a（之後：transcript.txt、report.md、geo.json）
@MainActor
final class MeetingStore: ObservableObject {
    static let shared = MeetingStore()

    @Published private(set) var meetings: [Meeting] = []

    nonisolated static let root: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("meetings", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    nonisolated static func dir(_ id: String) -> URL { root.appendingPathComponent(id, isDirectory: true) }
    nonisolated static func audioURL(_ id: String) -> URL { dir(id).appendingPathComponent("audio.m4a") }
    nonisolated static func reportURL(_ id: String) -> URL { dir(id).appendingPathComponent("report.md") }
    nonisolated static func transcriptURL(_ id: String) -> URL { dir(id).appendingPathComponent("transcript.txt") }
    /// 匯入的文字檔（會議紀錄 / 逐字稿）：沒有錄音，直接拿來寫報告。
    nonisolated static func textURL(_ id: String) -> URL { dir(id).appendingPathComponent("input.txt") }
    nonisolated static func isText(_ id: String) -> Bool { FileManager.default.fileExists(atPath: textURL(id).path) }
    nonisolated static func segmentsDir(_ id: String) -> URL { dir(id).appendingPathComponent("segments", isDirectory: true) }
    nonisolated static func segmentURL(_ id: String, _ seq: Int) -> URL { segmentsDir(id).appendingPathComponent("\(seq).m4a") }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .millisecondsSince1970
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return e
    }()
    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .millisecondsSince1970
        return d
    }()

    private var assembling: Set<String> = []

    init() {
        reload()
        Task { await recoverUnassembled() }
    }

    func reload() {
        let dirs = (try? FileManager.default.contentsOfDirectory(at: Self.root, includingPropertiesForKeys: nil)) ?? []
        meetings = dirs.compactMap { load($0.lastPathComponent) }.sorted { $0.createdAt > $1.createdAt }
    }

    func load(_ id: String) -> Meeting? {
        guard let data = try? Data(contentsOf: Self.dir(id).appendingPathComponent("meta.json")) else { return nil }
        return try? Self.decoder.decode(Meeting.self, from: data)
    }

    func save(_ m: Meeting) {
        try? FileManager.default.createDirectory(at: Self.dir(m.id), withIntermediateDirectories: true)
        if let data = try? Self.encoder.encode(m) {
            try? data.write(to: Self.dir(m.id).appendingPathComponent("meta.json"), options: .atomic)
        }
        if let i = meetings.firstIndex(where: { $0.id == m.id }) { meetings[i] = m } else {
            meetings.append(m)
            meetings.sort { $0.createdAt > $1.createdAt }
        }
    }

    func delete(_ id: String) {
        try? FileManager.default.removeItem(at: Self.dir(id))
        meetings.removeAll { $0.id == id }
    }

    /// WatchConnectivity 給的暫存檔在 delegate 回傳後就會被刪，所以要在 delegate 裡同步搬走。
    /// 重複收到同一段會直接覆蓋（安全）。
    nonisolated static func stash(_ file: URL, _ seg: Segment) throws {
        let dest = segmentURL(seg.recordingId, seg.seq)
        try FileManager.default.createDirectory(at: segmentsDir(seg.recordingId), withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: file, to: dest)
    }

    /// 一段到了：更新 meta；分段到齊就接成 audio.m4a。回傳到齊後的錄音（給 PhoneLink 回報手錶）。
    @discardableResult
    func segmentArrived(_ seg: Segment, source: String = "watch") async -> Meeting? {
        var m = load(seg.recordingId) ?? .new(id: seg.recordingId, source: source)
        m.segments[String(seg.seq)] = seg.samples
        if seg.last { m.lastSeq = seg.seq }
        m.durationSec = Double(m.segments.values.reduce(0, +)) / AudioSpec.sampleRate
        m.updatedAt = Date()
        save(m)
        guard m.isComplete, m.status != .received else { return nil }
        return await assemble(m, seqs: Array(0...(m.lastSeq ?? 0)))
    }

    /// 開 App 時補做：分段已經到齊、但上次合併到一半 App 被關掉的錄音。
    func recoverUnassembled() async {
        for m in meetings where m.status == .recording && m.isComplete {
            await assemble(m, seqs: Array(0...(m.lastSeq ?? 0)))
        }
        // 「分析中」超過 10 分鐘、系統也沒有這筆的背景任務 → 當作卡住，重新分析。
        // （剛啟動時不能馬上判斷：App 可能正是被系統叫醒來交結果的，那時任務清單是空的，會重複分析）
        for m in meetings where m.status == .summarizing {
            let started = m.processStartedAt ?? .distantPast
            if Date().timeIntervalSince(started) > 600, await !GeminiJobs.shared.hasTask(for: m.id) {
                await process(m.id)
            }
        }
    }

    /// 錄音卡在「錄音中」（例如手錶在最後一段傳到前被重置）：用已收到的分段完成。缺的段會跳過。
    func finishWithReceived(_ id: String) async {
        guard var m = load(id), m.status == .recording, !m.segments.isEmpty else { return }
        let seqs = m.segments.keys.compactMap(Int.init).sorted()
        m.lastSeq = seqs.last
        save(m)
        await assemble(m, seqs: seqs)
    }

    /// 分段多久沒有新的進來，就當作可能卡住（在詳情頁提供「用已收到的分段完成」）。
    static func looksStuck(_ m: Meeting, now: Date = Date()) -> Bool {
        guard m.status == .recording else { return false }
        let last = m.updatedAt ?? m.createdAt.addingTimeInterval(m.durationSec)
        return now.timeIntervalSince(last) > AudioSpec.segmentSeconds * 2 + 120
    }

    @discardableResult
    private func assemble(_ meeting: Meeting, seqs: [Int]) async -> Meeting? {
        guard !assembling.contains(meeting.id) else { return nil }
        assembling.insert(meeting.id)
        defer { assembling.remove(meeting.id) }

        var m = meeting
        let urls = seqs.map { Self.segmentURL(m.id, $0) }
        do {
            try await SegmentAssembler.assemble(urls, to: Self.audioURL(m.id))
            m.status = .received
            m.error = nil
            m.durationSec = Double(seqs.compactMap { m.segments[String($0)] }.reduce(0, +)) / AudioSpec.sampleRate
        } catch {
            NSLog("[MeetingStore] assemble failed: \(error)")
            m.status = .failed
            m.error = "分段合併失敗：\(error.localizedDescription)"
        }
        m.updatedAt = Date()
        save(m)
        // 直接接著開始 AI（在同一段背景時間內把上傳交給系統，App 暗著也能繼續）
        if m.status == .received, await Self.canReport() { await process(m.id) }
        return m
    }

    // MARK: - AI 報告
    //
    // 路由（2026-11-22 使用者決定）：
    // - 隱私模式（設定頁）→ 一律 Apple（不上傳）。
    // - 飲食 → Apple 優先（一個人說話、不需要分人）；沒有 Apple 模型才用 Gemini。
    // - 錄音 → Gemini（分說話者）；同時先用 Apple 秒出「預覽逐字稿」。
    //   沒金鑰 / Gemini 失敗（連不上、額度用完…）→ Apple 輔助。

    private var processing: Set<String> = []

    static let privacyKey = "privacy_mode"
    static var privacyMode: Bool { UserDefaults.standard.bool(forKey: privacyKey) }
    static var hasGeminiKey: Bool { !(Keychain.get(Keychain.geminiKey) ?? "").isEmpty }

    /// 能不能自動產生報告 / 逐字稿：設定了自架 Mac、有 Gemini 金鑰，或這支 iPhone 有 Apple 語音辨識。
    static func canReport() async -> Bool {
        if LocalAI.configured || (hasGeminiKey && !privacyMode) { return true }
        return await AppleAI.speechAvailable()
    }

    func report(_ id: String) -> String? { try? String(contentsOf: Self.reportURL(id), encoding: .utf8) }
    func transcript(_ id: String) -> String? { try? String(contentsOf: Self.transcriptURL(id), encoding: .utf8) }

    /// 產生（或重新產生）報告。
    func process(_ id: String) async {
        guard !processing.contains(id), let m = load(id),
              FileManager.default.fileExists(atPath: Self.audioURL(id).path) || Self.isText(id) else { return }
        if Self.isText(id) {   // 匯入的文字：Mac → Gemini；Apple 不處理文字
            if LocalAI.configured { await runLocal(id) }
            else if Self.hasGeminiKey && !Self.privacyMode { await runGeminiText(id) }
            else { aiFailed(id, message: "文字檔要用大D的主機 或 Gemini 產生報告：請到設定填好其中一個") }
            return
        }
        // 順序：飲食 → Apple（有模型時）；有設定自架 Mac → Mac；隱私模式 → Apple（不送第三方雲端）；
        //       有 Gemini 金鑰 → Gemini；都沒有 → Apple。隱私模式下自己的 Mac 可以用（錄音不出你的設備），Gemini 不行。
        if m.isFood && AppleAI.availableModel != nil {
            await runApple(id, note: nil)
        } else if LocalAI.configured {
            await runLocal(id)
        } else if Self.privacyMode {
            await runApple(id, note: "隱私模式：不上傳到雲端，在手機上處理")
        } else if Self.hasGeminiKey {
            await runGemini(id)
        } else {
            await runApple(id, note: nil)
        }
    }

    /// 自架：錄音傳給這台 Mac（FunASR＋Qwen），結果回到 geminiReplied（同格式）/ localFailed。
    private func runLocal(_ id: String) async {
        guard var m = load(id) else { return }
        processing.insert(id)
        defer { processing.remove(id) }
        begin(&m, step: "傳給大D的主機 處理中")
        PhoneLink.shared.sendStatus(id: id, text: "AI 分析中…")
        // 先花幾秒確認 Mac 連得到：背景傳輸遇到連不到的主機不會失敗，只會一直等
        do {
            _ = try await LocalAI.health(url: LocalAI.url, token: LocalAI.token)
        } catch {
            await localFailed(id, message: error.localizedDescription)
            return
        }
        // Mac 在跑的同時，Apple 先出預覽逐字稿（Apple 不會台語：台語錄音不出預覽）
        if !m.isFood && !Self.isText(id) && m.speechLang == .zh { Task { await self.preview(id) } }   // 台語、客語都不出
        do {
            try GeminiJobs.shared.startLocal(meetingId: id, kind: m.isFood ? "food" : "meeting",
                                             recordedAt: Fmt.stamp(m.createdAt, "yyyy/MM/dd HH:mm"), durationSec: m.durationSec,
                                             lang: m.speechLang, dialect: m.hakkaDialect)
        } catch {
            await localFailed(id, message: error.localizedDescription)
        }
    }

    /// 這台 Mac 連不到 / 失敗：有 Gemini 金鑰就改用 Gemini，否則 Apple 輔助。
    func localFailed(_ id: String, message: String) async {
        guard var m = load(id) else { return }
        NSLog("[MeetingStore] local Mac failed (\(message)), falling back")
        if Self.isText(id) {
            if Self.hasGeminiKey && !Self.privacyMode {
                if var m = load(id) { m.engineNote = "大D的主機 連不到（\(message)），改用 Gemini"; save(m) }
                await runGeminiText(id)
            } else {
                aiFailed(id, message: message)
            }
            return
        }
        if Self.hasGeminiKey && !Self.privacyMode {
            m.engineNote = "大D的主機 沒有完成（\(message)），改用 Gemini"
            save(m)
            await runGemini(id)
        } else {
            // 隱私模式或沒有金鑰：有 Apple 語音就改用 Apple，否則標失敗（隱私模式絕不送 Gemini）
            await geminiFailed(id, message: message)
        }
    }

    /// Gemini：交給 GeminiJobs 在背景跑（App 暗了也會完成），結果回到 geminiReplied / geminiFailed。
    private func runGemini(_ id: String) async {
        guard var m = load(id) else { return }
        processing.insert(id)
        defer { processing.remove(id) }
        begin(&m, step: "上傳錄音中")
        PhoneLink.shared.sendStatus(id: id, text: "AI 分析中…")
        // 即時預覽：Gemini 在跑的同時，先用 Apple 秒出逐字稿（不分說話者），Gemini 完成後換掉
        if !m.isFood && m.speechLang == .zh {   // Apple 不會台語：台語錄音不出預覽
            Task { await self.preview(id) }
        }
        do {
            try await GeminiJobs.shared.start(meetingId: id, key: Keychain.get(Keychain.geminiKey) ?? "")
        } catch {
            await geminiFailed(id, message: error.localizedDescription)
        }
    }

    /// 文字檔用 Gemini（前景、純文字，不用上傳檔案）。
    private func runGeminiText(_ id: String) async {
        guard var m = load(id), let text = try? String(contentsOf: Self.textURL(id), encoding: .utf8) else { return }
        processing.insert(id)
        defer { processing.remove(id) }
        begin(&m, step: "AI 整理中")
        do {
            let r = try await GeminiClient(apiKey: Keychain.get(Keychain.geminiKey) ?? "")
                .generateText(text, recordedAt: Fmt.stamp(m.createdAt, "yyyy/MM/dd HH:mm"))
            geminiReplied(id, text: r.text, model: r.model, truncated: false)
        } catch {
            aiFailed(id, message: "Gemini：\(error.localizedDescription)")
        }
    }

    private func preview(_ id: String) async {
        guard await AppleAI.speechAvailable(),
              let lines = try? await AppleAI.transcribe(Self.audioURL(id)), !lines.isEmpty,
              var m = load(id), m.status == .summarizing else { return }   // Gemini 已經完成就不要蓋掉
        try? AppleAI.transcriptText(lines).write(to: Self.transcriptURL(id), atomically: true, encoding: .utf8)
        m.transcriptPreview = true
        save(m)
    }

    /// Apple：逐字稿（必有）＋整理 / 飲食估算（有 Apple 模型時）。在手機上跑，用背景時間撐住。
    private func runApple(_ id: String, note: String?) async {
        guard var m = load(id) else { return }
        guard await AppleAI.speechAvailable() else {
            aiFailed(id, message: Self.hasGeminiKey
                     ? "這支 iPhone 沒有 Apple 語音辨識（需要 iOS 26 以上），隱私模式無法使用；請關掉隱私模式改用 Gemini"
                     : "還沒設定 Gemini API Key，而這支 iPhone 沒有 Apple 語音辨識；請到「設定」貼上金鑰後按「重試」")
            return
        }
        processing.insert(id)
        defer { processing.remove(id) }
        begin(&m, step: "Apple 語音辨識中")
        m.engineNote = note
        save(m)
        PhoneLink.shared.sendStatus(id: id, text: "AI 分析中…")
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            GeminiJobs.withBackgroundTime("apple-\(id)") {
                await self.appleWork(id)
                done.resume()
            }
        }
    }

    private func appleWork(_ id: String) async {
        do {
            let lines = try await AppleAI.transcribe(Self.audioURL(id))
            let text = AppleAI.transcriptText(lines)
            guard var m = load(id) else { return }
            try text.write(to: Self.transcriptURL(id), atomically: true, encoding: .utf8)
            m.transcriptPreview = false
            var model = "apple-speech"
            var report: GeneratedReport?
            if AppleAI.availableModel != nil, !lines.isEmpty {
                setAiStep(id, "Apple 整理中")
                let recordedAt = Fmt.stamp(m.createdAt, "yyyy/MM/dd HH:mm")
                if m.isFood {
                    let (r, n, mdl) = try await AppleAI.food(transcript: text, recordedAt: recordedAt)
                    report = r; m.nutrition = n; model += "|" + mdl
                } else {
                    let (r, mdl) = try await AppleAI.report(transcript: text, durationSec: m.durationSec, recordedAt: recordedAt)
                    report = r; model += "|" + mdl
                }
            }
            guard var latest = load(id) else { return }   // 處理中被刪掉
            if let report {
                try report.markdown.write(to: Self.reportURL(id), atomically: true, encoding: .utf8)
                if latest.titleEdited != true { latest.title = report.title }
                latest.summary = report.shortSummary
            } else {
                try? FileManager.default.removeItem(at: Self.reportURL(id))
                latest.summary = lines.first?.text
            }
            latest.nutrition = m.nutrition
            latest.transcriptPreview = false
            latest.speakerNames = [:]
            latest.aiModel = model
            finish(&latest)
            PhoneLink.shared.sendStatus(id: id, text: "報告完成：\(latest.title)")
        } catch {
            aiFailed(id, message: "Apple 處理失敗：\(error.localizedDescription)")
        }
    }

    private func begin(_ m: inout Meeting, step: String) {
        m.status = .summarizing
        m.error = nil
        m.aiStep = step
        m.processStartedAt = Date()
        m.processEndedAt = nil
        save(m)
    }

    private func finish(_ m: inout Meeting) {
        m.status = .done
        m.error = nil
        m.aiStep = nil
        m.processEndedAt = Date()
        save(m)
    }

    /// 背景傳輸被取消（強制關閉 App）→ 重新分析。
    func restartAI(_ id: String) async {
        guard let m = load(id), m.status == .summarizing else { return }
        await process(id)
    }

    func setAiStep(_ id: String, _ step: String) {
        guard var m = load(id), m.status == .summarizing else { return }
        m.aiStep = step
        save(m)
    }

    /// Gemini 完成（可能是 App 在背景被叫醒時）：存報告 / 逐字稿，更新標題、摘要、說話者、營養，回報手錶。
    func geminiReplied(_ id: String, text: String, model: String, truncated: Bool) {
        guard var m = load(id) else { return }   // 分析期間被刪掉了：不要寫檔
        var (report, transcript) = GeminiClient.split(text)
        if m.isFood {
            m.nutrition = GeminiClient.parseNutrition(text)
            report.markdown = GeminiClient.stripNutrition(report.markdown)
        }
        if truncated {
            transcript += "\n\n（錄音太長，Gemini 一次輸出不完，後面的逐字稿被截斷；長錄音分段分析在之後的版本加上）"
        }
        do {
            try report.markdown.write(to: Self.reportURL(id), atomically: true, encoding: .utf8)
            try transcript.write(to: Self.transcriptURL(id), atomically: true, encoding: .utf8)
        } catch {
            aiFailed(id, message: "報告存檔失敗：\(error.localizedDescription)")
            return
        }
        if m.titleEdited != true { m.title = report.title }
        m.summary = report.shortSummary
        m.aiModel = model
        // 每次重新產生，說話者代號可能重編 → 名字一律用這次報告的表格
        m.speakerNames = Speakers.names(fromReport: report.markdown)
        m.transcriptPreview = false
        if !(m.engineNote ?? "").contains("改用 Gemini") { m.engineNote = nil }   // Mac 失敗改用 Gemini 的說明保留
        finish(&m)
        PhoneLink.shared.sendStatus(id: id, text: "報告完成：\(m.title)")
    }

    /// Gemini 失敗（連不上、額度用完…）：這支 iPhone 有 Apple 語音辨識就改用 Apple 輔助，否則標失敗。
    func geminiFailed(_ id: String, message: String) async {
        guard load(id) != nil else { return }
        if await AppleAI.speechAvailable() {
            NSLog("[MeetingStore] Gemini failed (\(message)), falling back to Apple")
            await runApple(id, note: "Gemini 沒有完成（\(message)），改用 Apple 在手機上整理，沒有分說話者")
        } else {
            aiFailed(id, message: message)
        }
    }

    func aiFailed(_ id: String, message: String) {
        guard var m = load(id) else { return } // 被刪掉了
        m.status = .failed
        m.error = message
        m.aiStep = nil
        m.processEndedAt = Date()
        save(m)
        PhoneLink.shared.sendStatus(id: id, text: "AI 分析失敗")
    }
}
