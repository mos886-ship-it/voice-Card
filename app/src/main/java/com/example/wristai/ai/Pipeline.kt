package com.example.wristai.ai

import android.content.Context
import android.util.Log
import com.example.wristai.MeetingStore
import com.example.wristai.VoiceRecorderExporter
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 组合式 AI：语音辨识（[Stt]）→ 逐字稿 → 报告模型（[Llm]）。
 *
 * - 长录音依服务上限切段（Fun-ASR 约 110 分、OpenAI 85 分、讯飞 4.5 小时），一段一段送。
 * - 进度存在会议资料夹的 stt_state.json：WorkManager 一次最多 10 分钟，没做完下次接着做（排队中的任务编号也存着）。
 * - 同一家、同一模型已经辨识完的，「重新产生」时直接沿用逐字稿，不重复付费。
 */
object Pipeline {
    private const val TAG = "Pipeline"
    private const val POLL_MS = 5_000L

    private fun stateFile(ctx: Context, id: String) = File(MeetingStore.dir(ctx, id), "stt_state.json")

    /** 辨识进度。segs 每笔：{c: 段落序号, s: 开始毫秒（整段录音的时间）, p: 说话者原始代号, t: 文字} */
    private class State(val provider: String, val total: Int, var done: Int, var pending: String?, val segs: JSONArray) {
        fun toJson() = JSONObject().put("provider", provider).put("total", total).put("done", done)
            .put("pending", pending).put("segs", segs).toString()
        companion object {
            fun from(text: String) = runCatching {
                val j = JSONObject(text)
                State(j.getString("provider"), j.getInt("total"), j.getInt("done"),
                    if (j.isNull("pending")) null else j.optString("pending").ifBlank { null }, j.optJSONArray("segs") ?: JSONArray())
            }.getOrNull()
        }
    }

    /**
     * 辨识到做完或时间到。回传 true = 全部做完；false = 时间到（进度已存，请再排一次任务）。
     * [onProgress] (已完成段数, 总段数)。
     */
    suspend fun transcribe(
        ctx: Context, id: String, durationSec: Double, stt: Stt, deadline: Long,
        onProgress: (Int, Int) -> Unit,
    ): Boolean {
        val file = stateFile(ctx, id)
        val providerKey = "${stt.name}|${stt.modelId}"
        val total = maxOf(1, kotlin.math.ceil(durationSec / stt.maxChunkSec).toInt())
        val st = State.from(file.takeIf { it.exists() }?.readText().orEmpty())?.takeIf { it.provider == providerKey && it.total == total }
            ?: State(providerKey, total, 0, null, JSONArray())
        fun save() = file.writeText(st.toJson())
        onProgress(st.done, total)
        val audio = MeetingStore.audioFile(ctx, id)
        while (st.done < total) {
            if (System.currentTimeMillis() > deadline) { save(); return false }
            val k = st.done
            val offsetMs = k.toLong() * stt.maxChunkSec * 1000
            var segs: List<Seg>? = null
            if (st.pending == null) {
                // 这一段编成 16kHz、32kbps 的 m4a 送出
                val m4a = File(ctx.cacheDir, "stt_${id}_$k.m4a")
                try {
                    VoiceRecorderExporter.encodeM4a(audio, m4a, outRate = 16_000, bitRate = 32_000,
                        startSec = k.toDouble() * stt.maxChunkSec,
                        endSec = if (k == total - 1) -1.0 else (k + 1).toDouble() * stt.maxChunkSec)
                    val chunkSec = if (k == total - 1) durationSec - k * stt.maxChunkSec else stt.maxChunkSec.toDouble()
                    when (val r = stt.start(m4a, chunkSec)) {
                        is SttStart.Done -> segs = r.segs
                        is SttStart.Pending -> { st.pending = r.taskId; save() }
                    }
                } finally {
                    m4a.delete()
                }
            }
            while (segs == null) {
                val task = st.pending ?: break
                segs = stt.poll(task)
                if (segs == null) {
                    if (System.currentTimeMillis() > deadline) { save(); return false }
                    delay(POLL_MS)
                }
            }
            for (s in segs ?: emptyList()) {
                st.segs.put(JSONObject().put("c", k).put("s", s.startMs + offsetMs).put("p", s.speaker).put("t", s.text))
            }
            st.done = k + 1
            st.pending = null
            save()
            onProgress(st.done, total)
            Log.d(TAG, "$id: chunk ${k + 1}/$total done (${segs?.size ?: 0} sentences)")
        }
        return true
    }

