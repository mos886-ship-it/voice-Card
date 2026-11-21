package com.example.wristai

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 长会议（超过约 45 分钟）的 Gemini 处理：每 30 分钟一段各自转录，最后合成一份报告。
 *
 * - 每段结果存在 meetings/<id>/gemini/chunk_<k>.txt，做过的不再做（断点续做、App 被杀也不怕）。
 * - 手表还在录的时候（RECORDING），就先把已经录满的段处理掉，录完只剩最后一段 + 合成报告。
 * - 前面段落认出的说话者名单会带给下一段，让同一个人在整场会议用同一个代号。
 */
object LongMeeting {
    private const val TAG = "LongMeeting"

    /** 短于 1.5 段的会议照旧一次处理（报告品质最好）。 */
    fun isLong(context: Context, meeting: Meeting): Boolean =
        meeting.status == MeetingStatus.RECORDING ||
            meeting.durationSec > Settings.geminiChunkSec(context) * 1.5 ||
            chunkFile(context, meeting.id, 0).exists()

    fun chunkFile(context: Context, id: String, k: Int) = File(MeetingStore.geminiDir(context, id), "chunk_$k.txt")

    /** 已结束会议的总段数；最后一段可能比较短。 */
    fun chunkCount(durationSec: Double, chunkSec: Int): Int =
        maxOf(1, kotlin.math.ceil((durationSec - 1.0) / chunkSec).toInt())

    data class Chunk(val speakers: List<String>, val notes: String, val transcript: String)

    fun parseChunk(text: String): Chunk {
        val tIdx = text.indexOf(GeminiReportGenerator.TRANSCRIPT_MARKER)
        val head = if (tIdx >= 0) text.substring(0, tIdx) else text
        val transcript = if (tIdx >= 0) text.substring(tIdx + GeminiReportGenerator.TRANSCRIPT_MARKER.length).trim() else ""
        val nIdx = head.indexOf(GeminiReportGenerator.NOTES_MARKER)
        val speakersPart = (if (nIdx >= 0) head.substring(0, nIdx) else head).substringAfter(GeminiReportGenerator.SPEAKERS_MARKER)
        val notes = if (nIdx >= 0) head.substring(nIdx + GeminiReportGenerator.NOTES_MARKER.length).trim() else ""
        val speakers = speakersPart.lines().map { it.trim().trimStart('-', '*', ' ') }.filter { it.contains("|") && it.startsWith("說話者") }
        return Chunk(speakers, notes, transcript)
    }

    /** 合并各段的说话者行：同一代号以后段为准，但名字已知的不会被「未知」盖掉。 */
    fun mergeRoster(chunks: List<Chunk>): String {
        val byLabel = linkedMapOf<String, List<String>>()
        for (c in chunks) for (line in c.speakers) {
            val cells = line.split("|").map { it.trim() }
            val label = cells.firstOrNull().orEmpty()
            if (label.isBlank()) continue
            val old = byLabel[label]
            byLabel[label] = if (old != null && cells.getOrNull(1).orEmpty().startsWith("未知") && !old.getOrNull(1).orEmpty().startsWith("未知"))
                cells.toMutableList().also { it[1] = old[1] } else cells
        }
        return byLabel.values.joinToString("\n") { it.joinToString(" | ") }
    }

    /** 把这一段的逐字稿时间（从 00:00 起算）加上这段在整场会议的起点。 */
    fun shiftTranscript(transcript: String, offsetSec: Int, longFormat: Boolean): String {
        val re = Regex("^\\[(\\d{1,2}):(\\d{2})(?::(\\d{2}))?]")
        // 没人说话的段，模型会写「（本段錄音無發言內容）」之类的说明，不放进逐字稿
        val note = Regex("^[（(][^）)]*[）)]$")
        return transcript.lines().map { it.trim() }.filter { it.isNotBlank() && !note.matches(it) }.joinToString("\n") { line ->
            val m = re.find(line.trim()) ?: return@joinToString line.trim()
            val (a, b, c) = m.destructured
            val sec = if (c.isEmpty()) a.toInt() * 60 + b.toInt() else a.toInt() * 3600 + b.toInt() * 60 + c.toInt()
            val t = offsetSec + sec
            val stamp = if (longFormat) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
            "[$stamp]" + line.trim().substring(m.range.last + 1)
        }
    }

    private fun loadChunks(context: Context, id: String): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var k = 0
        while (true) {
            val f = chunkFile(context, id, k)
            if (!f.exists()) break
            out += parseChunk(f.readText()); k++
        }
        return out
    }

    /**
     * 处理目前能处理的段。[final] = 录音已经结束（最后一段可以比较短）。
     * 时间到了（[deadline]）就先停，返回 false；全部做完返回 true。
     */
    fun processChunks(
        context: Context, id: String, final: Boolean, deadline: Long,
        gemini: GeminiReportGenerator, recordedAt: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Boolean {
        val chunkSec = Settings.geminiChunkSec(context)
        val wav = MeetingStore.audioFile(context, id)
        MeetingStore.geminiDir(context, id).mkdirs()
        var k = 0
        while (true) {
            val meeting = MeetingStore.get(context, id) ?: return false
            val duration = meeting.durationSec
            val total = if (final) chunkCount(duration, chunkSec) else -1
            if (final && k >= total) return true
            val start = k * chunkSec
            val end = if (final && k == total - 1) duration.toInt() + 1 else (k + 1) * chunkSec
            if (!final && duration < end) return false  // 这一段还没录满
            if (chunkFile(context, id, k).exists()) { k++; continue }
            if (System.currentTimeMillis() > deadline) return false

            onProgress(k, total)
            val roster = mergeRoster(loadChunks(context, id).take(k))
            val audio = File(context.cacheDir, "gemini_${id}_$k.m4a")
            val text = try {
                VoiceRecorderExporter.encodeM4a(wav, audio, outRate = 16_000, bitRate = 32_000,
                    startSec = start.toDouble(), endSec = minOf(end.toDouble(), duration))
                gemini.transcribeChunk(audio, k, total, start, minOf(end, duration.toInt()), roster, recordedAt)
            } finally {
                audio.delete()
            }
            chunkFile(context, id, k).writeText(text)
            Log.d(TAG, "$id: chunk ${k + 1}${if (total > 0) "/$total" else ""} done (${text.length} chars)")
            k++
        }
    }

    /** 全部段做完后：合成报告 + 接起整场逐字稿。 */
    fun finish(context: Context, id: String, gemini: GeminiReportGenerator, durationSec: Double, recordedAt: String): Pair<GeneratedReport, String> {
        val chunkSec = Settings.geminiChunkSec(context)
        val chunks = loadChunks(context, id)
        val longFormat = durationSec >= 3600
        val notes = chunks.mapIndexed { k, c ->
            val s = k * chunkSec
            val e = minOf((k + 1) * chunkSec, durationSec.toInt())
            "### 第 ${k + 1} 段（${GeminiReportGenerator.hms(s)}～${GeminiReportGenerator.hms(e)}）\n${c.notes.ifBlank { "（無重點）" }}"
        }.joinToString("\n\n")
        val report = gemini.mergeReport(notes, mergeRoster(chunks), durationSec, recordedAt)
        val transcript = chunks.mapIndexed { k, c -> shiftTranscript(c.transcript, k * chunkSec, longFormat) }
            .filter { it.isNotBlank() }.joinToString("\n")
        return report to transcript
    }
}
