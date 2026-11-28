package com.example.wristai

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 就醫週報：近 7 天或 30 天的飲食整理（Markdown → PDF / Word），給醫師、營養師或家人看。
 * 內容：期間與目標、平均攝取對目標、每天一列（含熱量長條）、熱量來源比例、常吃的、控糖時的碳水超標天數。
 */
object FoodReport {
    data class Goals(val kcal: Int, val protein: Int, val fiber: Int, val carbCap: Int, val modeLabel: String)

    private const val DAY = 86_400_000L

    /** [days] 天（含今天）每天 00:00 的時間。 */
    fun dayStarts(days: Int, now: Long = System.currentTimeMillis()): List<Long> {
        val c = Calendar.getInstance().apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val today = c.timeInMillis
        return (days - 1 downTo 0).map { today - it * DAY }
    }

    /** 熱量長條：10 格 = 目標，超過的用 ▲ 標。 */
    fun bar(kcal: Int, goal: Int): String {
        if (goal <= 0 || kcal <= 0) return ""
        val n = (kcal * 10f / goal).let { kotlin.math.round(it).toInt() }.coerceIn(0, 15)
        return "█".repeat(n.coerceAtMost(10)) + (if (n > 10) "▲".repeat(n - 10) else "")
    }

    fun title(days: Int, now: Long = System.currentTimeMillis()): String {
        val f = SimpleDateFormat("yyyy/MM/dd", Locale.US)
        val starts = dayStarts(days, now)
        return "飲食週報 {0}–{1}".trf(f.format(Date(starts.first())), f.format(Date(now)))
    }

    fun markdown(food: List<Meeting>, days: Int, g: Goals, now: Long = System.currentTimeMillis()): String {
        val starts = dayStarts(days, now)
        val done = food.filter { it.status == MeetingStatus.DONE && it.nutrition != null && it.createdAt >= starts.first() && it.createdAt <= now }
        val perDay = starts.map { s -> done.filter { it.createdAt in s until s + DAY } }
        val logged = perDay.filter { it.isNotEmpty() }
        val dayFmt = SimpleDateFormat("M/d (EEE)", com.example.voiceai.shared.I18n.locale)
        fun sum(list: List<Meeting>, f: (Nutrition) -> Int) = list.sumOf { f(it.nutrition!!) }
        fun avg(f: (Nutrition) -> Int) = if (logged.isEmpty()) 0 else logged.sumOf { sum(it, f) } / logged.size
        return buildString {
            appendLine("## " + "摘要".tr)
            appendLine("- " + "期間：近 {0} 天，有記錄 {1} 天、共 {2} 餐".trf(days, logged.size, done.size))
            appendLine("- " + "健康目標：{0}；每日目標 {1} 大卡、蛋白質 {2} g、纖維 {3} g".trf(g.modeLabel, g.kcal, g.protein, g.fiber) +
                (if (g.carbCap > 0) "、" + "碳水上限 {0} g".trf(g.carbCap) else ""))
            if (logged.isEmpty()) { appendLine(); appendLine("這段期間沒有飲食記錄。".tr); return@buildString }
            val within = logged.count { sum(it) { n -> n.kcal } <= g.kcal }
            appendLine("- " + "平均每天：{0} 大卡（目標的 {1}%）".trf(avg { it.kcal }, avg { it.kcal } * 100 / g.kcal.coerceAtLeast(1)))
            appendLine("- " + "熱量未超標：{0} / {1} 天".trf(within, logged.size))
            appendLine("- " + "蛋白質達標：{0} / {1} 天".trf(logged.count { sum(it) { n -> n.protein } >= g.protein }, logged.size))
            if (g.carbCap > 0) appendLine("- " + "碳水超過上限：{0} / {1} 天".trf(logged.count { sum(it) { n -> n.carbs } > g.carbCap }, logged.size))
            appendLine()

            appendLine("## " + "每日平均 vs 目標".tr)
            appendLine("| " + "項目".tr + " | " + "平均".tr + " | " + "目標".tr + " |")
            appendLine("|---|---|---|")
            appendLine("| " + "熱量（大卡）".tr + " | ${avg { it.kcal }} | ${g.kcal} |")
            appendLine("| " + "蛋白質（g）".tr + " | ${avg { it.protein }} | ${g.protein} |")
            appendLine("| " + "碳水（g）".tr + " | ${avg { it.carbs }} | ${if (g.carbCap > 0) "≤ ${g.carbCap}" else "—"} |")
            appendLine("| " + "脂肪（g）".tr + " | ${avg { it.fat }} | — |")
            appendLine("| " + "纖維（g）".tr + " | ${avg { it.fiber }} | ${g.fiber} |")
            appendLine()

            val p = logged.sumOf { sum(it) { n -> n.protein } }; val c = logged.sumOf { sum(it) { n -> n.carbs } }; val f = logged.sumOf { sum(it) { n -> n.fat } }
            val e = (p * 4 + c * 4 + f * 9).coerceAtLeast(1)
            appendLine("## " + "熱量來源".tr)
            appendLine("- " + "蛋白質 {0}%、碳水 {1}%、脂肪 {2}%（一般建議：蛋白質 10–35%、碳水 45–65%、脂肪 20–35%）".trf(p * 400 / e, c * 400 / e, f * 900 / e))
            appendLine()

            appendLine("## " + "每天".tr)
            appendLine("| " + "日期".tr + " | " + "餐數".tr + " | " + "大卡".tr + " | " + "對目標".tr + " | " + "蛋白質".tr + " | " + "碳水".tr + " | " + "脂肪".tr + " | " + "纖維".tr + " |")
            appendLine("|---|---|---|---|---|---|---|---|")
            for ((i, list) in perDay.withIndex()) {
                val k = sum(list) { it.kcal }
                appendLine("| ${dayFmt.format(Date(starts[i]))} | ${list.size} | ${if (list.isEmpty()) "—" else k} | ${bar(k, g.kcal)} | " +
                    "${sum(list) { it.protein }} | ${sum(list) { it.carbs }} | ${sum(list) { it.fat }} | ${sum(list) { it.fiber }} |")
            }
            appendLine()

            val favs = done.groupBy { FoodRepeat.dish(it.title) }.filterKeys { it.isNotBlank() }.entries
                .sortedByDescending { it.value.size }.take(8)
            if (favs.isNotEmpty()) {
                appendLine("## " + "常吃的".tr)
                for ((dish, list) in favs) appendLine("- " + "{0}：{1} 次，平均 {2} 大卡".trf(dish, list.size, list.sumOf { it.nutrition!!.kcal } / list.size))
                appendLine()
            }
            appendLine("熱量與營養為 AI 依口述或照片估算，僅供參考，不能取代營養師或醫療建議。".tr)
        }
    }
}
