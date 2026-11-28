package com.example.wristai

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 報告匯出：Markdown（.md）、Word（.docx）、PDF。Word 跟 PDF 都在手機上產生，不上傳。
 * 支援報告用到的 Markdown：# / ## / ### 標題、- 條列（含縮排）、1. 編號、| 表格 |、**粗體**、一般段落。
 * 檔案放在 cache/share/（[FileProvider] 開放的資料夾），用系統分享送出。
 */
object ReportExport {
    enum class Format(val ext: String, val mime: String) {
        MARKDOWN("md", "text/markdown"),
        WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        PDF("pdf", "application/pdf"),
    }

    // ---------- Markdown → 區塊 ----------

    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Bullet(val depth: Int, val text: String, val number: String? = null) : Block()
        data class Table(val rows: List<List<String>>) : Block()
        data class Para(val text: String) : Block()
    }

    fun blocks(md: String): List<Block> {
        val out = mutableListOf<Block>()
        val table = mutableListOf<List<String>>()
        fun flushTable() { if (table.isNotEmpty()) { out += Block.Table(table.toList()); table.clear() } }
        for (raw in md.lines()) {
            val line = raw.trimEnd()
            val t = line.trim()
            if (t.startsWith("|")) {
                val cells = t.trim('|').split('|').map { it.trim() }
                if (cells.all { it.matches(Regex(":?-{2,}:?")) }) continue     // |---|---| 分隔線
                table += cells
                continue
            }
            flushTable()
            if (t.isEmpty()) continue
            val h = Regex("^(#{1,6})\\s+(.*)$").find(t)
            val num = Regex("^(\\d+)[.、)]\\s+(.*)$").find(t)
            val indent = line.length - line.trimStart().length
            when {
                h != null -> out += Block.Heading(h.groupValues[1].length, h.groupValues[2].trim())
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") ->
                    out += Block.Bullet((indent / 2).coerceAtMost(3), t.substring(2).trim())
                num != null -> out += Block.Bullet((indent / 2).coerceAtMost(3), num.groupValues[2], num.groupValues[1] + ".")
                t == "---" || t == "***" -> {}
                else -> out += Block.Para(t)
            }
        }
        flushTable()
        return out
    }

    /** **粗體** → (文字, 是否粗體) 片段。 */
    fun runs(text: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        var bold = false
        for (part in text.split("**")) { if (part.isNotEmpty()) out += part to bold; bold = !bold }
        return out.ifEmpty { listOf("" to false) }
    }

    private fun plain(text: String) = text.replace("**", "")

    // ---------- Word ----------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun wRuns(text: String, size: Int? = null, forceBold: Boolean = false, color: String? = null) = runs(text).joinToString("") { (t, b) ->
        val props = buildString {
            if (b || forceBold) append("<w:b/>")
            if (color != null) append("<w:color w:val=\"$color\"/>")
            if (size != null) append("<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/>")
        }
        "<w:r>" + (if (props.isNotEmpty()) "<w:rPr>$props</w:rPr>" else "") + "<w:t xml:space=\"preserve\">${esc(t)}</w:t></w:r>"
    }

    private fun wPara(inner: String, spacingAfter: Int = 120, indentTwips: Int = 0, before: Int = 0) =
        "<w:p><w:pPr><w:spacing w:before=\"$before\" w:after=\"$spacingAfter\"/>" +
            (if (indentTwips > 0) "<w:ind w:left=\"$indentTwips\" w:hanging=\"240\"/>" else "") + "</w:pPr>$inner</w:p>"

    /** word/document.xml 的內容（測試用得到）。 */
    fun documentXml(title: String, md: String): String {
        val body = StringBuilder()
        body.append(wPara(wRuns(title, size = 36, forceBold = true), spacingAfter = 240))
        for (b in blocks(md)) body.append(when (b) {
            is Block.Heading -> wPara(wRuns(b.text, size = when (b.level) { 1 -> 32; 2 -> 28; else -> 24 }, forceBold = true, color = "1F4E79"),
                spacingAfter = 100, before = 200)
            is Block.Bullet -> wPara(wRuns((b.number ?: "•") + " " + b.text), spacingAfter = 60, indentTwips = 360 + b.depth * 360)
            is Block.Para -> wPara(wRuns(b.text))
            is Block.Table -> {
                val cols = b.rows.maxOf { it.size }
                val grid = (0 until cols).joinToString("") { "<w:gridCol/>" }
                val rows = b.rows.mapIndexed { r, row ->
                    "<w:tr>" + (0 until cols).joinToString("") { c ->
                        "<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/>" +
                            (if (r == 0) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"DCE6F1\"/>" else "") +
                            "</w:tcPr><w:p>${wRuns(row.getOrElse(c) { "" }, forceBold = r == 0)}</w:p></w:tc>"
                    } + "</w:tr>"
                }.joinToString("")
                "<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>" +
                    listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") {
                        "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/>"
                    } + "</w:tblBorders></w:tblPr><w:tblGrid>$grid</w:tblGrid>$rows</w:tbl>" + wPara("", spacingAfter = 60)
            }
        })
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>" +
            "</w:body></w:document>"
    }

    fun docx(title: String, md: String): ByteArray {
        val files = linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                "</Types>",
            "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                "</Relationships>",
            "word/_rels/document.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
                "</Relationships>",
            "word/styles.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:docDefaults><w:rPrDefault><w:rPr>" +
                "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Microsoft JhengHei\" w:cs=\"Calibri\"/>" +
                "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:rPrDefault></w:docDefaults></w:styles>",
            "word/document.xml" to documentXml(title, md),
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, text) in files) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    // ---------- PDF（A4，手機內建字型，中日韓都能顯示）----------

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 48f

    fun pdf(title: String, md: String, out: File, footer: String = "") {
        val doc = PdfDocument()
        val width = (PAGE_W - 2 * MARGIN).toInt()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            y = MARGIN
            if (footer.isNotEmpty() || pageNo > 1) {
                val p = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.GRAY }
                page!!.canvas.drawText(listOf(footer, "$pageNo").filter { it.isNotEmpty() }.joinToString("  ·  "), MARGIN, PAGE_H - 24f, p)
            }
        }
        fun paint(size: Float, bold: Boolean = false, color: Int = Color.rgb(33, 33, 33)) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        fun layout(text: CharSequence, p: TextPaint, w: Int) = StaticLayout.Builder.obtain(text, 0, text.length, p, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1.1f).build()
        /** 一段文字，放不下就換頁（逐行切）。 */
        fun draw(text: CharSequence, p: TextPaint, x: Float = MARGIN, w: Int = width, gapAfter: Float = 6f) {
            val l = layout(text, p, w)
            var line = 0
            while (line < l.lineCount) {
                if (page == null || y + (l.getLineBottom(line) - l.getLineTop(line)) > PAGE_H - MARGIN) newPage()
                var last = line
                while (last + 1 < l.lineCount && y + (l.getLineBottom(last + 1) - l.getLineTop(line)) <= PAGE_H - MARGIN) last++
                val c = page!!.canvas
                c.save()
                c.translate(x, y - l.getLineTop(line))
                c.clipRect(0f, l.getLineTop(line).toFloat(), w.toFloat(), l.getLineBottom(last).toFloat())
                l.draw(c)
                c.restore()
                y += (l.getLineBottom(last) - l.getLineTop(line))
                line = last + 1
            }
            y += gapAfter
        }
        fun styled(text: String): CharSequence {
            val sb = android.text.SpannableStringBuilder()
            for ((t, b) in runs(text)) {
                val start = sb.length
                sb.append(t)
                if (b) sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, sb.length, 0)
            }
            return sb
        }

        newPage()
        draw(title, paint(20f, bold = true), gapAfter = 14f)
        val accent = Color.rgb(31, 78, 121)
        for (b in blocks(md)) when (b) {
            is Block.Heading -> { y += 6f; draw(plain(b.text), paint(if (b.level <= 1) 16f else if (b.level == 2) 14f else 12f, true, accent), gapAfter = 6f) }
            is Block.Para -> draw(styled(b.text), paint(10.5f))
            is Block.Bullet -> {
                val indent = 12f + b.depth * 14f
                draw(styled((b.number ?: "•") + " " + b.text), paint(10.5f), x = MARGIN + indent, w = (width - indent).toInt(), gapAfter = 3f)
            }
            is Block.Table -> {
                val cols = b.rows.maxOf { it.size }.coerceAtLeast(1)
                val cw = width / cols
                for ((r, row) in b.rows.withIndex()) {
                    val p = paint(9.5f, bold = r == 0)
                    val cells = (0 until cols).map { layout(plain(row.getOrElse(it) { "" }), p, cw - 8) }
                    val h = cells.maxOf { it.height } + 8f
                    if (page == null || y + h > PAGE_H - MARGIN) newPage()
                    val c = page!!.canvas
                    val line = android.graphics.Paint().apply { color = Color.rgb(191, 191, 191); style = android.graphics.Paint.Style.STROKE; strokeWidth = 0.6f }
                    if (r == 0) c.drawRect(MARGIN, y, MARGIN + cw * cols, y + h, android.graphics.Paint().apply { color = Color.rgb(220, 230, 241) })
                    for ((i, l) in cells.withIndex()) {
                        c.drawRect(MARGIN + i * cw, y, MARGIN + (i + 1) * cw, y + h, line)
                        c.save(); c.translate(MARGIN + i * cw + 4f, y + 4f); l.draw(c); c.restore()
                    }
                    y += h
                }
                y += 8f
            }
        }
        page?.let { doc.finishPage(it) }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    // ---------- 存檔＋分享 ----------

    fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(60).ifEmpty { "report" }

    /** 產生檔案（cache/share/），回傳檔案。在背景執行緒呼叫。 */
    fun write(c: Context, title: String, md: String, f: Format, footer: String = ""): File {
        val dir = File(c.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, safeName(title) + "." + f.ext)
        when (f) {
            Format.MARKDOWN -> out.writeText("# $title\n\n$md\n")
            Format.WORD -> out.writeBytes(docx(title, md))
            Format.PDF -> pdf(title, md, out, footer)
        }
        return out
    }

    fun share(c: Context, file: File, f: Format, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType(f.mime).putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
