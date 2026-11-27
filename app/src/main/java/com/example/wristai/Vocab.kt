package com.example.wristai

import android.content.Context

/**
 * 個人詞庫：使用者自己輸入的專有名詞（公司、產品、縮寫如 BOM、NDA、ESG、人名）＋常替說話者取的名字。
 * 處理錄音時加進報告提示詞（[ReportLang.localize] → [applyTo]）：辨識和整理時優先用這些寫法，降低同音錯字；
 * 大D的主機也收到（X-Vocab，base64）。說話者改名時，常用的名字可以一鍵選（[frequentNames]）。
 */
object Vocab {
    private const val PREFS = "wristai_settings"
    private const val KEY = "custom_vocab"
    private const val MAX_TERMS = 200

    fun terms(c: Context): List<String> =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            .split('\n', ',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_TERMS)

    fun raw(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    fun setRaw(c: Context, text: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, text.take(8000)).apply()

    /** 常替說話者取的名字（次數多的在前）。 */
    fun frequentNames(c: Context, limit: Int = 12): List<String> {
        MeetingStore.reload(c)
        return MeetingStore.meetings.value.flatMap { it.speakerNames.values }
            .map { it.trim() }.filter { it.isNotEmpty() && it !in ReportLang.UNKNOWN && it != "本人" }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(limit)
    }

    /** 這次處理用的詞（[ProcessMeetingWorker] 開始時設）。 */
    @Volatile var active: List<String> = emptyList()

    fun prepare(c: Context) { active = (terms(c) + frequentNames(c, 30)).distinct().take(MAX_TERMS) }

    fun rule(words: List<String> = active): String =
        if (words.isEmpty()) "" else "【使用者的專有名詞與常見人名】辨識和整理時，聽到同音或相近的詞，優先用這些寫法：" + words.joinToString("、") + "。沒出現的不要硬套。"

    /** 報告提示詞（有「待辦事項」章節）才加；已經加過不再加。 */
    fun applyTo(prompt: String, words: List<String> = active): String {
        val r = rule(words)
        if (r.isEmpty() || !prompt.contains("## 待辦事項") || prompt.contains("【使用者的專有名詞")) return prompt
        return prompt.trimEnd() + "\n\n" + r
    }

    /** 送給大D的主機（標頭只能 ASCII）。 */
    fun header(words: List<String> = active): String? =
        words.takeIf { it.isNotEmpty() }?.joinToString("\n")?.let {
            android.util.Base64.encodeToString(it.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        }?.takeIf { it.length < 6000 }
}
