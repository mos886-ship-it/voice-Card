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
        if m.status == .received, Self.canReport { await process(m.id) }
        return m
    }

    // MARK: - AI 報告（里程碑 2：Gemini；Apple 裝置端 / 私有雲在里程碑 4）

    private var processing: Set<String> = []

    /// 目前能不能自動產生報告：有 Gemini 金鑰（Apple 引擎還沒做，先一律用 Gemini）。
    static var canReport: Bool { !(Keychain.get(Keychain.geminiKey) ?? "").isEmpty }

    func report(_ id: String) -> String? { try? String(contentsOf: Self.reportURL(id), encoding: .utf8) }
    func transcript(_ id: String) -> String? { try? String(contentsOf: Self.transcriptURL(id), encoding: .utf8) }

    /// 產生（或重新產生）報告：交給 GeminiJobs 在背景跑（App 暗了也會完成），結果回到 aiFinished / aiFailed。
    func process(_ id: String) async {
        guard !processing.contains(id), var m = load(id), FileManager.default.fileExists(atPath: Self.audioURL(id).path) else { return }
        let key = Keychain.get(Keychain.geminiKey) ?? ""
        guard !key.isEmpty else {
            m.status = .failed
            m.error = "還沒設定 Gemini API Key，請到「設定」貼上後按「重試」"
            save(m)
            return
        }
        processing.insert(id)
        defer { processing.remove(id) }
        m.status = .summarizing
        m.error = nil
        m.aiStep = "上傳錄音中"
        m.processStartedAt = Date()
        m.processEndedAt = nil
        save(m)
        PhoneLink.shared.sendStatus(id: id, text: "AI 分析中…")
        do {
            try await GeminiJobs.shared.start(meetingId: id, key: key)
        } catch {
            aiFailed(id, message: error.localizedDescription)
        }
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

    /// Gemini 完成（可能是 App 在背景被叫醒時）：存報告 / 逐字稿，更新標題、摘要、說話者，回報手錶。
    func aiFinished(_ id: String, report: GeneratedReport, transcript: String, model: String, truncated: Bool) {
        // 分析期間錄音被刪掉了：不要再寫檔（不然會把刪掉的錄音又建回來）
        guard var m = load(id) else { return }
        var text = transcript
        if truncated {
            text += "\n\n（錄音太長，Gemini 一次輸出不完，後面的逐字稿被截斷；長錄音分段分析在之後的版本加上）"
        }
        do {
            try report.markdown.write(to: Self.reportURL(id), atomically: true, encoding: .utf8)
            try text.write(to: Self.transcriptURL(id), atomically: true, encoding: .utf8)
        } catch {
            aiFailed(id, message: "報告存檔失敗：\(error.localizedDescription)")
            return
        }
        if m.titleEdited != true { m.title = report.title }
        m.summary = report.shortSummary
        m.aiModel = model
        // 每次重新產生，說話者代號可能重編 → 名字一律用這次報告的表格（使用者手動改名在之後的版本加上）
        m.speakerNames = Speakers.names(fromReport: report.markdown)
        m.status = .done
        m.error = nil
        m.aiStep = nil
        m.processEndedAt = Date()
        save(m)
        PhoneLink.shared.sendStatus(id: id, text: "報告完成：\(m.title)")
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
