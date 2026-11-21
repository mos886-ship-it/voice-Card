import Foundation

/// 一筆錄音（對應 Android `Meeting`，存成每筆資料夾裡的 meta.json）。
/// 程式內部沿用 Meeting 這個名字，介面上一律叫「錄音」。
struct Meeting: Codable, Identifiable, Equatable {
    enum Status: String, Codable {
        case recording = "RECORDING"   // 分段還在陸續傳來
        case received = "RECEIVED"     // 分段到齊、已接成 audio.m4a（還沒有 AI 報告）
        case summarizing = "SUMMARIZING" // AI 分析中（照 Android）
        case done = "DONE"             // 報告完成
        case failed = "FAILED"
    }

    var id: String
    var title: String
    /// 錄音開始時間（不是收到的時間）。
    var createdAt: Date
    var durationSec: Double
    var status: Status
    /// watch / phone / import / food（之後的里程碑）
    var source: String
    /// 已收到的分段：序號 → PCM 樣本數。
    var segments: [String: Int64]
    /// 收到「最後一段」後才知道總段數。
    var lastSeq: Int?
    var error: String?
    /// 最後一次收到分段 / 合併的時間（判斷「錄音中」是否卡住）。舊資料沒有這欄。
    var updatedAt: Date?
    /// 給手錶 / 主頁的短摘要（報告的 SUMMARY 行）。
    var summary: String?
    /// 產生報告的模型（照 Android Meeting.aiModel，例如 gemini-3.8-flash）。
    var aiModel: String?
    /// 說話者代號 → 名字（從報告的說話者表格來，之後可在逐字稿改名）。
    var speakerNames: [String: String]?
    /// 這次 AI 處理的開始 / 結束時間（顯示耗時）。
    var processStartedAt: Date?
    var processEndedAt: Date?
    /// AI 分析目前在哪一步（上傳錄音中 / AI 聆聽整理中），完整的步驟視覺化在里程碑 5。
    var aiStep: String?
    /// 使用者自己改過標題：AI 報告不再覆蓋。
    var titleEdited: Bool?

    static func new(id: String, source: String) -> Meeting {
        let start = RecordingID.date(of: id) ?? Date()
        return Meeting(id: id, title: "錄音 " + Fmt.stamp(start, "M/d HH:mm"), createdAt: start,
                       durationSec: 0, status: .recording, source: source, segments: [:], lastSeq: nil, error: nil,
                       updatedAt: Date())
    }

    var isComplete: Bool {
        guard let lastSeq else { return false }
        return (0...lastSeq).allSatisfy { segments[String($0)] != nil }
    }

    var sourceLabel: String { Self.label(source: source) }

    static func label(source: String) -> String {
        switch source {
        case "watch": return "手錶"
        case "phone": return "手機"
        case "import": return "匯入"
        case "food": return "飲食"
        default: return source
        }
    }

    var statusLabel: String {
        switch status {
        case .recording: return "錄音中"
        case .received: return "已收到"
        case .summarizing: return "AI 分析中"
        case .done: return "報告完成"
        case .failed: return "失敗"
        }
    }

    /// AI 模型代號 → 好讀的名字（照 Android aiModelLabel）：gemini-3.8-flash → Google Gemini 3.8 Flash。
    static func aiModelLabel(_ model: String?) -> String {
        guard let model, !model.isEmpty else { return "未記錄" }
        let m = model.lowercased()
        if m.hasPrefix("gemini-") {
            let p = m.dropFirst(7).split(separator: "-").map(String.init)
            let rest = p.dropFirst().map { $0.prefix(1).uppercased() + $0.dropFirst() }.joined(separator: "-")
            return "Google Gemini \(p.first ?? "")" + (rest.isEmpty ? "" : " \(rest)")
        }
        return model
    }

    /// 「2026/11/21 15:00:03 – 15:30:10（30:07）」，跨日會寫結束日期（照 Android recordingRange）。
    var recordingRange: String {
        let end = createdAt.addingTimeInterval(durationSec)
        let sameDay = Calendar.current.isDate(createdAt, inSameDayAs: end)
        return Fmt.stamp(createdAt, "yyyy/MM/dd HH:mm:ss") + " – " +
            Fmt.stamp(end, sameDay ? "HH:mm:ss" : "yyyy/MM/dd HH:mm:ss") + "（\(Fmt.duration(durationSec))）"
    }
}
