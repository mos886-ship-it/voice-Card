package com.example.wristai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 翻譯更正：使用者在翻譯記錄裡改過的詞（「翻錯的 → 正確的」，依翻成的語言分開）。
 * 之後的即時翻譯、遠距對話譯文都會先套用（[apply]）。例如人名、公司名、專有名詞老是翻錯，改一次就好。
 */
object TranslateFixes {
    data class Fix(val lang: String, val wrong: String, val right: String)

    private const val KEY = "translate_fixes"
    private const val MAX = 300
    private fun p(c: Context) = c.getSharedPreferences("wristai_settings", Context.MODE_PRIVATE)

    @Volatile private var cache: List<Fix>? = null

    fun all(c: Context): List<Fix> = cache ?: runCatching {
        val a = JSONArray(p(c).getString(KEY, "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { j -> Fix(j.getString("lang"), j.getString("wrong"), j.getString("right")) } }
    }.getOrDefault(emptyList()).also { cache = it }

    private fun store(c: Context, list: List<Fix>) {
        val a = JSONArray()
        for (f in list.takeLast(MAX)) a.put(JSONObject().put("lang", f.lang).put("wrong", f.wrong).put("right", f.right))
        p(c).edit().putString(KEY, a.toString()).apply()
        cache = list.takeLast(MAX)
    }

    fun add(c: Context, lang: String, wrong: String, right: String) {
        val w = wrong.trim(); val r = right.trim()
        if (w.isEmpty() || w == r) return
        store(c, all(c).filterNot { it.lang == lang && it.wrong == w } + Fix(lang, w, r))
    }

    fun remove(c: Context, f: Fix) = store(c, all(c) - f)

    /** 套用這個語言的更正（長的先換，免得短的先吃掉）。 */
    fun apply(text: String, lang: String, fixes: List<Fix>): String {
        var t = text
        for (f in fixes.filter { it.lang == lang }.sortedByDescending { it.wrong.length }) t = t.replace(f.wrong, f.right, ignoreCase = true)
        return t
    }

    fun apply(c: Context, text: String, lang: String): String = all(c).let { if (it.isEmpty()) text else apply(text, lang, it) }
}
