package com.example.wristai.ai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 语音辨识的一句：开始时间（毫秒，从这段音档开头算）、说话者（服务给的原始代号，可能是 null）、文字。 */
data class Seg(val startMs: Long, val speaker: String?, val text: String)

/** 送出辨识：有的服务马上有结果（[Done]），有的要排队之后查（[Pending]，带任务编号）。 */
sealed class SttStart {
    class Done(val segs: List<Seg>) : SttStart()
    class Pending(val taskId: String) : SttStart()
}

/** 一家语音辨识服务。音档一律是 16kHz 单声道 m4a（AAC）。 */
interface Stt {
    /** 显示名称 */
    val name: String
    /** 存进报告「AI」栏位的模型代号 */
    val modelId: String
    /** 一次能送的最长秒数（超过就切段） */
    val maxChunkSec: Int
    fun start(audio: File, durationSec: Double): SttStart
    /** 查任务：做完回句子；还在做回 null；失败丢 [AiException]。 */
    fun poll(taskId: String): List<Seg>?
}

// ======================= 阿里云百炼 Fun-ASR =======================

/**
 * 百炼录音档辨识（Fun-ASR）。流程：
 * 1. 取上传凭证（/api/v1/uploads?action=getPolicy）→ 2. 音档传到百炼的临时空间（oss:// 网址，48 小时后自动删除）
 * 3. 送非同步辨识任务（开说话者分离）→ 4. 查任务 → 5. 下载辨识结果 JSON。
 * [region]：intl = 国际（新加坡）、cn = 中国大陆（北京），两区金钥不通用。
 */
class FunAsrStt(private val key: String, region: String, private val model: String) : Stt {
    override val name = "Fun-ASR"
    override val modelId = model
    /** 说话者分离建议 2 小时内 */
    override val maxChunkSec = 110 * 60
    private val base = if (region == "cn") "https://dashscope.aliyuncs.com" else "https://dashscope-intl.aliyuncs.com"
    private val auth get() = mapOf("Authorization" to "Bearer $key")

    override fun start(audio: File, durationSec: Double): SttStart {
        // 1. 上传凭证
        val policy = Http.json("百煉（上傳）", "$base/api/v1/uploads?action=getPolicy&model=${enc(model)}", "GET", auth)
            .optJSONObject("data") ?: throw AiException("百煉沒有給上傳憑證")
        val objectKey = policy.getString("upload_dir") + "/" + audio.name
        // 2. 上传到临时空间
        val up = Http.multipart(
            policy.getString("upload_host"), emptyMap(),
            listOf(
                "OSSAccessKeyId" to policy.getString("oss_access_key_id"),
                "Signature" to policy.getString("signature"),
                "policy" to policy.getString("policy"),
                "x-oss-object-acl" to policy.getString("x_oss_object_acl"),
                "x-oss-forbid-overwrite" to policy.getString("x_oss_forbid_overwrite"),
                "key" to objectKey,
                "success_action_status" to "200",
            ),
            "file", audio, audio.name, "audio/mp4",
        )
        if (up.code !in 200..299) throw AiException("百煉上傳音檔失敗（HTTP ${up.code}）")
        // 3. 送辨识任务
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONObject().put("file_urls", JSONArray().put("oss://$objectKey")))
            .put("parameters", JSONObject().put("diarization_enabled", true))
        val r = Http.json("Fun-ASR", "$base/api/v1/services/audio/asr/transcription", "POST",
            auth + mapOf("X-DashScope-Async" to "enable", "X-DashScope-OssResourceResolve" to "enable"), body)
        val task = r.optJSONObject("output")?.optString("task_id").orEmpty()
        if (task.isBlank()) throw AiException("Fun-ASR 沒有回傳任務編號")
        return SttStart.Pending(task)
    }

    override fun poll(taskId: String): List<Seg>? {
        val out = Http.json("Fun-ASR", "$base/api/v1/tasks/${enc(taskId)}", "GET", auth).optJSONObject("output")
            ?: throw AiException("Fun-ASR 查詢結果格式不對")
        when (out.optString("task_status")) {
            "PENDING", "RUNNING" -> return null
            "SUCCEEDED" -> Unit
            else -> throw AiException("Fun-ASR 辨識失敗：${out.optString("message").ifBlank { out.optString("task_status") }}")
        }
        val result = out.optJSONArray("results")?.optJSONObject(0) ?: throw AiException("Fun-ASR 沒有結果")
        if (result.optString("subtask_status") == "FAILED")
            throw AiException("Fun-ASR 辨識失敗：${result.optString("message").ifBlank { result.optString("code") }}")
        val url = result.optString("transcription_url")
        val r = Http.request(url, "GET")
        if (r.code !in 200..299) throw AiException("Fun-ASR 下載結果失敗（HTTP ${r.code}）")
        return parseFunAsr(r.body)
    }

    companion object {
        /** 辨识结果：transcripts[].sentences[]{begin_time, text, speaker_id}。 */
        fun parseFunAsr(json: String): List<Seg> {
            val out = mutableListOf<Seg>()
            val ts = JSONObject(json).optJSONArray("transcripts") ?: return out
            for (i in 0 until ts.length()) {
                val ss = ts.getJSONObject(i).optJSONArray("sentences") ?: continue
                for (k in 0 until ss.length()) {
                    val s = ss.getJSONObject(k)
                    val text = s.optString("text").trim()
                    if (text.isEmpty()) continue
                    out += Seg(s.optLong("begin_time"), if (s.has("speaker_id")) s.opt("speaker_id").toString() else null, text)
                }
            }
            return out.sortedBy { it.startMs }
        }
    }
}

