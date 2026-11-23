import SwiftUI

/// 介面模式：商用＝簡潔（只顯示要用的），科技＝細節（模型、耗時、裝置能力、進階設定）。
enum UIMode: String, CaseIterable, Identifiable {
    case business, tech

    static let key = "ui_mode"

    var id: String { rawValue }
    var label: String { self == .business ? "商用" : "科技" }
    var icon: String { self == .business ? "briefcase.fill" : "cpu" }
    var note: String {
        self == .business
            ? "簡潔：只顯示要用的。報告、清單和設定都精簡，適合每天使用。"
            : "細節：顯示 AI 模型、處理耗時、裝置能力與進階設定（換模型），方便測試和調整。"
    }
}

extension Fmt {
    /// 給人看的時長：16 秒、2 分、1 小時 5 分。
    static func human(_ sec: Double) -> String {
        let s = Int(sec.rounded())
        if s < 60 { return "\(s) 秒" }
        if s < 3600 { return "\(s / 60) 分" }
        return s % 3600 / 60 == 0 ? "\(s / 3600) 小時" : "\(s / 3600) 小時 \(s % 3600 / 60) 分"
    }

    /// 清單的日期分組：今天、昨天、11月20日 週四（不是今年再加年份）。
    static func dayLabel(_ day: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(day) { return "今天" }
        if cal.isDateInYesterday(day) { return "昨天" }
        let sameYear = cal.component(.year, from: day) == cal.component(.year, from: Date())
        return stamp(day, sameYear ? "M月d日 EEEE" : "yyyy年M月d日 EEEE")
    }
}
