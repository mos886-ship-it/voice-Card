import SwiftUI

/// 存到 Notion（照 Android `NotionExport.kt`）：使用者自己建一個 Notion「內部整合」，金鑰貼到「錄音 → 設定」（存在鑰匙圈），
/// 再把要放報告的頁面連結給那個整合（頁面右上 ⋯ → 連結）。每份報告存成那頁底下的子頁面。金鑰只用來呼叫 api.notion.com。
enum NotionExport {
    private static let api = "https://api.notion.com/v1"
    private static let version = "2022-06-28"
    private static let maxText = 1900
    private static let batch = 90
    static let tokenAccount = "notion_token", parentKey = "notion_parent"

    static var token: String { Keychain.get(tokenAccount) ?? "" }
    static var parent: String { UserDefaults.standard.string(forKey: parentKey) ?? "" }
    static var configured: Bool { !token.isEmpty && pageId(parent) != nil }

    /// 頁面連結或 id → 加上連字號的 id。
    static func pageId(_ raw: String) -> String? {
        let dashed = try! NSRegularExpression(pattern: "(?<![0-9a-fA-F])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![0-9a-fA-F])")
        let plain = try! NSRegularExpression(pattern: "(?<![0-9a-fA-F])[0-9a-fA-F]{32}(?![0-9a-fA-F])")
        let r = NSRange(raw.startIndex..., in: raw)
        var hex: String?
        if let m = dashed.matches(in: raw, range: r).last, let rr = Range(m.range, in: raw) { hex = raw[rr].replacingOccurrences(of: "-", with: "") }
        else if let m = plain.matches(in: raw, range: r).last, let rr = Range(m.range, in: raw) { hex = String(raw[rr]) }
        guard let h = hex?.lowercased(), h.count == 32 else { return nil }
        let a = Array(h)
        return [String(a[0..<8]), String(a[8..<12]), String(a[12..<16]), String(a[16..<20]), String(a[20...])].joined(separator: "-")
    }

    private static func chunks(_ s: String) -> [String] {
        guard s.count > maxText else { return [s] }
        var out: [String] = [], rest = Substring(s)
        while !rest.isEmpty { out.append(String(rest.prefix(maxText))); rest = rest.dropFirst(maxText) }
        return out
    }

    private static func rich(_ text: String) -> [[String: Any]] {
        ReportExport.runs(text).flatMap { t, bold in
            chunks(t).map { c -> [String: Any] in
                var o: [String: Any] = ["type": "text", "text": ["content": c]]
                if bold { o["annotations"] = ["bold": true] }
                return o
            }
        }
    }

    private static func block(_ type: String, _ text: String) -> [String: Any] {
        ["object": "block", "type": type, type: ["rich_text": rich(text)]]
    }

    /// Markdown → Notion 區塊。
    static func blocks(_ md: String) -> [[String: Any]] {
        ReportExport.blocks(md).flatMap { b -> [[String: Any]] in
            switch b {
            case let .heading(l, t): return [block(l <= 1 ? "heading_1" : (l == 2 ? "heading_2" : "heading_3"), t)]
            case let .bullet(d, t, n): return [block(n != nil ? "numbered_list_item" : "bulleted_list_item", String(repeating: "  ", count: d) + t)]
            case let .para(t): return chunks(t).map { block("paragraph", $0) }
            case let .table(rows):
                let w = rows.map(\.count).max() ?? 1
                let children = rows.map { row -> [String: Any] in
                    ["object": "block", "type": "table_row",
                     "table_row": ["cells": (0..<w).map { rich($0 < row.count ? row[$0] : "") }]]
                }
                return [["object": "block", "type": "table",
                         "table": ["table_width": w, "has_column_header": true, "has_row_header": false, "children": children]]]
            }
        }
    }

    struct Failure: LocalizedError { let message: String; var errorDescription: String? { message } }

    private static func call(_ method: String, _ path: String, _ body: [String: Any]) async throws -> [String: Any] {
        var r = URLRequest(url: URL(string: api + path)!, timeoutInterval: 30)
        r.httpMethod = method
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        r.setValue(version, forHTTPHeaderField: "Notion-Version")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, resp) = try await URLSession.shared.data(for: r)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let j = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        guard (200...299).contains(code) else {
            switch code {
            case 401: throw Failure(message: "Notion 金鑰不對".tr)
            case 404: throw Failure(message: "找不到頁面：請在 Notion 頁面右上 ⋯ → 連結，把它連結給你的整合".tr)
            default: throw Failure(message: "Notion 回應錯誤（{0}）：{1}".trf(code, j["message"] as? String ?? ""))
            }
        }
        return j
    }

    /// 存成子頁面，回傳網址。
    static func save(title: String, md: String) async throws -> String {
        guard let parentId = pageId(parent) else { throw Failure(message: "請先在設定填 Notion 頁面連結".tr) }
        let all = blocks(md)
        let page = try await call("POST", "/pages", [
            "parent": ["page_id": parentId],
            "properties": ["title": ["title": rich(String(title.prefix(200)))]],
            "children": Array(all.prefix(batch)),
        ])
        guard let id = page["id"] as? String else { throw Failure(message: "Notion 回應錯誤（{0}）：{1}".trf(200, "id")) }
        var rest = Array(all.dropFirst(batch))
        while !rest.isEmpty {
            _ = try await call("PATCH", "/blocks/\(id)/children", ["children": Array(rest.prefix(batch))])
            rest = Array(rest.dropFirst(batch))
        }
        return page["url"] as? String ?? ""
    }
}

/// 錄音 → 設定：存到 Notion。
struct NotionCard: View {
    @AppStorage(NotionExport.parentKey) private var parent = ""
    @State private var token = NotionExport.token

    var body: some View {
        GlassCard {
            Text("存到 Notion".tr).font(.titleSmall).foregroundStyle(Brand.text)
            Text("到 notion.so/my-integrations 建一個「內部整合」，把金鑰貼在下面；再到要放報告的頁面右上 ⋯ → 連結，選這個整合，然後貼上那頁的連結。之後報告的分享選單就有「存到 Notion」。".tr)
                .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.vertical, 6)
            SecureField("Notion 金鑰（ntn_… 或 secret_…）".tr, text: $token)
                .textFieldStyle(.roundedBorder).autocorrectionDisabled().textInputAutocapitalization(.never)
                .onChange(of: token) { Keychain.set($0.trimmingCharacters(in: .whitespacesAndNewlines), for: NotionExport.tokenAccount) }
            TextField("頁面連結".tr, text: $parent)
                .textFieldStyle(.roundedBorder).autocorrectionDisabled().textInputAutocapitalization(.never).padding(.top, 6)
            Text(NotionExport.configured ? "已設定".tr : (parent.isEmpty || NotionExport.pageId(parent) != nil ? "" : "看不懂這個頁面連結".tr))
                .font(.labelMedium).foregroundStyle(NotionExport.configured ? Brand.success : Brand.warning).padding(.top, 4)
        }
    }
}