    /**
     * 逐字稿文字：`[mm:ss] 說話者A：内容`。说话者依出现顺序编 A、B、C…；
     * 每一段（切段时）各自编号，所以加一行提示，请 AI 依内容合并同一人。
     */
    fun transcriptText(ctx: Context, id: String): String {
        val st = State.from(stateFile(ctx, id).takeIf { it.exists() }?.readText().orEmpty()) ?: return ""
        val sb = StringBuilder()
        val letters = mutableMapOf<String, String>()
        var lastChunk = -1
        for (i in 0 until st.segs.length()) {
            val s = st.segs.getJSONObject(i)
            val c = s.optInt("c")
            if (c != lastChunk) {
                if (c > 0) sb.append("（以下第 ${c + 1} 段錄音，說話者代號重新編排）\n")
                letters.clear(); lastChunk = c
            }
            val sec = s.optLong("s") / 1000
            val stamp = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60)
            val p = if (s.isNull("p")) null else s.optString("p").ifBlank { null }
            val who = p?.let { letters.getOrPut(it) { com.example.wristai.ReportLang.active.code('A' + letters.size) } }
            sb.append("[").append(stamp).append("] ")
            if (who != null) sb.append(who).append("：")
            sb.append(s.optString("t")).append('\n')
        }
        return sb.toString().trim()
    }

    private val NOTES_PROMPT = """
        你是專業的語音記錄助理。以下是一段長錄音逐字稿的「其中一部分」，請整理成重點筆記，之後會和其他部分合併成完整報告。
        - 條列這部分的重點、結論 / 決定、待辦事項（負責人、期限）、待確認事項，每點註明是哪位說話者（沿用逐字稿的代號）。
        - 列出這部分出現的說話者、他們的名字或稱謂（有線索才寫）與角色。
        - 不要捏造逐字稿沒有的內容。一律使用繁體中文（台灣用語）。
    """.trimIndent()

    /**
     * 产生报告原文（TITLE / SUMMARY / Markdown）。逐字稿超过模型能放的字数，就先每部分整理笔记，再从笔记写完整报告。
     */
    fun report(llm: Llm, transcript: String, durationSec: Double, recordedAt: String, system: String): String {
        val head = "錄音時間：$recordedAt，長度約 ${"%.1f".format(durationSec / 60)} 分鐘。"
        if (transcript.length <= llm.maxChars) {
            return llm.complete(com.example.wristai.ReportLang.localize(system), "$head\n以下是語音辨識產生的逐字稿：\n<transcript>\n$transcript\n</transcript>")
        }
        val limit = (llm.maxChars * 0.9).toInt()
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        for (line in transcript.lines()) {
            if (cur.length + line.length + 1 > limit && cur.isNotEmpty()) { parts += cur.toString(); cur.clear() }
            cur.append(line.take(limit)).append('\n')
        }
        if (cur.isNotEmpty()) parts += cur.toString()
        val notes = parts.mapIndexed { i, part ->
            "## 第 ${i + 1} 部分\n" + llm.complete(com.example.wristai.ReportLang.localize(NOTES_PROMPT), "$head 這是第 ${i + 1}/${parts.size} 部分：\n<transcript>\n$part\n</transcript>")
        }
        return llm.complete(com.example.wristai.ReportLang.localize(system),
            "$head\n逐字稿太長，已先分成 ${parts.size} 部分整理重點筆記如下，請依這些筆記寫出完整報告：\n<notes>\n${notes.joinToString("\n\n")}\n</notes>")
    }
}
