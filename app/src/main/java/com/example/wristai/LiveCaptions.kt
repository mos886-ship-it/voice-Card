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
 * 即时翻译（字幕）：手机内建语音辨识 → ML Kit 装置端翻译。语言不限：手机辨识得了、ML Kit 翻得了的都可以（[TranslateLangs]）。
 *
 * 两种听法：
 * - 麦克风（只翻译、不录音）：辨识器自己开麦克风，一句结束就接下一句。旧手机也能用。
 * - 录音中（[feed]）：手机录音的声音同时送进辨识器（Android 13+，[RecognizerIntent.EXTRA_AUDIO_SOURCE]），录完照常产生报告。
 * 每句翻好推给手表（[WatchNotifier.sendCaption]），可以自动朗读（[Speaker]）。
 */
object LiveCaptions {
    private const val TAG = "LiveCaptions"
    private const val MAX_LINES = 300

    /** 一句：原文、译文（null = 翻译中）、是不是还在说（辨识中的暂时结果）。 */
    data class Line(val id: Long, val original: String, val translated: String?, val partial: Boolean = false)

    data class State(
        val running: Boolean = false,
        val lines: List<Line> = emptyList(),
        /** 现在的状态说明（下载语言模型中、改用线上辨识…）；null = 正常 */
        val status: String? = null,
        val error: String? = null,
        /** 说的语言（辨识服务的代码，例如 zh-TW、en-GB） */
        val source: String = "zh-TW",
        /** 翻成的语言（ML Kit 代码，例如 en、ja） */
        val target: String = "en",
        /** true = 用录音的声音（录音中）；false = 辨识器自己开麦克风 */
        val fromRecording: Boolean = false,
        /** 这次翻译的编号＝开始时间（存记录用）；同时录的那段录音。 */
        val sessionId: Long = 0,
        val meetingId: String? = null,
    )

    /** 翻成什么的预设：介面语言；跟说的一样就换一个（中文 ↔ 英文）。 */
    fun defaultTarget(sourceTag: String, uiLang: String): String {
        val src = TranslateLangs.mlkitOf(sourceTag)
        val ui = TranslateLangs.mlkitOf(uiLang) ?: "en"
        return if (ui != src) ui else if (ui == "zh") "en" else "zh"
    }

    /** 录音的声音送进辨识器要 Android 13+；麦克风模式都可以。 */
    val canUseRecording get() = Build.VERSION.SDK_INT >= 33

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 录音刚停止时那段录音的编号（录音停止会一起停字幕，那时 [PhoneRecorder.active] 已经清掉）。 */
    @Volatile var lastMeetingId: String? = null

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
    /** 离线辨识没有这个语言 → 改用线上辨识（会传到 Google），画面上会写。 */
    private var online = false
    /** 辨识服务候选（[engines]）里现在用第几个；这次开字幕有没有听到过字；连续出错几次。 */
    private var engine = 0
    @Volatile private var heard = false
    private var errorsInRow = 0
    private const val ON_DEVICE = "on-device"
    /** 这次开 App 已经知道离线没有的语言（不要每次都跳「下载离线语音」）。 */
    private val offlineMissing = mutableSetOf<String>()

    /** 辨识清单更新了（例如刚下载好离线语音）：已经装好的语言不再当成「没有离线」，下次开始就用离线。 */
    fun offlineInstalled(s: TranslateLangs.Support) {
        offlineMissing.removeAll { s.status(it) == TranslateLangs.Status.OFFLINE }
    }

    /**
     * 辨识服务的候选顺序：离线（有、而且这个语言没确认缺）→ Google 的线上服务 → 手机上其他服务。
     * 设定关掉「允许线上辨识」就只用离线。
     */
    private fun engines(c: Context): List<String> {
        val src = _state.value.source
        val out = mutableListOf<String>()
        if (src !in offlineMissing && SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) out += ON_DEVICE
        if (Settings.allowOnlineRecognition(c)) out += TranslateLangs.services(c).map { it.flattenToString() }
        return out
    }

