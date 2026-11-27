package com.example.wristai

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

/**
 * 手机录音用哪支麦克风：手机本身（预设）或车上 / 蓝牙耳机的麦克风（蓝牙通话通道 HFP）。
 *
 * - 手机：收整车 / 整间的人声，音质完整，分说话者比较准。
 * - 车上 / 蓝牙：对准驾驶、降噪重，一个人开车记事好用；但取样率可能只有 8 kHz（电话音质），乘客声音会被压低；
 *   开着时车子当成在通话（音乐会暂停）。
 * 选了车上但没连着蓝牙麦克风 → 改用手机，报告「基本資料」会写。录完把实际用的麦克风记进 [Meeting.mic]，方便比较辨识率。
 */
object MicRoute {
    private const val TAG = "MicRoute"
    private const val PREFS = "wristai_settings"
    private const val KEY = "mic_source"
    const val PHONE = "phone"
    const val CAR = "car"

    fun setting(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, PHONE) ?: PHONE
    fun set(c: Context, v: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v).apply()

    /** 现在连着的蓝牙通话麦克风（车上 / 耳机）；没有 → null。 */
    fun btInput(c: Context): AudioDeviceInfo? =
        c.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    /** 这次录音要用的录音来源：车上用通话来源（蓝牙通道要这个），手机用 MIC（有自动增益，远的人声也收得到）。 */
    fun audioSource(c: Context): Int =
        if (setting(c) == CAR && btInput(c) != null) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC

    @Volatile private var routed = false

    /** 开始录音前：选了车上就把通话通道切到蓝牙、指定录音装置。回传 null = 照常用手机。 */
    fun apply(c: Context, record: AudioRecord): AudioDeviceInfo? {
        if (setting(c) != CAR) return null
        val am = c.getSystemService(AudioManager::class.java) ?: return null
        val input = btInput(c) ?: return null
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION") am.startBluetoothSco()
                @Suppress("DEPRECATION") run { am.isBluetoothScoOn = true }
            }
            routed = true
            record.setPreferredDevice(input)
        }.onFailure { Log.w(TAG, "route to bluetooth failed", it) }
        return input
    }

    /** 录音结束：通话通道还原（音乐恢复）。 */
    fun release(c: Context) {
        if (!routed) return
        routed = false
        val am = c.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else { @Suppress("DEPRECATION") am.stopBluetoothSco(); @Suppress("DEPRECATION") run { am.isBluetoothScoOn = false } }
        }
    }

    /** 录音开始后实际用的麦克风 → 存进 [Meeting.mic] 的字串。 */
    fun describe(c: Context, record: AudioRecord): String {
        val d = record.routedDevice
        if (d == null || d.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return if (setting(c) == CAR) "$PHONE:fallback" else PHONE
        }
        val rate = d.sampleRates.maxOrNull() ?: 0
        val name = d.productName?.toString().orEmpty().replace(":", " ").take(40)
        return "$CAR:$name:$rate"
    }

    /** [Meeting.mic] → 给人看的文字（报告「基本資料」）。 */
    fun label(mic: String?): String? {
        if (mic == null) return null
        val p = mic.split(":")
        return when (p[0]) {
            PHONE -> if (p.getOrNull(1) == "fallback") "手機（選了車上，但沒有連著藍牙麥克風）".tr else "手機".tr
            CAR -> {
                val name = p.getOrNull(1).orEmpty().ifBlank { "藍牙".tr }
                val rate = p.getOrNull(2)?.toIntOrNull() ?: 0
                "車上／藍牙：{0}".trf(name) + when {
                    rate in 1..8000 -> "（8 kHz 電話音質）".tr
                    rate > 8000 -> "（{0} kHz）".trf(rate / 1000)
                    else -> ""
                }
            }
            else -> mic
        }
    }
}
