package com.example.wristai

import android.app.Activity
import android.app.Application
import android.content.Intent
import com.example.voiceai.shared.I18n

/** 程式一启动就载入介面语言的对照表（背景工作、通知也用得到）。 */
class WristAiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        // 上次即时翻译时被关掉：把暂时静音的声音恢复
        runCatching { QuietMode.restoreLeftover(this) }
        // 旧的饮食记录：脂肪等数字是 0 但表格有 → 补上（只做一次）
        Thread { runCatching { FoodLog.repairStored(this) } }.start()
    }

    companion object {
        /**
         * 介面语言换了：重新启动 App（列举、物件里一开始就算好的字才会换成新语言）。
         * 正在录音时不重启（只换之后查的字），录完下次打开就全部是新语言。回传 true = 正在重启，呼叫端不要再做事。
         */
        fun restartIfLanguageChanged(activity: Activity): Boolean {
            if (!I18n.needsRestart(activity)) return false
            if (PhoneRecorder.active.value != null) { I18n.init(activity); return false }
            activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity.finish()
            Runtime.getRuntime().exit(0)
            return true
        }
    }
}
