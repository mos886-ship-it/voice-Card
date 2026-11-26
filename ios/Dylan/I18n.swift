import Foundation

/// 介面多語言（繁中 / English / 한국어，照 Android `shared/.../I18n.kt`）：程式裡照舊寫中文，顯示前查表。
///
/// - 對照表 `i18n/strings.json`（從 `shared/src/main/assets/i18n/strings.json` 複製，手機、手錶、iOS 共用同一份；
///   改了要跑 `tools/i18n_ios.py --sync` 複製過來）：{"中文原文": {"en": "...", "ko": "..."}}。
/// - `"中文".tr` → 目前語言的字；`"今天記了 {0} 筆".trf(n)` → 帶參數（{0}、{1}… 可以換順序）。
/// - 查不到 → 原本的中文（不會空白）。
/// - 語言在 App 啟動時決定（設定 `ui_lang`：auto / zh / en / ko；auto 跟系統：中文 → 繁中、韓文 → 한국어、其他 → English）。
///   在 App 裡換介面語言要重新開啟 App 才會全部換過來（列舉、一開始就算好的字）。
enum I18n {
    static let key = "ui_lang"
    static let auto = "auto"

    /// zh / en / ko（啟動時算好，這次執行不變）
    static let lang: String = langOf(setting)

    /// 設定值：auto / zh / en / ko
    static var setting: String {
        get { UserDefaults.standard.string(forKey: key) ?? auto }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }

    /// 設定改了、跟這次啟動時不同 → 要重新開啟 App。
    static var needsRestart: Bool { langOf(setting) != lang }

    static func langOf(_ setting: String) -> String {
        if ["zh", "en", "ko"].contains(setting) { return setting }
        switch Locale.preferredLanguages.first.map({ Locale(identifier: $0).language.languageCode?.identifier ?? "" }) {
        case "zh": return "zh"
        case "ko": return "ko"
        default: return "en"
        }
    }

    private static let table: [String: (en: String, ko: String)] = {
        guard let url = Bundle.main.url(forResource: "strings", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: [String: String]] else { return [:] }
        var out: [String: (en: String, ko: String)] = [:]
        out.reserveCapacity(root.count)
        for (k, v) in root { out[k] = (v["en"] ?? "", v["ko"] ?? "") }
        return out
    }()

    static func tr(_ zh: String) -> String {
        switch lang {
        case "zh": return zh
        case "en": return table[zh].flatMap { $0.en.isEmpty ? nil : $0.en } ?? zh
        default: return table[zh].flatMap { $0.ko.isEmpty ? ($0.en.isEmpty ? nil : $0.en) : $0.ko } ?? zh
        }
    }

    /// {0}、{1}… 換成參數。
    static func format(_ s: String, _ args: [Any]) -> String {
        var r = s
        for (i, a) in args.enumerated() { r = r.replacingOccurrences(of: "{\(i)}", with: "\(a)") }
        return r
    }

    /// 日期格式用的 Locale（星期、月份名稱）。
    static var locale: Locale {
        switch lang { case "zh": return Locale(identifier: "zh_Hant_TW"); case "ko": return Locale(identifier: "ko_KR"); default: return Locale(identifier: "en_US") }
    }

    /// 選項：（設定值, 顯示）
    static let options: [(String, String)] = [(auto, "跟系統"), ("zh", "繁體中文"), ("en", "English"), ("ko", "한국어")]
}

extension String {
    /// 目前介面語言的字（查不到就是原本的中文）。
    var tr: String { I18n.tr(self) }
    /// 帶參數：`"今天記了 {0} 筆".trf(3)`。
    func trf(_ args: Any...) -> String { I18n.format(I18n.tr(self), args) }
}
