import AVFoundation
import Speech
import SwiftUI
#if canImport(Translation)
import Translation
#endif

/// 即時翻譯（字幕，照 Android `LiveCaptions.kt`）：手機錄音的聲音同時送進 Apple 語音辨識（能在手機上辨識就在手機上），
/// 一句辨識完就用 Apple「翻譯」框架翻好（裝置端，第一次用某個語言系統會問要不要下載）。只是輔助，完整報告照舊。
///
/// - `feed`：PhoneRecorder 的音訊 tap 每來一段就丟進來；沒開字幕時什麼都不做。
/// - 辨識一段結束或出錯 → 自動接新的一段，錄音不中斷。錄音停止時字幕一起停。
/// - 每句翻好推給手錶（PhoneLink.sendCaption）。
@MainActor
final class LiveCaptions: ObservableObject {
    static let shared = LiveCaptions()
    private static let maxLines = 200

    /// 一句：原文、譯文（nil = 翻譯中）、是不是還在說（辨識中的暫時結果）。
    struct Line: Identifiable, Equatable {
        let id: Int
        var original: String
        var translated: String?
        var partial = false
        /// 還在說的那行的暫時譯文（邊說邊翻）
        var draft: String?
    }

    /// 翻譯成：名稱用該語言自己的寫法。
    enum Target: String, CaseIterable, Identifiable {
        case zh, en, ko, ja, vi
        var id: String { rawValue }
        var label: String {
            switch self { case .zh: "繁體中文"; case .en: "English"; case .ko: "한국어"; case .ja: "日本語"; case .vi: "Tiếng Việt" }
        }
        /// 翻譯框架用的語言
        var language: Locale.Language {
            switch self {
            case .zh: Locale.Language(identifier: "zh-Hant")
            case .en: Locale.Language(identifier: "en")
            case .ko: Locale.Language(identifier: "ko")
            case .ja: Locale.Language(identifier: "ja")
            case .vi: Locale.Language(identifier: "vi")
            }
        }
    }

    /// 翻譯框架認得的來源語言；台語、客語、粵語沒有（手機也辨識不了）。
    static func source(_ l: SpeechLang) -> Locale.Language? {
        switch l {
        case .zh: Locale.Language(identifier: "zh-Hant")
        case .en: Locale.Language(identifier: "en")
        case .ja: Locale.Language(identifier: "ja")
        case .ko: Locale.Language(identifier: "ko")
        case .vi: Locale.Language(identifier: "vi")
        case .th: Locale.Language(identifier: "th")
        case .id: Locale.Language(identifier: "id")
        default: nil
        }
    }

    static func supported(_ l: SpeechLang) -> Bool { source(l) != nil }

    /// 預設翻成什麼：介面語言；跟來源一樣就換一個（中文 ↔ 英文）。
    /// 翻譯設定：預設翻成（空 = 跟介面）、字幕大小（0～3）、手錶字幕
    static let targetKey = "translate_target", textSizeKey = "translate_text_size", watchKey = "translate_watch"
    static var watchCaptions: Bool { UserDefaults.standard.object(forKey: watchKey) as? Bool ?? true }

    static func defaultTarget(_ source: SpeechLang, uiLang: String) -> Target {
        let saved = UserDefaults.standard.string(forKey: targetKey).flatMap(Target.init(rawValue:))
        let ui = saved ?? Target(rawValue: uiLang) ?? .en
        let same = (source == .zh && ui == .zh) || source.rawValue == ui.rawValue
        return !same ? ui : (ui == .zh ? .en : .zh)
    }

    @Published private(set) var running = false
    @Published private(set) var lines: [Line] = []
    @Published private(set) var status: String?
    @Published private(set) var error: String?
    /// 說的（辨識代碼，例如 zh-TW）、翻成（翻譯代碼，例如 en、zh-Hant）
    @Published private(set) var source: String = "zh-TW"
    @Published private(set) var target: String = "en"
    /// 只翻譯（自己開麥克風，沒有錄音）
    @Published private(set) var micMode = false
    /// 換一次語言就加一，讓翻譯工作（LiveTranslateHost）重新開一個 session
    @Published private(set) var generation = 0

