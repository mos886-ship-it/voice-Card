package com.example.wristai.ai

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import android.content.Context
import com.example.wristai.ReportGenerator
import com.example.wristai.Settings

/**
 * AI 引擎设定（存在手机本机，跟其他设定同一个档案；金钥只存在这支手机）。
 *
 * 三种模式：
 * - [MODE_GEMINI]：Google Gemini 一站式（直接听录音，出报告＋分说话者逐字稿）。
 * - [MODE_PIPELINE]：组合式 = 语音辨识（[stt]）＋ 报告模型（[llm]），各家可以混搭。
 * - [MODE_DEVICE]：只存进三星录音机，用 Galaxy AI 在手机上整理（不上传）。
 */
object AiConfig {
    private const val PREFS = "wristai_settings"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val MODE_GEMINI = "gemini"
    const val MODE_PIPELINE = "pipeline"
    const val MODE_DEVICE = "device"

    /** 语音辨识服务。 */
    enum class SttKind(val label: String, val detail: String) {
        FUNASR("阿里百煉 Fun-ASR".tr, "通義語音辨識大模型，中文、方言、30 種語言，可分辨說話者".tr),
        IFLYTEK("科大訊飛 錄音轉寫".tr, "中文與方言強，可分辨說話者（最長 5 小時）".tr),
        OPENAI("OpenAI 轉錄".tr, "gpt-4o-transcribe-diarize，多語言，可分辨說話者".tr),
    }

    /** 整理报告的大模型。 */
    enum class LlmKind(val label: String, val detail: String) {
        QWEN("阿里百煉 通義千問".tr, "Qwen 系列，中文報告品質好".tr),
        SPARK("科大訊飛 星火".tr, "星火大模型（4.0Ultra 等）".tr),
        LLAMA("Meta Llama", "Llama 4 等開源模型（OpenAI 相容端點，可改）".tr),
        OPENAI("OpenAI GPT", "GPT 系列".tr),
        CLAUDE("Anthropic Claude", "Claude 系列，長篇整理與結構化強".tr),
    }

    /** 各个模型栏位的预设值（使用者可以在设定里改成新版本）。 */
    val DEFAULT_MODELS = mapOf(
        "funasr" to "fun-asr",
        "openai_stt" to "gpt-4o-transcribe-diarize",
        "qwen" to "qwen-plus",
        "spark" to "4.0Ultra",
        "llama" to "Llama-4-Maverick-17B-128E-Instruct-FP8",
        "openai" to "gpt-5-mini",
        "claude" to ReportGenerator.MODEL,
    )
    const val DEFAULT_LLAMA_BASE = "https://api.llama.com/compat/v1"

    /** 使用者有没有在新版设定里明确选过模式（没选过就沿用旧版行为：有 Gemini 金钥用 Gemini）。 */
    fun explicit(c: Context) = p(c).contains("ai_mode")

    fun mode(c: Context): String = p(c).getString("ai_mode", null)
        ?: if (Settings.geminiKey(c).isNotBlank()) MODE_GEMINI else MODE_DEVICE
    fun setMode(c: Context, v: String) = p(c).edit().putString("ai_mode", v).apply()

    fun sttKind(c: Context): SttKind = runCatching { SttKind.valueOf(p(c).getString("ai_stt", "FUNASR")!!) }.getOrDefault(SttKind.FUNASR)
    fun setSttKind(c: Context, v: SttKind) = p(c).edit().putString("ai_stt", v.name).apply()
    fun llmKind(c: Context): LlmKind = runCatching { LlmKind.valueOf(p(c).getString("ai_llm", "QWEN")!!) }.getOrDefault(LlmKind.QWEN)
    fun setLlmKind(c: Context, v: LlmKind) = p(c).edit().putString("ai_llm", v.name).apply()

