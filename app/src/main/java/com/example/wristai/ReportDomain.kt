package com.example.wristai

import android.content.Context
import org.json.JSONObject

/**
 * 报告的「领域专家模式」：法律、医疗、财务…（清单在共用的 assets/report_domains.json，大D的主机、iOS 用同一份）。
 *
 * 设定 `report_domain`：auto（AI 自动判断领域，一般对话不加）/ off（关闭）/ 领域 id。
 * 开着时报告多一节「## 專業重點」（第一行「領域：○○」），整理角度、术语照该领域；法律、医疗、财务会加免责说明。
 * [ProcessMeetingWorker] 开始时设好 [activeRules]，[ReportLang.localize] 把它加进报告提示词（饮食、分段笔记不加）。
 */
object ReportDomain {
    const val AUTO = "auto"
    const val OFF = "off"
    private const val PREFS = "wristai_settings"
    private const val KEY = "report_domain"

    data class Domain(val id: String, val name: String, val focus: String, val disclaimer: String?)
    data class Table(val section: String, val common: String, val auto: String, val domains: List<Domain>)

    @Volatile private var table: Table? = null

    fun parse(json: String): Table {
        val j = JSONObject(json)
        val arr = j.getJSONArray("domains")
        val list = (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            Domain(d.getString("id"), d.getString("name"), d.getString("focus"), d.optString("disclaimer").ifBlank { null })
        }
        return Table(j.getString("section"), j.getString("common"), j.getString("auto"), list)
    }

    fun table(c: Context): Table = table ?: parse(c.assets.open("report_domains.json").bufferedReader().use { it.readText() }).also { table = it }

    fun setting(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO
    fun set(c: Context, v: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v).apply()

    /** 设定值 → 加进提示词的规则（中文；关闭或不认得 → 空字串）。 */
    fun rules(t: Table, setting: String): String = when (setting) {
        OFF -> ""
        AUTO -> {
            val focus = t.domains.joinToString("\n") { d ->
                "- ${d.name}：${d.focus}" + (d.disclaimer?.let { "（報告最後加一行：「$it」）" } ?: "")
            }
            t.auto.replace("{names}", t.domains.joinToString("、") { it.name }).replace("{focus}", focus)
        }
        else -> t.domains.firstOrNull { it.id == setting }?.let { d ->
            t.common.replace("【領域專家模式】", "【領域專家模式：${d.name}】") +
                "\n這個領域要注意的事項：${d.focus}。" +
                (d.disclaimer?.let { "\n報告最後加一行：「$it」" } ?: "")
        } ?: ""
    }

    /** 这次处理用的规则（[ProcessMeetingWorker] 开始时设）。 */
    @Volatile var activeRules: String = ""
    /** 这次处理的设定值（送给大D的主机的 X-Domain）。 */
    @Volatile var active: String = AUTO

    fun prepare(c: Context) {
        active = setting(c)
        activeRules = runCatching { rules(table(c), active) }.getOrDefault("")
    }

    /** 报告提示词（有「待辦事項」章节的那种）才加；饮食、分段笔记不加。 */
    fun applyTo(prompt: String, rules: String = activeRules): String =
        if (rules.isBlank() || !prompt.contains("## 待辦事項") || prompt.contains("NUTRITION")) prompt
        else prompt.trimEnd() + "\n\n" + rules
}