    private var nextId = 1
    /// 這次翻譯（開始到停止）：存翻譯記錄用
    private var session: (id: String, start: Date, firstLine: Int, meetingId: String?)?
    private var recognizer: SFSpeechRecognizer?
    private var task: SFSpeechRecognitionTask?
    /// 這一段辨識已經變成整句（送去翻譯）的字數
    private var committed = 0
    /// 連續出錯、而且什麼都沒聽到的次數（3 次就停，照 Android）
    private var failures = 0
    /// 手機上辨識失敗過 → 這次改用 Apple 線上辨識（照 Android：先裝置端，不行再換）
    private var online = false
    /// 第幾段辨識（舊的一段結束後還會回呼，用這個忽略）
    private var segment = 0
    private var partialText = ""
    private var silence: Task<Void, Never>?
    /// 等著翻譯的句子（行 id, 原文）
    private(set) var queue: [(Int, String)] = []
    /// 還在說的那行要暫時翻的字（行 id, 原文）；新的蓋掉舊的
    private(set) var draftQueue: (Int, String)?
    private var lastDraftAt = Date.distantPast
    /// 只翻譯模式自己的麥克風
    private let micEngine = AVAudioEngine()
    private var micRunning = false
    var queueChanged: (() -> Void)?

    /// 音訊 tap（錄音執行緒）→ 目前的辨識請求。用鎖保護，tap 不能等主執行緒。
    nonisolated private static let lock = NSLock()
    nonisolated(unsafe) private static var request: SFSpeechAudioBufferRecognitionRequest?

    /// PhoneRecorder 的音訊 tap 呼叫：開著字幕才送。
    nonisolated static func feed(_ buffer: AVAudioPCMBuffer) {
        if Speaker.busy { return }   // 正在念譯文：聽到的是自己的聲音
        lock.lock(); let r = request; lock.unlock()
        r?.append(buffer)
    }

    // MARK: - 開始 / 停止

    /// 舊的入口（錄音語言、5 種翻成）：debug 餵音檔用
    func start(source: SpeechLang, target: Target) {
        start(sourceId: TranslateLangs.sourceId(source), targetId: target == .zh ? "zh-Hant" : target.rawValue)
    }

    /// 開始翻譯。錄音中：用錄音的聲音；沒在錄音：自己開麥克風（只翻譯）。keepLines：對調方向時字幕保留。
    func start(sourceId: String, targetId: String) {
        if running { stop() }
        source = sourceId
        target = targetId
        error = nil
        status = nil
        running = true
        failures = 0
        var mid: String?
        if case let .recording(id, _) = PhoneRecorder.shared.state, !id.hasPrefix(Meeting.foodPrefix) { mid = id }
        micMode = !PhoneRecorder.shared.isRecording
        session = (TranslationStore.newId(), Date(), nextId, mid)
        online = false
        generation += 1
        SFSpeechRecognizer.requestAuthorization { auth in
            Task { @MainActor in
                guard self.running else { return }
                guard auth == .authorized else {
                    self.error = "沒有語音辨識權限：到 iPhone 設定 → 大D 打開「語音辨識」".tr
                    self.running = false
                    return
                }
                guard let r = SFSpeechRecognizer(locale: Locale(identifier: sourceId)), r.isAvailable else {
                    self.error = "這支 iPhone 沒有這個語言的語音辨識".tr
                    self.running = false
                    return
                }
                if !r.supportsOnDeviceRecognition && !Self.allowOnline {
                    self.error = "這個語言沒有手機上的語音辨識；要用的話在翻譯設定打開「允許線上辨識」".tr
                    self.running = false
                    return
                }
                self.recognizer = r
                self.status = r.supportsOnDeviceRecognition ? nil
                    : "這個語言沒有手機上的語音辨識，聲音會傳到 Apple 辨識".tr
                if self.micMode && !self.startMic() { return }
                UIApplication.shared.isIdleTimerDisabled = true   // 翻譯中螢幕保持亮著
                WidgetBridge.translateStarted(pair: TranslateLangs.name(sourceId) + " → " + TranslateLangs.name(targetId))
                self.startSegment()
            }
        }
        if #unavailable(iOS 18.0) { status = "翻譯需要 iOS 18 以上；這支 iPhone 只顯示原文".tr }
    }

    /// 對調方向（翻譯中也可以）：字幕保留，記錄各自分開存。
    func swap() {
        guard let newSource = TranslateLangs.speechLocale(for: target) else {
            status = "手機辨識不了「{0}」，沒辦法對調".trf(TranslateLangs.name(target))
            return
        }
        let newTarget = TranslateLangs.translationId(source: source)
        TranslateLangs.source = newSource
        UserDefaults.standard.set(newTarget, forKey: Self.targetKey)
        if running { start(sourceId: newSource, targetId: newTarget) }
        else { source = newSource; target = newTarget }
    }

