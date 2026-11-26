package com.example.wristai.wear

import android.app.Application
import com.example.voiceai.shared.I18n

/** 手表：启动时载入介面语言（跟着手表系统语言；手机换了语言会推过来，见 [PhoneMessageListenerService]）。 */
class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
    }
}
