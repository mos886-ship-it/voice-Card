package com.example.wristai

import android.content.Context
import android.widget.Toast
import com.example.voiceai.shared.tr

/**
 * App 捷径（res/xml/shortcuts.xml）：长按 App 图示、三星「模式與日常」（例：连上车子蓝牙 → 开始录音）、
 * 语音助理开捷径都会走到这里（[MainActivity] 收到 action 后呼叫 [handle]）。开车时一键 / 一句话就能用。
 */
object CarShortcuts {
    const val PREFIX = "com.example.wristai.shortcut."
    const val RECORD = PREFIX + "RECORD"
    const val STOP = PREFIX + "STOP"
    const val FOOD = PREFIX + "FOOD"
    const val READ_LATEST = PREFIX + "READ_LATEST"
    const val READ_TODOS = PREFIX + "READ_TODOS"

    fun handle(c: Context, action: String) {
        when (action) {
            RECORD -> if (PhoneRecorder.active.value == null) PhoneRecordingService.start(c) else toast(c, "已經在錄音".tr)
            STOP -> if (PhoneRecorder.active.value != null) PhoneRecordingService.stop(c) else toast(c, "沒有在錄音".tr)
            FOOD -> if (PhoneRecorder.active.value == null) PhoneRecordingService.start(c, food = true) else toast(c, "已經在錄音".tr)
            READ_LATEST -> toast(c, CarMode.readLatest(c))
            READ_TODOS -> toast(c, CarMode.readTodos(c))
        }
    }

    private fun toast(c: Context, s: String) = Toast.makeText(c, s.take(120), Toast.LENGTH_SHORT).show()
}
