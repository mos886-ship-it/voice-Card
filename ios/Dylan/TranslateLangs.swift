import Foundation
import Speech
#if canImport(Translation)
import Translation
#endif

/// 即時翻譯的語言（照 Android `TranslateLangs`）：不再寫死，直接問手機。
/// - 說的：`SFSpeechRecognizer.supportedLocales()`（存辨識代碼，例如 zh-TW、en-GB，可以選地區口音），標「手機上」／「線上」。
/// - 翻成：iOS 18 的 Apple 翻譯支援的語言（`LanguageAvailability`）；問不到時用常見的 5 種。
/// 設定：`translate_source`（說的，跟錄音語言分開）、`translate_target`（翻成）、`translate_recent`（最近用過）。
@MainActor
enum TranslateLangs {
    static let sourceKey = "translate_source", recentKey = "translate_recent"

    struct Source: Identifiable, Hashable {
        let id: String        // zh-TW、en-US…
        let name: String
        let onDevice: Bool
    }

    /// 手機能辨識的語言（依名稱排序）。
    static func sources() -> [Source] {
        SFSpeechRecognizer.supportedLocales().map { l in
            let id = l.identifier.replacingOccurrences(of: "_", with: "-")
            return Source(id: id, name: name(id), onDevice: SFSpeechRecognizer(locale: l)?.supportsOnDeviceRecognition ?? false)
        }
        .sorted { $0.name.localizedCompare($1.name) == .orderedAscending }
    }

    /// 常見的翻成語言（iOS 17 以下、或問不到時）。
    nonisolated static let fallbackTargets = ["zh-Hant", "en", "ja", "ko", "vi", "th", "id", "fr", "de", "es"]

    /// Apple 翻譯支援的語言代碼（zh-Hant、en、ja…）。
    static func targets() async -> [String] {
        #if canImport(Translation)
        if #available(iOS 18.0, *) {
            let langs = await LanguageAvailability().supportedLanguages
            let ids = Set(langs.compactMap { l -> String? in
                guard let code = l.languageCode?.identifier else { return nil }
                if code == "zh" { return l.script?.identifier == "Hans" ? "zh-Hans" : "zh-Hant" }
                return code
            })
            if !ids.isEmpty { return ids.sorted { name($0).localizedCompare(name($1)) == .orderedAscending } }
        }
        #endif
        return fallbackTargets
    }

    /// 顯示名稱（照 App 介面語言）。
    nonisolated static func name(_ id: String) -> String {
        let ui = Locale(identifier: I18n.lang == "zh" ? "zh-Hant-TW" : I18n.lang)
        return ui.localizedString(forIdentifier: id) ?? id
    }

    /// 辨識代碼 → 翻譯框架的來源語言（中文、粵語都當繁體中文翻；zh-CN 當簡體）。
    nonisolated static func translationId(source id: String) -> String {
        let l = id.lowercased()
        if l.hasPrefix("zh-cn") || l.hasPrefix("zh-hans") { return "zh-Hans" }
        if l.hasPrefix("zh") || l.hasPrefix("yue") || l.hasPrefix("cmn") { return "zh-Hant" }
        return String(id.split(separator: "-").first ?? Substring(id))
    }

    /// 同一個語言嗎（來源辨識代碼 vs 翻成代碼）。
    nonisolated static func same(source: String, target: String) -> Bool {
        translationId(source: source).split(separator: "-").first == target.split(separator: "-").first
    }

    /// 錄音語言 → 辨識代碼。
    static func sourceId(_ l: SpeechLang) -> String {
        l == .zh ? "zh-TW" : l.locale.identifier.replacingOccurrences(of: "_", with: "-")
    }

    /// 說的語言：存過的 → 錄音語言（手機辨識得了的）→ 手機地區 → 台灣中文。
    static var source: String {
        get {
            if let s = UserDefaults.standard.string(forKey: sourceKey), !s.isEmpty { return s }
            let rec = LangSettings.shared.lang
            if LiveCaptions.supported(rec) { return sourceId(rec) }
            // 台語、客語、粵語手機辨識不了：在台灣多半也會說國語 → 台灣國語
            if [.nan, .hak, .yue].contains(rec) { return "zh-TW" }
            // 手機地區：只用辨識清單裡真的有的（不要湊出 zh-Hant-CN 這種）
            let cur = Locale.current.identifier.replacingOccurrences(of: "_", with: "-")
            let all = SFSpeechRecognizer.supportedLocales().map { $0.identifier.replacingOccurrences(of: "_", with: "-") }
            if all.contains(cur) { return cur }
            let lang = Locale.current.language.languageCode?.identifier ?? "zh"
            if lang == "zh" { return "zh-TW" }
            return all.first { $0.hasPrefix(lang + "-") } ?? "zh-TW"
        }
        set {
            UserDefaults.standard.set(newValue, forKey: sourceKey)
            var r = recent.filter { $0 != newValue }
            r.insert(newValue, at: 0)
            UserDefaults.standard.set(Array(r.prefix(6)), forKey: recentKey)
        }
    }
    static var recent: [String] { UserDefaults.standard.stringArray(forKey: recentKey) ?? [] }

    /// 翻成：存過的 → 介面語言；跟說的一樣就中↔英。
    static func target(for source: String) -> String {
        let saved = UserDefaults.standard.string(forKey: LiveCaptions.targetKey).flatMap { $0.isEmpty ? nil : $0 }
        let legacy = ["zh": "zh-Hant"]   // 舊版存的是 zh
        let ui = saved.map { legacy[$0] ?? $0 } ?? (I18n.lang == "zh" ? "zh-Hant" : I18n.lang)
        if !same(source: source, target: ui) { return ui }
        return ui.hasPrefix("zh") ? "en" : "zh-Hant"
    }

    /// 翻成的語言 → 辨識代碼（對調方向用）：最近用過 → 手機地區 → 常見口音。
    static func speechLocale(for target: String) -> String? {
        let code = String(target.split(separator: "-").first ?? "")
        let all = SFSpeechRecognizer.supportedLocales().map { $0.identifier.replacingOccurrences(of: "_", with: "-") }
        let match = all.filter { translationId(source: $0).split(separator: "-").first.map(String.init) == code }
        if let r = recent.first(where: { match.contains($0) }) { return r }
        let preferred = ["zh-TW", "en-US", "ja-JP", "ko-KR", "vi-VN", "th-TH", "id-ID", "fr-FR", "de-DE", "es-ES"]
        if let p = preferred.first(where: { match.contains($0) }) { return p }
        if let region = Locale.current.region?.identifier, let m = match.first(where: { $0.hasSuffix("-" + region) }) { return m }
        return match.first
    }
}
