package com.example.wristai

import android.content.Context
import com.example.wristai.ui.origin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 报告搜寻（手机「錄音」页与手表「報告書」共用）。
 *
 * - 每笔录音建一份可搜寻的文字：标题、日期（2026/11/19、11/19、11月19日）、来源、地点、说话者名字、报告、逐字稿。
 * - 有快取：档案没变就不重读（上百笔录音每打一个字也不卡）。
 * - 关键字以空白分隔，每个都要出现（不分大小写、顺序不拘）。
 * - [snippet] 找出第一个符合的地方，前后各留一些字，给清单显示「在哪里找到」。
 */
object SearchIndex {
    /** 一笔录音可搜寻的内容。[parts] 各段的名称（标题 / 日期 / 地點 / 說話者 / 報告 / 逐字稿）对应原文。 */
    class Doc(val stamp: String, val parts: List<Pair<String, String>>) {
        val lower: String = parts.joinToString("\n") { it.second }.lowercase()
    }

    private val cache = ConcurrentHashMap<String, Doc>()

    fun terms(query: String): List<String> = query.lowercase().split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** 取（或重建）一笔录音的搜寻资料。会读档，请在背景执行。 */
    fun doc(context: Context, m: Meeting): Doc {
        val report = MeetingStore.reportFile(context, m.id)
        val transcript = MeetingStore.transcriptFile(context, m.id)
        val geo = MeetingStore.geo(context, m.id)
        val stamp = listOf(
            report.lastModified(), transcript.lastModified(), m.title, m.speakerNames, geo?.place, m.createdAt, m.status,
        ).joinToString("|")
        cache[m.id]?.takeIf { it.stamp == stamp }?.let { return it }
        val names = m.speakerNames
        val date = Date(m.createdAt)
        val parts = listOf(
            "標題" to com.example.wristai.ui.meetingTitle(m),
            "日期" to listOf("yyyy/MM/dd", "yyyy/M/d", "M/d", "MM/dd", "M月d日", "yyyy-MM-dd")
                .joinToString(" ") { SimpleDateFormat(it, Locale.TAIWAN).format(date) },
            "來源" to m.origin().label,
            "地點" to (geo?.place ?: ""),
            "說話者" to names.values.joinToString(" "),
            "報告" to (report.takeIf { it.exists() }?.readText()?.let { Speakers.apply(it, names) } ?: ""),
            "逐字稿" to (transcript.takeIf { it.exists() }?.readText()?.let { Speakers.apply(it, names) } ?: ""),
        ).filter { it.second.isNotBlank() }
        return Doc(stamp, parts).also { cache[m.id] = it }
    }

    fun matches(doc: Doc, terms: List<String>): Boolean = terms.all { doc.lower.contains(it) }

    /** 符合的地方：在哪一段＋前后文（标题 / 日期 / 来源本来就显示了，只回段名）。 */
    data class Snippet(val where: String, val text: String)

    fun snippet(doc: Doc, terms: List<String>, radius: Int = 26): Snippet? {
        if (terms.isEmpty()) return null
        val order = listOf("報告", "逐字稿", "地點", "說話者", "標題", "來源", "日期")
        for (name in order) {
            val text = doc.parts.firstOrNull { it.first == name }?.second ?: continue
            val low = text.lowercase()
            val hit = terms.map { low.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: continue
            if (name == "標題" || name == "日期" || name == "來源") return Snippet(name, "")
            val start = (hit - radius).coerceAtLeast(0)
            val end = (hit + radius * 2).coerceAtMost(text.length)
            val body = text.substring(start, end)
                .replace(Regex("[#*|`]+"), " ").replace(Regex("\\s+"), " ").trim()
            return Snippet(name, (if (start > 0) "…" else "") + body + (if (end < text.length) "…" else ""))
        }
        return null
    }
}
