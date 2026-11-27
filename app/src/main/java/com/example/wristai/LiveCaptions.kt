package com.example.wristai

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.voiceai.shared.AudioSpec
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 即时翻译（字幕）：手机录音的声音同时送进手机内建语音辨识（Android 13+，[RecognizerIntent.EXTRA_AUDIO_SOURCE]），
 * 一句辨识完就用 ML Kit 装置端翻译。都在手机上做、免费、不上传；只是辅助，完整报告照旧。
 *
 * - [feed]：录音回圈（[PhoneRecordingService]）每读到一段 PCM 就丢进来；没开字幕时什么都不做。
 * - 辨识器一段落结束或出错 → 自动接新的一段，录音不中断。
 * - 每句翻好推给手表（[WatchNotifier.sendCaption]）。
 */
object LiveCaptions {
    private const val TAG = "LiveCaptions"
    private const val MAX_LINES = 200

    /** 一句：原文、译文（null = 翻译中）、是不是还在说（辨识中的暂时结果）。 */
    data class Line(val id: Long, val original: String, val translated: String?, val partial: Boolean = false)

    data class State(
        val running: Boolean = false,
        val lines: List<Line> = emptyList(),
        /** 现在的状态说明（下载语言模型中、没有语音辨识…）；null = 正常 */
        val status: String? = null,
        val error: String? = null,
        val source: SpeechLang = SpeechLang.ZH,
        val target: Target = Target.EN,
        /** 这次翻译的编号＝开始时间（存记录用）；同时录的那段录音。 */
        val sessionId: Long = 0,
        val meetingId: String? = null,
    )

    /** 翻译成：显示名称用该语言自己的写法。 */
    enum class Target(val code: String, val label: String, val mlkit: String) {
        ZH("zh", "繁體中文", TranslateLanguage.CHINESE),
        EN("en", "English", TranslateLanguage.ENGLISH),
        KO("ko", "한국어", TranslateLanguage.KOREAN),
        JA("ja", "日本語", TranslateLanguage.JAPANESE),
        VI("vi", "Tiếng Việt", TranslateLanguage.VIETNAMESE);

        companion object {
            fun of(code: String?) = entries.firstOrNull { it.code == code }
        }
    }

    /** ML Kit 翻译认得的来源语言；台语、客语、粤语没有（手机也辨识不了）。 */
    fun mlkitSource(l: SpeechLang): String? = when (l) {
        SpeechLang.ZH -> TranslateLanguage.CHINESE
        SpeechLang.EN -> TranslateLanguage.ENGLISH
        SpeechLang.JA -> TranslateLanguage.JAPANESE
        SpeechLang.KO -> TranslateLanguage.KOREAN
        SpeechLang.VI -> TranslateLanguage.VIETNAMESE
        SpeechLang.TH -> TranslateLanguage.THAI
        SpeechLang.ID -> TranslateLanguage.INDONESIAN
        else -> null
    }

    fun supported(l: SpeechLang) = mlkitSource(l) != null && Build.VERSION.SDK_INT >= 33

    /** 预设翻成什么：介面语言；跟来源一样就换一个（中文 ↔ 英文）。 */
    fun defaultTarget(source: SpeechLang, uiLang: String): Target {
        val ui = Target.of(uiLang) ?: Target.EN
        val same = mlkitSource(source) == ui.mlkit
        return if (!same) ui else if (ui == Target.ZH) Target.EN else Target.ZH
    }

    /** 录音刚停止时那段录音的编号（录音停止会一起停字幕，那时 [PhoneRecorder.active] 已经清掉）。 */
    @Volatile var lastMeetingId: String? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var nextId = 1L
    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    @Volatile private var sink: OutputStream? = null
    private var readEnd: ParcelFileDescriptor? = null
    /** 录音回圈 → 写进 pipe 的执行绪（辨识器读慢了也不会卡住录音；满了就丢）。 */
    private val queue = ArrayBlockingQueue<ByteArray>(64)
    private var writer: Thread? = null
    private var appContext: Context? = null
    private var segments = 0
    /** 离线辨识没有这个语言 → 改用线上辨识（会传到 Google），画面上会写。 */
    private var online = false
    /** 辨识服务候选（[engines]）里现在用第几个；这次开字幕有没有听到过字；连续出错几次。 */
    private var engine = 0
    @Volatile private var heard = false
    private var errorsInRow = 0
    private const val ON_DEVICE = "on-device"

