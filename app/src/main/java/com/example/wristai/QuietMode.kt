package com.example.wristai

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * 即时翻译时不要「叮」：手机语音辨识每次开始听都会响一声（只翻译模式一句一次）。
 * 翻译中把媒体、系统、通知的声音暂时静音，停止就恢复；只恢复我们自己静音的（原本就静音的不动）。
 * 静音了哪些记在设定里：App 被关掉没恢复的话，下次开 App 会恢复（[restoreLeftover]）。
 * 朗读改走闹钟声道（[Speaker]），所以静音时还听得到。
 */
object QuietMode {
    private const val TAG = "QuietMode"
    private const val PREFS = "quiet_mode"
    private const val KEY = "muted"
    private val STREAMS = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)

    @Synchronized
    fun on(c: Context) {
        val am = c.getSystemService(AudioManager::class.java) ?: return
        val already = muted(c)
        val now = mutableSetOf<Int>()
        for (s in STREAMS) {
            if (s in already) { now += s; continue }
            if (am.isStreamMute(s)) continue
            // 通知声道在勿扰模式下可能不让改：改不了就算了
            runCatching { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0); now += s }
                .onFailure { Log.d(TAG, "can't mute $s: ${it.message}") }
        }
        save(c, now)
    }

    @Synchronized
    fun off(c: Context) {
        val am = c.getSystemService(AudioManager::class.java) ?: return
        for (s in muted(c)) runCatching { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) }
        save(c, emptySet())
    }

    /** 现在有没有静音（朗读要不要改走闹钟声道）。 */
    fun active(c: Context) = muted(c).isNotEmpty()

    /** 上次翻译中 App 被关掉、没恢复的声音：开 App 时恢复。 */
    fun restoreLeftover(c: Context) { if (muted(c).isNotEmpty()) off(c) }

    private fun muted(c: Context): Set<Int> =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    private fun save(c: Context, s: Set<Int>) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, s.map { it.toString() }.toSet()).apply()
}
