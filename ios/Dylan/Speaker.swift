import AVFoundation

/// 朗讀（照 Android `Speaker`）：翻譯的譯文、車用的報告與待辦。正在念的時候即時翻譯不把聽到的聲音當成新的一句。
@MainActor
final class Speaker: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    static let shared = Speaker()
    static let rateKey = "speak_rate"   // 0.5～1.5（1 = 正常）

    @Published private(set) var speaking = false
    private let synth = AVSpeechSynthesizer()
    private var onIdle: (() -> Void)?
    /// 音訊 tap 執行緒讀：念的時候不要辨識
    nonisolated(unsafe) static var busy = false

    override private init() {
        super.init()
        synth.delegate = self
    }

    nonisolated static var rate: Double {
        get { (UserDefaults.standard.object(forKey: rateKey) as? Double) ?? 1.0 }
        set { UserDefaults.standard.set(newValue, forKey: rateKey) }
    }

    /// 這個語言能不能念（手機有沒有這個語言的聲音）。
    static func canSpeak(_ lang: String) -> Bool { voice(lang) != nil }

    private static func voice(_ lang: String) -> AVSpeechSynthesisVoice? {
        let map = ["zh-Hant": "zh-TW", "zh-Hans": "zh-CN", "zh": "zh-TW"]
        let id = map[lang] ?? lang
        if let v = AVSpeechSynthesisVoice(language: id) { return v }
        let code = String(id.split(separator: "-").first ?? "")
        return AVSpeechSynthesisVoice.speechVoices().first { $0.language.hasPrefix(code) }
    }

    /// 念一段字；queue = false 先停掉正在念的。
    func speak(_ text: String, lang: String, rate: Double = Speaker.rate, queue: Bool = true, onIdle: (() -> Void)? = nil) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { onIdle?(); return }
        if !queue { synth.stopSpeaking(at: .immediate) }
        let session = AVAudioSession.sharedInstance()
        if session.category != .playAndRecord {   // 沒在錄音：用播放（藍牙、車上喇叭）
            try? session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
            try? session.setActive(true)
        }
        let u = AVSpeechUtterance(string: t)
        u.voice = Self.voice(lang)
        u.rate = Float(Double(AVSpeechUtteranceDefaultSpeechRate) * min(max(rate, 0.5), 1.5))
        self.onIdle = onIdle
        speaking = true
        Self.busy = true
        synth.speak(u)
    }

    func stop() {
        synth.stopSpeaking(at: .immediate)
        speaking = false
        Self.busy = false
    }

    nonisolated func speechSynthesizer(_ s: AVSpeechSynthesizer, didFinish u: AVSpeechUtterance) {
        Task { @MainActor in self.finishedOne() }
    }
    nonisolated func speechSynthesizer(_ s: AVSpeechSynthesizer, didCancel u: AVSpeechUtterance) {
        Task { @MainActor in self.finishedOne() }
    }

    private func finishedOne() {
        guard !synth.isSpeaking else { return }
        speaking = false
        // 念完停一下再開始聽，免得聽到自己的尾音
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { if !self.synth.isSpeaking { Speaker.busy = false } }
        let cb = onIdle; onIdle = nil; cb?()
    }
}