    /**
     * 辨识服务的候选顺序：离线（有、而且这个语言没确认缺）→ Google 的线上服务 → 手机上其他服务。
     * 三星手机预设的服务可能不接受「直接把录音送进去」，所以明确指定 Google 的，不行再换。
     */
    private fun engines(c: Context): List<String> {
        val src = _state.value.source
        val out = mutableListOf<String>()
        if (src.code !in offlineMissing && SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) out += ON_DEVICE
        val services = c.packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
            .map { android.content.ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        out += services.sortedBy { if (it.packageName.contains("google")) 0 else 1 }.map { it.flattenToString() }
        return out
    }

    /** 这次开 App 已经知道离线没有的语言（不要每次都跳「下载离线语音」）。 */
    private val offlineMissing = mutableSetOf<String>()

    /** 录音回圈呼叫：开着字幕才送。 */
    fun feed(buf: ByteArray, n: Int) {
        if (sink == null || n <= 0) return
        queue.offer(buf.copyOf(n))
    }

    fun start(context: Context, source: SpeechLang, target: Target) {
        if (_state.value.running) stop()
        val c = context.applicationContext
        appContext = c
        if (!supported(source)) {
            _state.value = State(error = "這個語言沒有即時字幕（台語、客語、粵語手機辨識不了）".tr, source = source, target = target)
            return
        }
        online = source.code in offlineMissing
        lastMeetingId = null
        engine = 0; heard = false; errorsInRow = 0
        _state.value = State(running = true, source = source, target = target,
            sessionId = System.currentTimeMillis(), meetingId = PhoneRecorder.active.value?.meetingId,
            status = if (online) "手機的離線語音沒有{0}，這次改用線上辨識（聲音會傳到 Google 辨識）".trf(source.label) else "準備翻譯模型…".tr)
        // 翻译模型：第一次要下载（约 30 MB）
        translator?.close()
        val t = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(mlkitSource(source)!!).setTargetLanguage(target.mlkit).build())
        translator = t
        scope.launch {
            try {
                t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                update { it.copy(status = if (online) it.status else null) }
                // 先前没翻到的补翻
                for (l in _state.value.lines) if (!l.partial && l.translated == null) translate(l.id, l.original)
            } catch (e: Exception) {
                Log.w(TAG, "model download failed", e)
                update { it.copy(status = "翻譯模型下載失敗，只顯示原文（{0}）".trf(e.message)) }
            }
        }
        startWriter()
        startRecognizer(c, source)
    }

    fun stop() {
        update { it.copy(running = false, status = null) }
        sink = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        writer?.interrupt(); writer = null
        queue.clear()
        runCatching { readEnd?.close() }; readEnd = null
        // 还在说的那句留下来当成一句
        update { s -> s.copy(lines = s.lines.map { if (it.partial) it.copy(partial = false) else it }) }
        val c = appContext ?: return
        WatchNotifier.sendCaption(c, "")
        // 存成一笔记录；最后几句的翻译可能还没回来，3 秒后再存一次（同一笔）
        val id = _state.value.sessionId
        if (id != 0L && Settings.saveTranslations(c)) {
            saveSession(c, _state.value)
            scope.launch { kotlinx.coroutines.delay(3_000); if (_state.value.sessionId == id) saveSession(c, _state.value) }
        }
    }

    private fun saveSession(c: Context, s: State) {
        val lines = s.lines.filter { it.original.isNotBlank() }.map { TranslationStore.Line(it.original, it.translated) }
        if (lines.isEmpty()) return
        runCatching {
            TranslationStore.save(c, TranslationStore.Session(s.sessionId, s.sessionId, System.currentTimeMillis(),
                s.source.code, s.target.code, s.meetingId ?: PhoneRecorder.active.value?.meetingId ?: lastMeetingId, lines))
        }.onFailure { Log.w(TAG, "save session failed", it) }
    }

