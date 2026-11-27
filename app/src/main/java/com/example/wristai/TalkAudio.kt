package com.example.wristai

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import com.example.voiceai.shared.AudioSpec
import com.example.voiceai.shared.WavFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 遠距對話的聲音：
 * - [Mic]：自己開麥克風（通話用的收音，有回音消除），同一份聲音一邊餵手機辨識（[LiveCaptions.feed]，Android 13+），
 *   一邊留最近 60 秒，說完一句就剪出那句的原聲（[clip]）。播放對方的聲音時暫停餵辨識，不會把對方的話當成自己說的。
 * - [Player]：對方的一句：先播原聲片段，再唸翻譯（照順序排隊）。
 */
object TalkAudio {
    private const val TAG = "TalkAudio"
    private const val RATE = AudioSpec.SAMPLE_RATE
    private const val KEEP_SECONDS = 60

    // ---------- 麥克風 ----------
    object Mic {
        private var record: AudioRecord? = null
        private var thread: Thread? = null
        private val ring = ShortArray(RATE * KEEP_SECONDS)
        /** 從開始收音到現在的總樣本數（剪片段用的時間軸）。 */
        @Volatile var samples = 0L
            private set
        @Volatile var running = false
            private set

        @SuppressLint("MissingPermission")
        fun start(): Boolean {
            if (running) return true
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 8192))
            }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return false
            record = r
            samples = 0L
            running = true
            r.startRecording()
            thread = Thread {
                val buf = ShortArray(1024)
                val bytes = ByteArray(2048)
                val silence = ByteArray(2048)
                while (running) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    synchronized(ring) {
                        for (i in 0 until n) ring[((samples + i) % ring.size).toInt()] = buf[i]
                        samples += n
                    }
                    for (i in 0 until n) { bytes[2 * i] = (buf[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
                    // 正在播對方的聲音：送靜音給辨識（不然會把對方的話辨識成自己的）
                    LiveCaptions.feed(if (Player.busy) silence else bytes, n * 2)
                }
            }.apply { isDaemon = true; name = "talk-mic"; start() }
            return true
        }

        fun stop() {
            running = false
            runCatching { record?.stop() }
            runCatching { record?.release() }
            record = null
            thread = null
        }

        /** 剪一段 [from, to)（樣本位置）存成 m4a；太短（< 0.4 秒）或已經不在緩衝裡 → null。 */
        fun clip(c: Context, from: Long, to: Long, out: File): File? {
            val start = maxOf(from, samples - ring.size + 1, 0L)
            val end = minOf(to, samples)
            if (end - start < RATE * 4 / 10) return null
            val pcm = ShortArray((end - start).toInt())
            synchronized(ring) { for (i in pcm.indices) pcm[i] = ring[((start + i) % ring.size).toInt()] }
            val wav = File(c.cacheDir, "talk_${System.nanoTime()}.wav")
            return try {
                FileOutputStream(wav).buffered().use { o ->
                    WavFile.writePlaceholderHeader(o)
                    val b = ByteArray(pcm.size * 2)
                    for (i in pcm.indices) { b[2 * i] = (pcm[i].toInt() and 0xff).toByte(); b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
                    o.write(b)
                }
                WavFile.finalizeHeader(wav)
                out.parentFile?.mkdirs()
                VoiceRecorderExporter.encodeM4a(wav, out, outRate = RATE, bitRate = 32_000)
                out.takeIf { it.exists() && it.length() > 0 }
            } catch (e: Exception) {
                Log.w(TAG, "clip failed", e); null
            } finally { wav.delete() }
        }
    }

    // ---------- 播放：原聲 → 翻譯 ----------
    object Player {
        private data class Item(val audio: File?, val text: String?, val lang: String)
        private val queue = Channel<Item>(Channel.UNLIMITED)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        /** 正在播或還有排著的（麥克風這時送靜音給辨識）。 */
        val busy get() = pending.get() > 0
        private val pending = java.util.concurrent.atomic.AtomicInteger(0)
        private var started = false
        private var current: MediaPlayer? = null

        fun enqueue(c: Context, audio: File?, text: String?, lang: String) {
            ensure(c.applicationContext)
            pending.incrementAndGet()
            queue.trySend(Item(audio, text, lang))
        }

        fun stopAll() {
            while (queue.tryReceive().isSuccess) pending.decrementAndGet()
            runCatching { current?.stop() }
            Speaker.stop()
        }

        private fun ensure(c: Context) {
            if (started) return
            started = true
            scope.launch {
                for (item in queue) {
                    item.audio?.takeIf { it.exists() }?.let { play(it) }
                    item.text?.takeIf { it.isNotBlank() }?.let { t ->
                        if (Speaker.speak(c, t, item.lang)) {
                            delay(300)
                            while (Speaker.speaking) delay(150)
                        }
                    }
                    delay(200)
                    pending.updateAndGet { maxOf(0, it - 1) }
                }
            }
        }

        private suspend fun play(f: File) {
            val done = CompletableDeferred<Unit>()
            val mp = withContext(Dispatchers.Main) {
                runCatching {
                    MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        setDataSource(f.absolutePath)
                        setOnCompletionListener { done.complete(Unit) }
                        setOnErrorListener { _, _, _ -> done.complete(Unit); true }
                        prepare()
                        start()
                    }
                }.getOrNull()
            } ?: return
            current = mp
            kotlinx.coroutines.withTimeoutOrNull(40_000) { done.await() }
            runCatching { mp.release() }
            current = null
        }
    }
}
