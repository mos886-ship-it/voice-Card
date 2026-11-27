package com.example.wristai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 朗读译文：手机内建的文字转语音（TextToSpeech），用手机已有的语音包，不上传。
 * [speaking] = 正在念（即时翻译这时听到的声音是自己念的，不当成一句）。
 */
object Speaker {
    @Volatile var speaking = false
        private set
    /** 念完（没有排着的句子了）时呼叫；车机播放状态用（[CarMediaService]）。 */
    @Volatile var onIdle: (() -> Unit)? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<Triple<String, String, Float?>>()

    private fun ensure(c: Context) {
        if (tts != null) return
        tts = TextToSpeech(c.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { speaking = true }
                    override fun onDone(id: String?) { speaking = tts?.isSpeaking == true; if (!speaking) onIdle?.invoke() }
                    @Deprecated("old API") override fun onError(id: String?) { speaking = false; onIdle?.invoke() }
                })
                val copy = pending.toList(); pending.clear()
                for ((t, l, r) in copy) speak(c, t, l, r)
            }
        }
    }

    /** 念一句（[lang] = ML Kit 代码，例如 en、ja；[rate] 没给就用翻译设定的速度）。手机没有那个语言的语音就不念，回 false。 */
    fun speak(c: Context, text: String, lang: String, rate: Float? = null): Boolean {
        ensure(c)
        val t = tts ?: return false
        if (!ready) { pending += Triple(text, lang, rate); return true }
        val locale = if (lang == "zh") Locale.TAIWAN else Locale.forLanguageTag(lang)
        if (t.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) return false
        t.language = locale
        // 翻译中媒体声音静音了（不要辨识的「叮」）：朗读改走闹钟声道才听得到
        t.setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(if (QuietMode.active(c)) android.media.AudioAttributes.USAGE_ALARM else android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        t.setSpeechRate(rate ?: Settings.speakRate(c))
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "cap-${System.nanoTime()}")
        return true
    }

    /** 这个语言手机能不能念（设定页显示用）；还没准备好回 null。 */
    fun canSpeak(c: Context, lang: String): Boolean? {
        ensure(c)
        val t = tts ?: return null
        if (!ready) return null
        val locale = if (lang == "zh") Locale.TAIWAN else Locale.forLanguageTag(lang)
        return t.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
    }

    fun stop() { runCatching { tts?.stop() }; speaking = false }
}
