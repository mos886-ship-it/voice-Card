import AVFoundation
import ReplayKit

/// 通話錄音（iOS 畫面廣播）：只收聲音、丟掉畫面。
/// - audioApp：其他 App 播出的聲音（LINE / Zoom / Teams 對方的聲音；戴 AirPods 也收得到）
/// - audioMic：自己的聲音（要在廣播面板打開「麥克風」）
/// 兩軌各寫一個 m4a 到 App Group 的 calls/<錄音id>/，結束時寫 done.json；
/// 大D 打開時把兩軌對齊混成一個檔，當成一筆「通話」錄音處理（CallInbox.swift）。
///
/// 廣播擴充的記憶體只有約 50 MB：這裡只做寫檔，不做任何轉檔或分析。
final class SampleHandler: RPBroadcastSampleHandler {
    private let queue = DispatchQueue(label: "dylan.broadcast")
    private var dir: URL?
    private var startedAt = Date()
    private var app: TrackWriter?
    private var mic: TrackWriter?

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        guard let root = Self.inbox else {
            finishBroadcastWithError(NSError(domain: "Dylan", code: 1,
                                             userInfo: [NSLocalizedDescriptionKey: "大D 的共用資料夾打不開"]))
            return
        }
        startedAt = Date()
        let id = Self.recordingID(startedAt)
        let dir = root.appendingPathComponent(id, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        self.dir = dir
        app = TrackWriter(url: dir.appendingPathComponent("app.m4a"))
        mic = TrackWriter(url: dir.appendingPathComponent("mic.m4a"))
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        switch sampleBufferType {
        case .audioApp: queue.sync { app?.append(sampleBuffer) }
        case .audioMic: queue.sync { mic?.append(sampleBuffer) }
        default: break   // 畫面：不要
        }
    }

    override func broadcastFinished() {
        let group = DispatchGroup()
        queue.sync {
            for w in [app, mic].compactMap({ $0 }) {
                group.enter()
                w.finish { group.leave() }
            }
        }
        _ = group.wait(timeout: .now() + 10)
        guard let dir else { return }
        var info: [String: Any] = [
            "startedAt": startedAt.timeIntervalSince1970,
            "endedAt": Date().timeIntervalSince1970,
        ]
        if let t = app?.firstTime { info["appStart"] = t }
        if let t = mic?.firstTime { info["micStart"] = t }
        if let data = try? JSONSerialization.data(withJSONObject: info) {
            try? data.write(to: dir.appendingPathComponent("done.json"))
        }
    }

    // MARK: 共用資料夾

    static var inbox: URL? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "DylanAppGroup") as? String,
              let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else { return nil }
        let dir = base.appendingPathComponent("calls", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// 跟其他錄音一樣：開始時間 yyyyMMdd_HHmmss。
    static func recordingID(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd_HHmmss"
        return f.string(from: date)
    }
}

/// 一軌聲音 → AAC m4a（單聲道）。第一個 buffer 到了才建檔（沿用它的取樣率）。
private final class TrackWriter {
    private let url: URL
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var failed = false
    /// 第一個 buffer 的時間（秒，主機時鐘）：兩軌用它對齊。
    private(set) var firstTime: Double?

    init(url: URL) { self.url = url }

    func append(_ sb: CMSampleBuffer) {
        guard !failed, CMSampleBufferDataIsReady(sb) else { return }
        if writer == nil { start(sb) }
        guard let writer, let input, writer.status == .writing, input.isReadyForMoreMediaData else { return }
        if !input.append(sb) {
            NSLog("[Broadcast] append failed: \(String(describing: writer.error))")
        }
    }

    private func start(_ sb: CMSampleBuffer) {
        guard let fmt = CMSampleBufferGetFormatDescription(sb),
              let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(fmt)?.pointee else { failed = true; return }
        let rate = asbd.mSampleRate > 0 ? asbd.mSampleRate : 44_100
        do {
            try? FileManager.default.removeItem(at: url)
            let w = try AVAssetWriter(outputURL: url, fileType: .m4a)
            let i = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: min(rate, 48_000),
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: 64_000,
            ])
            i.expectsMediaDataInRealTime = true
            guard w.canAdd(i) else { failed = true; return }
            w.add(i)
            guard w.startWriting() else { failed = true; return }
            let pts = CMSampleBufferGetPresentationTimeStamp(sb)
            w.startSession(atSourceTime: pts)
            firstTime = pts.seconds
            writer = w
            input = i
        } catch {
            NSLog("[Broadcast] writer failed: \(error)")
            failed = true
        }
    }

    func finish(_ done: @escaping () -> Void) {
        guard let writer, writer.status == .writing else { done(); return }
        input?.markAsFinished()
        writer.finishWriting { done() }
    }
}
