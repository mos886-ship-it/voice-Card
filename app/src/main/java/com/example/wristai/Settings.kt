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

    /** 即时翻译：预设翻成什么（空 = 跟介面语言）、字幕大小 0 小 / 1 中 / 2 大、送到手表、自动存记录。 */
    fun translateTarget(context: Context): String = prefs(context).getString("translate_target", "") ?: ""
    fun setTranslateTarget(context: Context, v: String) = prefs(context).edit().putString("translate_target", v).apply()
    fun captionSize(context: Context): Int = prefs(context).getInt("caption_size", 1)
    fun setCaptionSize(context: Context, v: Int) = prefs(context).edit().putInt("caption_size", v).apply()
    fun captionToWatch(context: Context): Boolean = prefs(context).getBoolean("caption_watch", true)
    fun setCaptionToWatch(context: Context, v: Boolean) = prefs(context).edit().putBoolean("caption_watch", v).apply()
    fun saveTranslations(context: Context): Boolean = prefs(context).getBoolean("translate_save", true)
    fun setSaveTranslations(context: Context, v: Boolean) = prefs(context).edit().putBoolean("translate_save", v).apply()

    /** 每日蛋白质 / 膳食纤维目标（克）。 */
    fun proteinGoal(context: Context): Int = prefs(context).getInt("protein_goal", 60)
    fun setProteinGoal(context: Context, value: Int) = prefs(context).edit().putInt("protein_goal", value.coerceIn(10, 400)).apply()
    fun fiberGoal(context: Context): Int = prefs(context).getInt("fiber_goal", 25)
    fun setFiberGoal(context: Context, value: Int) = prefs(context).edit().putInt("fiber_goal", value.coerceIn(5, 100)).apply()

    /** 外观主题：clear 清晰 / warm 暖和日常（[com.example.wristai.ui.theme.ThemeMode]；旧值 dark / light / ink / system 当清晰）。 */
    fun themeMode(context: Context): String = prefs(context).getString("theme_mode", "clear") ?: "clear"
    fun setThemeMode(context: Context, value: String) = prefs(context).edit().putString("theme_mode", value).apply()

    /** 水墨配色的背景画：boat 孤舟 / lotus 荷塘 / shrimp 魚蝦 / none（[com.example.wristai.ui.theme.InkBackground]）。 */
    fun inkBackground(context: Context): String = prefs(context).getString("ink_bg", "boat") ?: "boat"
    fun setInkBackground(context: Context, value: String) = prefs(context).edit().putString("ink_bg", value).apply()
    /** 台灣配色的背景画：island 島嶼電路 / wafer 晶圓 / city 夜城 / none。 */
    fun taiwanBackground(context: Context): String = prefs(context).getString("taiwan_bg", "island") ?: "island"
    fun setTaiwanBackground(context: Context, value: String) = prefs(context).edit().putString("taiwan_bg", value).apply()
    /** 浮世繪配色的背景画：geisha 藝伎武士 / crane 靈鶴 / castle 大阪城 / none。 */
    fun ukiyoeBackground(context: Context): String = prefs(context).getString("ukiyoe_bg", "geisha") ?: "geisha"
    fun setUkiyoeBackground(context: Context, value: String) = prefs(context).edit().putString("ukiyoe_bg", value).apply()

    /** 饮食同步到健康（Health Connect → Google / Samsung Health）；预设关。 */
    fun healthSync(context: Context): Boolean = prefs(context).getBoolean("health_sync", false)
    fun setHealthSync(context: Context, on: Boolean) = prefs(context).edit().putBoolean("health_sync", on).apply()
    /** 上次同步的时间与笔数（设定页显示）。 */
    fun healthSyncLast(context: Context): Pair<Long, Int> =
        prefs(context).getLong("health_sync_at", 0L) to prefs(context).getInt("health_sync_count", 0)
    fun setHealthSyncLast(context: Context, at: Long, count: Int) =
        prefs(context).edit().putLong("health_sync_at", at).putInt("health_sync_count", count).apply()

    /** 排布：quick 快錄 / overview 總覽（[com.example.wristai.ui.theme.LayoutMode]）。 */
    fun layoutMode(context: Context): String = prefs(context).getString("layout_mode", "quick") ?: "quick"
    fun setLayoutMode(context: Context, value: String) = prefs(context).edit().putString("layout_mode", value).apply()

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
