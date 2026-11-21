package com.example.wristai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.example.voiceai.shared.AudioSpec
import com.example.voiceai.shared.WavFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把手机上的任意音频（m4a / mp3 / wav / ogg…，例如录音机 App 的文件）
 * 解码并转成 16kHz 单声道 WAV，当成一场新会议处理。不需要手表。
 */
object AudioImporter {
    private const val TAG = "AudioImporter"
    const val SOURCE_CALL = "call"

    /**
     * 阻塞执行，请在后台线程调用。返回新会议 id。
     * 会议的 createdAt = 原本「录音的时间」（从档名 / 档案资料推回来，见 [recordedAtOf]）；
     * importedAt = 现在（汇入的时间），两个分开记。[fallbackEnd] = 找不到录音时间时，档案大约录完的时间。
     */
    fun import(
        context: Context, uri: Uri,
        source: String? = null,
        title: String? = null,
        fallbackEnd: Long? = null,
    ): String {
        val importedAt = System.currentTimeMillis()
        // id 用汇入时间：同一个时间点录的两个档也不会撞名
        var id = (if (source == SOURCE_CALL) "call_" else "imp_") + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(importedAt))
        while (MeetingStore.meetings.value.any { it.id == id }) id += "_"
        val target = MeetingStore.audioFile(context, id)
        val tmp = File(target.parentFile, "audio.part")
        try {
            BufferedOutputStream(tmp.outputStream(), 256 * 1024).use { out ->
                WavFile.writePlaceholderHeader(out)
                decodeTo16kMono(context, uri, out)
            }
            WavFile.finalizeHeader(tmp)
            if (!tmp.renameTo(target)) { tmp.copyTo(target, true); tmp.delete() }
            val duration = WavFile.durationSeconds(target)
            if (duration < 1.0) throw IllegalStateException("音檔太短或無法解碼（${"%.1f".format(duration)} 秒）")

            MeetingStore.save(
                context,
                Meeting(id, MeetingStatus.RECEIVED, recordedAtOf(context, uri, duration, fallbackEnd) ?: importedAt, duration, null, title, null,
                    source = source, importedAt = importedAt),
            )
            ProcessMeetingWorker.enqueue(context, id)
            Log.d(TAG, "Imported $uri as $id (${duration}s)")
            return id
        } catch (e: Exception) {
            MeetingStore.delete(context, id)
            throw e
        }
    }

    /**
     * 推回档案原本的「录音开始时间」，依序试：
     * 1. 档名里的日期时间（三星通话录音「…_241118_195656」、录音机「20241118_195656」等）；
     * 2. MediaStore 的 DATE_TAKEN；
     * 3. 音档本身的建立日期（MP4 metadata）；
     * 4. 档案最后修改时间（≈ 录完的时间）减掉长度；
     * 5. 呼叫端给的 [fallbackEnd] 减掉长度。
     * 都没有就回 null（用汇入时间）。不合理的时间（2000 年以前、未来）一律不用。
     */
    fun recordedAtOf(context: Context, uri: Uri, durationSec: Double, fallbackEnd: Long? = null): Long? {
        val now = System.currentTimeMillis()
        fun ok(t: Long?) = t != null && t in 946_684_800_000L..(now + 60_000)
        val durMs = (durationSec * 1000).toLong()
        var name: String? = null; var taken: Long? = null; var modified: Long? = null
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    fun col(n: String) = c.getColumnIndex(n).takeIf { it >= 0 && !c.isNull(it) }
                    col(android.provider.OpenableColumns.DISPLAY_NAME)?.let { name = c.getString(it) }
                    col(android.provider.MediaStore.MediaColumns.DATE_TAKEN)?.let { taken = c.getLong(it).takeIf { v -> v > 0 } }
                    col(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)?.let { modified = c.getLong(it).takeIf { v -> v > 0 }?.times(1000) }
                    if (modified == null) col(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.let { modified = c.getLong(it).takeIf { v -> v > 0 } }
                }
            }
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        timeFromName(name)?.takeIf(::ok)?.let { return it }
        taken?.takeIf(::ok)?.let { return it }
        runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)?.let(::parseMetaDate)
            } finally { r.release() }
        }.getOrNull()?.takeIf(::ok)?.let { return it }
        modified?.minus(durMs)?.takeIf(::ok)?.let { return it }
        return fallbackEnd?.minus(durMs)?.takeIf(::ok)
    }

    /** 档名里的「年月日 + 时分(秒)」：20241118_195656、241118_195656、2024-11-18 19.56.56 等。 */
    internal fun timeFromName(name: String?): Long? {
        if (name == null) return null
        val sep = """[-_.]?"""
        val tail = """[ _T-]?(\d{2})[-_.:]?(\d{2})(?:[-_.:]?(\d{2}))?(?!\d)"""
        for (yearPat in listOf("""(?<!\d)((?:19|20)\d{2})""", """(?<!\d)(\d{2})""")) {
            for (m in Regex(yearPat + sep + """(\d{2})""" + sep + """(\d{2})""" + tail).findAll(name)) {
                val g = m.groupValues
                val year = g[1].toInt().let { if (it < 100) 2000 + it else it }
                val mo = g[2].toInt(); val d = g[3].toInt(); val h = g[4].toInt(); val mi = g[5].toInt(); val sec = g[6].toIntOrNull() ?: 0
                if (mo !in 1..12 || d !in 1..31 || h > 23 || mi > 59 || sec > 59) continue
                return java.util.Calendar.getInstance().apply { clear(); set(year, mo - 1, d, h, mi, sec) }.timeInMillis
            }
        }
        return null
    }

    /** MP4 的建立日期（例如 "20241118T115656.000Z"，UTC）。1904 / 1970 这种空值不用。 */
    private fun parseMetaDate(s: String): Long? {
        for (p in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss")) {
            val f = SimpleDateFormat(p, Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            runCatching { f.parse(s)?.time }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun decodeTo16kMono(context: Context, uri: Uri, out: OutputStream) {
        val extractor = MediaExtractor()
        // 经 ContentResolver 打开 fd，比 setDataSource(context, uri) 稳定（分享来的 content:// 只授权给本 App）
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            if (afd.declaredLength >= 0) extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
            else extractor.setDataSource(afd.fileDescriptor)
        } ?: throw IllegalArgumentException("無法開啟音檔")
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("檔案裡沒有音訊")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val resampler = Resampler(out)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        resampler.push(shorts, channels, rate)
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
    }

    /** 混成单声道 + 线性插值重采样到 16kHz，输出 16-bit little-endian PCM。 */
    private class Resampler(private val out: OutputStream) {
        private var pos = 0.0          // 下一个输出样本在输入时间轴上的位置
        private var prev = 0           // 上一块最后一个单声道样本（跨块插值用）
        private var inIndex = 0L       // 已消耗输入样本数
        private val bytes = ByteArray(8192)
        private var bi = 0

        fun push(src: java.nio.ShortBuffer, channels: Int, rate: Int) {
            val step = rate.toDouble() / AudioSpec.SAMPLE_RATE
            val frames = src.remaining() / channels
            val mono = IntArray(frames) { f ->
                var sum = 0
                for (c in 0 until channels) sum += src.get(f * channels + c)
                sum / channels
            }
            val base = inIndex
            // 可用输入范围：[base-1 (prev), base+frames-1]
            while (pos <= base + frames - 1) {
                val i = Math.floor(pos).toLong()
                val frac = pos - i
                val a = if (i < base) prev else mono[(i - base).toInt()]
                val b = if (i + 1 < base) prev else if (i + 1 - base < frames) mono[(i + 1 - base).toInt()] else a
                write((a + (b - a) * frac).toInt())
                pos += step
            }
            if (frames > 0) prev = mono[frames - 1]
            inIndex += frames
            flush()
        }

        private fun write(sample: Int) {
            val s = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[bi++] = (s and 0xff).toByte()
            bytes[bi++] = (s shr 8 and 0xff).toByte()
            if (bi == bytes.size) flush()
        }

        private fun flush() { out.write(bytes, 0, bi); bi = 0 }
    }
}