    func clear() { lines.removeAll() }

    /// 設定：允許線上辨識（手機上辨識不了的語言送 Apple）、自動朗讀
    static let onlineKey = "translate_online", readAloudKey = "translate_read_aloud", showOriginalKey = "translate_show_original"
    static var allowOnline: Bool { UserDefaults.standard.object(forKey: onlineKey) as? Bool ?? true }
    static var readAloud: Bool { UserDefaults.standard.bool(forKey: readAloudKey) }

    // MARK: - 只翻譯：自己開麥克風

    private func startMic() -> Bool {
        let s = AVAudioSession.sharedInstance()
        do {
            try s.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth, .allowBluetoothA2DP])
            try s.setActive(true)
            let input = micEngine.inputNode
            let fmt = input.outputFormat(forBus: 0)
            input.removeTap(onBus: 0)
            input.installTap(onBus: 0, bufferSize: 4096, format: fmt) { buf, _ in LiveCaptions.feed(buf) }
            micEngine.prepare()
            try micEngine.start()
            micRunning = true
            return true
        } catch {
            self.error = "麥克風打不開：{0}".trf(error.localizedDescription)
            running = false
            return false
        }
    }

    private func stopMic() {
        guard micRunning else { return }
        micRunning = false
        micEngine.inputNode.removeTap(onBus: 0)
        micEngine.stop()
        if !PhoneRecorder.shared.isRecording {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    func stop() {
        guard running else { return }
        running = false
        stopMic()
        UIApplication.shared.isIdleTimerDisabled = false
        WidgetBridge.translateStopped()
        silence?.cancel()
        commitRemainder()
        endSegment()
        lines.removeAll { $0.partial }
        PhoneLink.shared.sendCaption("")
        saveSession()
    }

    /// 存翻譯記錄：停止時存一次，3 秒後補存晚到的翻譯（同一個 id 覆寫）。
    private func saveSession() {
        guard let s = session, TranslationStore.autoSave else { session = nil; return }
        session = nil
        let src = source, tgt = target
        func snapshot() -> TranslationRecord {
            TranslationRecord(id: s.id, start: s.start, end: Date(), source: src, target: tgt,
                              lines: lines.filter { $0.id >= s.firstLine && !$0.partial }
                                  .map { .init(original: $0.original, translated: $0.translated) },
                              meetingId: s.meetingId, reportId: nil)
        }
        let first = snapshot()
        TranslationStore.shared.save(first)
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
            var later = snapshot()
            later.end = first.end
            if later != first { TranslationStore.shared.save(later) }
        }
    }

    /// 錄音停止：字幕一起停。
    func recordingStopped() { if running && !micMode { stop() } }

    // MARK: - 辨識

    private func startSegment() {
        guard running, let recognizer else { return }
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.addsPunctuation = true
        if recognizer.supportsOnDeviceRecognition && !online { req.requiresOnDeviceRecognition = true }
        let onDevice = req.requiresOnDeviceRecognition
        Self.lock.lock(); Self.request = req; Self.lock.unlock()
        committed = 0
        partialText = ""
        segment += 1
        let seg = segment
        task = recognizer.recognitionTask(with: req) { [weak self] result, err in
            let text = result?.bestTranscription.formattedString
            let final = result?.isFinal ?? false
            Task { @MainActor in
                guard let self, self.running, seg == self.segment else { return }
                if let text {
                    self.failures = 0
                    self.update(text, final: final)
                }
                // 1110 = 一段時間沒人說話、216 / 301 = 我們自己取消：都不算失敗
                if let err { NSLog("[LiveCaptions] onDevice=\(onDevice) error \((err as NSError).domain) \((err as NSError).code): \(err.localizedDescription)") }
                if let err, text == nil, ![1110, 216, 301].contains((err as NSError).code) {
                    if onDevice && !self.online {
                        // 手機上的辨識起不來：改用 Apple 線上辨識再試，不算一次失敗
                        self.online = true
                        self.status = "這個語言沒有手機上的語音辨識，聲音會傳到 Apple 辨識".tr
                        self.endSegment()
                        self.startSegment()
                        return
                    }
                    self.failures += 1
                    if self.failures >= 3 {
                        // 錄音不受影響，只是沒有字幕
                        self.error = "手機的語音辨識沒辦法邊錄邊辨識（錯誤 {0}）。錄音照常，錄完的報告不受影響。".trf((err as NSError).code)
                        self.stop()
                        return
                    }
                }
                if final || err != nil {
                    // 一段結束（系統一段大約 1 分鐘）或出錯：收尾，接新的一段
                    self.commitRemainder()
                    self.endSegment()
                    try? await Task.sleep(nanoseconds: 300_000_000)
                    self.startSegment()
                }
            }
        }
    }

    private func endSegment() {
        segment += 1
        Self.lock.lock(); let r = Self.request; Self.request = nil; Self.lock.unlock()
        r?.endAudio()
        task?.cancel()
        task = nil
    }

    /// 這一段到目前為止的辨識結果：已經整句的部分送去翻譯，剩下的顯示成「還在說」。
    private func update(_ text: String, final: Bool) {
        guard text.count >= committed else { return }
        var rest = String(text.dropFirst(committed))
        // 句尾（。！？!?.）之前的都算整句
        while let r = rest.range(of: #"^.*?[。！？!?](\s*)"#, options: .regularExpression) ?? englishSentence(rest) {
            let sentence = String(rest[r]).trimmingCharacters(in: .whitespaces)
            committed += rest.distance(from: rest.startIndex, to: r.upperBound)
            rest = String(rest[r.upperBound...])
            commit(sentence)
        }
        // 一直沒停頓又太長（中日韓約 40 字、其他約 20 個字）：在逗號或空白處硬切
        if let cut = Self.backstop(rest) {
            let sentence = String(rest[..<cut]).trimmingCharacters(in: .whitespaces)
            committed += rest.distance(from: rest.startIndex, to: cut)
            rest = String(rest[cut...])
            if !sentence.isEmpty { commit(sentence) }
        }
        partialText = rest
        setPartial(rest)
        requestDraft()
        // 主要靠停頓切句：文字約 0.7 秒沒變（只翻譯模式 1 秒）就把這段定下來翻
        silence?.cancel()
        if !rest.trimmingCharacters(in: .whitespaces).isEmpty {
            let wait: UInt64 = micMode ? 1_000_000_000 : 700_000_000
            silence = Task { [weak self] in
                try? await Task.sleep(nanoseconds: wait)
                guard !Task.isCancelled else { return }
                self?.commitRemainder()
            }
        }
    }

    /// 太長了要切在哪裡（nil = 還不用切）。
    static func backstop(_ s: String) -> String.Index? {
        let cjk = s.unicodeScalars.contains { $0.properties.isIdeographic || (0xAC00...0xD7A3).contains($0.value) || (0x3040...0x30FF).contains($0.value) }
        if cjk {
            guard s.count > 40 else { return nil }
            let head = s.prefix(40)
            if let i = head.lastIndex(where: { "，、,；; ".contains($0) }), i > head.startIndex { return s.index(after: i) }
            return s.index(s.startIndex, offsetBy: 40)
        }
        let words = s.split(separator: " ", omittingEmptySubsequences: true)
        guard words.count > 20 else { return nil }
        let head = s[..<words[19].endIndex]
        if let i = head.lastIndex(of: ","), i > head.startIndex { return s.index(after: i) }
        return words[19].endIndex
    }

    /// 還在說的那行：最多每 0.8 秒送一次暫時翻譯。
    private func requestDraft() {
        guard let l = lines.last(where: { $0.partial }), l.original.count >= 4,
              Date().timeIntervalSince(lastDraftAt) > 0.8 else { return }
        lastDraftAt = Date()
        draftQueue = (l.id, l.original)
        queueChanged?()
    }

    func takeDraft() -> (Int, String)? { defer { draftQueue = nil }; return draftQueue }

    func drafted(_ id: Int, _ text: String) {
        guard let i = lines.firstIndex(where: { $0.id == id && $0.partial }) else { return }
        lines[i].draft = text
    }

    /// 英文句點：後面有空白才算（避免 3.5、U.S. 這種）
    private func englishSentence(_ s: String) -> Range<String.Index>? {
        s.range(of: #"^.*?[a-zA-Z0-9][.](\s+)"#, options: .regularExpression)
    }

    private func commitRemainder() {
        let t = partialText.trimmingCharacters(in: .whitespaces)
        committed += partialText.count
        partialText = ""
        setPartial("")
        if !t.isEmpty { commit(t) }
    }

    private func setPartial(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespaces)
        if let i = lines.lastIndex(where: { $0.partial }) {
            if t.isEmpty { lines.remove(at: i) } else { lines[i].original = t }
        } else if !t.isEmpty {
            lines.append(Line(id: nextId, original: t, translated: nil, partial: true))
            nextId += 1
        }
    }

    private func commit(_ sentence: String) {
        lines.removeAll { $0.partial }
        let id = nextId
        nextId += 1
        lines.append(Line(id: id, original: sentence, translated: nil))
        if lines.count > Self.maxLines { lines.removeFirst(lines.count - Self.maxLines) }
        if !partialText.isEmpty { setPartial(partialText) }
        queue.append((id, sentence))
        queueChanged?()
    }

    #if DEBUG
    /// 測試用（模擬器沒辦法說話）：把一個音檔照實際速度餵進辨識。
    /// 放 Documents/debug_caption.json（見 DylanApp）再開 App。
    func debugFeed(_ url: URL) async {
        guard let file = try? AVAudioFile(forReading: url) else { status = "debug: 讀不到 \(url.lastPathComponent)"; return }
        for _ in 0..<50 where Self.request == nil { try? await Task.sleep(nanoseconds: 100_000_000) }
        let frames: AVAudioFrameCount = 4096
        while running, let buf = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: frames) {
            guard (try? file.read(into: buf, frameCount: frames)) != nil, buf.frameLength > 0 else { break }
            Self.feed(buf)
            try? await Task.sleep(nanoseconds: UInt64(Double(buf.frameLength) / file.processingFormat.sampleRate * 1_000_000_000))
        }
        // 尾巴留一點靜音，讓最後一句收尾
        try? await Task.sleep(nanoseconds: 2_000_000_000)
        commitRemainder()
    }
    #endif

    // MARK: - 翻譯（LiveTranslateHost 拿 session 來翻）

    func takeQueue() -> [(Int, String)] { defer { queue = [] }; return queue }

    func translated(_ id: Int, _ text: String) {
        guard let i = lines.firstIndex(where: { $0.id == id }) else { return }
        lines[i].translated = text
        if Self.watchCaptions { PhoneLink.shared.sendCaption(text) }
        if Self.readAloud { Speaker.shared.speak(text, lang: target) }
        WidgetBridge.translated(text, original: lines[i].original)
    }

    func translationFailed(_ message: String) {
        status = "翻譯失敗：{0}".trf(message)
    }

    /// 不能翻譯（iOS 18 以下）：只顯示原文
    func showOriginalOnly(_ id: Int) {
        guard let i = lines.firstIndex(where: { $0.id == id }) else { return }
        lines[i].translated = ""
    }
}

