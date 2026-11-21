package com.example.wristai

/**
 * 说话者代号（說話者A、說話者B…）↔ 真实名字。
 *
 * - Gemini 在逐字稿里一律用代号，并在报告的「## 與會者」表格写出听得出来的名字。
 * - 名字存进 meta.json（Meeting.speakerNames），使用者可以在逐字稿页面手动改名。
 */
object Speakers {
    private val UNKNOWN = setOf("", "未知", "無", "不明", "未指定", "未提及", "-", "—", "N/A")

    /** 从报告「說話者」（旧版「與會者」）表格取出 代号 → 名字（名字栏是未知的略过）。 */
    fun namesFromReport(markdown: String): Map<String, String> {
        val lines = markdown.lines()
        // 新版报告的章节叫「說話者」，旧版叫「與會者」
        val start = lines.indexOfFirst { l ->
            val t = l.trim()
            t.startsWith("#") && (t.contains("與會者") || t.trimStart('#').trim() == "說話者")
        }
        if (start < 0) return emptyMap()
        val out = linkedMapOf<String, String>()
        var header: List<String>? = null
        for (raw in lines.drop(start + 1)) {
            val t = raw.trim()
            if (t.startsWith("#")) break
            if (!t.startsWith("|")) continue
            val cells = t.trim('|').split("|").map { it.replace("**", "").trim() }
            if (cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } }) continue
            val h = header
            if (h == null) { header = cells; continue }
            val nameIdx = h.indexOfFirst { it.contains("名字") || it.contains("姓名") || it.contains("稱呼") }
                .takeIf { it > 0 } ?: 1
            val label = cells.getOrNull(0).orEmpty()
            val name = cells.getOrNull(nameIdx).orEmpty()
            if (label.isNotEmpty() && name !in UNKNOWN && !name.startsWith("未知")) out[label] = name
        }
        return out
    }

    /** 把文字里的代号换成名字（长的代号先换，避免「說話者A」吃掉「說話者AB」）。 */
    fun apply(text: String, names: Map<String, String>): String {
        var r = text
        for ((k, v) in names.entries.sortedByDescending { it.key.length }) {
            if (k.isNotBlank() && v.isNotBlank() && k != v) r = r.replace(k, v)
        }
        return r
    }
}
