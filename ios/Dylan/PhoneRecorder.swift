import AVFoundation
import Combine

/// 手機錄音：跟手錶用同一套分段（Shared/SegmentWriter，16kHz 單聲道 AAC，每 5 分鐘一段），
/// 分段一關好就交給 MeetingStore（跟手錶傳來的一樣：存好 → 到齊就合併 → AI）。
/// 鎖螢幕 / 切到別的 App 時靠 UIBackgroundModes=audio 繼續錄。
/// 收音被打斷（來電、Siri、耳機切換）時自動接著錄，跟手錶的 Recorder 一樣。
@MainActor
final class PhoneRecorder: ObservableObject {
    static let shared = PhoneRecorder()

    enum State: Equatable { case idle, recording(id: String, since: Date), denied, failed(String) }

    @Published private(set) var state: State = .idle
    @Published private(set) var interrupted = false

    private let engine = AVAudioEngine()
    private let queue = DispatchQueue(label: "dylan.phone-recorder")
    private var writer: SegmentWriter?
    private var starting = false
    private var watchdog: Timer?
    private var observers: [NSObjectProtocol] = []

    /// 分段先寫在這裡，關好後搬進 MeetingStore（跟手錶傳來的檔一樣處理）。
    nonisolated static let pendingDir: URL = {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("phone-rec", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    var isRecording: Bool { if case .recording = state { return true } else { return false } }
    var isFood: Bool { if case let .recording(id, _) = state { return id.hasPrefix(Meeting.foodPrefix) } else { return false } }
    var since: Date? { if case let .recording(_, since) = state { return since } else { return nil } }

    private init() {
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

    /// food = 飲食記錄（錄音 id 以 food_ 開頭）。
    func start(food: Bool = false) {
        guard !isRecording, !starting else { return }
        starting = true
        let done: (Bool) -> Void = { granted in
            Task { @MainActor in
                if granted { self.begin(food: food) } else { self.state = .denied }
                self.starting = false
            }
        }
        Task { @MainActor in
            await GeoLocator.shared.askOnce()   // 定位權限只問一次（拒絕也能錄，只是沒有地點）
            if #available(iOS 17.0, *) {
                AVAudioApplication.requestRecordPermission(completionHandler: done)
            } else {
                AVAudioSession.sharedInstance().requestRecordPermission(done)
            }
        }
    }

    private func begin(food: Bool) {
        guard !isRecording else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .default)
            try session.setActive(true)

            let id = RecordingID.make(prefix: food ? Meeting.foodPrefix : "")
            interrupted = false
            AudioLevel.shared.reset()
            writer = SegmentWriter(dir: Self.pendingDir, recordingId: id,
                                   segmentSeconds: AudioSpec.segmentSeconds) { url, seg in
                Self.hand(url, seg)
            }
            try installTapAndStart()
            state = .recording(id: id, since: Date())
            startWatchdog()
            // 錄音地點：開始錄音時的位置（「拍＋說」也是這裡定位）
            Task { @MainActor in
                if let loc = await GeoLocator.shared.current() { await GeoStore.save(id, loc) }
            }
            // 「拍＋說」：先拍的照片接進這筆飲食記錄
            if food, !FoodPhotos.pendingForVoice.isEmpty {
                FoodPhotos.save(FoodPhotos.pendingForVoice, to: id)
                FoodPhotos.pendingForVoice = []
            }
        } catch {
            NSLog("[PhoneRecorder] start failed: \(error)")
            engine.inputNode.removeTap(onBus: 0)
            writer = nil
            state = .failed("無法開始錄音".tr)
            // 「拍＋說」錄不了音：照片不要丟，改成只拍照的記錄
            if food, !FoodPhotos.pendingForVoice.isEmpty {
                _ = FoodPhotos.createPhotoOnly(FoodPhotos.pendingForVoice)
                FoodPhotos.pendingForVoice = []
            }
        }
    }

    /// 一段關好了：搬進 MeetingStore → 更新記錄；最後一段到了就合併並開始 AI。
    nonisolated private static func hand(_ url: URL, _ seg: Segment) {
        do {
            try MeetingStore.stash(url, seg)
        } catch {
            NSLog("[PhoneRecorder] stash failed: \(error)")
            return
        }
        Task { @MainActor in
            GeminiJobs.withBackgroundTime("phone-\(seg.recordingId)-\(seg.seq)") {
                _ = await MeetingStore.shared.segmentArrived(seg, source: "phone")
            }
        }
    }

    private func installTapAndStart() throws {
        guard let writer else { return }
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0 else {
            throw NSError(domain: "Dylan", code: 1, userInfo: [NSLocalizedDescriptionKey: "找不到麥克風".tr])
        }
        let queue = self.queue
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { buffer, _ in
            // 音量條：在這裡先量（很便宜），丟回主執行緒更新
            if let m = AudioLevel.measure(buffer) {
                Task { @MainActor in AudioLevel.shared.push(m) }
            }
            queue.async { writer.write(buffer) }
        }
        engine.prepare()
        try engine.start()
    }

    private func resume() {
        guard isRecording else { return }
        interrupted = true
        do {
            try AVAudioSession.sharedInstance().setActive(true)
            if engine.isRunning { engine.stop() }
            try installTapAndStart()
            interrupted = false
            NSLog("[PhoneRecorder] resumed")
        } catch {
            NSLog("[PhoneRecorder] resume failed, will retry: \(error)")
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
        AudioLevel.shared.reset()
        queue.async {
            let recorded = writer?.totalSamples ?? 0
            writer?.finish()
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            if recorded == 0 {
                Task { @MainActor in self.state = .failed("沒有錄到聲音".tr) }
            }
        }
    }

    /// 錄音不到 1 秒、整筆刪掉了（MeetingStore.assemble）：錄音卡顯示原因。
    func tooShort() {
        if !isRecording { state = .failed("錄音不到 1 秒，沒有送出辨識".tr) }
    }

    func clearMessage() {
        if case .failed = state { state = .idle }
        if state == .denied { state = .idle }
    }
}
