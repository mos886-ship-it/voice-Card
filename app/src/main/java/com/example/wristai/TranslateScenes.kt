package com.example.wristai

import android.content.Context
import com.example.voiceai.shared.I18n
import com.example.voiceai.shared.tr
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 翻譯情境模式：一般、照護居家、商務、旅遊。選了就套一組字幕設定（字大小、原文、朗讀、語速），
 * 並在即時翻譯頁顯示這個情境的常用片語（點一下翻成對方的語言、放大＋念出來）。旅遊模式另有「拍菜單翻譯」。
 */
object TranslateScene {
    const val GENERAL = "general"
    const val CARE = "care"
    const val BUSINESS = "business"
    const val TRAVEL = "travel"
    val ALL = listOf(GENERAL, CARE, BUSINESS, TRAVEL)

    private const val KEY = "translate_scene"
    private const val KEY_CUSTOM = "translate_phrases_custom"
    private fun p(c: Context) = c.getSharedPreferences("wristai_settings", Context.MODE_PRIVATE)

    fun mode(c: Context): String = p(c).getString(KEY, GENERAL).orEmpty().takeIf { it in ALL } ?: GENERAL

    /** 換情境：存起來並套用字幕設定。 */
    fun setMode(c: Context, m: String) {
        p(c).edit().putString(KEY, m).apply()
        val p = preset(m) ?: return
        Settings.setCaptionSize(c, p.size)
        Settings.setShowOriginal(c, p.original)
        Settings.setAutoSpeak(c, p.speak)
        Settings.setSpeakRate(c, p.rate)
    }

    data class Preset(val size: Int, val original: Boolean, val speak: Boolean, val rate: Float)

    /** 一般模式不改使用者自己的設定。 */
    fun preset(m: String): Preset? = when (m) {
        CARE -> Preset(size = 3, original = false, speak = true, rate = 0.8f)      // 大字、慢慢念
        BUSINESS -> Preset(size = 1, original = true, speak = false, rate = 1.0f)  // 原文＋譯文對照、安靜
        TRAVEL -> Preset(size = 2, original = false, speak = true, rate = 1.0f)    // 念給對方聽
        else -> null
    }

    fun label(m: String): String = when (m) {
        CARE -> "照護居家".tr
        BUSINESS -> "商務".tr
        TRAVEL -> "旅遊".tr
        else -> "一般".tr
    }

    fun note(m: String): String = when (m) {
        CARE -> "大字、慢速朗讀，常用照護片語一點就念（適合外籍看護、長輩）。".tr
        BUSINESS -> "原文與譯文對照、不朗讀，適合會議與商談。".tr
        TRAVEL -> "譯文念給對方聽，常用旅遊片語；可以拍菜單翻譯。".tr
        else -> "用你自己的字幕設定。".tr
    }

    /** 情境片語（已換成使用者的語言，從 [myLang] 翻成對方的語言）。 */
    fun phrases(m: String): List<String> = when (m) {
        CARE -> listOf("該吃藥了".tr, "要不要喝水？".tr, "哪裡不舒服？".tr, "要去廁所嗎？".tr, "慢慢來，不急".tr, "我扶你起來".tr,
            "會痛嗎？".tr, "該吃飯了".tr, "要洗澡了".tr, "要量血壓了".tr, "有沒有頭暈？".tr, "晚安，好好休息".tr)
        BUSINESS -> listOf("很高興認識你".tr, "請再說一次".tr, "可以說慢一點嗎？".tr, "我們再確認一下細節".tr, "我會再寄郵件給你".tr,
            "報價什麼時候可以提供？".tr, "交期是什麼時候？".tr, "我需要跟團隊討論".tr, "謝謝你的時間".tr)
        TRAVEL -> listOf("多少錢？".tr, "廁所在哪裡？".tr, "可以刷卡嗎？".tr, "請給我菜單".tr, "我對花生過敏".tr, "不要辣".tr,
            "請帶我去這個地址".tr, "可以幫我拍照嗎？".tr, "請問車站怎麼走？".tr, "結帳，謝謝".tr)
        else -> emptyList()
    }

    fun customPhrases(c: Context): List<String> =
        p(c).getString(KEY_CUSTOM, "").orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun addCustom(c: Context, text: String) {
        val t = text.trim().take(120)
        if (t.isEmpty()) return
        val list = (customPhrases(c) - t + t).takeLast(30)
        p(c).edit().putString(KEY_CUSTOM, list.joinToString("\n")).apply()
    }

    fun removeCustom(c: Context, text: String) {
        p(c).edit().putString(KEY_CUSTOM, (customPhrases(c) - text).joinToString("\n")).apply()
    }

    /** 使用者自己的語言（ML Kit 代碼）：畫面語言。 */
    fun myLang(): String = when (I18n.lang) { "en" -> "en"; "ko" -> "ko"; else -> "zh" }

    /** 對方的語言：「說的 / 翻成」兩邊裡不是我的那一個。兩邊都不是我的語言時，片語翻成「說的」那邊（對方在說的語言）。 */
    fun otherLang(source: String, target: String, me: String = myLang()): String {
        val src = TranslateLangs.mlkitOf(source) ?: source
        return if (src == me) target else src
    }

    /** 片語翻成對方的語言（第一次用到會下載模型）。同語言就原樣。 */
    suspend fun translate(c: Context, text: String, from: String, to: String): String? {
        if (from == to) return text
        val t = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        return try {
            val cond = DownloadConditions.Builder().apply { if (Settings.translateModelsWifiOnly(c)) requireWifi() }.build()
            t.downloadModelIfNeeded(cond).await()
            val out = t.translate(text).await()
            TranslateFixes.apply(c, if (to == "zh") TraditionalChinese.convert(out) else out, to)
        } catch (_: Exception) { null } finally { t.close() }
    }

    /** 拍菜單 / 告示翻譯的提示詞（[lang] = 讀的人的語言名稱）。 */
    fun menuPrompt(lang: String): String = """
你是旅遊翻譯。使用者拍了一張菜單、告示或標示，請翻成「${lang}」。
規則：
- 菜單：每道菜一行，格式「原文 → 譯名：一句說明（主要食材、口味；辣、含堅果、海鮮、豬肉、酒精等要標出）· 價格」。
- 告示或標示：逐句翻譯，最後一行用一句話說重點。
- 看不清楚的寫「（看不清楚）」，不要猜。
- 只輸出翻譯內容，不要前言。
""".trim()

    /** 照片 → Gemini 翻譯（需要 Gemini 金鑰）。在背景執行緒呼叫。 */
    suspend fun translateMenu(c: Context, photo: File, langName: String): Result<String> = withContext(Dispatchers.IO) {
        val key = Settings.geminiKey(c)
        if (key.isBlank()) return@withContext Result.failure(IllegalStateException("拍照翻譯需要 Gemini（免費）：請到設定填 Gemini 金鑰".tr))
        runCatching { GeminiReportGenerator(key).completeImages(menuPrompt(langName), "請翻譯這張照片。", listOf(photo)).trim() }
    }
}
