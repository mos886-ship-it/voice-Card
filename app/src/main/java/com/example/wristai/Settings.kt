package com.example.wristai

import android.content.Context

/** 手机端设置：Claude API Key 与识别语言，存在本机 SharedPreferences（不写进代码）。 */
object Settings {
    private const val PREFS = "wristai_settings"
    private const val KEY_API = "anthropic_api_key"
    private const val KEY_GEMINI = "gemini_api_key"
    private const val KEY_LANG = "speech_language"
    private const val KEY_EXPORT = "export_voice_recorder"
    const val DEFAULT_LANGUAGE = "zh-TW"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Google Gemini API Key（免费方案）：填了就自动产生报告。 */
    fun geminiKey(context: Context): String = prefs(context).getString(KEY_GEMINI, "").orEmpty()
    fun setGeminiKey(context: Context, value: String) =
        prefs(context).edit().putString(KEY_GEMINI, value.trim()).apply()

    fun apiKey(context: Context): String = prefs(context).getString(KEY_API, "").orEmpty()
    fun setApiKey(context: Context, value: String) =
        prefs(context).edit().putString(KEY_API, value.trim()).apply()

    /** 是否把录音存进三星「录音机」（给 Galaxy AI 用），默认开。 */
    /**
     * 长会议每段送 Gemini 的长度（秒），预设 30 分钟。没有 UI，只给测试用：
     * adb shell "run-as com.example.wristai ..." 改 shared_prefs 里的 gemini_chunk_sec。
     */
    fun geminiChunkSec(context: Context): Int =
        prefs(context).getInt("gemini_chunk_sec", 1800).coerceIn(30, 3 * 3600)

    /** 每日热量目标（大卡），饮食记录用。 */
    fun dailyKcalGoal(context: Context): Int = prefs(context).getInt("daily_kcal_goal", 2000)
    fun setDailyKcalGoal(context: Context, value: Int) =
        prefs(context).edit().putInt("daily_kcal_goal", value.coerceIn(800, 6000)).apply()

    /** 外观主题：clear 清晰 / warm 暖和日常（[com.example.wristai.ui.theme.ThemeMode]；旧值 dark / light / ink / system 当清晰）。 */
    fun themeMode(context: Context): String = prefs(context).getString("theme_mode", "clear") ?: "clear"
    fun setThemeMode(context: Context, value: String) = prefs(context).edit().putString("theme_mode", value).apply()

    /** 第一次使用的引导是否已完成。 */
    fun onboarded(context: Context): Boolean = prefs(context).getBoolean("onboarded", false)
    fun setOnboarded(context: Context) = prefs(context).edit().putBoolean("onboarded", true).apply()

    fun exportToVoiceRecorder(context: Context): Boolean = prefs(context).getBoolean(KEY_EXPORT, true)
    fun setExportToVoiceRecorder(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_EXPORT, value).apply()

    fun language(context: Context): String =
        prefs(context).getString(KEY_LANG, DEFAULT_LANGUAGE).orEmpty().ifBlank { DEFAULT_LANGUAGE }
    fun setLanguage(context: Context, value: String) =
        prefs(context).edit().putString(KEY_LANG, value.trim()).apply()
}
