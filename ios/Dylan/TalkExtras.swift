import AVFoundation
import AudioToolbox
import UIKit

/// 遠距對話的鈴聲（照 Android `TalkRing.kt`）：
/// - 撥出去等對方接：回鈴音（嘟——嘟——）。
/// - 有人打來：鈴聲循環＋震動；用 ambient 音訊類別，所以手機的靜音開關會讓鈴聲不響（震動照系統設定）。
/// iOS 不讓 App 用使用者選的系統鈴聲，所以鈴聲是 App 自己合成的。畫面離開（接了、掛了、拒接）就停。
@MainActor
enum TalkRing {
    private static var player: AVAudioPlayer?
    private static var vibrate: Timer?

    static func startRingback() {
        stop()
        // 台灣回鈴音：440＋480 Hz，響 1 秒、停 2 秒
        play(tone(freqs: [440, 480], on: 1.0, off: 2.0, volume: 0.25), category: .playback)
    }

    static func startRinging() {
        stop()
        // 來電：兩段式鈴聲（響 0.4、停 0.2、響 0.4、停 2 秒）
        play(tone(freqs: [880, 1320], on: 0.4, off: 0.2, volume: 0.5, twice: true, rest: 2.0), category: .ambient)
        AudioServicesPlayAlertSound(kSystemSoundID_Vibrate)
        vibrate = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { _ in AudioServicesPlayAlertSound(kSystemSoundID_Vibrate) }
    }

    static func stop() {
        player?.stop(); player = nil
        vibrate?.invalidate(); vibrate = nil
    }

    private static func play(_ wav: Data, category: AVAudioSession.Category) {
        let s = AVAudioSession.sharedInstance()
        if s.category != .playAndRecord {   // 開著麥克風時不換類別
            try? s.setCategory(category, mode: .default, options: [.mixWithOthers])
            try? s.setActive(true)
        }
        guard let p = try? AVAudioPlayer(data: wav) else { return }
        p.numberOfLoops = -1
        p.play()
        player = p
    }

    /// 合成一段 16-bit 單聲道 WAV（響 on 秒、停 off 秒；twice = 再響一次後停 rest 秒）。
    private static func tone(freqs: [Double], on: Double, off: Double, volume: Double, twice: Bool = false, rest: Double = 0) -> Data {
        let rate = 16_000.0
        var samples: [Int16] = []
        func beep(_ sec: Double) {
            let n = Int(sec * rate)
            for i in 0..<n {
                let t = Double(i) / rate
                let env = min(1, min(t, sec - t) / 0.02)   // 20 ms 淡入淡出，不會喀一聲
                let v = freqs.reduce(0.0) { $0 + sin(2 * .pi * $1 * t) } / Double(freqs.count)
                samples.append(Int16(v * env * volume * 32_000))
            }
        }
        func silence(_ sec: Double) { samples.append(contentsOf: repeatElement(0, count: Int(sec * rate))) }
        beep(on); silence(off)
        if twice { beep(on); silence(rest) }
        var d = Data()
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        let bytes = UInt32(samples.count * 2)
        d.append(contentsOf: Array("RIFF".utf8)); u32(36 + bytes); d.append(contentsOf: Array("WAVEfmt ".utf8))
        u32(16); u16(1); u16(1); u32(UInt32(rate)); u32(UInt32(rate) * 2); u16(2); u16(16)
        d.append(contentsOf: Array("data".utf8)); u32(bytes)
        samples.withUnsafeBufferPointer { p in d.append(UnsafeBufferPointer(start: UnsafeRawPointer(p.baseAddress!).assumingMemoryBound(to: UInt8.self), count: p.count * 2)) }
        return d
    }
}

/// 遠距對話的頭貼（照 Android `TalkAvatars.kt`）：
/// - 自己的：選一張照片 → 裁正中間的正方形、縮成 256×256 JPEG → 存在手機（Documents/talk_av/me.jpg）並傳到每台有身分的主機。
/// - 好友的：上線回報、好友邀請、來電帶頭貼版本（av）；版本變了才去主機抓（只有好友抓得到）。
@MainActor
final class TalkAvatars: ObservableObject {
    static let shared = TalkAvatars()
    private static let size: CGFloat = 256
    /// 換了頭貼就加一，畫面跟著重畫
    @Published private(set) var version = 0
    private var fetching = Set<String>()
    private var cache: [String: UIImage] = [:]

