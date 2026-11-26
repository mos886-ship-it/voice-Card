package com.example.wristai

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用 Google Gemini API（免费方案）直接「听」录音，一次产出会议报告 + 逐字稿。
 *
 * - 免费 Key：https://aistudio.google.com/apikey （Google 账号即可，不用信用卡）
 * - 免费方案的数据会被 Google 用来改进产品；每日次数有限（以 AI Studio 显示为准）。
 * - 录音用 Files API 上传（>20MB 不能内嵌），用完立刻删除。
 * - 只用 Android 内建的 HttpURLConnection + org.json，不加依赖。
 */
class GeminiReportGenerator(
    private val apiKey: String,
    /** 加在提示词后面的说明（例：台语录音的 [SpeechLang.geminiNote]）。 */
    private val note: String = "",
) {

    companion object {
        private const val TAG = "GeminiReport"
        private const val BASE = "https://generativelanguage.googleapis.com"
        /** 免费方案可用的模型（2026-11 AI Studio 价目表）。第一个额度用完（429）就换下一个。 */
        val MODELS = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite")
        const val UPLOAD_MIME = "audio/m4a"
        const val TRANSCRIPT_MARKER = "===TRANSCRIPT==="

        /** 各种录音共用：先判断录音类型，再决定重点放在哪里。 */
        private val TYPE_RULES = """
            錄音可能是會議、課堂、訪談、電話通話、演講、討論、或個人口述的備忘 / 想法，請先判斷是哪一種，再依類型整理重點：
            - 會議 / 討論：討論內容、決定事項、誰負責什麼。
            - 課堂 / 演講：主要概念、重要定義與例子、要複習的地方。
            - 訪談 / 通話：問了什麼、對方怎麼回答、約定事項。
            - 個人備忘 / 想法：把零散的想法整理成清楚的條列與下一步。
            不要捏造錄音裡沒有的內容；聽不清楚的地方標註「（聽不清）」，不確定的標註「（待確認）」。
            一律使用繁體中文（台灣用語）。
            夾雜在中文裡的英文（單字、術語、縮寫、人名、公司、品牌與產品名，例如 KPI、deadline、iPhone、Python）照原文寫英文，
            逐字稿和報告都一樣：不要翻成中文，也不要寫成同音的中文字。
        """

        private val SYSTEM_PROMPT = """
            你是專業的語音記錄助理。使用者會給你一段錄音，請仔細聽完整段錄音後整理。
            %TYPE_RULES%

            輸出格式（嚴格遵守，不要加其他前言）：
            第 1 行：TITLE: <15 字內的標題，能看出內容與類型，例如「Q3 預算週會」「統計學第 5 講」「王經理電話訪談」>
            第 2 行：SUMMARY: <2～3 句、80 字內的摘要，會顯示在手錶上>
            第 3 行起：Markdown 報告，依序包含以下章節（沒有內容的章節寫「無」）：
            ## 摘要
            （3～5 句完整摘要：這段錄音在講什麼、誰說了什麼重要的事、結論是什麼。這一節一定要寫，不可以寫「無」；比 SUMMARY 那一行更完整）
            ## 說話者
            （用表格：說話者 | 名字 | 角色 | 主要觀點；每位說話者一列。
             名字盡量從錄音內容推斷：自我介紹、別人怎麼稱呼他（例如「王經理」「李老師」「陳醫師」「媽」「小美」）、新聞的署名（「記者○○○報導」）、會議中點名或提到的姓名與職稱都算；只有職稱或稱謂也可以直接當名字（例如「王經理」）。若能從音量與距離判斷誰是戴著手錶 / 拿著手機錄音的人（通常聲音最大、最清楚），名字寫「本人」。完全沒有線索才寫「未知」，不要亂猜真名；角色依內容推測，例如主持人、老師、受訪者、提問者、本人）
            ## 重點
            （依錄音類型整理；有多位說話者時，每點註明是誰說的，例如「說話者B 提出…」）
            ## 結論 / 決定
            ## 待辦事項
            （用表格：事項 | 負責人 | 期限；負責人用說話者代號，例如「說話者B」；錄音沒提到就寫「未指定」）
            ## 待確認 / 後續問題

            報告之後，單獨一行輸出 $TRANSCRIPT_MARKER
            之後是完整逐字稿，每段一行，格式：[mm:ss] 說話者X：內容

            說話者辨識規則（非常重要）：
            - 依聲音特徵（音色、音高、語速、口音、男女聲）區分不同的人，不是依內容猜。
            - 依第一次開口的順序編代號：說話者A、說話者B、說話者C…；同一個人從頭到尾都用同一個代號。
            - 逐字稿與報告一律只用代號，不要把名字寫進代號（名字寫在「說話者」表格）。
            - 只要換人說話就開新的一行，即使只講一兩個字（例如「對」「好」）也要獨立一行。
            - 同一人連續說很久時，大約每 30 秒或換話題時分段，時間戳記用該段開始的時間。
            - 兩人同時說話時分成兩行；實在分不出是誰時用「說話者?」。
            - 整段只有一個人說話時，就全部用說話者A，並在說話者表格只列一位。
        """.trimIndent().replace("%TYPE_RULES%", TYPE_RULES.trimIndent())

        /**
         * 组合式（语音辨识 → 大模型）用：输入是逐字稿，不是音档。章节与格式跟 [SYSTEM_PROMPT] 同一套，
         * 只换掉开头说明与说话者规则，也不要求再输出逐字稿。
         */
        val TEXT_REPORT_PROMPT: String by lazy {
            SYSTEM_PROMPT
                .replace("使用者會給你一段錄音，請仔細聽完整段錄音後整理。",
                    "使用者會給你一份語音辨識產生的逐字稿（可能有同音錯字、標點不完整），請依上下文理解後整理。")
                .substringBefore("報告之後，單獨一行輸出").trimEnd() + "\n\n" + """
                說話者規則：
                - 逐字稿裡的「說話者A、說話者B…」是語音辨識依聲音分出來的代號，報告裡請沿用同樣的代號。
                - 逐字稿若分成好幾段（會有「說話者代號重新編排」的提示），同一個人在不同段可能用不同代號：請依內容、稱呼與發言角色判斷，在「說話者」表格合併為同一人，並在報告其他地方統一用第一段的代號。
                - 逐字稿若沒有標示說話者，就依內容判斷；不確定時都寫說話者A。
                - 只輸出 TITLE、SUMMARY 與 Markdown 報告，不要再輸出逐字稿。
            """.trimIndent()
        }

        const val SPEAKERS_MARKER = "SPEAKERS:"
        const val NOTES_MARKER = "NOTES:"

        private val CHUNK_PROMPT = """
            你是專業的語音記錄助理。使用者會給你一段長錄音中的「一部分」（約 30 分鐘），請仔細聽完後整理。
            %TYPE_RULES%

            輸出格式（嚴格遵守，不要加其他前言）：
            $SPEAKERS_MARKER
            每位在這段出現的說話者一行：說話者X | 名字（名字盡量從錄音內容推斷：自我介紹、別人怎麼稱呼他（例如「王經理」「李老師」「陳醫師」「媽」「小美」）、新聞的署名（「記者○○○報導」）、會議中點名或提到的姓名與職稱都算；只有職稱或稱謂也可以直接當名字（例如「王經理」）。若能從音量與距離判斷誰是戴著手錶 / 拿著手機錄音的人（通常聲音最大、最清楚），名字寫「本人」。完全沒有線索才寫「未知」，不要亂猜真名）| 角色 | 聲音特徵（例如：男聲、低沉、語速快）
            $NOTES_MARKER
            這段的重點條列（有多位說話者時標註是誰說的），包含重點、結論 / 決定、待辦事項（負責人用說話者代號、期限）、待確認事項。
            $TRANSCRIPT_MARKER
            完整逐字稿，每段一行：[mm:ss] 說話者X：內容（時間從這段錄音開頭 00:00 起算）

            說話者辨識規則（非常重要）：
            - 依聲音特徵（音色、音高、語速、口音、男女聲）區分不同的人，不是依內容猜。
            - 若有提供「前面段落已出現的說話者」，聲音或身分相符的人一定沿用原本的代號；新的人從下一個未用過的字母開始。
            - 逐字稿與筆記一律只用代號（說話者A、說話者B…），名字寫在 SPEAKERS 那一行。
            - 只要換人說話就開新的一行，即使只講一兩個字也要獨立一行。
            - 同一人連續說很久時，大約每 30 秒或換話題時分段。
            - 分不出是誰時用「說話者?」。
        """.trimIndent().replace("%TYPE_RULES%", TYPE_RULES.trimIndent())

        private val MERGE_PROMPT = """
            你是專業的語音記錄助理。使用者會給你一段長錄音分段整理好的筆記與說話者名單，請整合成一份完整的報告。
            只根據提供的筆記整理，不要捏造；同一件事在多段出現時合併，前後矛盾時以後段為準並註明。
            %TYPE_RULES%
            說話者一律用代號（說話者A…）。

            輸出格式（嚴格遵守，不要加其他前言）：
            第 1 行：TITLE: <15 字內的標題，能看出內容與類型>
            第 2 行：SUMMARY: <2～3 句、80 字內的摘要，會顯示在手錶上>
            第 3 行起：Markdown 報告，依序包含以下章節（沒有內容的章節寫「無」）：
            ## 摘要
            （長錄音請依時間順序分幾個階段簡述；這一節一定要寫，不可以寫「無」）
            ## 說話者
            （用表格：說話者 | 名字 | 角色 | 主要觀點；名字未知就寫「未知」）
            ## 重點
            （有多位說話者時，每點註明是誰說的）
            ## 結論 / 決定
            ## 待辦事項
            （用表格：事項 | 負責人 | 期限；負責人用說話者代號；沒提到就寫「未指定」）
            ## 待確認 / 後續問題
            不要輸出逐字稿。
        """.trimIndent().replace("%TYPE_RULES%", TYPE_RULES.trimIndent())

        /** 秒 → h:mm:ss（不足 1 小时 → mm:ss）。 */
        fun hms(sec: Int): String =
            if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60)
    }

    class GeminiException(message: String, val code: Int = 0) : Exception(message)

    // ======================= 长会议：分段处理 =======================
    //
    // 4～6 小时的会议，逐字稿会超过 Gemini 一次能输出的长度，所以：
    // 1. 每 30 分钟一段，各自转逐字稿 + 记重点（[transcribeChunk]），前面段落认出的说话者名单带给下一段，代号才会一致；
    // 2. 最后把各段重点合成一份完整报告（[mergeReport]，纯文字，不用再听录音）。

    /** 转一段长会议。返回模型原始输出（SPEAKERS / NOTES / 逐字稿），由 [LongMeeting] 解析与存档。 */
    fun transcribeChunk(audio: File, index: Int, count: Int, startSec: Int, endSec: Int, roster: String, recordedAt: String): String {
        if (apiKey.isBlank()) throw GeminiException("尚未設定 Gemini API Key（請在手機 App 設定）")
        val file = upload(audio)
        try {
            waitUntilActive(file.getString("name"))
            val prompt = buildString {
                append("錄音時間：$recordedAt。這是一段長錄音的第 ${index + 1} 部分（共 ${if (count > 0) "$count 部分" else "多個部分，仍在錄音中"}），")
                appendLine("對應整段錄音的 ${hms(startSec)}～${hms(endSec)}。逐字稿的時間戳記請從這段錄音的開頭 00:00 起算。")
                if (roster.isNotBlank()) {
                    appendLine()
                    appendLine("前面段落已出現的說話者（請沿用相同代號；聲音相符就用同一代號，新出現的人從下一個字母開始編）：")
                    appendLine(roster)
                }
                if (note.isNotBlank()) { appendLine(); appendLine(note) }
            }
            return withModels("chunk ${index + 1}") { model -> generateContent(model, file.getString("uri"), prompt, CHUNK_PROMPT) }
        } finally {
            runCatching { delete(file.getString("name")) }
        }
    }

    /** 把各段重点合成完整报告（不含逐字稿）。 */
    fun mergeReport(notes: String, roster: String, durationSec: Double, recordedAt: String): GeneratedReport {
        val prompt = buildString {
            appendLine("錄音時間：$recordedAt，長度約 ${"%.1f".format(durationSec / 3600)} 小時。")
            appendLine("以下是這段錄音分段整理的說話者名單與各段筆記，請整合成一份完整的報告。")
            appendLine()
            appendLine("【說話者名單】")
            appendLine(roster.ifBlank { "（無）" })
            appendLine()
            appendLine("【各段筆記】")
            append(notes)
        }
        val text = withModels("merge") { model -> generateContent(model, null, prompt, MERGE_PROMPT) }
        return ReportGenerator.parseReport(text.substringBefore(TRANSCRIPT_MARKER).trim())
    }

    /** 依序试各个模型；每分钟次数用完（429）先等一下再试，长会议连续好几次请求很常见。 */
    /** 最后一次实际回答的模型（长录音整合完后记到报告上）。 */
    @Volatile var lastModel: String? = null
        private set

    private fun withModels(what: String, call: (String) -> String): String {
        var last: GeminiException? = null
        for (model in MODELS) {
            var waits = 0
            while (true) {
                try {
                    val t = System.currentTimeMillis()
                    val text = call(model)
                    lastModel = model
                    Log.d(TAG, "$what: $model replied ${text.length} chars (${System.currentTimeMillis() - t}ms)")
                    return text
                } catch (e: GeminiException) {
                    last = e
                    if (e.code == 429 && waits < 3) {
                        waits++
                        Log.w(TAG, "$what: $model rate limited, waiting (${waits}/3)")
                        Thread.sleep(40_000)
                        continue
                    }
                    if (e.code !in setOf(429, 404, 503, 500)) throw e
                    Log.w(TAG, "$what: $model unavailable (${e.code}), trying next model")
                    break
                }
            }
        }
        throw last ?: GeminiException("沒有可用的 Gemini 模型")
    }

    data class Result(val report: GeneratedReport, val transcript: String, val model: String, val raw: String = "")

    /** [audio] 是 m4a（AAC）。阻塞执行，请在后台线程调用。 */
    fun generate(
        audio: File?, durationSec: Double, recordedAt: String,
        /** 换成别的用途（例如 [FoodLog.PROMPT]）时传入 */
        system: String = SYSTEM_PROMPT,
        /** 音档上传完、AI 开始聆听时呼叫（画面进度用） */
        onListening: () -> Unit = {},
        /** 饮食照片（JPEG，已缩小）：直接夹在请求里（每张约 100 KB）；[audio] 可以是 null（只有照片） */
        images: List<File> = emptyList(),
    ): Result {
        if (apiKey.isBlank()) throw GeminiException("尚未設定 Gemini API Key（請在手機 App 設定）")
        require(audio != null || images.isNotEmpty())
        val t0 = System.currentTimeMillis()
        val file = audio?.let { a ->
            upload(a).also { Log.d(TAG, "uploaded ${a.length()} bytes as ${it.optString("name")} state=${it.optString("state")} (${System.currentTimeMillis() - t0}ms)") }
        }
        try {
            file?.let { waitUntilActive(it.getString("name")); Log.d(TAG, "file ACTIVE (${System.currentTimeMillis() - t0}ms)") }
            onListening()
            val minutes = "%.1f".format(durationSec / 60)
            val photos = if (images.isEmpty()) "" else "附上 ${images.size} 張這一餐的照片。"
            val prompt = (if (audio != null) "錄音時間：$recordedAt，長度約 $minutes 分鐘。${photos}請依格式整理這段錄音。"
                else "拍照時間：$recordedAt。${photos}沒有錄音，請依照片與格式整理，逐字稿留空。") + note
            var last: GeminiException? = null
            for (model in MODELS) {
                try {
                    val tm = System.currentTimeMillis()
                    Log.d(TAG, "calling $model")
                    val text = generateContent(model, file?.getString("uri"), prompt, system, images)
                    Log.d(TAG, "$model replied ${text.length} chars (${System.currentTimeMillis() - tm}ms)")
                    return parse(text).let { (report, transcript) -> Result(report, transcript, model, text) }
                } catch (e: GeminiException) {
                    last = e
                    // 额度用完(429) / 模型下架(404) / 服务器太忙重试后仍失败(503,500) → 换下一个模型
                    if (e.code !in setOf(429, 404, 503, 500)) throw e
                    Log.w(TAG, "$model unavailable (${e.code}), trying next model")
                }
            }
            throw last ?: GeminiException("沒有可用的 Gemini 模型")
        } finally {
            if (file != null) runCatching { delete(file.getString("name")) }
                .onFailure { Log.w(TAG, "delete uploaded file failed", it) }
        }
    }

    /** 把模型输出拆成报告和逐字稿。 */
    internal fun parse(text: String): Pair<GeneratedReport, String> {
        val idx = text.indexOf(TRANSCRIPT_MARKER)
        val reportText = if (idx >= 0) text.substring(0, idx) else text
        val transcript = if (idx >= 0) text.substring(idx + TRANSCRIPT_MARKER.length).trim() else ""
        return ReportGenerator.parseReport(reportText.trim()) to transcript
    }

    // ---------------- REST ----------------

    private fun upload(audio: File): JSONObject {
        val size = audio.length()
        val start = open("$BASE/upload/v1beta/files", "POST").apply {
            setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            setRequestProperty("X-Goog-Upload-Command", "start")
            setRequestProperty("X-Goog-Upload-Header-Content-Length", size.toString())
            setRequestProperty("X-Goog-Upload-Header-Content-Type", UPLOAD_MIME)
            setRequestProperty("Content-Type", "application/json")
        }
        send(start, JSONObject().put("file", JSONObject().put("display_name", "WristAI meeting")).toString().toByteArray())
        val uploadUrl = start.getHeaderField("x-goog-upload-url")
            ?: throw GeminiException(errorMessage(start.responseCode, readError(start)), start.responseCode)
        start.disconnect()

        val put = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setFixedLengthStreamingMode(size)
            setRequestProperty("X-Goog-Upload-Offset", "0")
            setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        }
        put.outputStream.use { out -> audio.inputStream().use { it.copyTo(out) } }
        return JSONObject(readBody(put)).getJSONObject("file")
    }

    /** 上传后档案可能还在 PROCESSING，等到 ACTIVE 才能用。 */
    private fun waitUntilActive(name: String) {
        repeat(60) {
            val conn = open("$BASE/v1beta/$name", "GET")
            val state = JSONObject(readBody(conn)).optString("state", "ACTIVE")
            when (state) {
                "ACTIVE" -> return
                "FAILED" -> throw GeminiException("Gemini 無法處理這個錄音檔")
            }
            Thread.sleep(2_000)
        }
        throw GeminiException("Gemini 處理錄音檔逾時")
    }

    private fun generateContent(model: String, fileUri: String?, prompt: String, system: String = SYSTEM_PROMPT,
                                images: List<File> = emptyList()): String {
        val parts = JSONArray()
        if (fileUri != null) parts.put(JSONObject().put("file_data", JSONObject().put("mime_type", UPLOAD_MIME).put("file_uri", fileUri)))
        for (img in images) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg")
            .put("data", android.util.Base64.encodeToString(img.readBytes(), android.util.Base64.NO_WRAP))))
        parts.put(JSONObject().put("text", prompt))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.2))

        var attempt = 0
        while (true) {
            // 用串流（SSE）：边产生边传回，长会议不会因为长时间没数据而超时，也能在 log 看到进度
            val conn = open("$BASE/v1beta/models/$model:streamGenerateContent?alt=sse", "POST").apply {
                setRequestProperty("Content-Type", "application/json")
                readTimeout = 3 * 60_000  // 两段数据之间最多等 3 分钟（模型先「听」完录音才开始输出）
            }
            try {
                send(conn, body.toString().toByteArray())
                return readStream(conn)
            } catch (e: GeminiException) {
                // 503 / 500 = 服务器忙，退避重试；其他错误直接抛出
                if ((e.code == 503 || e.code == 500) && attempt < 2) {
                    Log.w(TAG, "$model busy (${e.code}), retry ${attempt + 1}")
                    attempt++
                    Thread.sleep(5_000L * attempt)
                    continue
                }
                throw e
            }
        }
    }

    /** 读 SSE：每行 `data: {json}`，每个 json 是一段 candidates[0].content.parts。 */
    private fun readStream(conn: HttpURLConnection): String {
        val sb = StringBuilder()
        var finish = ""
        var block = ""
        var chunks = 0
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw GeminiException(errorMessage(code, readError(conn)), code)
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val json = JSONObject(line.removePrefix("data:").trim())
                    json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let { block = it }
                    val cand = json.optJSONArray("candidates")?.optJSONObject(0) ?: continue
                    val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                    for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i)
                        if (p.optBoolean("thought", false)) continue  // 跳过思考过程
                        sb.append(p.optString("text"))
                    }
                    cand.optString("finishReason").takeIf { it.isNotEmpty() }?.let { finish = it }
                    if (++chunks % 20 == 1) Log.d(TAG, "streaming… ${sb.length} chars")
                }
            }
        } catch (e: IOException) {
            throw GeminiException("連不上 Gemini 或回應逾時（${e.message}），請檢查網路後重試")
        } finally {
            conn.disconnect()
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) {
            val why = block.ifEmpty { finish }
            throw GeminiException("Gemini 沒有回傳內容${if (why.isNotEmpty()) "（$why）" else ""}")
        }
        if (finish == "MAX_TOKENS") Log.w(TAG, "output truncated at MAX_TOKENS")
        return text
    }

    private fun delete(name: String) {
        val conn = open("$BASE/v1beta/$name", "DELETE")
        conn.responseCode
        conn.disconnect()
    }

    private fun open(url: String, method: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 30_000
        readTimeout = 60_000
        setRequestProperty("x-goog-api-key", apiKey)
    }

    private fun send(conn: HttpURLConnection, bytes: ByteArray) {
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
    }

    private fun readBody(conn: HttpURLConnection): String {
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw GeminiException(errorMessage(code, readError(conn)), code)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw GeminiException("連不上 Gemini（${e.message}），請檢查網路後重試")
        } finally {
            conn.disconnect()
        }
    }

    private fun readError(conn: HttpURLConnection): String =
        runCatching {
            val raw = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            JSONObject(raw).optJSONObject("error")?.optString("message") ?: raw
        }.getOrDefault("")

    private fun errorMessage(code: Int, detail: String) = when (code) {
        400 -> if (detail.contains("API key", ignoreCase = true)) "Gemini API Key 無效，請到設定重新貼上" else "Gemini 拒絕這個請求：$detail"
        401, 403 -> "Gemini API Key 無效或沒有權限，請到設定重新貼上"
        429 -> "今天的 Gemini 免費額度用完了，明天再按「重試」"
        500, 503 -> "Gemini 目前太忙（Google 伺服器需求量大），過幾分鐘再按「重試」"
        else -> "Gemini 錯誤 $code：$detail"
    }
}
