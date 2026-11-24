package com.example.wristai

import android.content.Context
import android.util.Log
import com.example.voiceai.shared.AacCodec
import com.example.voiceai.shared.Segment
import com.example.voiceai.shared.WavFile
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 手机端：把手表边录边传的分段接成一场会议。
 *
 * - 每段（AAC m4a）存进 meetings/<id>/segments/，依序解码接到 audio.wav（后面的流程照旧用 WAV）。
 * - 分段可能乱序或重传：只接「下一个该接的序号」，重复的直接忽略。
 * - 最后一段到齐 → 状态改成 RECEIVED，开始完整处理；还在录 → 让 Gemini 先把已满 30 分钟的部分转录。
 */
object SegmentAssembler {
    private const val TAG = "SegmentAssembler"

    private fun segFile(dir: File, seq: Int): File? =
        dir.listFiles { f -> f.name.startsWith("%05d_".format(seq)) && f.extension == "m4a" }?.firstOrNull()

    fun segmentFiles(context: Context, id: String): List<File> =
        MeetingStore.segmentsDir(context, id).listFiles { f -> f.extension == "m4a" }
            ?.sortedBy { it.name } ?: emptyList()

    /** 录音 id（yyyyMMdd_HHmmss）就是手表开始录音的时间。 */
    private fun startTimeOf(id: String): Long =
        runCatching { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(id.removePrefix(FoodLog.ID_PREFIX))!!.time }
            .getOrDefault(System.currentTimeMillis())

    /** [received] = 刚收完的暂存档。返回 true = 整场录音到齐了。 */
    @Synchronized
    fun onSegment(context: Context, seg: Segment, received: File, nodeId: String): Boolean {
        val id = seg.recordingId
        val segDir = MeetingStore.segmentsDir(context, id).apply { mkdirs() }
        var meeting = MeetingStore.get(context, id) ?: Meeting(
            id = id, status = MeetingStatus.RECORDING, createdAt = startTimeOf(id), durationSec = 0.0,
            watchNodeId = nodeId, title = null, error = null, lang = SpeechLang.currentCode(context), dialect = SpeechLang.currentDialect(context),
        ).also { MeetingStore.save(context, it) }

        if (seg.seq < meeting.segmentsAppended || segFile(segDir, seg.seq) != null) {
            Log.d(TAG, "$id: duplicate segment ${seg.seq}, ignored")
            received.delete()
        } else {
            val target = File(segDir, "%05d_%d.m4a".format(seg.seq, seg.samples))
            if (!received.renameTo(target)) { received.copyTo(target, true); received.delete() }
        }

        // 依序接到 audio.wav
        val wav = MeetingStore.audioFile(context, id)
        if (!wav.exists() || wav.length() < WavFile.HEADER_SIZE) wav.outputStream().use { WavFile.writePlaceholderHeader(it) }
        var appended = meeting.segmentsAppended
        while (true) {
            val f = segFile(segDir, appended) ?: break
            val expected = f.nameWithoutExtension.substringAfter('_').toLongOrNull() ?: 0
            val before = wav.length()
            try {
                java.io.FileOutputStream(wav, true).buffered().use { AacCodec.decodeToPcm(f, it, expected) }
            } catch (e: Exception) {
                // 解码失败：还原 WAV 长度，下次再试
                RandomAccessFile(wav, "rw").use { it.setLength(before) }
                Log.e(TAG, "$id: decode segment $appended failed", e)
                break
            }
            appended++
        }
        WavFile.finalizeHeader(wav)

        val total = if (seg.last) seg.seq + 1 else meeting.segmentsTotal
        val complete = total > 0 && appended >= total
        meeting = MeetingStore.update(context, id) {
            it.copy(
                segmentsAppended = appended,
                segmentsTotal = total,
                durationSec = WavFile.durationSeconds(wav),
                watchNodeId = nodeId,
                status = if (complete && it.status == MeetingStatus.RECORDING) MeetingStatus.RECEIVED else it.status,
            )
        }!!
        Log.d(TAG, "$id: segment ${seg.seq} (last=${seg.last}) → appended $appended/${if (total > 0) total else "?"}, ${"%.0f".format(meeting.durationSec)}s")
        return complete
    }
}
