package com.example.wristai

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

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
import androidx.annotation.RequiresApi
import com.example.voiceai.shared.AudioSpec
import com.example.voiceai.shared.WavFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 用手机系统内建的语音识别（Android 13+ 支持直接读音频文件）把录音转成文字。
 * 不需要额外的 API Key。长录音切成 [CHUNK_SECONDS] 秒一段逐段识别。
 *
 * 想换成 Whisper / 云端 STT，只要换掉 [transcribeChunk] 的实现即可。
 */
class SpeechTranscriber(private val context: Context, private val language: String) {

    companion object {
        private const val TAG = "SpeechTranscriber"
        const val CHUNK_SECONDS = 120

        fun chunkCount(wav: File): Int {
            val pcmBytes = (wav.length() - WavFile.HEADER_SIZE).coerceAtLeast(0)
            val chunkBytes = CHUNK_SECONDS.toLong() * AudioSpec.BYTES_PER_SECOND
            return ((pcmBytes + chunkBytes - 1) / chunkBytes).toInt()
        }

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    class TranscriptionException(message: String) : Exception(message)

    /** 识别第 [index] 段（从 0 开始），返回文字（可能为空字符串 = 这段没人说话）。 */
    suspend fun transcribeChunk(wav: File, index: Int): String {
        if (!isSupported()) {
            throw TranscriptionException("手機需要 Android 13 以上才能辨識錄音檔（目前 Android {0}）".trf(Build.VERSION.RELEASE))
        }
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw TranscriptionException("請打開手機 大D App 並允許「麥克風」權限（系統語音辨識需要），再按重試".tr)
        }
        val pcm = File(context.cacheDir, "chunk_${index}.pcm")
        try {
            val bytes = extractChunk(wav, index, pcm)
            if (bytes < AudioSpec.BYTES_PER_SECOND / 2) return ""
            val seconds = bytes / AudioSpec.BYTES_PER_SECOND
            // 文件识别通常比实时快；给足够的超时空间
            return withTimeout((seconds * 2 + 60) * 1000L) {
                recognizeWithFallback(pcm)
            }
        } finally {
            pcm.delete()
        }
    }

    private fun extractChunk(wav: File, index: Int, out: File): Long {
        val chunkBytes = CHUNK_SECONDS.toLong() * AudioSpec.BYTES_PER_SECOND
        val start = WavFile.HEADER_SIZE + index * chunkBytes
        val length = (wav.length() - start).coerceIn(0, chunkBytes)
        RandomAccessFile(wav, "r").use { raf ->
            raf.seek(start)
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    o.write(buf, 0, n)
                    left -= n
                }
            }
        }
        return out.length()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun recognizeWithFallback(pcm: File): String {
        // 优先用离线（on-device）识别：免网络、隐私好；不支持该语言时退回系统默认识别服务
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            try {
                return recognize(pcm, onDevice = true)
            } catch (e: TranscriptionException) {
                Log.w(TAG, "On-device recognition failed, falling back: ${e.message}")
            }
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw TranscriptionException("這支手機沒有可用的語音辨識服務（請安裝 / 啟用 Google 語音服務）".tr)
        }
        return recognize(pcm, onDevice = false)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun recognize(pcm: File, onDevice: Boolean): String = withContext(Dispatchers.Main) {
        // SpeechRecognizer 必须在主线程创建与使用
        val pfd = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
        val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

        try {
            suspendCancellableCoroutine { cont ->
                val segments = mutableListOf<String>()
                fun finish() {
                    if (cont.isActive) cont.resume(segments.joinToString("，".tr))
                }

                fun firstResult(bundle: Bundle?): String? =
                    bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.takeIf { it.isNotBlank() }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onSegmentResults(segmentResults: Bundle) {
                        firstResult(segmentResults)?.let { segments += it }
                    }

                    override fun onEndOfSegmentedSession() = finish()

                    override fun onResults(results: Bundle?) {
                        firstResult(results)?.let { segments += it }
                        finish()
                    }

                    override fun onError(error: Int) {
                        when {
                            // 有结果之后的「没听到 / 超时」视为正常结束
                            segments.isNotEmpty() ||
                                error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish()
                            cont.isActive -> cont.resumeWithException(
                                TranscriptionException("語音辨識錯誤 {0}（{1}）".trf(errorName(error), if (onDevice) "離線".tr else "線上".tr))
                            )
                        }
                    }

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, AudioSpec.CHANNELS)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioSpec.SAMPLE_RATE)
                    // 分段模式：整段文件一路识别下去，不会在第一次停顿就结束
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
                recognizer.startListening(intent)
                cont.invokeOnCancellation {
                    // 在主线程上下文里取消
                    runCatching { recognizer.cancel() }
                }
            }
        } finally {
            runCatching { recognizer.destroy() }
            runCatching { pfd.close() }
        }
    }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMISSIONS"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE（請到系統設定下載離線語音包）".tr
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
        else -> "code=${code}"
    }
}
