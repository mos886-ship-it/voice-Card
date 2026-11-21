import AVFoundation
import Combine

/// 手錶錄音（對應 Android `wear/.../RecordingService.kt`）：AVAudioEngine 收音，
/// 每 5 分鐘關一段 m4a 交給 WatchLink 傳到手機；錄音中靠 WKBackgroundModes=audio 在放下手腕時繼續錄。
/// 收音被打斷（來電、Siri、藍牙切換）或系統暫停時會自動接著錄：打斷結束、音訊設定改變、回到前景，
/// 以及錄音中每 3 秒檢查一次「最近有沒有收到聲音」。
@MainActor
final class Recorder: ObservableObject {
    enum State: Equatable { case idle, recording(id: String, since: Date), denied, failed(String) }

    @Published private(set) var state: State = .idle
    /// 這次錄音已經關好、交給 WatchLink 的分段數（錄音中顯示「已同步 N 段」用）。
    @Published private(set) var closedSegments = 0
    /// 剛停止、分段還在傳：主畫面顯示「正在傳送到手機…」。
    @Published var sending = false
    /// 剛停止、最後一段還在收尾（還沒交給 WatchLink）。
    @Published private(set) var finalizing = false
    /// 錄音中收音停了，正在恢復。
    @Published private(set) var interrupted = false

    private let engine = AVAudioEngine()
    private let queue = DispatchQueue(label: "dylan.recorder")
    private var writer: SegmentWriter?
    /// 正在問麥克風權限 / 啟動中：擋掉連按造成的重複啟動。
    private var starting = false
    private var watchdog: Timer?
    private var observers: [NSObjectProtocol] = []

    var isRecording: Bool { if case .recording = state { return true } else { return false } }
    var isFood: Bool { if case let .recording(id, _) = state { return id.hasPrefix("food_") } else { return false } }

    init() {
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            Task { @MainActor in
                guard let self, self.isRecording else { return }
                if type == .began { self.interrupted = true } else { self.resume() }
            }
        })
        observers.append(nc.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.isRecording else { return }
                self.resume()
            }
        })
    }

    func toggle() {
        isRecording ? stop() : start()
    }

    /// food = 飲食記錄（錄音 id 以 food_ 開頭，照 Android FoodLog.ID_PREFIX）。
    func start(food: Bool = false) {
        guard !isRecording, !starting else { return }
        guard Self.freeBytes() > 100_000_000 else {
            state = .failed("手錶空間不足")
            return
        }
        starting = true
        AVAudioApplication.requestRecordPermission { granted in
            Task { @MainActor in
                if granted { self.begin(food: food) } else { self.state = .denied }
                self.starting = false
            }
        }
    }

    private func begin(food: Bool) {
        guard !isRecording else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .default)
            try session.setActive(true)

            let id = (food ? "food_" : "") + RecordingID.make()
            let link = WatchLink.shared
            closedSegments = 0
            sending = false
            finalizing = false
            interrupted = false
            let writer = SegmentWriter(dir: link.pendingDir, recordingId: id,
                                       segmentSeconds: AudioSpec.segmentSeconds) { url, seg in
                Task { @MainActor in
                    self.closedSegments += 1
                    link.send(url, seg)
                }
            }
            self.writer = writer
            try installTapAndStart()
            state = .recording(id: id, since: Date())
            startWatchdog()
        } catch {
            NSLog("[Recorder] start failed: \(error)")
            engine.inputNode.removeTap(onBus: 0)
            writer = nil
            state = .failed("無法開始錄音")
        }
    }

    private func installTapAndStart() throws {
        guard let writer else { return }
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0 else {
            throw NSError(domain: "Dylan", code: 1, userInfo: [NSLocalizedDescriptionKey: "找不到麥克風"])
        }
        let queue = self.queue
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { buffer, _ in
            queue.async { writer.write(buffer) }
        }
        engine.prepare()
        try engine.start()
    }

    /// 收音停了：重新啟用音訊、重新接上麥克風（同一個 writer，分段接著寫，不會多出新錄音）。
    private func resume() {
        guard isRecording else { return }
        interrupted = true
        do {
            try AVAudioSession.sharedInstance().setActive(true)
            if engine.isRunning { engine.stop() }
            try installTapAndStart()
            interrupted = false
            NSLog("[Recorder] resumed")
        } catch {
            NSLog("[Recorder] resume failed, will retry: \(error)")
        }
    }

    /// 回到前景時呼叫：錄音中但引擎沒在跑就接著錄。
    func checkAlive() {
        guard isRecording else { return }
        if !engine.isRunning || interrupted { resume() }
    }

    private func startWatchdog() {
        watchdog?.invalidate()
        watchdog = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    private func tick() {
        guard isRecording, let writer else { return }
        let last = queue.sync { writer.lastWriteAt }
        // 超過 4 秒沒收到聲音，或引擎停了 → 恢復
        if !engine.isRunning || Date().timeIntervalSince(last) > 4 { resume() }
    }

    func stop() {
        guard isRecording else { return }
        watchdog?.invalidate()
        watchdog = nil
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        let writer = self.writer
        self.writer = nil
        state = .idle
        interrupted = false
        sending = true
        finalizing = true
        WatchLink.shared.clearPhoneStatus()
        queue.async {
            let recorded = writer?.totalSamples ?? 0
            writer?.finish()
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            Task { @MainActor in
                self.finalizing = false
                if recorded == 0 {
                    // 一按就停、什麼都沒錄到：不要顯示「已傳送」
                    self.sending = false
                    self.state = .failed("沒有錄到聲音")
                }
            }
        }
    }

    static func freeBytes() -> Int64 {
        let attrs = try? FileManager.default.attributesOfFileSystem(forPath: NSHomeDirectory())
        return (attrs?[.systemFreeSize] as? NSNumber)?.int64Value ?? Int64.max
    }
}
