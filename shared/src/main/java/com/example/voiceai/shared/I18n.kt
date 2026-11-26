package com.example.voiceai.shared

import android.content.Context
import org.json.JSONObject

/**
 * 介面多语言（繁中 / English / 한국어）：程式里照旧写中文，显示前查表。
 *
 * - 对照表 `assets/i18n/strings.json`：{"中文原文": {"en": "...", "ko": "..."}}，手机、手表、iOS 共用同一份。
 * - `"中文".tr` → 目前语言的字；`"今天記了 {0} 筆".trf(n)` → 带参数（{0}、{1}… 可以换顺序）。
 * - 查不到 → 原本的中文（不会空白）。
 * - [lang] 在 App 启动时依介面语言设好；介面语言换了，App 会重新启动（[needsRestart]），
 *   所以列举、物件里一开始就算好的字也会是新的语言。
 */
object I18n {
    /** zh / en / ko */
    @Volatile var lang: String = "zh"
        private set

    @Volatile private var table: Map<String, Pair<String, String>> = emptyMap()

    fun init(c: Context) {
        lang = langOf(c)
        if (table.isEmpty()) table = runCatching { load(c.assets.open("i18n/strings.json").bufferedReader().readText()) }.getOrDefault(emptyMap())
    }

    /** 介面语言换了（跟这次启动时不同）→ 需要重新启动 App。 */
    fun needsRestart(c: Context): Boolean = langOf(c) != lang

    /** 手表：手机推来的介面语言（存在这里；没有就用自己的系统语言）。 */
    private const val PREFS = "i18n"
    fun setOverride(c: Context, lang: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lang", lang).apply()
        this.lang = langOf(c)
    }

    fun langOf(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lang", null)
        ?.takeIf { it in setOf("zh", "en", "ko") } ?: when (c.resources.configuration.locales[0].language) {
        "zh" -> "zh"
        "ko" -> "ko"
        else -> "en"
    }

    /** 测试用：直接给对照表与语言。 */
    fun loadForTest(json: String, language: String) { table = load(json); lang = language }

    private fun load(json: String): Map<String, Pair<String, String>> {
        val j = JSONObject(json)
        val out = HashMap<String, Pair<String, String>>(j.length() * 2)
        for (k in j.keys()) {
            val v = j.optJSONObject(k) ?: continue
            out[k] = v.optString("en") to v.optString("ko")
        }
        return out
    }

    fun tr(zh: String): String = when (lang) {
        "zh" -> zh
        "en" -> table[zh]?.first?.takeIf { it.isNotEmpty() } ?: zh
        else -> table[zh]?.second?.takeIf { it.isNotEmpty() } ?: table[zh]?.first?.takeIf { it.isNotEmpty() } ?: zh
    }

    /** 日期格式用的 Locale（星期、月份名称）。 */
    val locale: java.util.Locale get() = when (lang) { "zh" -> java.util.Locale.TAIWAN; "ko" -> java.util.Locale.KOREA; else -> java.util.Locale.US }

    /** 星期简称：日一二…（中文）/ Sun Mon…（英文）/ 일 월…（韩文）。[dow] = Calendar.DAY_OF_WEEK（1 = 星期日）。 */
    fun weekdayShort(dow: Int): String =
        if (lang == "zh") "日一二三四五六"[dow - 1].toString() else java.text.DateFormatSymbols(locale).shortWeekdays[dow]

    /** {0}、{1}… 换成参数。 */
    fun format(s: String, args: Array<out Any?>): String {
        var r = s
        args.forEachIndexed { i, a -> r = r.replace("{$i}", a.toString()) }
        return r
    }
}

/** 目前介面语言的字（查不到就是原本的中文）。 */
val String.tr: String get() = I18n.tr(this)

/** 带参数：`"今天記了 {0} 筆".trf(3)`。 */
fun String.trf(vararg args: Any?): String = I18n.format(I18n.tr(this), args)
