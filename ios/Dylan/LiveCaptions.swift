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
    @Published private(set) var source: SpeechLang = .zh
    @Published private(set) var target: Target = .en
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
    var queueChanged: (() -> Void)?

    /// 音訊 tap（錄音執行緒）→ 目前的辨識請求。用鎖保護，tap 不能等主執行緒。
    nonisolated private static let lock = NSLock()
    nonisolated(unsafe) private static var request: SFSpeechAudioBufferRecognitionRequest?

    /// PhoneRecorder 的音訊 tap 呼叫：開著字幕才送。
    nonisolated static func feed(_ buffer: AVAudioPCMBuffer) {
        lock.lock(); let r = request; lock.unlock()
        r?.append(buffer)
    }

    // MARK: - 開始 / 停止

    func start(source: SpeechLang, target: Target) {
        if running { stop() }
        self.source = source
        self.target = target
        error = nil
        status = nil
        guard Self.supported(source) else {
            error = "台語、客語、粵語手機辨識不了，沒有即時字幕；錄完的報告不受影響。".tr
            return
        }
        running = true
        failures = 0
        var mid: String?
        if case let .recording(id, _) = PhoneRecorder.shared.state, !id.hasPrefix(Meeting.foodPrefix) { mid = id }
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
                let locale = source == .zh ? Locale(identifier: "zh-TW") : source.locale
                guard let r = SFSpeechRecognizer(locale: locale), r.isAvailable else {
                    self.error = "這支 iPhone 沒有這個語言的語音辨識".tr
                    self.running = false
                    return
                }
                self.recognizer = r
                self.status = r.supportsOnDeviceRecognition ? nil
                    : "這個語言沒有手機上的語音辨識，聲音會傳到 Apple 辨識".tr
                self.startSegment()
            }
        }
        if #unavailable(iOS 18.0) { status = "翻譯需要 iOS 18 以上；這支 iPhone 只顯示原文".tr }
    }

    func stop() {
        guard running else { return }
        running = false
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
        let src = source.rawValue, tgt = target.rawValue
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
    func recordingStopped() { if running { stop() } }

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
        partialText = rest
        setPartial(rest)
        // 停頓一下（1.5 秒沒變）也算一句
        silence?.cancel()
        if !rest.trimmingCharacters(in: .whitespaces).isEmpty {
            silence = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                guard !Task.isCancelled else { return }
                self?.commitRemainder()
            }
        }
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
            guard captions.running, let src = LiveCaptions.source(captions.source) else { config = nil; return }
            config = TranslationSession.Configuration(source: src, target: captions.target.language)
        }
    }
}
