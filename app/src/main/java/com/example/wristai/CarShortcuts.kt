package com.example.wristai

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    /** 小工具：录音键（没在录 → 开始，在录 → 停止）、开即时翻译、开最新报告。 */
    const val TOGGLE = PREFIX + "TOGGLE"
    const val TRANSLATE = PREFIX + "TRANSLATE"
    const val OPEN_LATEST = PREFIX + "OPEN_LATEST"

    fun handle(c: Context, action: String) {
        when (action) {
            RECORD -> if (PhoneRecorder.active.value == null) PhoneRecordingService.start(c) else toast(c, "已經在錄音".tr)
            STOP -> if (PhoneRecorder.active.value != null) PhoneRecordingService.stop(c) else toast(c, "沒有在錄音".tr)
            FOOD -> if (PhoneRecorder.active.value == null) PhoneRecordingService.start(c, food = true) else toast(c, "已經在錄音".tr)
            READ_LATEST -> toast(c, CarMode.readLatest(c))
            READ_TODOS -> toast(c, CarMode.readTodos(c))
            TOGGLE -> if (PhoneRecorder.active.value == null) PhoneRecordingService.start(c) else PhoneRecordingService.stop(c)
            TRANSLATE -> com.example.wristai.ui.TranslatePage.open()
            OPEN_LATEST -> MeetingStore.meetings.value.filter { it.status == MeetingStatus.DONE && !FoodLog.isFood(it.id) }
                .maxByOrNull { it.createdAt }?.let { PendingOpen.meetingId = it.id }
        }
    }

    private fun toast(c: Context, s: String) = Toast.makeText(c, s.take(120), Toast.LENGTH_SHORT).show()
}

/** 要打开的报告（小工具点最新报告）；[MainActivity] 看到就切到那份的详情。 */
object PendingOpen {
    var meetingId by androidx.compose.runtime.mutableStateOf<String?>(null)
}
