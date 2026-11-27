package com.example.wristai

import android.content.Context

/**
 * 待办总览：从每份报告的「待辦事項 / Action items / 할 일」章节读出事项（表格：事項 | 負責人 | 期限，或条列）。
 * 打勾的记在本机（用 录音编号＋事项文字 当钥匙）。
 */
object ActionItems {
    data class Item(val meetingId: String, val task: String, val owner: String?, val due: String?) {
        val key get() = "$meetingId|$task"
    }

    private val HEADINGS = setOf("待辦事項", "待办事项", "action items", "할 일")

    fun parse(meetingId: String, markdown: String): List<Item> {
        val out = mutableListOf<Item>()
        var inSection = false
        var headerSeen = false
        for (raw in markdown.lines()) {
            val line = raw.trim()
            if (line.startsWith("#")) {
                inSection = line.trimStart('#').trim().trim('*').trim().lowercase() in HEADINGS
                headerSeen = false
                continue
            }
            if (!inSection || line.isEmpty()) continue
            if (line.startsWith("|")) {
                val cells = line.trim('|').split('|').map { it.trim().trim('*').trim() }
                if (cells.all { c -> c.isEmpty() || c.all { it == '-' || it == ':' || it == ' ' } }) continue   // |---|---|
                if (!headerSeen) { headerSeen = true; continue }                                                    // 表头
                val task = cells.getOrNull(0).orEmpty()
                if (task.isEmpty() || ReportLang.isNone(task)) continue
                out += Item(meetingId, task, cells.getOrNull(1)?.takeUnless { blank(it) }, cells.getOrNull(2)?.takeUnless { blank(it) })
            } else if (line.startsWith("- ") || line.startsWith("* ") || line.matches(Regex("^\\d+[.、)]\\s*.+"))) {
                val task = line.replaceFirst(Regex("^([-*]|\\d+[.、)])\\s*"), "").replace("**", "").trim()
                if (task.isNotEmpty() && !ReportLang.isNone(task)) out += Item(meetingId, task, null, null)
            } else if (ReportLang.isNone(line)) continue
        }
        return out
    }

    /** 「未指定 / Unassigned / 미지정」这类当作没有。 */
    private fun blank(s: String) = s.isEmpty() || ReportLang.isNone(s) || s in setOf("未指定", "未提及", "Unassigned", "Not specified", "미지정", "-", "—")

    private fun prefs(c: Context) = c.getSharedPreferences("action_items", Context.MODE_PRIVATE)
    fun done(c: Context): Set<String> = prefs(c).getStringSet("done", emptySet()).orEmpty()
    fun setDone(c: Context, item: Item, on: Boolean) {
        val s = done(c).toMutableSet()
        if (on) s += item.key else s -= item.key
        prefs(c).edit().putStringSet("done", s).apply()
        HomeWidget.refresh(c)   // 小工具的待办数
    }
}