/// 掛在 App 最外層（不會跟著換頁消失）：iOS 18 以上用 `.translationTask` 拿翻譯 session，把佇列裡的句子一句句翻。
struct LiveTranslateHost: View {
    @ObservedObject private var captions = LiveCaptions.shared

    var body: some View {
        if #available(iOS 18.0, *) {
            Translator(captions: captions)
        } else {
            Color.clear.onReceive(captions.$lines) { _ in
                for (id, _) in captions.takeQueue() { captions.showOriginalOnly(id) }
            }
        }
    }

    @available(iOS 18.0, *)
    private struct Translator: View {
        @ObservedObject var captions: LiveCaptions
        @State private var config: TranslationSession.Configuration?

        var body: some View {
            Color.clear
                .translationTask(config) { session in
                    // 這個 session 一直用到換語言或停止；有新句子就翻
                    while captions.running {
                        let items = captions.takeQueue()
                        if items.isEmpty, let (id, text) = captions.takeDraft(),
                           let r = try? await session.translate(text) {
                            captions.drafted(id, r.targetText)
                        }
                        for (id, text) in items {
                            do {
                                let r = try await session.translate(text)
                                captions.translated(id, r.targetText)
                            } catch {
                                captions.translationFailed(error.localizedDescription)
                            }
                        }
                        try? await Task.sleep(nanoseconds: 250_000_000)
                    }
                }
                .onChange(of: captions.generation) { _ in reconfigure() }
                .onChange(of: captions.running) { _ in reconfigure() }
        }

        private func reconfigure() {
            guard captions.running else { config = nil; return }
            config = TranslationSession.Configuration(
                source: Locale.Language(identifier: TranslateLangs.translationId(source: captions.source)),
                target: Locale.Language(identifier: captions.target))
        }
    }
}
