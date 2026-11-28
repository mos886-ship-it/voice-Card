import Foundation

/// 報告的「領域專家模式」：法律、醫療、財務…（照 Android `ReportDomain.kt`；清單是共用的 report_domains.json，
/// 由 `tools/i18n_ios.py --sync` 複製到 ios/Dylan/）。
///
/// 設定 `report_domain`：auto（AI 自動判斷領域，一般對話不加）/ off（關閉）/ 領域 id。
/// 開著時報告多一節「## 專業重點」（第一行「領域：○○」）；法律、醫療、財務會加免責說明。
/// `ReportLang.localize` 先用 `applyTo` 把規則加進報告提示詞（飲食、分段筆記不加），大D的主機收 X-Domain。
enum ReportDomain {
    static let auto = "auto", off = "off"
    static let key = "report_domain"

    struct Domain: Decodable { let id: String; let name: String; let focus: String; let disclaimer: String?; let extra: String? }
    struct Table: Decodable { let section: String; let common: String; let auto: String; let domains: [Domain] }

    static let table: Table? = {
        guard let u = Bundle.main.url(forResource: "report_domains", withExtension: "json"),
              let d = try? Data(contentsOf: u) else { return nil }
        return try? JSONDecoder().decode(Table.self, from: d)
    }()

    static var setting: String {
        get { UserDefaults.standard.string(forKey: key) ?? auto }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }

    /// 設定值 → 加進提示詞的規則（中文；關閉或不認得 → 空字串）。跟 Android 一字不差。
    static func rules(_ t: Table, _ setting: String) -> String {
        switch setting {
        case off: return ""
        case auto:
            let focus = t.domains.map { d in
                "- \(d.name)：\(d.focus)" + (d.disclaimer.map { "（報告最後加一行：「\($0)」）" } ?? "")
            }.joined(separator: "\n")
            return t.auto.replacingOccurrences(of: "{names}", with: t.domains.map(\.name).joined(separator: "、"))
                .replacingOccurrences(of: "{focus}", with: focus)
        default:
            guard let d = t.domains.first(where: { $0.id == setting }) else { return "" }
            return t.common.replacingOccurrences(of: "【領域專家模式】", with: "【領域專家模式：\(d.name)】")
                + "\n這個領域要注意的事項：\(d.focus)。"
                + (d.extra.map { "\n" + $0 } ?? "")
                + (d.disclaimer.map { "\n報告最後加一行：「\($0)」" } ?? "")
        }
    }

    static var activeRules: String { table.map { rules($0, setting) } ?? "" }

    /// 報告提示詞（有「待辦事項」章節的那種）才加；飲食、分段筆記、已經加過的不加。
    static func applyTo(_ prompt: String, rules: String = activeRules) -> String {
        if rules.isEmpty || !prompt.contains("## 待辦事項") || prompt.contains("NUTRITION") || prompt.contains("【領域專家模式") {
            return prompt
        }
        return prompt.trimmingCharacters(in: .whitespacesAndNewlines) + "\n\n" + rules
    }

    /// 設定頁的選項：自動、關閉、各領域。
    static var options: [(id: String, name: String)] {
        [(auto, "自動判斷".tr), (off, "關閉".tr)] + (table?.domains.map { ($0.id, $0.name.tr) } ?? [])
    }
}
