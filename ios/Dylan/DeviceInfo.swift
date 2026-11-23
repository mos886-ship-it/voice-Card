import UIKit

/// 這台 iPhone / iPad 的名字：顯示在錄音的「來源」（例如「iPad · 會議室 iPad」）。
/// iOS 16 起 App 讀不到使用者在系統設定取的名字，所以預設用型號，使用者可以在設定頁自己取名。
enum DeviceInfo {
    static let key = "device_name"

    /// 使用者取的名字；沒取就用型號。
    static var name: String {
        let v = (UserDefaults.standard.string(forKey: key) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return v.isEmpty ? model : v
    }

    /// 型號（iPad Pro 11 吋（第 2 代）、iPhone 15 Pro Max…）；認不得就用 iPhone / iPad。
    static var model: String { models[machine] ?? (UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone") }

    static var machine: String {
        if let sim = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return sim }
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
    }

    private static let models: [String: String] = [
        "iPhone12,1": "iPhone 11", "iPhone12,3": "iPhone 11 Pro", "iPhone12,5": "iPhone 11 Pro Max", "iPhone12,8": "iPhone SE（第 2 代）",
        "iPhone13,1": "iPhone 12 mini", "iPhone13,2": "iPhone 12", "iPhone13,3": "iPhone 12 Pro", "iPhone13,4": "iPhone 12 Pro Max",
        "iPhone14,4": "iPhone 13 mini", "iPhone14,5": "iPhone 13", "iPhone14,2": "iPhone 13 Pro", "iPhone14,3": "iPhone 13 Pro Max",
        "iPhone14,6": "iPhone SE（第 3 代）", "iPhone14,7": "iPhone 14", "iPhone14,8": "iPhone 14 Plus",
        "iPhone15,2": "iPhone 14 Pro", "iPhone15,3": "iPhone 14 Pro Max", "iPhone15,4": "iPhone 15", "iPhone15,5": "iPhone 15 Plus",
        "iPhone16,1": "iPhone 15 Pro", "iPhone16,2": "iPhone 15 Pro Max", "iPhone17,3": "iPhone 16", "iPhone17,4": "iPhone 16 Plus",
        "iPhone17,1": "iPhone 16 Pro", "iPhone17,2": "iPhone 16 Pro Max", "iPhone17,5": "iPhone 16e",
        "iPhone18,3": "iPhone 17", "iPhone18,1": "iPhone 17 Pro", "iPhone18,2": "iPhone 17 Pro Max", "iPhone18,4": "iPhone Air",
        "iPad8,1": "iPad Pro 11 吋", "iPad8,2": "iPad Pro 11 吋", "iPad8,3": "iPad Pro 11 吋", "iPad8,4": "iPad Pro 11 吋",
        "iPad8,9": "iPad Pro 11 吋（第 2 代）", "iPad8,10": "iPad Pro 11 吋（第 2 代）",
        "iPad8,11": "iPad Pro 12.9 吋（第 4 代）", "iPad8,12": "iPad Pro 12.9 吋（第 4 代）",
        "iPad13,4": "iPad Pro 11 吋（第 3 代）", "iPad13,5": "iPad Pro 11 吋（第 3 代）", "iPad13,6": "iPad Pro 11 吋（第 3 代）", "iPad13,7": "iPad Pro 11 吋（第 3 代）",
        "iPad13,8": "iPad Pro 12.9 吋（第 5 代）", "iPad13,9": "iPad Pro 12.9 吋（第 5 代）", "iPad13,10": "iPad Pro 12.9 吋（第 5 代）", "iPad13,11": "iPad Pro 12.9 吋（第 5 代）",
        "iPad14,3": "iPad Pro 11 吋（第 4 代）", "iPad14,4": "iPad Pro 11 吋（第 4 代）", "iPad14,5": "iPad Pro 12.9 吋（第 6 代）", "iPad14,6": "iPad Pro 12.9 吋（第 6 代）",
        "iPad16,3": "iPad Pro 11 吋（M4）", "iPad16,4": "iPad Pro 11 吋（M4）", "iPad16,5": "iPad Pro 13 吋（M4）", "iPad16,6": "iPad Pro 13 吋（M4）",
        "iPad13,1": "iPad Air（第 4 代）", "iPad13,2": "iPad Air（第 4 代）", "iPad13,16": "iPad Air（第 5 代）", "iPad13,17": "iPad Air（第 5 代）",
        "iPad14,8": "iPad Air 11 吋（M2）", "iPad14,9": "iPad Air 11 吋（M2）", "iPad14,10": "iPad Air 13 吋（M2）", "iPad14,11": "iPad Air 13 吋（M2）",
        "iPad12,1": "iPad（第 9 代）", "iPad12,2": "iPad（第 9 代）", "iPad13,18": "iPad（第 10 代）", "iPad13,19": "iPad（第 10 代）",
        "iPad14,1": "iPad mini（第 6 代）", "iPad14,2": "iPad mini（第 6 代）", "iPad16,1": "iPad mini（A17 Pro）", "iPad16,2": "iPad mini（A17 Pro）",
    ]
}