    /** 金钥与其他值：dashscope_key、dashscope_region（intl / cn）、iflytek_appid、iflytek_secret、spark_password、openai_key、llama_key、llama_base。 */
    fun get(c: Context, key: String): String = p(c).getString("ai_${key}", "").orEmpty()
    fun set(c: Context, key: String, v: String) = p(c).edit().putString("ai_${key}", v.trim()).apply()

    fun model(c: Context, slot: String): String = get(c, "model_${slot}").ifBlank { DEFAULT_MODELS[slot].orEmpty() }
    fun dashscopeRegion(c: Context): String = get(c, "dashscope_region").ifBlank { "intl" }

    /** 建立目前选的语音辨识服务；金钥没填 → null。 */
    fun stt(c: Context): Stt? = when (sttKind(c)) {
        SttKind.FUNASR -> get(c, "dashscope_key").takeIf { it.isNotBlank() }
            ?.let { FunAsrStt(it, dashscopeRegion(c), model(c, "funasr")) }
        SttKind.IFLYTEK -> if (get(c, "iflytek_appid").isNotBlank() && get(c, "iflytek_secret").isNotBlank())
            IflytekStt(get(c, "iflytek_appid"), get(c, "iflytek_secret")) else null
        SttKind.OPENAI -> get(c, "openai_key").takeIf { it.isNotBlank() }?.let { OpenAiStt(it, model(c, "openai_stt")) }
    }

    /** 建立目前选的报告模型；金钥没填 → null。 */
    fun llm(c: Context): Llm? = when (llmKind(c)) {
        LlmKind.QWEN -> get(c, "dashscope_key").takeIf { it.isNotBlank() }?.let { key ->
            val host = if (dashscopeRegion(c) == "cn") "https://dashscope.aliyuncs.com" else "https://dashscope-intl.aliyuncs.com"
            OpenAiCompatLlm("通義千問".tr, "${host}/compatible-mode/v1", key, model(c, "qwen"), maxChars = 90_000, maxTokens = 8192)
        }
        LlmKind.SPARK -> get(c, "spark_password").takeIf { it.isNotBlank() }?.let { key ->
            val m = model(c, "spark")
            OpenAiCompatLlm("訊飛星火".tr, "https://spark-api-open.xf-yun.com/v1", key, m,
                maxChars = if (m == "lite") 6_000 else 20_000, maxTokens = if (m == "lite" || m == "generalv3" || m == "generalv3.5") 4096 else 8192)
        }
        LlmKind.LLAMA -> get(c, "llama_key").takeIf { it.isNotBlank() }?.let { key ->
            OpenAiCompatLlm("Meta Llama", get(c, "llama_base").ifBlank { DEFAULT_LLAMA_BASE }.trimEnd('/'), key, model(c, "llama"),
                maxChars = 60_000, maxTokens = null)
        }
        LlmKind.OPENAI -> get(c, "openai_key").takeIf { it.isNotBlank() }?.let { key ->
            OpenAiCompatLlm("OpenAI GPT", "https://api.openai.com/v1", key, model(c, "openai"), maxChars = 150_000, maxTokens = null)
        }
        LlmKind.CLAUDE -> Settings.apiKey(c).takeIf { it.isNotBlank() }?.let { ClaudeLlm(it, model(c, "claude")) }
    }

    /** 组合式还缺什么（给设定页与错误讯息）；都好了 → null。 */
    fun pipelineMissing(c: Context): String? = when {
        stt(c) == null -> "請填寫「{0}」的金鑰".trf(sttKind(c).label)
        llm(c) == null -> "請填寫「{0}」的金鑰".trf(llmKind(c).label)
        else -> null
    }

    /** 现在能不能产生 AI 报告（详情页的「产生 AI 报告」按钮用）。 */
    fun canReport(c: Context): Boolean = if (LocalHost.configured(c)) true else when (mode(c)) {
        MODE_GEMINI -> Settings.geminiKey(c).isNotBlank()
        MODE_PIPELINE -> pipelineMissing(c) == null
        else -> !explicit(c) && Settings.apiKey(c).isNotBlank()   // 旧版：只有 Claude 金钥
    }
}