    /** 测试用（只有 debug 版会呼叫）：把一个 16 kHz 单声道 WAV 照实际速度送进字幕，不用真的对麦克风说话。 */
    fun debugFeedWav(file: java.io.File) {
        Thread {
            file.inputStream().use { input ->
                input.skip(44)
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf); if (n <= 0) break
                    feed(buf, n)
                    Thread.sleep(n * 1000L / AudioSpec.BYTES_PER_SECOND)
                }
                // 结尾补 2 秒静音，让最后一句结束
                val silence = ByteArray(4096)
                repeat(16) { feed(silence, silence.size); Thread.sleep(128) }
            }
        }.apply { isDaemon = true }.start()
    }

    fun clear() = update { it.copy(lines = emptyList(), error = null) }

    private fun update(f: (State) -> State) { _state.value = f(_state.value) }

    private fun startWriter() {
        writer?.interrupt()
        writer = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val b = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    try { sink?.write(b) } catch (_: IOException) { /* 辨识器换段时 pipe 关了：这段丢掉 */ }
                }
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; name = "live-captions"; start() }
    }

    /** 开一段新的辨识（pipe 的读端交给辨识器，写端给 [feed]）。 */
    private fun startRecognizer(c: Context, source: SpeechLang) {
        if (Build.VERSION.SDK_INT < 33) return
        val pipe = ParcelFileDescriptor.createPipe()
        runCatching { readEnd?.close() }
        readEnd = pipe[0]
        val oldSink = sink
        sink = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        runCatching { oldSink?.close() }
        runCatching { recognizer?.destroy() }
        val list = engines(c)
        if (list.isEmpty()) {
            update { it.copy(error = "這支手機沒有可用的語音辨識服務（請安裝 / 啟用 Google 語音服務）".tr) }
            stop(); return
        }
        if (engine >= list.size) engine = 0
        val eng = list[engine]
        val r = when (eng) {
            ON_DEVICE -> SpeechRecognizer.createOnDeviceSpeechRecognizer(c)
            else -> SpeechRecognizer.createSpeechRecognizer(c, android.content.ComponentName.unflattenFromString(eng))
        }
        recognizer = r
        segments++
        r.setRecognitionListener(object : RecognitionListener {
            fun text(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

            override fun onPartialResults(b: Bundle?) {
                val t = text(b); if (t.isEmpty()) return
                heard = true; errorsInRow = 0
                update { s ->
                    val last = s.lines.lastOrNull()
                    val lines = if (last != null && last.partial) s.lines.dropLast(1) + last.copy(original = t)
                    else s.lines + Line(nextId++, t, null, partial = true)
                    s.copy(lines = lines.takeLast(MAX_LINES))
                }
            }

            override fun onSegmentResults(b: Bundle) { val t = text(b); if (t.isNotEmpty()) { heard = true; errorsInRow = 0 }; finishLine(t) }
            override fun onResults(b: Bundle?) { val t = text(b); if (t.isNotEmpty()) { heard = true; errorsInRow = 0 }; finishLine(t); restart() }
            override fun onEndOfSegmentedSession() = restart()

            override fun onError(error: Int) {
                Log.d(TAG, "recognizer error $error on $eng")
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        update { it.copy(error = "需要麥克風權限".tr) }; stop(); return
                    }
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                        if (eng == ON_DEVICE) {
                            // 离线没有这个语言：请系统下载离线语音（下次就能离线），这次改用线上辨识
                            runCatching { r.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, source.locale)) }
                            offlineMissing += source.code
                            online = true
                            update { it.copy(status = "手機的離線語音沒有{0}，這次改用線上辨識（聲音會傳到 Google 辨識）".trf(source.label)) }
                        }
                        nextEngine(list, error); return
                    }
                }
                // 这个辨识服务一直出错、一句都没听到（例如三星的服务不接受直接送录音）→ 换下一个
                errorsInRow++
                if (!heard && errorsInRow >= 3) { nextEngine(list, error); return }
                restart()   // 没听到、逾时、忙碌 → 接着听
            }

            fun nextEngine(list: List<String>, error: Int) {
                errorsInRow = 0
                if (engine + 1 < list.size) {
                    engine++
                    restart()
                } else {
                    update { it.copy(error = "手機的語音辨識沒辦法邊錄邊辨識（錯誤 {0}）。錄音照常，錄完的報告不受影響。".trf(error)) }
                    stop()
                }
            }

            fun restart() {
                if (!_state.value.running) return
                scope.launch { kotlinx.coroutines.delay(200); if (_state.value.running) startRecognizer(c, source) }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, source.locale)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, AudioSpec.CHANNELS)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioSpec.SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        r.startListening(intent)
    }

    /** 一句说完：定下原文、开始翻译。 */
    private fun finishLine(t: String) {
        update { s ->
            val last = s.lines.lastOrNull()
            when {
                t.isEmpty() -> s.copy(lines = if (last?.partial == true) s.lines.dropLast(1) else s.lines)
                last != null && last.partial -> s.copy(lines = s.lines.dropLast(1) + last.copy(original = t, partial = false))
                else -> s.copy(lines = (s.lines + Line(nextId++, t, null)).takeLast(MAX_LINES))
            }
        }
        if (t.isEmpty()) return
        val line = _state.value.lines.lastOrNull { !it.partial && it.original == t } ?: return
        translate(line.id, t)
    }

    private fun translate(id: Long, text: String) {
        val t = translator ?: return
        scope.launch {
            val out = runCatching { t.translate(text).await() }.getOrNull() ?: return@launch
            val shown = if (_state.value.target == Target.ZH) TraditionalChinese.convert(out) else out
            update { s -> s.copy(lines = s.lines.map { if (it.id == id) it.copy(translated = shown) else it }) }
            appContext?.takeIf { Settings.captionToWatch(it) }?.let { WatchNotifier.sendCaption(it, shown) }
        }
    }
}

/**
 * ML Kit 翻成中文是简体：用 Android 内建的 ICU 转成繁体（台湾）。转不了就原样（至少看得懂）。
 */
object TraditionalChinese {
    private val tr by lazy {
        if (Build.VERSION.SDK_INT < 29) null
        else runCatching { android.icu.text.Transliterator.getInstance("Hans-Hant") }.getOrNull()
    }

    fun convert(s: String): String = tr?.let { runCatching { it.transliterate(s) }.getOrNull() } ?: s
}
