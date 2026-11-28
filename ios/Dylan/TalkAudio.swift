import AVFoundation

/// 遠距對話的原聲（照 Android `TalkAudio.kt`）：
/// - `TalkClip`：即時翻譯的音訊 tap 同時存最近 30 秒的聲音（只在遠距對話開著麥克風時），一句說完剪成 m4a。
/// - `TalkPlayer`：先播原聲、再唸翻譯，一句接一句；播的時候辨識不聽（Speaker.busy）。
enum TalkClip {
    nonisolated(unsafe) private static var enabledFlag = false
    nonisolated(unsafe) private static var ring: [Float] = []
    nonisolated(unsafe) private static var ringStart: Int64 = 0      // ring[0] 是第幾個樣本
    nonisolated(unsafe) private static var total: Int64 = 0
    nonisolated(unsafe) private static var rate: Double = 48_000
    private static let lock = NSLock()
    private static let keepSeconds = 30.0

    static var enabled: Bool {
        get { lock.lock(); defer { lock.unlock() }; return enabledFlag }
        set {
            lock.lock()
            enabledFlag = newValue
            ring = []; ringStart = total
            lock.unlock()
        }
    }

    /// 目前收到第幾個樣本（剪句子用）。
    static var samples: Int64 { lock.lock(); defer { lock.unlock() }; return total }
    static var sampleRate: Double { lock.lock(); defer { lock.unlock() }; return rate }

    /// 音訊 tap（錄音執行緒）呼叫。
    static func append(_ buf: AVAudioPCMBuffer, silent: Bool = false) {
        guard let ch = buf.floatChannelData else { return }
        let n = Int(buf.frameLength)
        lock.lock(); defer { lock.unlock() }
        guard enabledFlag else { total += Int64(n); ringStart = total; return }
        rate = buf.format.sampleRate
        if silent { ring.append(contentsOf: repeatElement(0, count: n)) }   // 正在播：不要把自己的喇叭聲剪進去
        else { ring.append(contentsOf: UnsafeBufferPointer(start: ch[0], count: n)) }
        total += Int64(n)
        let keep = Int(keepSeconds * rate)
        if ring.count > keep {
            let drop = ring.count - keep
            ring.removeFirst(drop)
            ringStart += Int64(drop)
        }
    }

    /// 剪出 [start, end) 的聲音存成 m4a（AAC 單聲道）。太短（< 0.3 秒）或已經不在緩衝裡 → nil。
    static func clip(from start: Int64, to end: Int64, out: URL) -> URL? {
        lock.lock()
        let s = max(start, ringStart), e = min(end, total)
        let r = rate
        guard e - s > Int64(0.3 * r) else { lock.unlock(); return nil }
        let slice = Array(ring[Int(s - ringStart)..<Int(e - ringStart)])
        lock.unlock()
        guard let fmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: r, channels: 1, interleaved: false),
              let pcm = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(slice.count)) else { return nil }
        pcm.frameLength = AVAudioFrameCount(slice.count)
        slice.withUnsafeBufferPointer { p in pcm.floatChannelData![0].update(from: p.baseAddress!, count: slice.count) }
        try? FileManager.default.removeItem(at: out)
        let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: r, AVNumberOfChannelsKey: 1,
                                       AVEncoderBitRateKey: 32_000]
        do {
            let f = try AVAudioFile(forWriting: out, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
            try f.write(from: pcm)
        } catch {
            NSLog("[TalkClip] write failed: \(error)")
            return nil
        }
        return out
    }
}

/// 先播原聲、再唸翻譯（一句接一句）。
@MainActor
final class TalkPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    static let shared = TalkPlayer()
    private struct Item { let clip: URL?, text: String?, lang: String }
    private var queue: [Item] = []
    private var player: AVAudioPlayer?
    @Published private(set) var playing = false

    func enqueue(clip: URL?, text: String?, lang: String) {
        guard clip != nil || !(text ?? "").isEmpty else { return }
        queue.append(Item(clip: clip, text: text, lang: lang))
        if !playing { next() }
    }

    func stopAll() {
        queue.removeAll()
        player?.stop(); player = nil
        Speaker.shared.stop()
        playing = false
        Speaker.busy = false
    }

    private func next() {
        guard !queue.isEmpty else { playing = false; return }
        playing = true
        let it = queue.removeFirst()
        if let c = it.clip, let p = try? AVAudioPlayer(contentsOf: c) {
            let s = AVAudioSession.sharedInstance()
            if s.category != .playAndRecord {
                try? s.setCategory(.playback, mode: .spokenAudio)
                try? s.setActive(true)
            }
            Speaker.busy = true
            player = p
            p.delegate = self
            pendingText = it
            p.play()
        } else {
            speak(it)
        }
    }

    private var pendingText: Item?

    private func speak(_ it: Item) {
        if let t = it.text, !t.isEmpty {
            Speaker.shared.speak(t, lang: it.lang, onIdle: { [weak self] in self?.next() })
        } else {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { Speaker.busy = Speaker.shared.speaking }
            next()
        }
    }

    nonisolated func audioPlayerDidFinishPlaying(_ p: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            self.player = nil
            if let it = self.pendingText { self.pendingText = nil; self.speak(it) } else { self.next() }
        }
    }

    nonisolated func audioPlayerDecodeErrorDidOccur(_ p: AVAudioPlayer, error: Error?) {
        audioPlayerDidFinishPlaying(p, successfully: false)
    }
}