// ======================= 科大讯飞 录音文件转写 =======================

/**
 * 讯飞录音文件转写（标准版）。上传（音档放 body）→ 查结果；roleType=1 开说话者分离。
 * 签名：signa = Base64(HmacSHA1(secretKey, MD5(appId + ts)))。
 */
class IflytekStt(private val appId: String, private val secret: String) : Stt {
    override val name = "訊飛錄音轉寫"
    override val modelId = "iflytek-lfasr"
    override val maxChunkSec = 4 * 3600 + 30 * 60
    private val host = "https://raasr.xfyun.cn/v2/api"

    private fun authParams(): String {
        val ts = (System.currentTimeMillis() / 1000).toString()
        return "appId=${enc(appId)}&ts=$ts&signa=${enc(signa(appId, secret, ts))}"
    }

    override fun start(audio: File, durationSec: Double): SttStart {
        val url = "$host/upload?${authParams()}&fileName=${enc(audio.name)}&fileSize=${audio.length()}" +
            "&duration=${(durationSec * 1000).toLong()}&roleType=1&language=cn"
        val r = Http.request(url, "POST", body = audio.readBytes(), contentType = "application/octet-stream")
        val j = runCatching { JSONObject(r.body) }.getOrNull() ?: throw AiException("訊飛上傳失敗（HTTP ${r.code}）")
        if (j.optString("code") != "000000") throw AiException("訊飛上傳失敗：${j.optString("descInfo")}（${j.optString("code")}）")
        val order = j.optJSONObject("content")?.optString("orderId").orEmpty()
        if (order.isBlank()) throw AiException("訊飛沒有回傳訂單編號")
        return SttStart.Pending(order)
    }

    override fun poll(taskId: String): List<Seg>? {
        val url = "$host/getResult?${authParams()}&orderId=${enc(taskId)}&resultType=transfer"
        // 文件同时写了 GET 和 POST：先 POST，不接受再用 GET
        var r = Http.request(url, "POST", body = ByteArray(0), contentType = "application/json")
        if (r.code == 405) r = Http.request(url, "GET")
        val j = runCatching { JSONObject(r.body) }.getOrNull() ?: throw AiException("訊飛查詢失敗（HTTP ${r.code}）")
        if (j.optString("code") != "000000") throw AiException("訊飛查詢失敗：${j.optString("descInfo")}（${j.optString("code")}）")
        val content = j.optJSONObject("content") ?: return null
        val info = content.optJSONObject("orderInfo")
        return when (info?.optInt("status", 0)) {
            4 -> parseIflytek(content.optString("orderResult"))
            -1 -> throw AiException("訊飛轉寫失敗（failType ${info.optInt("failType")}）")
            else -> null
        }
    }

