package com.example.wristai

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * App 介面语言：跟系统 / 繁體中文 / English / 한국어（个别 App 语言，不用改整支手机）。
 * - Android 13+：系统的个别 App 语言（[LocaleManager]），系统设定「应用程式 → 语言」也看得到、改得到。
 * - 更旧的：存在设定档，[wrap] 在 MainActivity.attachBaseContext 套用。
 * 手表跟着手机这个设定（[WatchNotifier.broadcastUiLang]）。
 */
object AppLocale {
    private const val PREFS = "wristai_settings"
    private const val KEY = "ui_lang"
    const val SYSTEM = ""

    /** 选项：代号（空字串 = 跟系统）→ 显示名称（用该语言自己的写法）。 */
    val OPTIONS = listOf(SYSTEM to null, "zh-TW" to "繁體中文", "en" to "English", "ko" to "한국어")

    /** 目前的设定（空字串 = 跟系统）。 */
    fun setting(c: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            c.getSystemService(LocaleManager::class.java)?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.let { tagOf(it) } ?: SYSTEM
        } else c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    /** 选项里的代号（zh-Hant-TW、zh-TW → zh-TW）。 */
    private fun tagOf(l: Locale): String = when (l.language) {
        "zh" -> "zh-TW"
        else -> l.language
    }

    /** 改介面语言：13+ 交给系统（会自动重建画面）；旧版存起来、重建画面。 */
    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
    }

    /** Android 12 以下：用设定的语言包一层 Context（13+ 系统已经处理，原样回传）。 */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM).orEmpty()
        if (tag.isEmpty()) return base
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
        return base.createConfigurationContext(config)
    }

    /** 介面现在实际用的语言（zh / en / ko …）。 */
    fun current(c: Context): String = c.resources.configuration.locales[0].language
}