    /** 录音回圈呼叫：用录音的声音翻译时才送。 */
    fun feed(buf: ByteArray, n: Int) {
        if (sink == null || n <= 0) return
        queue.offer(buf.copyOf(n))
    }

    /**
     * 开始翻译。[fromRecording] = 用正在录的声音（录音中才可以）；否则辨识器自己开麦克风。
     */
    fun start(context: Context, source: String, target: String, fromRecording: Boolean = PhoneRecorder.active.value != null) {
        if (_state.value.running) stop()
        val c = context.applicationContext
        appContext = c
        val srcMl = TranslateLangs.mlkitOf(source)
        val base = State(source = source, target = target, fromRecording = fromRecording)
        when {
            srcMl == null -> { _state.value = base.copy(error = "手機的翻譯不支援{0}".trf(TranslateLangs.name(source))); return }
            srcMl == target -> { _state.value = base.copy(error = "說的語言和翻成的語言一樣".tr); return }
            fromRecording && !canUseRecording -> { _state.value = base.copy(error = "這支手機（Android 13 以下）錄音中不能同時翻譯；請先停止錄音，改用只翻譯".tr); return }
        }
        online = source in offlineMissing
        lastMeetingId = null
        if (Settings.quietTranslate(c)) QuietMode.on(c)
        engine = 0; heard = false; errorsInRow = 0
        Settings.addRecentTranslateLang(c, source)
        Settings.addRecentTranslateLang(c, target)
        _state.value = base.copy(running = true,
            sessionId = System.currentTimeMillis(), meetingId = PhoneRecorder.active.value?.meetingId,
            status = if (online) onlineNote(source) else "準備翻譯模型…".tr)
        // 翻译模型：第一次要下载（约 30 MB）
        translator?.close()
        val t = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(srcMl!!).setTargetLanguage(target).build())
        translator = t
        scope.launch {
            try {
                val cond = DownloadConditions.Builder().apply { if (Settings.translateModelsWifiOnly(c)) requireWifi() }.build()
                t.downloadModelIfNeeded(cond).await()
                update { it.copy(status = if (online) it.status else null) }
                // 先前没翻到的补翻
                for (l in _state.value.lines) if (!l.partial && l.translated == null) translate(l.id, l.original)
            } catch (e: Exception) {
                Log.w(TAG, "model download failed", e)
                update { it.copy(status = if (Settings.translateModelsWifiOnly(c)) "翻譯模型還沒下載（設定為只用 Wi-Fi 下載），先只顯示原文".tr
                    else "翻譯模型下載失敗，只顯示原文（{0}）".trf(e.message)) }
            }
        }
        if (fromRecording) startWriter()
        startRecognizer(c)
    }

    private fun onlineNote(tag: String) = "手機的離線語音沒有{0}，這次改用線上辨識（聲音會傳到 Google 辨識）".trf(TranslateLangs.shortName(tag))

    /** 对调说的与翻成的（对话：换对方说）。正在翻译就用新的方向重新开始。 */
    fun swap(context: Context) {
        val s = _state.value
        val newSource = TranslateLangs.defaultTag(s.target, preferred = Settings.recentTranslateLangs(context))
        val newTarget = TranslateLangs.mlkitOf(s.source) ?: return
        Settings.setTranslateSource(context, newSource)
        Settings.setTranslateTarget(context, newTarget)
        if (s.running) {
            // 同一次对话：字幕留着（存记录时各自那一段分开存）
            val keep = s.lines
            start(context, newSource, newTarget, s.fromRecording)
            update { it.copy(lines = (keep + it.lines).takeLast(MAX_LINES)) }
        } else update { it.copy(source = newSource, target = newTarget) }
    }

    fun stop() {
        pauseJob?.cancel()
        update { it.copy(running = false, status = null) }
        sink = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        writer?.interrupt(); writer = null
        queue.clear()
        runCatching { readEnd?.close() }; readEnd = null
        appContext?.let { QuietMode.off(it) }
        // 还在说的那句留下来当成一句
        update { s -> s.copy(lines = s.lines.map { if (it.partial) it.copy(partial = false) else it }) }
        val c = appContext ?: return
        WatchNotifier.sendCaption(c, "")
        // 存成一笔记录；最后几句的翻译可能还没回来，3 秒后再存一次（同一笔）
        val id = _state.value.sessionId
        if (id != 0L && Settings.saveTranslations(c)) {
            val firstLine = startLineOf[id] ?: 0L
            saveSession(c, _state.value, firstLine)
            scope.launch { kotlinx.coroutines.delay(3_000); if (_state.value.sessionId == id) saveSession(c, _state.value, firstLine) }
        }
    }

    /** 每次开始时第一句的编号（对调方向时字幕留着，但存记录只存这一段）。 */
    private val startLineOf = mutableMapOf<Long, Long>()

    private fun saveSession(c: Context, s: State, firstLine: Long) {
        val lines = s.lines.filter { it.id >= firstLine && it.original.isNotBlank() }.map { TranslationStore.Line(it.original, it.translated) }
        if (lines.isEmpty()) return
        runCatching {
            TranslationStore.save(c, TranslationStore.Session(s.sessionId, s.sessionId, System.currentTimeMillis(),
                s.source, s.target, s.meetingId ?: PhoneRecorder.active.value?.meetingId ?: lastMeetingId, lines))
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

    /** 开一段新的辨识（录音模式：pipe 的读端交给辨识器，写端给 [feed]；麦克风模式：辨识器自己听）。 */
    private fun startRecognizer(c: Context) {
        val s = _state.value
        cut = ""; pauseJob?.cancel()
        if (!startLineOf.containsKey(s.sessionId)) startLineOf[s.sessionId] = nextId
        val source = s.source
        var pipeRead: ParcelFileDescriptor? = null
        if (s.fromRecording) {
            if (Build.VERSION.SDK_INT < 33) return
            val pipe = ParcelFileDescriptor.createPipe()
            runCatching { readEnd?.close() }
            readEnd = pipe[0]; pipeRead = pipe[0]
            val oldSink = sink
            sink = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            runCatching { oldSink?.close() }
        }
        runCatching { recognizer?.destroy() }
        val list = engines(c)
        if (list.isEmpty()) {
            update { it.copy(error = if (!Settings.allowOnlineRecognition(c)) "{0}沒有離線語音辨識，而且線上辨識已關閉（翻譯 → 設定）".trf(TranslateLangs.name(source))
                else "這支手機沒有可用的語音辨識服務（請安裝 / 啟用 Google 語音服務）".tr) }
            stop(); return
        }
        if (engine >= list.size) engine = 0
        val eng = list[engine]
        val r = when {
            eng == ON_DEVICE && Build.VERSION.SDK_INT >= 31 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(c)
            else -> SpeechRecognizer.createSpeechRecognizer(c, android.content.ComponentName.unflattenFromString(eng))
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            fun text(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

            override fun onPartialResults(b: Bundle?) {
                val t = text(b); if (t.isEmpty() || Speaker.speaking) return
                heard = true; errorsInRow = 0
                // 已经切出去的前段拿掉；还在说的这段太长就先切一句出去翻
                var rest = Chunker.remainder(t, cut)
                Chunker.split(rest)?.let { (piece, after) ->
                    commitPiece(piece)
                    cut = Chunker.newCut(t, cut, piece, after)
                    rest = after
                }
                if (rest.isEmpty()) return
                lastPartial = t
                // 停顿：文字一段时间没再变（说话的人换气、讲完一个意思）就把这段定下来翻，不等辨识器判断整句结束
                pauseJob?.cancel()
                pauseJob = scope.launch {
                    kotlinx.coroutines.delay(PAUSE_MS)
                    val last = _state.value.lines.lastOrNull()
                    if (_state.value.running && last != null && last.partial && lastPartial == t) {
                        commitPiece(last.original)
                        cut = t.trimEnd()
                    }
                }
                update { st ->
                    val last = st.lines.lastOrNull()
                    val lines = if (last != null && last.partial) st.lines.dropLast(1) + last.copy(original = rest)
                    else st.lines + Line(nextId++, rest, null, partial = true)
                    st.copy(lines = lines.takeLast(MAX_LINES))
                }
                draft()
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
                            // 离线没有这个语言：这次改用线上辨识（不自动跳系统下载视窗；要离线就到「翻譯 → 設定 → 下載離線語音」）
                            offlineMissing += source
                            online = true
                            if (Settings.allowOnlineRecognition(c)) update { it.copy(status = onlineNote(source)) }
                        }
                        nextEngine(list, error); return
                    }
                    // 麦克风模式：没听到、没说话是常态 → 接着听
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> if (!_state.value.fromRecording) { restart(); return }   // 麦克风：安静、没说话是常态
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
                    update { it.copy(error = if (_state.value.fromRecording)
                        "手機的語音辨識沒辦法邊錄邊辨識（錯誤 {0}）。錄音照常，錄完的報告不受影響。".trf(error)
                        else "手機的語音辨識不能用（錯誤 {0}）".trf(error)) }
                    stop()
                }
            }

            fun restart() {
                if (!_state.value.running) return
                scope.launch { kotlinx.coroutines.delay(150); if (_state.value.running) startRecognizer(c) }
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, source)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (pipeRead != null && Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipeRead)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, AudioSpec.CHANNELS)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioSpec.SAMPLE_RATE)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            } else {
                // 麦克风：说完停约 1 秒算一句（有的辨识服务不理这个，所以还会边说边切、边说边翻）
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            }
        }
        r.startListening(intent)
    }

    /** 这段话（辨识器的一次结果）里已经切出去翻好的前段；辨识器给最终结果时清掉。 */
    private var cut = ""
    /** 最近一次的暂时结果（整段原文）；停顿计时器比对用。 */
    private var lastPartial = ""
    private var pauseJob: kotlinx.coroutines.Job? = null
    /** 文字多久没变算「停顿」：一般对话换气、讲完一个意思大约 0.5～1 秒。 */
    private const val PAUSE_MS = 700L

    /** 还在说就先切出去的一句：当成说完的一句翻译（推手表、朗读），后面接着的再开新的一行。 */
    private fun commitPiece(piece: String) {
        update { s ->
            val last = s.lines.lastOrNull()
            val lines = if (last != null && last.partial) s.lines.dropLast(1) + last.copy(original = piece, partial = false)
            else s.lines + Line(nextId++, piece, null)
            s.copy(lines = lines.takeLast(MAX_LINES))
        }
        val line = _state.value.lines.lastOrNull { !it.partial && it.original == piece } ?: return
        translate(line.id, piece)
    }

    @Volatile private var drafting = false

    /** 还在说的那行边说边翻（暂时的译文，说完再翻一次定下来）；同时只翻一个，翻完看文字变了再翻。 */
    private fun draft() {
        if (drafting || translator == null) return
        drafting = true
        scope.launch {
            var done: String? = null
            try {
                while (true) {
                    val line = _state.value.lines.lastOrNull()?.takeIf { it.partial } ?: break
                    if (line.original == done) break
                    done = line.original
                    val t = translator ?: break
                    val out = runCatching { t.translate(line.original).await() }.getOrNull() ?: break
                    val shown = if (_state.value.target == "zh") TraditionalChinese.convert(out) else out
                    update { s -> s.copy(lines = s.lines.map { if (it.id == line.id && it.partial) it.copy(translated = shown) else it }) }
                    kotlinx.coroutines.delay(250)
                }
            } finally { drafting = false }
        }
    }

    /** 一句说完：定下原文、开始翻译。朗读中听到的（自己的朗读）不算。 */
    private fun finishLine(t0: String) {
        pauseJob?.cancel()
        val t = if (Speaker.speaking) "" else Chunker.remainder(t0, cut)
        cut = ""
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
        val target = _state.value.target
        scope.launch {
            val out = runCatching { t.translate(text).await() }.getOrNull() ?: return@launch
            val shown = if (target == "zh") TraditionalChinese.convert(out) else out
            update { s -> s.copy(lines = s.lines.map { if (it.id == id) it.copy(translated = shown) else it }) }
            val c = appContext ?: return@launch
            if (Settings.captionToWatch(c)) WatchNotifier.sendCaption(c, shown, text)
            if (Settings.autoSpeak(c) && _state.value.running) Speaker.speak(c, shown, target)
        }
    }
}