    companion object {
        fun signa(appId: String, secret: String, ts: String): String {
            val md5 = MessageDigest.getInstance("MD5").digest((appId + ts).toByteArray()).joinToString("") { "%02x".format(it) }
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA1")) }
            return Base64.encodeToString(mac.doFinal(md5.toByteArray()), Base64.NO_WRAP)
        }

        /** orderResult 是 JSON 字串；lattice[].json_1best 又是 JSON 字串 → st{bg, rl, rt[].ws[].cw[].w}。 */
        fun parseIflytek(orderResult: String): List<Seg> {
            if (orderResult.isBlank()) return emptyList()
            val lattice = JSONObject(orderResult).optJSONArray("lattice") ?: return emptyList()
            val out = mutableListOf<Seg>()
            for (i in 0 until lattice.length()) {
                val best = lattice.getJSONObject(i).opt("json_1best") ?: continue
                val st = (if (best is JSONObject) best else JSONObject(best.toString())).optJSONObject("st") ?: continue
                val sb = StringBuilder()
                val rt = st.optJSONArray("rt") ?: JSONArray()
                for (a in 0 until rt.length()) {
                    val ws = rt.getJSONObject(a).optJSONArray("ws") ?: continue
                    for (b in 0 until ws.length()) {
                        val cw = ws.getJSONObject(b).optJSONArray("cw") ?: continue
                        cw.optJSONObject(0)?.optString("w")?.let { sb.append(it) }
                    }
                }
                val text = sb.toString().trim()
                if (text.isEmpty()) continue
                val role = st.optString("rl").takeIf { it.isNotBlank() && it != "0" }
                out += Seg(st.optString("bg").toLongOrNull() ?: 0L, role, text)
            }
            return out.sortedBy { it.startMs }
        }
    }
}

// ======================= OpenAI 转录 =======================

/**
 * OpenAI 语音转文字（预设 gpt-4o-transcribe-diarize，回传 diarized_json：segments[]{speaker, start, text}）。
 * 一次最多 25 MB，所以每段 85 分钟（32 kbps 约 20 MB）。同步：送出就拿到结果。
 */
class OpenAiStt(private val key: String, private val model: String) : Stt {
    override val name = "OpenAI 轉錄"
    override val modelId = model
    override val maxChunkSec = 85 * 60

    override fun start(audio: File, durationSec: Double): SttStart {
        val diarize = model.contains("diarize")
        val fields = buildList {
            add("model" to model)
            add("response_format" to if (diarize) "diarized_json" else "json")
            if (diarize) add("chunking_strategy" to "auto")
        }
        val r = Http.multipart("https://api.openai.com/v1/audio/transcriptions",
            mapOf("Authorization" to "Bearer $key"), fields, "file", audio, audio.name, "audio/mp4")
        if (r.code !in 200..299) throw AiException("OpenAI 轉錄錯誤（HTTP ${r.code}）：${Http.errorText(r.body)}")
        return SttStart.Done(parseOpenAi(r.body))
    }

    override fun poll(taskId: String): List<Seg>? = throw AiException("OpenAI 轉錄不需要查詢")

    companion object {
        fun parseOpenAi(json: String): List<Seg> {
            val j = JSONObject(json)
            val segs = j.optJSONArray("segments")
            if (segs == null || segs.length() == 0) {
                val t = j.optString("text").trim()
                return if (t.isEmpty()) emptyList() else listOf(Seg(0, null, t))
            }
            return (0 until segs.length()).mapNotNull { i ->
                val s = segs.getJSONObject(i)
                val t = s.optString("text").trim()
                if (t.isEmpty()) null
                else Seg((s.optDouble("start", 0.0) * 1000).toLong(), s.optString("speaker").takeIf { it.isNotBlank() }, t)
            }
        }
    }
}

internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
