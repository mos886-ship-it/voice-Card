import Foundation

/// AI 整理出來的結果：標題 + 給手錶的短摘要 + 完整 Markdown 報告（照 Android `GeneratedReport`）。
struct GeneratedReport: Equatable {
    var title: String
    var shortSummary: String
    var markdown: String

    /// 解析「TITLE: / SUMMARY: / Markdown」格式（照 Android `ReportGenerator.parseReport`）。
    static func parse(_ text: String) -> GeneratedReport {
        let lines = text.components(separatedBy: "\n")
        var title = "錄音紀錄"
        var summary = ""
        var bodyStart = 0
        for (i, line) in lines.prefix(5).enumerated() {
            let t = line.trimmingCharacters(in: .whitespaces)
            if t.lowercased().hasPrefix("title:") {
                let v = t.dropFirst(6).trimmingCharacters(in: .whitespaces)
                if !v.isEmpty { title = v }
                bodyStart = i + 1
            } else if t.lowercased().hasPrefix("summary:") {
                summary = t.dropFirst(8).trimmingCharacters(in: .whitespaces)
                bodyStart = i + 1
            }
        }
        let body = fillEmptySummary(lines.dropFirst(bodyStart).joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines), summary: summary)
        return GeneratedReport(title: title,
                               shortSummary: summary.isEmpty ? String(body.prefix(80)) : summary,
                               markdown: "# \(title)\n\n\(body)")
    }

    /// 「## 摘要」底下只有「無」或空白時，用 SUMMARY 那一行補上（照 Android `fillEmptySummary`）。
    static func fillEmptySummary(_ body: String, summary: String) -> String {
        guard !summary.isEmpty else { return body }
        var lines = body.components(separatedBy: "\n")
        guard let h = lines.firstIndex(where: {
            let t = $0.trimmingCharacters(in: .whitespaces)
            return t.hasPrefix("#") && t.trimmingCharacters(in: CharacterSet(charactersIn: "# ")) == "摘要"
        }) else { return body }
        var end = h + 1
        while end < lines.count, !lines[end].trimmingCharacters(in: .whitespaces).hasPrefix("#") { end += 1 }
        let content = lines[(h + 1)..<end].map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        let empty: Set<String> = ["無", "无", "（無）", "-", "—"]
        if !content.isEmpty, content.contains(where: { !empty.contains($0) }) { return body }
        lines.removeSubrange((h + 1)..<end)
        lines.insert(summary, at: h + 1)
        return lines.joined(separator: "\n")
    }

    /// 報告「## 摘要」第一段（主頁「最新報告」用）。
    static func summarySection(_ markdown: String) -> String {
        let lines = markdown.components(separatedBy: "\n")
        guard let s = lines.firstIndex(where: { $0.trimmingCharacters(in: .whitespaces).hasPrefix("##") && $0.contains("摘要") })
        else { return "" }
        return lines[(s + 1)...].prefix { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("#") }
            .map { $0.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "**", with: "") }
            .map { $0.hasPrefix("- ") ? String($0.dropFirst(2)) : $0 }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }
}

/// 說話者代號（說話者A、說話者B…）↔ 名字（照 Android `Speakers`）。
enum Speakers {
    private static let unknown: Set<String> = ["", "未知", "無", "不明", "未指定", "未提及", "-", "—", "N/A"]

    /// 從報告「說話者」（舊版「與會者」）表格取出 代號 → 名字（名字是未知的略過）。
    static func names(fromReport markdown: String) -> [String: String] {
        let lines = markdown.components(separatedBy: "\n")
        guard let start = lines.firstIndex(where: {
            let t = $0.trimmingCharacters(in: .whitespaces)
            return t.hasPrefix("#") && (t.contains("與會者") || t.trimmingCharacters(in: CharacterSet(charactersIn: "# ")) == "說話者")
        }) else { return [:] }
        var out: [String: String] = [:]
        var header: [String]?
        for raw in lines[(start + 1)...] {
            let t = raw.trimmingCharacters(in: .whitespaces)
            if t.hasPrefix("#") { break }
            guard t.hasPrefix("|") else { continue }
            let cells = tableCells(t)
            if cells.allSatisfy({ c in !c.isEmpty && c.allSatisfy { $0 == "-" || $0 == ":" } }) { continue }
            guard let h = header else { header = cells; continue }
            let nameIdx = h.firstIndex { $0.contains("名字") || $0.contains("姓名") || $0.contains("稱呼") }.flatMap { $0 > 0 ? $0 : nil } ?? 1
            let label = cells.first ?? ""
            let name = nameIdx < cells.count ? cells[nameIdx] : ""
            if !label.isEmpty, !unknown.contains(name), !name.hasPrefix("未知") { out[label] = name }
        }
        return out
    }

    /// 把文字裡的代號換成名字（長的代號先換）。
    static func apply(_ text: String, _ names: [String: String]) -> String {
        var r = text
        for (k, v) in names.sorted(by: { $0.key.count > $1.key.count }) where !k.isEmpty && !v.isEmpty && k != v {
            r = r.replacingOccurrences(of: k, with: v)
        }
        return r
    }

    static func tableCells(_ line: String) -> [String] {
        line.trimmingCharacters(in: CharacterSet(charactersIn: "| "))
            .components(separatedBy: "|")
            .map { $0.replacingOccurrences(of: "**", with: "").trimmingCharacters(in: .whitespaces) }
    }
}
