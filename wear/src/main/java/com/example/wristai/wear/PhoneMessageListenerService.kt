package com.example.wristai.wear

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.example.voiceai.shared.MeetingSummary
import com.example.voiceai.shared.WristAiProtocol
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import kotlinx.coroutines.runBlocking
import com.google.android.gms.wearable.WearableListenerService

/** 接收手机推回来的处理进度与最终摘要（App 没开着也会被系统唤起）。 */
class PhoneMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WristAiProtocol.PATH_THEME -> {
                com.example.wristai.wear.ui.theme.Brand.apply(this, event.data.toString(Charsets.UTF_8))
            }

            WristAiProtocol.PATH_GEO_ACK -> WatchState.setGeoAck(event.data.toString(Charsets.UTF_8))

            WristAiProtocol.PATH_LIST -> WatchState.saveList(this, event.data.toString(Charsets.UTF_8))

            WristAiProtocol.PATH_DETAIL -> WatchState.saveDetail(this, event.data.toString(Charsets.UTF_8))

            WristAiProtocol.PATH_LANG -> {
                // 手表有还没传出去的修改 → 以手表的为准，送给手机
                if (!WatchState.setLangFromPhone(this, event.data.toString(Charsets.UTF_8)))
                    runBlocking { runCatching { PhoneLink.sendLang(this@PhoneMessageListenerService) } }
            }

            WristAiProtocol.PATH_FOOD_TODAY -> WatchState.saveFoodToday(this, event.data.toString(Charsets.UTF_8))

            WristAiProtocol.PATH_PHONE_REC_STATE -> {
                val since = event.data.toString(Charsets.UTF_8).toLongOrNull()?.takeIf { it > 0 }
                val was = WatchState.phoneRecordingSince.value
                WatchState.setPhoneRecording(since)
                if (since != null && was == null) { WatchState.setPhoneStatus(null); vibrate() }
            }

            WristAiProtocol.PATH_STATUS -> {
                WatchState.setPhoneStatus(event.data.toString(Charsets.UTF_8))
            }

            WristAiProtocol.PATH_SUMMARY -> {
                val summary = runCatching { MeetingSummary.fromBytes(event.data) }
                    .onFailure { Log.e("PhoneMsgListener", "Bad summary payload", it) }
                    .getOrNull() ?: return
                WatchState.saveSummary(this, summary)
                WatchState.setPhoneStatus("摘要已送達")
                vibrate()
            }
        }
    }

    /** 手机重新连上时，自动补传之前没送出去的录音（不用手动按「重送」）。 */
    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.name != WristAiProtocol.CAPABILITY_PHONE || info.nodes.isEmpty()) return
        val pending = PhoneLink.pendingFiles(this)
        if (pending.isEmpty()) return
        // 回调本来就在后台线程，阻塞等补传完成可让 Service 在传送期间保持存活
        val sent = runBlocking { runCatching { PhoneLink.sendPending(this@PhoneMessageListenerService) }.getOrDefault(0) }
        WatchState.refreshPending(this)
        if (sent > 0 && WatchState.recorder.value !is RecorderStatus.Recording) {
            WatchState.setPhoneStatus("已自動補傳 $sent 段錄音")
        }
        Log.d("PhoneMsgListener", "Phone reachable again, resent $sent/${pending.size}")
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(300)
        }
    }
}
