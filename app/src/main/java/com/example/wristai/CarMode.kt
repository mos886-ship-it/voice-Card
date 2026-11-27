package com.example.wristai

import android.content.Context
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

/**
 * 车用：开车时不看萤幕，用声音。
 * - 朗读最新报告的摘要、今天还没做完的待办（[readLatest] / [readTodos]），给捷径、语音助理、手表用。
 * - 报告做好时自动朗读（[onReportReady]）：关 / 只在连着蓝牙（车上、耳机）时 / 一律。
 * 设定在「設定 → 車用」（[com.example.wristai.ui.CarSettingsScreen]）。车上录音的麦克风用 [MicRoute]。
 */
object CarMode {
    private const val PREFS = "wristai_settings"
    const val READ_OFF = "off"
    const val READ_BT = "bt"
    const val READ_ALWAYS = "always"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 报告做好时朗读：off / bt（连着蓝牙麦克风才念）/ always。预设只在连着蓝牙时。 */
    fun readAfter(c: Context): String = p(c).getString("car_read_after", READ_BT) ?: READ_BT
    fun setReadAfter(c: Context, v: String) = p(c).edit().putString("car_read_after", v).apply()

    /** 朗读时连今天的待办一起念。 */
    fun readTodosToo(c: Context): Boolean = p(c).getBoolean("car_read_todos", true)
    fun setReadTodosToo(c: Context, v: Boolean) = p(c).edit().putBoolean("car_read_todos", v).apply()

    /** 朗读速度 0.7～1.5。 */
    fun rate(c: Context): Float = p(c).getFloat("car_read_rate", 1.0f)
    fun setRate(c: Context, v: Float) = p(c).edit().putFloat("car_read_rate", v).apply()

    /** 朗读用的语言：报告语言（繁中 / English / 한국어）。 */
    private fun lang(c: Context) = ReportLang.current(c).code

    private fun say(c: Context, text: String): Boolean = Speaker.speak(c, text, lang(c), rate(c))

    /** 最新一份做好的录音报告（不含饮食）：「标题。摘要」。 */
    fun latestText(c: Context): String? {
        MeetingStore.reload(c)
        val m = MeetingStore.meetings.value
            .filter { it.status == MeetingStatus.DONE && !FoodLog.isFood(it.id) }
            .maxByOrNull { it.createdAt } ?: return null
        val report = MeetingStore.reportFile(c, m.id).takeIf { it.exists() }?.readText()
        val summary = com.example.wristai.ui.summaryOf(report)?.let { Speakers.apply(it, m.speakerNames) }
        return listOfNotNull(m.title, summary).joinToString("。")
    }

    /** 今天的报告里还没打勾的待办。 */
    fun todayTodos(c: Context): List<String> {
        MeetingStore.reload(c)
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val done = ActionItems.done(c)
        return MeetingStore.meetings.value
            .filter { it.status == MeetingStatus.DONE && it.createdAt >= start && !FoodLog.isFood(it.id) }
            .sortedBy { it.createdAt }
            .flatMap { m ->
                val md = MeetingStore.reportFile(c, m.id).takeIf { it.exists() }?.readText() ?: return@flatMap emptyList()
                ActionItems.parse(m.id, md).filter { it.key !in done }.map { Speakers.apply(it.task, m.speakerNames) }
            }
    }

    fun todosText(c: Context): String {
        val t = todayTodos(c)
        return if (t.isEmpty()) "今天沒有待辦事項。".tr
        else "今天有 {0} 項待辦：".trf(t.size) + t.mapIndexed { i, s -> "${i + 1}，$s" }.joinToString("；") + "。"
    }

    /** 念最新报告（没有就说没有）；回传念的文字。 */
    fun readLatest(c: Context): String {
        val text = latestText(c) ?: "還沒有做好的報告。".tr
        say(c, text)
        return text
    }

    fun readTodos(c: Context): String = todosText(c).also { say(c, it) }

    /** 报告做好时（[ReadyNotification.showReport]）：设定要念就念「报告好了：标题。摘要」（加上这份的待办）。 */
    fun onReportReady(c: Context, meetingId: String, title: String, summary: String) {
        val mode = readAfter(c)
        if (mode == READ_OFF || FoodLog.isFood(meetingId)) return
        if (mode == READ_BT && MicRoute.btInput(c) == null) return
        val todos = if (readTodosToo(c)) {
            MeetingStore.reportFile(c, meetingId).takeIf { it.exists() }?.readText()
                ?.let { ActionItems.parse(meetingId, it).map { i -> i.task } }.orEmpty()
        } else emptyList()
        val text = "報告好了：{0}。".trf(title) + summary +
            (if (todos.isNotEmpty()) "待辦：".tr + todos.joinToString("；") + "。" else "")
        say(c, text)
    }
}
