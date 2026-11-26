package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Calendar

/**
 * 介面模式（跟 iOS 版 UIMode.swift 一样）：
 * - 商用（预设）：简洁，只给结果。
 * - 科技：多给细节（AI 模型、耗时、编号、为什么改用别的引擎、主机模型选单）。
 * Compose 状态：设定页一改，整个 App 马上换。存在设定 `ui_mode = business | tech`。
 */
object UiMode {
    private const val PREFS = "wristai_settings"
    var tech by mutableStateOf(false)

    fun load(c: Context) {
        tech = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ui_mode", "business") == "tech"
    }

    fun set(c: Context, isTech: Boolean) {
        tech = isTech
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("ui_mode", if (isTech) "tech" else "business").apply()
    }
}

/** 好读的长度：16 秒 / 2 分 / 1 小时 5 分（主页今日时长、清单）。 */
fun humanDuration(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    return when {
        s < 60 -> "{0} 秒".trf(s)
        s < 3600 -> "{0} 分".trf(s / 60)
        s % 3600 / 60 == 0L -> "{0} 小時".trf(s / 3600)
        else -> "{0} 小時 {1} 分".trf(s / 3600, s % 3600 / 60)
    }
}

/** 清单分组：今天 / 昨天 / 11月20日 週四（不是今年再加年份：2025年11月20日 週四）。 */
fun dayLabel(ms: Long, now: Long = System.currentTimeMillis()): String {
    fun start(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val day = start(ms)
    val today = start(now)
    val diff = Math.round((today.timeInMillis - day.timeInMillis) / 86_400_000.0)
    if (diff == 0L) return "今天".tr
    if (diff == 1L) return "昨天".tr
    val week = com.example.voiceai.shared.I18n.weekdayShort(day.get(Calendar.DAY_OF_WEEK))
    val md = "{0}月{1}日 週{2}".trf(day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH), week)
    return if (day.get(Calendar.YEAR) == today.get(Calendar.YEAR)) md else "{0}年{1}".trf(day.get(Calendar.YEAR), md)
}

/** 处理耗时（科技模式）：「耗時 42 秒」；没记录 → null。 */
fun processSeconds(started: Long, ended: Long): Long? =
    if (started > 0 && ended > started) (ended - started + 500) / 1000 else null