/**
 * 边说边切句：辨识器常常要等人停很久才给一句，一口气说很长就要等很久才翻。主要靠停顿切（[LiveCaptions] 文字约 0.7 秒没变）；这里是标点与太长的备用切法。
 * 还在说的文字里出现句号、问号…就把前面切成一句；没有标点但太长（中日韩约 40 字、其他约 20 个字，只在一直没停顿时才会用到）
 * 就在逗号或空白处切，最后几个字留着（辨识器还可能改）。
 */
object Chunker {
    private const val ENDS = "。！？!?；;…"
    private const val SOFT = "，、,："

    private fun isCjk(ch: Char) = ch in '぀'..'ヿ' || ch in '㐀'..'鿿' || ch in '가'..'힯' || ch in '豈'..'﫿'
    private fun cjk(s: String) = s.count { isCjk(it) } * 2 > s.count { it.isLetterOrDigit() }

    /** 已经切出去的前段 [cut] 拿掉，回传剩下的；辨识器改了前面的字（开头对不上）就照字数略过。 */
    fun remainder(t: String, cut: String): String {
        if (cut.isEmpty()) return t.trim()
        if (t.startsWith(cut)) return t.substring(cut.length).trimStart(' ', *SOFT.toCharArray(), *ENDS.toCharArray()).trim()
        var k = cut.count { it.isLetterOrDigit() }
        var i = 0
        while (i < t.length && k > 0) { if (t[i].isLetterOrDigit()) k--; i++ }
        return t.substring(i).trimStart(' ', *SOFT.toCharArray(), *ENDS.toCharArray()).trim()
    }

