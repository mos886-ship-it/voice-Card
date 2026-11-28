import UIKit

/// 報告匯出（照 Android `ReportExport.kt`）：文字、PDF（A4）、Word（.docx，自己組的 zip）、Markdown。都在手機上產生，不上傳。
enum ReportExport {
    enum Format: String, CaseIterable, Identifiable {
        case text, pdf, word, markdown
        var id: String { rawValue }
        var label: String {
            switch self { case .text: "文字".tr; case .pdf: "PDF"; case .word: "Word"; case .markdown: "Markdown" }
        }
        var ext: String { switch self { case .text: "txt"; case .pdf: "pdf"; case .word: "docx"; case .markdown: "md" } }
        var icon: String { switch self { case .text: "text.alignleft"; case .pdf: "doc.richtext"; case .word: "doc"; case .markdown: "number" } }
    }

    enum Block: Equatable {
        case heading(Int, String)
        case bullet(Int, String, String?)
        case table([[String]])
        case para(String)
    }

    /// Markdown → 區塊（標題、條列、表格、段落）。跟 Android 一樣的規則。
    static func blocks(_ md: String) -> [Block] {
        var out: [Block] = []
        var table: [[String]] = []
        func flush() { if !table.isEmpty { out.append(.table(table)); table = [] } }
        let sep = try! NSRegularExpression(pattern: "^:?-{2,}:?$")
        for raw in md.components(separatedBy: "\n") {
            let line = raw.replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression)
            let t = line.trimmingCharacters(in: .whitespaces)
            if t.hasPrefix("|") {
                let cells = t.trimmingCharacters(in: CharacterSet(charactersIn: "|")).components(separatedBy: "|")
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                if cells.allSatisfy({ sep.firstMatch(in: $0, range: NSRange($0.startIndex..., in: $0)) != nil }) { continue }
                table.append(cells)
                continue
            }
            flush()
            if t.isEmpty || t == "---" || t == "***" { continue }
            let indent = min(3, (line.count - line.drop(while: { $0 == " " }).count) / 2)
            if let r = t.range(of: "^#{1,6}\\s+", options: .regularExpression) {
                let level = t[r].filter { $0 == "#" }.count
                out.append(.heading(level, String(t[r.upperBound...]).trimmingCharacters(in: .whitespaces)))
            } else if t.hasPrefix("- ") || t.hasPrefix("* ") || t.hasPrefix("• ") {
                out.append(.bullet(indent, String(t.dropFirst(2)).trimmingCharacters(in: .whitespaces), nil))
            } else if let r = t.range(of: "^\\d+[.、)]\\s+", options: .regularExpression) {
                let n = t[r].prefix { $0.isNumber }
                out.append(.bullet(indent, String(t[r.upperBound...]), n + "."))
            } else {
                out.append(.para(t))
            }
        }
        flush()
        return out
    }

    /// **粗體** → (文字, 是否粗體)。
    static func runs(_ text: String) -> [(String, Bool)] {
        var out: [(String, Bool)] = []
        var bold = false
        for part in text.components(separatedBy: "**") { if !part.isEmpty { out.append((part, bold)) }; bold.toggle() }
        return out.isEmpty ? [("", false)] : out
    }

    static func plain(_ md: String) -> String { md.replacingOccurrences(of: "**", with: "") }

    private static func esc(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
    }

    // MARK: PDF（HTML 排版 → 系統列印引擎分頁，中日韓字都能顯示）

    static func html(title: String, md: String, footer: String = "") -> String {
        func inline(_ t: String) -> String { runs(t).map { $0.1 ? "<b>\(esc($0.0))</b>" : esc($0.0) }.joined() }
        var body = "<h1>\(esc(title))</h1>"
        for b in blocks(md) where b != .heading(1, title) {   // 報告自己的大標題跟檔名一樣：不重複
            switch b {
            case let .heading(l, t): body += "<h\(min(l + 1, 4))>\(inline(t))</h\(min(l + 1, 4))>"
            case let .bullet(d, t, n): body += "<p class=\"li\" style=\"margin-left:\(14 + d * 16)px\">\(n ?? "•") \(inline(t))</p>"
            case let .para(t): body += "<p>\(inline(t))</p>"
            case let .table(rows):
                let cols = rows.map(\.count).max() ?? 0
                body += "<table>" + rows.enumerated().map { r, row in
                    "<tr>" + (0..<cols).map { c in
                        let v = c < row.count ? inline(row[c]) : ""
                        return r == 0 ? "<th>\(v)</th>" : "<td>\(v)</td>"
                    }.joined() + "</tr>"
                }.joined() + "</table>"
            }
        }
        if !footer.isEmpty { body += "<p class=\"foot\">\(esc(footer))</p>" }
        return """
        <html><head><meta charset="utf-8"><style>
        body { font-family: -apple-system, "PingFang TC", sans-serif; font-size: 11pt; color: #212121; line-height: 1.45; }
        h1 { font-size: 18pt; margin: 0 0 10pt; } h2 { font-size: 14pt; color: #1F4E79; margin: 14pt 0 4pt; }
        h3, h4 { font-size: 12pt; color: #1F4E79; margin: 10pt 0 3pt; }
        p { margin: 0 0 5pt; } p.li { text-indent: -10px; margin-bottom: 3pt; }
        table { border-collapse: collapse; width: 100%; margin: 4pt 0 8pt; font-size: 9.5pt; }
        th, td { border: 0.5pt solid #BFBFBF; padding: 3pt 5pt; text-align: left; vertical-align: top; }
        th { background: #DCE6F1; } p.foot { color: #888; font-size: 8.5pt; margin-top: 14pt; }
        </style></head><body>\(body)</body></html>
        """
    }

    @MainActor static func pdf(title: String, md: String, footer: String = "") -> Data {
        let fmt = UIMarkupTextPrintFormatter(markupText: html(title: title, md: md, footer: footer))
        let r = UIPrintPageRenderer()
        r.addPrintFormatter(fmt, startingAtPageAt: 0)
        let page = CGRect(x: 0, y: 0, width: 595, height: 842)   // A4
        r.setValue(page, forKey: "paperRect")
        r.setValue(page.insetBy(dx: 48, dy: 48), forKey: "printableRect")
        let data = NSMutableData()
        UIGraphicsBeginPDFContextToData(data, page, nil)
        r.prepare(forDrawingPages: NSRange(location: 0, length: r.numberOfPages))
        for i in 0..<r.numberOfPages {
            UIGraphicsBeginPDFPage()
            r.drawPage(at: i, in: UIGraphicsGetPDFContextBounds())
        }
        UIGraphicsEndPDFContext()
        return data as Data
    }

    // MARK: Word

    private static func wRuns(_ text: String, size: Int? = nil, bold: Bool = false, color: String? = nil) -> String {
        runs(text).map { t, b in
            var props = ""
            if b || bold { props += "<w:b/>" }
            if let color { props += "<w:color w:val=\"\(color)\"/>" }
            if let size { props += "<w:sz w:val=\"\(size)\"/><w:szCs w:val=\"\(size)\"/>" }
            return "<w:r>" + (props.isEmpty ? "" : "<w:rPr>\(props)</w:rPr>") + "<w:t xml:space=\"preserve\">\(esc(t))</w:t></w:r>"
        }.joined()
    }

    private static func wPara(_ inner: String, after: Int = 120, indent: Int = 0, before: Int = 0) -> String {
        "<w:p><w:pPr><w:spacing w:before=\"\(before)\" w:after=\"\(after)\"/>"
            + (indent > 0 ? "<w:ind w:left=\"\(indent)\" w:hanging=\"240\"/>" : "") + "</w:pPr>\(inner)</w:p>"
    }

    static func documentXml(title: String, md: String) -> String {
        var body = wPara(wRuns(title, size: 36, bold: true), after: 240)
        for b in blocks(md) where b != .heading(1, title) {
            switch b {
            case let .heading(l, t):
                body += wPara(wRuns(t, size: l == 1 ? 32 : (l == 2 ? 28 : 24), bold: true, color: "1F4E79"), after: 100, before: 200)
            case let .bullet(d, t, n): body += wPara(wRuns((n ?? "•") + " " + t), after: 60, indent: 360 + d * 360)
            case let .para(t): body += wPara(wRuns(t))
            case let .table(rows):
                let cols = rows.map(\.count).max() ?? 0
                let grid = String(repeating: "<w:gridCol/>", count: cols)
                let trs = rows.enumerated().map { r, row in
                    "<w:tr>" + (0..<cols).map { c in
                        "<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/>"
                            + (r == 0 ? "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"DCE6F1\"/>" : "")
                            + "</w:tcPr><w:p>\(wRuns(c < row.count ? row[c] : "", bold: r == 0))</w:p></w:tc>"
                    }.joined() + "</w:tr>"
                }.joined()
                let borders = ["top", "left", "bottom", "right", "insideH", "insideV"]
                    .map { "<w:\($0) w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/>" }.joined()
                body += "<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>\(borders)</w:tblBorders></w:tblPr>"
                    + "<w:tblGrid>\(grid)</w:tblGrid>\(trs)</w:tbl>" + wPara("", after: 60)
            }
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body
            + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>"
            + "</w:body></w:document>"
    }

    static func docx(title: String, md: String) -> Data {
        let x = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        let files: [(String, String)] = [
            ("[Content_Types].xml", x + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "</Types>"),
            ("_rels/.rels", x + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>"),
            ("word/_rels/document.xml.rels", x + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>"),
            ("word/styles.xml", x + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:docDefaults><w:rPrDefault><w:rPr>"
                + "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Microsoft JhengHei\" w:cs=\"Calibri\"/>"
                + "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:rPrDefault></w:docDefaults></w:styles>"),
            ("word/document.xml", documentXml(title: title, md: md)),
        ]
        return Zip.store(files.map { ($0.0, Data($0.1.utf8)) })
    }

    // MARK: 存檔、分享

    static func safeName(_ title: String) -> String {
        let t = title.replacingOccurrences(of: "[\\\\/:*?\"<>|\\n\\r]", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        return t.isEmpty ? "report" : String(t.prefix(60))
    }

    /// 寫到暫存資料夾（分享用）。
    @MainActor static func write(title: String, md: String, format: Format, footer: String = "") -> URL? {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("export", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(safeName(title) + "." + format.ext)
        let data: Data
        switch format {
        case .text: data = Data((title + "\n\n" + plain(md) + (footer.isEmpty ? "" : "\n\n" + footer)).utf8)
        case .markdown: data = Data(("# " + title + "\n\n" + md + (footer.isEmpty ? "" : "\n\n" + footer)).utf8)
        case .pdf: data = pdf(title: title, md: md, footer: footer)
        case .word: data = docx(title: title, md: md + (footer.isEmpty ? "" : "\n\n" + footer))
        }
        do { try data.write(to: url, options: .atomic) } catch { return nil }
        return url
    }

    /// 系統分享面板。
    @MainActor static func share(_ items: [Any]) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
              var top = scene.windows.first(where: \.isKeyWindow)?.rootViewController else { return }
        while let p = top.presentedViewController { top = p }
        let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
        vc.popoverPresentationController?.sourceView = top.view
        vc.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: 80, width: 1, height: 1)
        top.present(vc, animated: true)
    }
}

/// 最簡單的 zip（不壓縮，Word 檔用）。
enum Zip {
    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func crc32(_ d: Data) -> UInt32 {
        var c: UInt32 = 0xFFFFFFFF
        for b in d { c = table[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFFFFFF
    }

    static func store(_ files: [(String, Data)]) -> Data {
        var out = Data(), central = Data()
        func u16(_ v: Int) -> Data { withUnsafeBytes(of: UInt16(v).littleEndian) { Data($0) } }
        func u32(_ v: UInt32) -> Data { withUnsafeBytes(of: v.littleEndian) { Data($0) } }
        for (name, data) in files {
            let n = Data(name.utf8), crc = crc32(data), offset = UInt32(out.count)
            out += u32(0x04034b50) + u16(20) + u16(0x0800) + u16(0) + u16(0) + u16(0x21)
            out += u32(crc) + u32(UInt32(data.count)) + u32(UInt32(data.count)) + u16(n.count) + u16(0) + n + data
            central += u32(0x02014b50) + u16(20) + u16(20) + u16(0x0800) + u16(0) + u16(0) + u16(0x21)
            central += u32(crc) + u32(UInt32(data.count)) + u32(UInt32(data.count)) + u16(n.count) + u16(0) + u16(0) + u16(0) + u16(0)
            central += u32(0) + u32(offset) + n
        }
        let start = UInt32(out.count)
        out += central
        out += u32(0x06054b50) + u16(0) + u16(0) + u16(files.count) + u16(files.count) + u32(UInt32(central.count)) + u32(start) + u16(0)
        return out
    }
}
