import AVFoundation
import ReplayKit
import SwiftUI

/// 通話錄音（DylanBroadcast 擴充）寫好的檔 → 收進錄音清單。
/// 擴充寫在 App Group 的 calls/<錄音id>/：app.m4a（對方 / 其他 App 的聲音）、mic.m4a（自己）、done.json。
/// 這裡把兩軌按開始時間對齊、混成一個 audio.m4a，建立一筆 source = "call" 的錄音，接著照常產生報告。
@MainActor
enum CallInbox {
    static var inbox: URL? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "DylanAppGroup") as? String,
              let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else { return nil }
        return base.appendingPathComponent("calls", isDirectory: true)
    }

    private static var importing = false

    /// 開 App / 回到前景時呼叫。
    static func importFinished() async {
        guard !importing, let inbox,
              let dirs = try? FileManager.default.contentsOfDirectory(at: inbox, includingPropertiesForKeys: nil) else { return }
        importing = true
        defer { importing = false }
        for dir in dirs.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
            let done = dir.appendingPathComponent("done.json")
            guard FileManager.default.fileExists(atPath: done.path) else { continue }   // 還在錄
            await importOne(dir, done: done)
        }
    }

    private static func importOne(_ dir: URL, done: URL) async {
        // 擴充用開始時間當資料夾名；收進來時加 call_ 前綴與這台的裝置代碼（跟 Android 一樣的編號格式）
        let name = dir.lastPathComponent
        let id = RecordingID.device(of: name) != nil ? name
            : RecordingID.date(of: name).map { RecordingID.make($0, prefix: "call_") } ?? name
        let store = MeetingStore.shared
        guard store.load(id) == nil else {   // 已經收過（上次刪資料夾失敗）
            try? FileManager.default.removeItem(at: dir)
            return
        }
        let info = (try? JSONSerialization.jsonObject(with: Data(contentsOf: done))) as? [String: Double] ?? [:]
        var tracks: [(URL, Double)] = []
        for (name, key) in [("app.m4a", "appStart"), ("mic.m4a", "micStart")] {
            let url = dir.appendingPathComponent(name)
            if let start = info[key], FileManager.default.fileExists(atPath: url.path) { tracks.append((url, start)) }
        }
        var m = Meeting.new(id: id, source: "call")
        m.title = "通話 ".tr + Fmt.stamp(m.createdAt, "M/d HH:mm")
        do {
            guard !tracks.isEmpty else { throw err("這次廣播沒有收到聲音（對方的 App 可能不允許錄製）".tr) }
            try FileManager.default.createDirectory(at: MeetingStore.dir(id), withIntermediateDirectories: true)
            m.durationSec = try await mix(tracks, to: MeetingStore.audioURL(id))
            m.status = .received
        } catch {
            NSLog("[CallInbox] import \(id) failed: \(error)")
            m.status = .failed
            m.error = "通話錄音處理失敗：{0}".trf(error.localizedDescription)
        }
        m.updatedAt = Date()
        store.save(m)
        try? FileManager.default.removeItem(at: dir)
        if m.status == .received, await MeetingStore.canReport() { await store.process(id) }
    }

    /// 幾軌（各自的開始時間）疊在一起輸出成一個 m4a（匯入也用：一軌）。回傳長度（秒）。
    static func mix(_ tracks: [(URL, Double)], to out: URL) async throws -> Double {
        let comp = AVMutableComposition()
        let t0 = tracks.map(\.1).min() ?? 0
        var length = 0.0
        for (url, start) in tracks {
            let asset = AVURLAsset(url: url)
            guard let src = try await asset.loadTracks(withMediaType: .audio).first else { continue }
            let duration = try await asset.load(.duration)
            guard duration.seconds > 0.2,
                  let dst = comp.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else { continue }
            let at = CMTime(seconds: start - t0, preferredTimescale: 600)
            try dst.insertTimeRange(CMTimeRange(start: .zero, duration: duration), of: src, at: at)
            length = max(length, start - t0 + duration.seconds)
        }
        guard !comp.tracks.isEmpty else { throw err("這次廣播沒有收到聲音（對方的 App 可能不允許錄製）".tr) }
        try? FileManager.default.removeItem(at: out)
        guard let export = AVAssetExportSession(asset: comp, presetName: AVAssetExportPresetAppleM4A) else {
            throw err("無法輸出音檔".tr)
        }
        export.outputURL = out
        export.outputFileType = .m4a
        await export.export()
        if export.status != .completed { throw export.error ?? err("輸出音檔失敗".tr) }
        return length
    }

    static func err(_ msg: String) -> NSError {
        NSError(domain: "Dylan", code: 2, userInfo: [NSLocalizedDescriptionKey: msg])
    }
}

/// 系統的「開始廣播」面板（只列出大D 的通話錄音）。藏起來，由我們自己的按鈕觸發。
struct BroadcastPicker: UIViewRepresentable {
    static let shared: RPSystemBroadcastPickerView = {
        let v = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
        v.preferredExtension = (Bundle.main.bundleIdentifier ?? "") + ".broadcast"
        v.showsMicrophoneButton = true
        return v
    }()

    func makeUIView(context: Context) -> UIView {
        let host = UIView(frame: .zero)
        host.addSubview(Self.shared)
        host.alpha = 0.011   // 不能是 0：完全透明的 view 不會回應
        return host
    }

    func updateUIView(_ uiView: UIView, context: Context) {}

    /// 打開系統面板（裡面的按鈕是系統的，只能這樣觸發）。
    static func open() {
        for case let button as UIButton in shared.subviews {
            button.sendActions(for: .allTouchEvents)
        }
    }
}
