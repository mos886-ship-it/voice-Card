package com.example.wristai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason

/** Claude 整理出来的结果：标题 + 给手表的短摘要 + 完整 Markdown 报告。 */
data class GeneratedReport(val title: String, val shortSummary: String, val markdown: String)

/** 用 Claude API 把逐字稿整理成会议报告。 */
class ReportGenerator(private val apiKey: String) {

    companion object {
        const val MODEL = "claude-opus-5-5"

        private val SYSTEM_PROMPT = """
            你是專業的語音記錄助理。使用者會給你一份「自動語音辨識」產生的逐字稿，可能是會議、課堂、訪談、通話或個人備忘，請依類型整理。
            逐字稿可能有同音錯字、沒有標點、沒有標示說話者，請依上下文理解，但不要捏造逐字稿裡沒有的內容；
            不確定的地方請標註「（待確認）」。一律使用繁體中文。

            輸出格式（嚴格遵守）：
            第 1 行：TITLE: <15 字內的標題>
            第 2 行：SUMMARY: <2～3 句、80 字內的摘要，會顯示在手錶上>
            第 3 行起：Markdown 報告，依序包含以下章節（沒有內容的章節寫「無」）：
            ## 摘要
            （3～5 句完整摘要：這段錄音在講什麼、誰說了什麼重要的事、結論是什麼。這一節一定要寫，不可以寫「無」；比 SUMMARY 那一行更完整）
            ## 討論重點
            ## 決議事項
            ## 待辦事項
            （用表格：事項 | 負責人 | 期限；逐字稿沒提到就寫「未指定」）
            ## 待確認 / 後續問題
        """.trimIndent()
    }

    class ReportException(message: String) : Exception(message)

    fun generate(transcript: String, durationSec: Double, recordedAt: String): GeneratedReport {
        if (apiKey.isBlank()) throw ReportException("尚未設定 Claude API Key（請在手機 App 設定）")
        if (transcript.isBlank()) throw ReportException("逐字稿是空的，沒有可整理的內容")

        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(4).build()
        try {
            val minutes = (durationSec / 60).let { "%.1f".format(it) }
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .system(ReportLang.localize(SYSTEM_PROMPT))
                .addUserMessage(
                    "錄音時間：$recordedAt，長度約 $minutes 分鐘。\n\n<transcript>\n$transcript\n</transcript>"
                )
                // 若主模型因安全分类器拒答，由服务器自动改用备用模型重试
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()

            // 用串流：长逐字稿的输出可能很久，非串流请求容易超时
            val sb = StringBuilder()
            var stopReason: StopReason? = null
            client.messages().createStreaming(params).use { stream ->
                stream.stream().forEach { event ->
                    event.contentBlockDelta().ifPresent { d ->
                        d.delta().text().ifPresent { sb.append(it.text()) }
                    }
                    event.messageDelta().ifPresent { md ->
                        md.delta().stopReason().ifPresent { stopReason = it }
                    }
                }
            }
            if (stopReason == StopReason.REFUSAL) throw ReportException("Claude 拒絕處理此內容")
            val text = sb.toString().trim()
            if (text.isEmpty()) throw ReportException("Claude 沒有回傳內容")
            return parse(text)
        } finally {
            client.close()
        }
    }

    internal fun parse(text: String): GeneratedReport = parseReport(text)

    /** 通用对话（组合式 AI 的报告模型用）：给系统提示与内容，回传文字。用串流避免长输出逾时。 */
    fun chat(system: String, user: String, model: String = MODEL): String {
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(4).build()
        try {
            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(system)
                .addUserMessage(user)
                .build()
            val sb = StringBuilder()
            var stopReason: StopReason? = null
            client.messages().createStreaming(params).use { stream ->
                stream.stream().forEach { event ->
                    event.contentBlockDelta().ifPresent { d -> d.delta().text().ifPresent { sb.append(it.text()) } }
                    event.messageDelta().ifPresent { md -> md.delta().stopReason().ifPresent { stopReason = it } }
                }
            }
            if (stopReason == StopReason.REFUSAL) throw ReportException("Claude 拒絕處理此內容")
            return sb.toString().trim().ifEmpty { throw ReportException("Claude 沒有回傳內容") }
        } finally {
            client.close()
        }
    }
}

/** 解析「TITLE: / SUMMARY: / Markdown」格式（Claude 和 Gemini 共用）。 */
internal fun ReportGenerator.Companion.parseReport(text: String): GeneratedReport {
    run {
        val lines = text.lines()
        var title = "錄音紀錄"
        var summary = ""
        var bodyStart = 0
        for ((i, line) in lines.withIndex().take(5)) {
            val t = line.trim()
            when {
                t.startsWith("TITLE:", ignoreCase = true) -> {
                    title = t.substringAfter(":").trim().ifBlank { title }; bodyStart = i + 1
                }
                t.startsWith("SUMMARY:", ignoreCase = true) -> {
                    summary = t.substringAfter(":").trim(); bodyStart = i + 1
                }
            }
        }
        val body = fillEmptySummary(lines.drop(bodyStart).joinToString("\n").trim(), summary)
        return GeneratedReport(
            title = title,
            shortSummary = summary.ifBlank { body.take(80) },
            markdown = "# $title\n\n$body",
        )
    }
}

/** 「## 摘要」底下只有「無」或空白时，用 [summary]（SUMMARY 那一行）补上。 */
internal fun fillEmptySummary(body: String, summary: String): String {
    if (summary.isBlank()) return body
    val lines = body.lines().toMutableList()
    val h = lines.indexOfFirst { it.trim().trimStart('#').trim() == "摘要" && it.trim().startsWith("#") }
    if (h < 0) return body
    var end = h + 1
    while (end < lines.size && !lines[end].trim().startsWith("#")) end++
    val content = lines.subList(h + 1, end).map { it.trim() }.filter { it.isNotEmpty() }
    if (content.isNotEmpty() && content.any { it !in setOf("無", "无", "（無）", "-", "—") }) return body
    for (i in end - 1 downTo h + 1) lines.removeAt(i)
    lines.add(h + 1, summary)
    return lines.joinToString("\n")
}
