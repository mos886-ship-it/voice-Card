import Foundation
import UIKit

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
    /// 飲食記錄的營養估算（照 Android Meeting.nutrition）。
    var nutrition: AppleAI.Nutrition?
    /// 逐字稿目前是 Apple 的即時預覽（不分說話者），Gemini 完成後會換掉。
    var transcriptPreview: Bool?
    /// 這次為什麼用 Apple（隱私模式 / Gemini 失敗改用 Apple）。
    var engineNote: String?
    /// 使用者自己改過標題：AI 報告不再覆蓋。
    var titleEdited: Bool?
    /// 在哪一台錄的 / 匯入的（iPhone、iPad 的名字；手錶錄的沒有）。舊資料沒有這欄。
    var device: String?
    /// 錄音的語言：zh（國語）/ nan（台語・國台語）。舊資料沒有這欄 = 國語。
    var lang: String?
    /// 客語腔調（lang = hak 時）：sixian / hailu / dapu / raoping / zhaoan / nansixian。
    var dialect: String?
    /// 大D的主機上的錄音 / 報告刪了沒（報告「基本資料」的「隱私」行；跟 Android Meeting.hostCleanup 一樣）。
    var hostCleanup: String?

    var speechLang: SpeechLang { SpeechLang(rawValue: lang ?? "") ?? .zh }
    var hakkaDialect: HakkaDialect { HakkaDialect(rawValue: dialect ?? "") ?? .sixian }
    /// 「客語・海陸」、「台語・國台語」、「國語」
    var langLabel: String { speechLang == .hak ? "客語・" + hakkaDialect.label : speechLang.label }

    /// 飲食記錄的錄音 id 開頭（照 Android FoodLog.ID_PREFIX）。
    static let foodPrefix = "food_"
    var isFood: Bool { id.hasPrefix(Self.foodPrefix) }

    static func new(id: String, source: String) -> Meeting {
        let start = RecordingID.date(of: id) ?? Date()
        let kind = id.hasPrefix(foodPrefix) ? "飲食 " : "錄音 "
        return Meeting(id: id, title: kind + Fmt.stamp(start, "M/d HH:mm"), createdAt: start,
                       durationSec: 0, status: .recording, source: source, segments: [:], lastSeq: nil, error: nil,
                       updatedAt: Date(), device: source == "watch" || DeviceInfo.name.isEmpty ? nil : DeviceInfo.name,
                       lang: SpeechLang.current.rawValue,
                       dialect: SpeechLang.current == .hak ? HakkaDialect.current.rawValue : nil)
    }

    var isComplete: Bool {
        guard let lastSeq else { return false }
        return (0...lastSeq).allSatisfy { segments[String($0)] != nil }
    }

    /// 來源＋裝置名字：「iPad · 會議室 iPad」、「通話 · iPhone 15 Pro Max」。
    var sourceLabel: String {
        let base = Self.label(source: source)
        guard source != "watch" else { return base }
        // 錄音只存在錄的那台：名字用這筆存的（舊資料存的是型號就不算），型號用這台的
        let saved = self.device.flatMap { DeviceInfo.isModel($0) ? nil : $0 }
        let name = saved ?? DeviceInfo.name
        let model = DeviceInfo.modelShort
        let who: String
        if name.isEmpty { who = model == base ? "" : model }
        else { who = model == base || model == name ? name : "\(name)（\(model)）" }
        return who.isEmpty ? base : "\(base) · \(who)"
    }

    static let isPad = UIDevice.current.userInterfaceIdiom == .pad

    static func label(source: String) -> String {
        switch source {
        case "watch": return "手錶"
        case "phone": return Meeting.isPad ? "iPad" : "手機"   // 錄音只存在錄的那台：在 iPad 上看到的就是 iPad 錄的
        case "import": return "匯入"
        case "call": return "通話"
        case "food": return "飲食"
        case "photo": return "拍照"
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
        if model.hasPrefix("local-") { return LocalAI.label(model) }
        // Apple：「apple-speech|apple-pcc」= 語音辨識 ＋ 整理
        if model.hasPrefix("apple-") {
            return model.split(separator: "|").map {
                switch $0 {
                case "apple-speech": return "Apple 語音辨識"
                case "apple-pcc": return "Apple 私有雲運算"
                case "apple-ondevice": return "Apple 裝置端模型"
                default: return String($0)
                }
            }.joined(separator: " ＋ ")
        }
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
        if source == "photo" { return "拍照 " + Fmt.stamp(createdAt, "yyyy/MM/dd HH:mm") }
        if durationSec < 1 { return Fmt.stamp(createdAt, "yyyy/MM/dd HH:mm") }   // 匯入的文字：沒有長度
        let end = createdAt.addingTimeInterval(durationSec)
        let sameDay = Calendar.current.isDate(createdAt, inSameDayAs: end)
        return Fmt.stamp(createdAt, "yyyy/MM/dd HH:mm:ss") + " – " +
            Fmt.stamp(end, sameDay ? "HH:mm:ss" : "yyyy/MM/dd HH:mm:ss") + "（\(Fmt.duration(durationSec))）"
    }
}
