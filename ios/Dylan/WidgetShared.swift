import Foundation
#if canImport(ActivityKit)
import ActivityKit
#endif

// App 與小工具共用的資料（這份檔案 ios/Dylan/WidgetShared.swift 與 ios/DylanWidget/WidgetShared.swift 要一模一樣）。

/// 小工具顯示的今天狀態：App 存在 App Group 的 UserDefaults（widget_snapshot）。
struct WidgetSnapshot: Codable {
    var day: Date = Date()
    var latestTitle: String?
    var latestId: String?
    var recordingsToday = 0
    var kcal = 0
    var kcalGoal = 2000
    var todos = 0
    /// 錄音中：開始時間（小工具顯示計時）
    var recordingSince: Date?
    var recordingFood = false
    /// 介面文字（小工具跟著 App 的介面語言）
    var labels: [String: String] = [:]

    static let key = "widget_snapshot"
    static var group: String? { Bundle.main.object(forInfoDictionaryKey: "DylanAppGroup") as? String }
    static var defaults: UserDefaults? { group.flatMap { UserDefaults(suiteName: $0) } }

    static func load() -> WidgetSnapshot {
        guard let d = defaults?.data(forKey: key), let s = try? JSONDecoder().decode(WidgetSnapshot.self, from: d) else { return WidgetSnapshot() }
        return s
    }
    func save() {
        if let d = try? JSONEncoder().encode(self) { Self.defaults?.set(d, forKey: Self.key) }
    }
    func label(_ k: String) -> String { labels[k] ?? k }
}

/// 小工具按鈕的連結：App 收到後照做（dylan://action/<名稱>）。
enum WidgetAction: String {
    case toggle, food, translate, todos, open
    var url: URL { URL(string: "dylan://action/" + rawValue)! }
}

#if canImport(ActivityKit)
/// 錄音中的即時動態（鎖定畫面、動態島）：計時。
@available(iOS 16.1, *)
struct RecordingAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var since: Date
        var food: Bool
    }
    var title: String
}

/// 即時翻譯的即時動態：最新一句譯文＋原文（iOS 的「懸浮翻譯」）。
@available(iOS 16.1, *)
struct TranslateAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var translated: String
        var original: String
    }
    var pair: String
}
#endif
