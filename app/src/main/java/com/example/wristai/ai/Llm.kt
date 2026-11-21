package com.example.wristai.ai

import com.example.wristai.ReportGenerator
import org.json.JSONArray
import org.json.JSONObject

/** 整理报告的大模型：给系统提示＋使用者内容，回传文字。 */
interface Llm {
    val name: String
    val modelId: String
    /** 一次能放进去的逐字稿字数（超过就先分段整理笔记再合并） */
    val maxChars: Int
    fun complete(system: String, user: String): String
}

/**
 * OpenAI 相容的 chat/completions：OpenAI GPT、百炼通义千问（compatible-mode）、讯飞星火（spark-api-open）、
 * Meta Llama API（/compat/v1）都用这个。[maxTokens] = null 就不送（OpenAI 新模型不收 max_tokens）。
 */
class OpenAiCompatLlm(
    override val name: String,
    private val baseUrl: String,
    private val key: String,
    override val modelId: String,
    override val maxChars: Int,
    private val maxTokens: Int?,
) : Llm {
    override fun complete(system: String, user: String): String {
        val body = JSONObject()
            .put("model", modelId)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        if (maxTokens != null) body.put("max_tokens", maxTokens)
        val r = Http.json(name, "$baseUrl/chat/completions", "POST", mapOf("Authorization" to "Bearer $key"), body)
        val text = parseChat(r)
        if (text.isBlank()) throw AiException("$name 沒有回傳內容")
        return text
    }

    companion object {
        /** choices[0].message.content（字串，或是 [{type:text,text}] 阵列）。 */
        fun parseChat(j: JSONObject): String {
            val msg = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            // Meta Llama 原生格式：completion_message.content.text
            val content = msg?.opt("content") ?: j.optJSONObject("completion_message")?.opt("content") ?: return ""
            return when (content) {
                is String -> content
                is JSONArray -> (0 until content.length()).joinToString("") { content.optJSONObject(it)?.optString("text").orEmpty() }
                is JSONObject -> content.optString("text")
                else -> content.toString()
            }.trim()
        }
    }
}

/** Anthropic Claude（用官方 Java SDK，见 [ReportGenerator.chat]）。 */
class ClaudeLlm(private val key: String, override val modelId: String) : Llm {
    override val name = "Claude"
    override val maxChars = 150_000
    override fun complete(system: String, user: String): String = ReportGenerator(key).chat(system, user, modelId)
}
