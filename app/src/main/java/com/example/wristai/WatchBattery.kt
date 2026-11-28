package com.example.wristai

import android.content.Context

/** 手錶電量（手錶 App 打開時送來，[WristAiProtocol.PATH_BATTERY]）。「今天」的手錶標籤顯示。 */
object WatchBattery {
    data class Level(val percent: Int, val charging: Boolean, val at: Long)

    private const val MAX_AGE = 6 * 3_600_000L
    private fun p(c: Context) = c.getSharedPreferences("wristai_ui", Context.MODE_PRIVATE)

    fun parse(raw: String, at: Long = System.currentTimeMillis()): Level? {
        val parts = raw.trim().split(',')
        val pct = parts.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it in 0..100 } ?: return null
        return Level(pct, parts.getOrNull(1)?.trim() == "1", at)
    }

    fun save(c: Context, raw: String) {
        val l = parse(raw) ?: return
        p(c).edit().putInt("watch_batt", l.percent).putBoolean("watch_batt_chg", l.charging).putLong("watch_batt_at", l.at).apply()
    }

    fun recent(c: Context, now: Long = System.currentTimeMillis()): Level? {
        val at = p(c).getLong("watch_batt_at", 0)
        if (at == 0L || now - at > MAX_AGE) return null
        return Level(p(c).getInt("watch_batt", 0), p(c).getBoolean("watch_batt_chg", false), at)
    }
}
