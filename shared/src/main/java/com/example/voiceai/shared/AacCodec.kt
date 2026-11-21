package com.example.voiceai.shared

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.OutputStream

/**
 * 边录边写的 AAC-LC m4a 编码器（16 kHz 单声道 PCM → m4a）。
 * 手表用它把录音压成 [AudioSpec.SEGMENT_BIT_RATE]，档案只有 WAV 的 1/5。
 */
class AacWriter(private val out: File, bitRate: Int = AudioSpec.SEGMENT_BIT_RATE) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var fedBytes = 0L
    private var closed = false

    /** 已写入的 PCM 样本数。 */
    val samples: Long get() = fedBytes / 2

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioSpec.SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release(); muxer.release()
            throw e
        }
    }

    /** 写入 16-bit PCM（little endian）。 */
    fun write(pcm: ByteArray, length: Int) {
        var off = 0
        while (off < length) {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val buf = codec.getInputBuffer(inIdx)!!
                buf.clear()
                val n = minOf(buf.remaining(), length - off) and 1.inv()
                buf.put(pcm, off, n)
                codec.queueInputBuffer(inIdx, 0, n, ptsUs(), 0)
                fedBytes += n
                off += n
            }
            drain(false)
        }
    }

    /** 结束编码、写完档案。 */
    fun close() {
        if (closed) return
        closed = true
        try {
            while (true) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(false)
            }
            drain(true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (track >= 0) muxer.stop() }
            muxer.release()
        }
    }

    private fun ptsUs() = samples * 1_000_000L / AudioSpec.SAMPLE_RATE

    /** 取走编好的数据；[untilEos] = 一直等到结束标记。 */
    private fun drain(untilEos: Boolean) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (untilEos) continue else return
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && track >= 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}

object AacCodec {
    /**
     * m4a → 16 kHz 单声道 16-bit PCM，写进 [out]，返回写入的样本数。
     * [expectedSamples] > 0 时：解码结果比预期多的部分是编码器在开头补的静音，从开头去掉；
     * 这样一段段接起来不会累积时间误差。
     */
    fun decodeToPcm(m4a: File, out: OutputStream, expectedSamples: Long = 0): Long {
        // 先完整解码到内存（一段 5 分钟 ≈ 9.6 MB PCM），才知道开头要去掉多少
        val pcm = java.io.ByteArrayOutputStream()
        val extractor = MediaExtractor()
        extractor.setDataSource(m4a.absolutePath)
        val trackIdx = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        extractor.selectTrack(trackIdx)
        val format = extractor.getTrackFormat(trackIdx)
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        check(rate == AudioSpec.SAMPLE_RATE && channels == 1) { "不支援的音訊格式：$rate Hz / $channels ch" }

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        val chunk = ByteArray(16 * 1024)
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
                if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    while (buf.hasRemaining()) {
                        val n = minOf(chunk.size, buf.remaining())
                        buf.get(chunk, 0, n)
                        pcm.write(chunk, 0, n)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        val bytes = pcm.toByteArray()
        val skip = if (expectedSamples > 0) (bytes.size - expectedSamples * 2).coerceIn(0, bytes.size.toLong()).toInt() else 0
        out.write(bytes, skip, bytes.size - skip)
        return (bytes.size - skip) / 2L
    }

    /**
     * 不重新编码，把几个 m4a 的 AAC 帧依序接成一个 m4a（几秒内完成，6 小时也一样）。
     * 用来把分段合成整场录音（给三星录音机），或合成一个 Gemini 处理段。
     */
    fun concat(inputs: List<File>, out: File) {
        require(inputs.isNotEmpty())
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var offsetUs = 0L
        val buf = java.nio.ByteBuffer.allocate(64 * 1024)
        val info = MediaCodec.BufferInfo()
        try {
            for (f in inputs) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(f.absolutePath)
                    val t = (0 until ex.trackCount).first {
                        ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }
                    ex.selectTrack(t)
                    if (track < 0) {
                        track = muxer.addTrack(ex.getTrackFormat(t))
                        muxer.start()
                    }
                    var lastUs = 0L
                    while (true) {
                        buf.clear()
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        lastUs = ex.sampleTime
                        info.set(0, n, offsetUs + lastUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        muxer.writeSampleData(track, buf, info)
                        ex.advance()
                    }
                    // 下一段接在这段最后一帧之后（AAC 一帧 1024 样本）
                    offsetUs += lastUs + 1024L * 1_000_000 / AudioSpec.SAMPLE_RATE
                } finally {
                    ex.release()
                }
            }
        } finally {
            runCatching { if (track >= 0) muxer.stop() }
            muxer.release()
        }
    }
}
