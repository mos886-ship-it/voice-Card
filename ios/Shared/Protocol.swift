import Foundation

/// 手機 ↔ 手錶 協議。路徑名照抄 Android `shared/.../WristAiProtocol.kt`，
/// 在 WatchConnectivity 裡當作 message / metadata 的 "path" 欄位。
enum DylanProtocol {
    /// 手錶 → 手機：分段錄音（transferFile 的 metadata["path"]），路徑 = 前綴/錄音id/序號/PCM樣本數/end|more。
    static let pathSegmentPrefix = "/wristai/seg"
    /// 手機 → 手錶：分段已存好（text = 分段檔名）。手錶收到這個確認才刪檔（照 Android：手機存好回 1 byte 確認）。
    static let pathSegmentAck = "/wristai/seg/ack"
    /// 手機 → 手錶：處理進度文字（userInfo / message：path、id、text）。
    static let pathStatus = "/wristai/status"
    /// 手機 → 手錶：最終摘要（之後的里程碑用）。
    static let pathSummary = "/wristai/summary"
    /// 手機 → 手錶：外觀主題（applicationContext：path、text = "ink" / "dark"）。
    static let pathTheme = "/wristai/theme"

    static let keyPath = "path"
    static let keyId = "id"
    static let keyText = "text"
}

enum AudioSpec {
    /// 分段長度：5 分鐘一段，錄音中就陸續傳到手機。
    /// 測試時可用 UserDefaults "segment_seconds" 改短（測完要刪掉）。
    static var segmentSeconds: Double {
        let v = UserDefaults.standard.double(forKey: "segment_seconds")
        return v > 0 ? v : 300
    }
    static let sampleRate: Double = 16_000
    static let bitRate = 48_000
}

/// 一段錄音。samples = 這段的 PCM 樣本數；last = 錄音的最後一段。
struct Segment: Hashable {
    let recordingId: String
    let seq: Int
    let samples: Int64
    let last: Bool

    var path: String {
        "\(DylanProtocol.pathSegmentPrefix)/\(recordingId)/\(seq)/\(samples)/\(last ? "end" : "more")"
    }

    /// 手錶上暫存的檔名（帶齊資料，重開 App 後也能重送）。
    var fileName: String { "\(recordingId)__\(seq)__\(samples)__\(last ? "end" : "more").m4a" }

    init(recordingId: String, seq: Int, samples: Int64, last: Bool) {
        self.recordingId = recordingId
        self.seq = seq
        self.samples = samples
        self.last = last
    }

    init?(path: String) {
        let prefix = DylanProtocol.pathSegmentPrefix + "/"
        guard path.hasPrefix(prefix) else { return nil }
        let p = path.dropFirst(prefix.count).split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard p.count == 4, !p[0].isEmpty,
              let seq = Int(p[1]), let samples = Int64(p[2]),
              p[3] == "end" || p[3] == "more" else { return nil }
        self.init(recordingId: p[0], seq: seq, samples: samples, last: p[3] == "end")
    }

    init?(fileName: String) {
        guard fileName.hasSuffix(".m4a") else { return nil }
        let p = fileName.dropLast(4).components(separatedBy: "__")
        guard p.count == 4 else { return nil }
        self.init(path: DylanProtocol.pathSegmentPrefix + "/" + p.joined(separator: "/"))
    }
}

/// 錄音 id = 開始時間 yyyyMMdd_HHmmss（跟 Android 一樣）。
enum RecordingID {
    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd_HHmmss"
        return f
    }()

    static func make(_ date: Date = Date()) -> String { formatter.string(from: date) }
    static func date(of id: String) -> Date? { formatter.date(from: String(id.suffix(15))) }
}

#if targetEnvironment(simulator)
/// 只在模擬器用：分段切成小塊經 sendMessage 傳（模擬器的 transferFile / transferUserInfo 都收不到，見 WatchLink.transport）。
enum SimChunk {
    static let path = "/wristai/seg/chunk"
    static let size = 32 * 1024
    static let keyFile = "file", keyIndex = "i", keyCount = "n", keyData = "data"
}
#endif
