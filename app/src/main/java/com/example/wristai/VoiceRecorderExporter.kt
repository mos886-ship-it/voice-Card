package com.example.wristai

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.voiceai.shared.AacCodec
import com.example.voiceai.shared.AudioSpec
import com.example.voiceai.shared.WavFile
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把录音转成 m4a（AAC）存进三星「录音机」的文件夹，
 * 让使用者在录音机里用 Galaxy AI 的「转录 / 摘要」处理。
 *
 * 第三方 App 无法直接调用 Galaxy AI（Z Fold5 也不支持 ML Kit / Gemini Nano），
 * 所以这是目前在 Fold5 上使用 Galaxy AI 的唯一方式。
 */
object VoiceRecorderExporter {
    private const val TAG = "VoiceRecorderExporter"
    const val SAMSUNG_VOICE_RECORDER = "com.sec.android.app.voicenote"

    /** 三星录音机读取的文件夹（内部存储 / Recordings / Voice Recorder）。 */
    private val RELATIVE_DIR = "${Environment.DIRECTORY_RECORDINGS}/Voice Recorder/"

    /**
     * 返回存好的 MediaStore Uri。阻塞执行，请在后台线程调用。
     * [segments] 不为空时（手表边录边传的长会议）：直接把分段的 AAC 接起来，不重新编码，6 小时也只要几秒；
     * 否则从 WAV 编码（超过 1 小时改用 16kHz，避免编码太久）。
     */
    fun export(context: Context, wav: File, createdAt: Long, segments: List<File> = emptyList()): Uri {
        val name = "Dylan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(createdAt)) + ".m4a"
        val tmp = File(context.cacheDir, name)
        try {
            when {
                segments.isNotEmpty() -> AacCodec.concat(segments, tmp)
                WavFile.durationSeconds(wav) > 3600 -> encodeM4a(wav, tmp, outRate = AudioSpec.SAMPLE_RATE, bitRate = 64_000)
                else -> encodeM4a(wav, tmp)
            }
            if (!SamsungRecordingTag.apply(tmp)) Log.w(TAG, "無法加上三星錄音標記，錄音機可能不會列出此檔")
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.DATE_TAKEN, createdAt)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, relativeDir())
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: error("無法建立錄音檔")
            try {
                resolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            Log.d(TAG, "Exported ${wav.name} → $uri ($name)")
            return uri
        } finally {
            tmp.delete()
        }
    }

    /** Recordings 目录在 Android 12 才开放给 MediaStore；更旧的系统放在 Music。 */
    private fun relativeDir() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RELATIVE_DIR
        else "${Environment.DIRECTORY_MUSIC}/Voice Recorder/"

    /** 开三星录音机；没装（非三星手机）时返回 null。 */
    fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(SAMSUNG_VOICE_RECORDER)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 跟三星录音机自己录的格式一致（48kHz / 128kbps）。录音机是否列出档案取决于
     * [SamsungRecordingTag]，不是取样率；但 Galaxy AI 转录实测是用这个格式验证的，所以保留。
     */
    private const val OUT_RATE = 48_000
    private const val OUT_BIT_RATE = 128_000

    /**
     * 16kHz 单声道 PCM WAV → AAC-LC m4a。默认 48kHz / 128kbps（给录音机）；
     * 上传给 Gemini 时用 16kHz / 32kbps，一小时约 14MB（Gemini 本来就会降到 16kbps 处理）。
     */
    fun encodeM4a(
        wav: File, out: File, outRate: Int = OUT_RATE, bitRate: Int = OUT_BIT_RATE,
        /** 只编码这一段（秒）；[endSec] < 0 = 到结尾。长会议分段送 Gemini 用。 */
        startSec: Double = 0.0, endSec: Double = -1.0,
    ) {
        check(AudioSpec.CHANNELS == 1 && AudioSpec.BITS_PER_SAMPLE == 16 && outRate % AudioSpec.SAMPLE_RATE == 0)
        val up = outRate / AudioSpec.SAMPLE_RATE
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()

        RandomAccessFile(wav, "r").use { raf ->
            val bytesPerSec = AudioSpec.BYTES_PER_SECOND.toLong()
            val startByte = WavFile.HEADER_SIZE + (startSec * AudioSpec.SAMPLE_RATE).toLong() * 2
            val endByte = if (endSec < 0) raf.length()
                else minOf(raf.length(), WavFile.HEADER_SIZE + (endSec * AudioSpec.SAMPLE_RATE).toLong() * 2)
            check(bytesPerSec > 0)
            raf.seek(startByte)
            val inBytes = ByteArray(4 * 1024)
            var pending = ByteArray(0)
            var pendOff = 0
            var prev: Int? = null

            /** 读一块 16kHz PCM，插值成 48kHz 放进 pending；读完返回 false。 */
            fun refill(): Boolean {
                val left = endByte - raf.filePointer
                if (left <= 0) return false
                var n = raf.read(inBytes, 0, minOf(inBytes.size.toLong(), left).toInt())
                if (n <= 0) return false
                if (n % 2 == 1) { raf.seek(raf.filePointer - 1); n -= 1 }
                val count = n / 2
                val buf = ByteArray(count * up * 2)
                var o = 0
                for (i in 0 until count) {
                    val s = (inBytes[2 * i].toInt() and 0xff) or (inBytes[2 * i + 1].toInt() shl 8)
                    val p = prev ?: s
                    for (k in 1..up) {
                        val v = p + (s - p) * k / up
                        buf[o++] = v.toByte(); buf[o++] = (v shr 8).toByte()
                    }
                    prev = s
                }
                pending = buf; pendOff = 0
                return true
            }

            var fed = 0L
            var inputDone = false
            try {
                while (true) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            buf.clear()
                            val ptsUs = fed / 2 * 1_000_000L / outRate
                            if (pendOff >= pending.size && !refill()) {
                                codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val n = minOf(buf.remaining(), pending.size - pendOff)
                                buf.put(pending, pendOff, n)
                                pendOff += n
                                codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                                fed += n
                            }
                        }
                    }
                    // 把已经编好的全部取走；还在送数据时不等待（之前每轮等 10ms，
                    // Fold5 实测 29 秒录音要转 13.7 秒，一小时会议会超过 WorkManager 的 10 分钟上限）
                    var eos = false
                    while (true) {
                        val outIdx = codec.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                        } else if (outIdx >= 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isConfig && info.size > 0 && track >= 0) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buf, info)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { eos = true; break }
                        }
                    }
                    if (eos) break
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
                runCatching { if (track >= 0) muxer.stop() }
                muxer.release()
            }
        }
    }
}
