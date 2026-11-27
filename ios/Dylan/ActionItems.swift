import Foundation

/// 待辦總覽（照 Android `ActionItems.kt`）：從每份報告的「待辦事項 / Action items / 할 일」章節讀出事項
/// （表格：事項 | 負責人 | 期限，或條列）。打勾的記在本機（錄音編號＋事項文字當鑰匙）。
enum ActionItems {
    struct Item: Identifiable, Hashable {
        let meetingId: String, task: String, owner: String?, due: String?
        var key: String { "\(meetingId)|\(task)" }
        var id: String { key }
    }

    private static let headings: Set<String> = ["待辦事項", "待办事项", "action items", "할 일"]
    private static let doneKey = "action_items_done"

    static func parse(_ meetingId: String, _ markdown: String) -> [Item] {
        var out: [Item] = []
        var inSection = false, headerSeen = false
        for raw in markdown.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("#") {
                let h = line.trimmingCharacters(in: CharacterSet(charactersIn: "#")).trimmingCharacters(in: .whitespaces)
                    .trimmingCharacters(in: CharacterSet(charactersIn: "*")).trimmingCharacters(in: .whitespaces).lowercased()
                inSection = headings.contains(h)
                headerSeen = false
                continue
            }
            if !inSection || line.isEmpty { continue }
            if line.hasPrefix("|") {
                let cells = line.trimmingCharacters(in: CharacterSet(charactersIn: "|")).components(separatedBy: "|")
                    .map { $0.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "*"))
                        .trimmingCharacters(in: .whitespaces) }
                if cells.allSatisfy({ c in c.isEmpty || c.allSatisfy { $0 == "-" || $0 == ":" || $0 == " " } }) { continue }
                if !headerSeen { headerSeen = true; continue }
                let task = cells.first ?? ""
                if task.isEmpty || ReportLang.isNone(task) { continue }
                func cell(_ i: Int) -> String? { i < cells.count && !blank(cells[i]) ? cells[i] : nil }
                out.append(Item(meetingId: meetingId, task: task, owner: cell(1), due: cell(2)))
            } else if line.hasPrefix("- ") || line.hasPrefix("* ") || line.range(of: #"^\d+[.、)]\s*.+"#, options: .regularExpression) != nil {
                let task = line.replacingOccurrences(of: #"^([-*]|\d+[.、)])\s*"#, with: "", options: .regularExpression)
                    .replacingOccurrences(of: "**", with: "").trimmingCharacters(in: .whitespaces)
                if !task.isEmpty && !ReportLang.isNone(task) { out.append(Item(meetingId: meetingId, task: task, owner: nil, due: nil)) }
            }
        }
        return out
    }

    /// 「未指定 / Unassigned / 미지정」這類當作沒有。
    private static func blank(_ s: String) -> Bool {
        s.isEmpty || ReportLang.isNone(s) || ["未指定", "未提及", "Unassigned", "Not specified", "미지정", "-", "—"].contains(s)
    }

    static var done: Set<String> { Set(UserDefaults.standard.stringArray(forKey: doneKey) ?? []) }
    static func setDone(_ item: Item, _ on: Bool) {
        var s = done
        if on { s.insert(item.key) } else { s.remove(item.key) }
        UserDefaults.standard.set(Array(s), forKey: doneKey)
    }

    /// 所有錄音報告的待辦（新的在前）。
    @MainActor static func all(_ store: MeetingStore) -> [(Meeting, [Item])] {
        store.meetings.filter { !$0.isFood && $0.status == .done }.compactMap { m in
            guard let md = store.report(m.id) else { return nil }
            let items = parse(m.id, md)
            return items.isEmpty ? nil : (m, items)
        }
    }
}
