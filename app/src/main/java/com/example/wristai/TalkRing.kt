package com.example.wristai

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 遠距對話的鈴聲：
 * - 撥出去等對方接：回鈴音（嘟——嘟——，[startRingback]）。
 * - 有人打來：手機的來電鈴聲＋震動（[startRinging]）；靜音 / 震動模式照系統設定（靜音不響、震動只震）。
 * 畫面離開（接了、掛了、拒接）就停（[stop]）。
 */
object TalkRing {
    private var tone: ToneGenerator? = null
    private var ring: Ringtone? = null
    private var vibrator: Vibrator? = null

    fun startRingback() {
        stop()
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 60) }.getOrNull()
        tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE)   // 一直重複，直到 stopTone
    }

    fun startRinging(c: Context) {
        stop()
        val am = c.getSystemService(AudioManager::class.java)
        val mode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            ring = runCatching {
                RingtoneManager.getRingtone(c.applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            }.getOrNull()?.apply {
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                runCatching { play() }
            }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            vibrator = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java)?.defaultVibrator
                else @Suppress("DEPRECATION") c.getSystemService(Vibrator::class.java)
            runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0)) }   // 震 0.8 秒、停 1.2 秒，重複
        }
    }

    fun stop() {
        runCatching { tone?.stopTone(); tone?.release() }
        tone = null
        runCatching { ring?.stop() }
        ring = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }
}
