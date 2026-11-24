package com.example.wristai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 辨识语言（每笔录音选一次，跟 iOS `SpeechLang` 一样）：
 * - 国语 `zh`（预设；旧资料没有这栏＝国语）：大D的主機用 FunASR。
 * - 台语・国台语 `nan`：主机用 Breeze-ASR-26（较慢）；Gemini 提示词加台语说明。
 * 台湾的国台语常在同一句里夹杂，所以不逐句切换，整笔录音一个语言。
 */
enum class SpeechLang(val code: String, val label: String, val short: String) {
    ZH("zh", "國語", "國語"),
    NAN("nan", "台語・國台語", "台語");

    companion object {
        private const val PREFS = "wristai_settings"
        private const val KEY = "speech_lang"

        /** 现在选的（新录音用）；Compose 状态，主页和录音页的选择器同步。 */
        var current by mutableStateOf(ZH)
            private set

        fun of(code: String?): SpeechLang = entries.firstOrNull { it.code == code } ?: ZH

        fun load(c: Context) {
            current = of(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
        }

        fun set(c: Context, v: SpeechLang) {
            current = v
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v.code).apply()
        }

        /** 建立新录音时存进 [Meeting.lang] 的值（读设定档，服务里也能用）。 */
        fun currentCode(c: Context): String =
            of(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)).code

        /** 台语录音给 Gemini 的额外说明（跟 iOS 一字不差）；国语没有。 */
        fun geminiNote(code: String?): String = if (of(code) == NAN)
            "這段錄音有台語（可能和國語夾雜在同一句裡）。逐字稿請一律用台灣繁體中文的華語字寫出意思（台語的部分翻成通順的華語，" +
                "國語照說的寫），不要用台羅拼音，也不要寫成同音的錯字。"
        else ""
    }
}