    private static var dir: URL {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("talk_av", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }
    private static var mineURL: URL { dir.appendingPathComponent("me.jpg") }
    private static func key(_ base: String, _ id: String) -> String {
        var h: UInt32 = 5381
        for b in base.utf8 { h = (h &* 33) ^ UInt32(b) }
        return String(h, radix: 16) + "_" + id.filter { $0.isLetter || $0.isNumber }
    }
    private static func url(_ base: String, _ id: String) -> URL { dir.appendingPathComponent(key(base, id) + ".jpg") }
    private static func known(_ base: String, _ id: String) -> Int64 { Int64(UserDefaults.standard.integer(forKey: "talk_av:" + key(base, id))) }

    var hasMine: Bool { FileManager.default.fileExists(atPath: Self.mineURL.path) }

    func mine() -> UIImage? { load(Self.mineURL) }
    func image(base: String?, id: String?) -> UIImage? {
        guard let base, let id, !id.isEmpty else { return nil }
        return load(Self.url(base, id))
    }

    private func load(_ u: URL) -> UIImage? {
        let k = u.lastPathComponent + "#\(version)"
        if let i = cache[k] { return i }
        guard let d = try? Data(contentsOf: u), let i = UIImage(data: d) else { return nil }
        cache[k] = i
        return i
    }

    /// 照片 → 正方形 256×256 JPEG（UIImage 畫的時候已經照方向轉正）。
    static func square(_ img: UIImage) -> Data? {
        let side = min(img.size.width, img.size.height)
        guard side > 0 else { return nil }
        let fmt = UIGraphicsImageRendererFormat(); fmt.scale = 1
        let out = UIGraphicsImageRenderer(size: CGSize(width: size, height: size), format: fmt).image { _ in
            let s = size / side
            img.draw(in: CGRect(x: -(img.size.width - side) / 2 * s, y: -(img.size.height - side) / 2 * s,
                                width: img.size.width * s, height: img.size.height * s))
        }
        return out.jpegData(compressionQuality: 0.85)
    }

    /// 換自己的頭貼（nil = 刪掉）：存手機、傳到每台有身分的主機。回錯誤訊息（每台都失敗才回）。
    func setMine(_ jpeg: Data?) async -> String? {
        if let jpeg { try? jpeg.write(to: Self.mineURL, options: .atomic) } else { try? FileManager.default.removeItem(at: Self.mineURL) }
        cache = [:]; version += 1
        var err: String?, ok = false
        for b in TalkStore.bases {
            guard let id = TalkStore.identity(b) else { continue }
            guard let (code, j) = try? await RemoteTalk.shared.http("POST", b + "/v1/talk/avatar", contactToken: id.token,
                                                                     raw: jpeg ?? Data(), contentType: "image/jpeg") else {
                err = "連不到大D的主機，頭貼之後再同步".tr; continue
            }
            let e = j["error"] as? String ?? ""
            if code == 404 && e.isEmpty { err = "大D的主機還沒有頭貼功能：請在 Mac 上重新部署大D的主機".tr }
            else if code != 200 { err = e.isEmpty ? "頭貼上傳失敗".tr : e }
            else { ok = true }
        }
        return ok || TalkStore.bases.isEmpty ? nil : err
    }

    /// 主機說某人的頭貼版本是 av：跟手機上的不一樣就去抓（0 = 對方沒有頭貼，刪掉）。
    func sync(base: String, id: String, av: Int64) {
        guard !id.isEmpty, av != Self.known(base, id) else { return }
        let k = Self.key(base, id)
        guard fetching.insert(k).inserted else { return }
        Task {
            defer { fetching.remove(k) }
            let f = Self.url(base, id)
            if av == 0 {
                try? FileManager.default.removeItem(at: f)
            } else {
                guard let token = TalkStore.identity(base)?.token, let u = URL(string: "\(base)/v1/talk/avatar/\(id)") else { return }
                var r = URLRequest(url: u, timeoutInterval: 15)
                r.setValue(token, forHTTPHeaderField: "X-Contact-Token")
                guard let (data, resp) = try? await URLSession.shared.data(for: r), (resp as? HTTPURLResponse)?.statusCode == 200,
                      data.count <= 100_000 else { return }
                try? data.write(to: f, options: .atomic)
            }
            UserDefaults.standard.set(Int(av), forKey: "talk_av:" + k)
            cache = [:]; version += 1
        }
    }

    /// 主機上沒有我的頭貼（剛取得身分、換了主機）：補傳。
    func pushIfMissing(base: String, serverAv: Int64) {
        guard serverAv == 0, let d = try? Data(contentsOf: Self.mineURL), let id = TalkStore.identity(base) else { return }
        Task { _ = try? await RemoteTalk.shared.http("POST", base + "/v1/talk/avatar", contactToken: id.token, raw: d, contentType: "image/jpeg") }
    }
}