    /** 切完以后的「已切前段」：尽量是辨识结果 [t] 的开头原文（下次比对用）。 */
    fun newCut(t: String, cut: String, piece: String, after: String): String {
        val tt = t.trimEnd()
        return if (after.isNotEmpty() && tt.endsWith(after)) tt.substring(0, tt.length - after.length) else cut + piece
    }

    /** 要切的话回传 (切出去的一句, 剩下还在说的)；不用切回 null。[rest] 是原文的尾段（切点前后都照原文）。 */
    fun split(rest: String): Pair<String, String>? {
        // 1) 句尾标点：后面还有字（标点后面的字表示这句真的说完了），前段至少 4 个字
        var at = -1
        for (i in rest.indices) {
            val c = rest[i]
            val end = c in ENDS || (c == '.' && i + 1 < rest.length && rest[i + 1] == ' ')
            if (end && i + 1 < rest.length && rest.substring(i + 1).any { it.isLetterOrDigit() } &&
                rest.substring(0, i).count { it.isLetterOrDigit() } >= 4) at = i
        }
        if (at >= 0) return rest.substring(0, at + 1).trim() to rest.substring(at + 1).trimStart()
        // 2) 太长：在逗号 / 空白处切，最后几个字留着
        val asian = cjk(rest)
        val letters = rest.count { it.isLetterOrDigit() }
        if (asian) {
            if (letters < 40) return null
            val limit = rest.length - 6
            val soft = (limit - 1 downTo 8).firstOrNull { rest[it] in SOFT }
            val cutAt = soft?.plus(1) ?: limit
            return rest.substring(0, cutAt).trim() to rest.substring(cutAt).trimStart()
        } else {
            val words = rest.trim().split(Regex("\\s+"))
            if (words.size < 20) return null
            // 留最后 3 个字；前面有逗号就在逗号切
            val keepFrom = words.size - 3
            val commaWord = (keepFrom - 1 downTo 4).firstOrNull { words[it].lastOrNull()?.let { c -> c in SOFT } == true }
            val n = (commaWord?.plus(1)) ?: keepFrom
            val head = words.take(n).joinToString(" ")
            val idx = rest.indexOf(head).takeIf { it >= 0 } ?: return null
            val end = idx + head.length
            return rest.substring(0, end).trim() to rest.substring(end).trimStart()
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
