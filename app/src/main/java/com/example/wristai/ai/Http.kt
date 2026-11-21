package com.example.wristai.ai

import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** AI 服务回传的错误（讯息给使用者看，不含金钥）。 */
class AiException(message: String) : Exception(message)

/** 简单的 HTTP 工具（不额外加套件）。逾时放长：语音辨识与长报告都可能要好几分钟。 */
internal object Http {
    data class Resp(val code: Int, val body: String)

    fun request(
        url: String, method: String, headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null, contentType: String? = null, readTimeoutMs: Int = 10 * 60_000,
    ): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Resp(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /** 送 JSON、收 JSON；非 2xx 就丢出带服务名称与错误讯息的 [AiException]。 */
    fun json(service: String, url: String, method: String, headers: Map<String, String>, body: JSONObject? = null): JSONObject {
        val r = request(url, method, headers, body?.toString()?.toByteArray(Charsets.UTF_8), if (body != null) "application/json" else null)
        if (r.code !in 200..299) throw AiException("$service 錯誤（HTTP ${r.code}）：${errorText(r.body)}")
        return runCatching { JSONObject(r.body) }.getOrElse { throw AiException("$service 回傳格式看不懂：${r.body.take(200)}") }
    }

    /** multipart/form-data 上传（[file] 一定放在最后一栏，阿里 OSS 规定）。 */
    fun multipart(
        url: String, headers: Map<String, String>, fields: List<Pair<String, String>>,
        fileField: String, file: File, fileName: String, fileType: String,
    ): Resp {
        val boundary = "----dylan" + System.nanoTime()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 10 * 60_000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            DataOutputStream(conn.outputStream).use { out ->
                for ((k, v) in fields) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n".toByteArray())
                    out.write(v.toByteArray(Charsets.UTF_8))
                    out.write("\r\n".toByteArray())
                }
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\nContent-Type: $fileType\r\n\r\n".toByteArray())
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Resp(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /** 从各家的错误 JSON 挑出看得懂的讯息。 */
    fun errorText(body: String): String = runCatching {
        val j = JSONObject(body)
        j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: j.optString("message").takeIf { it.isNotBlank() }
            ?: j.optString("descInfo").takeIf { it.isNotBlank() }
            ?: body.take(200)
    }.getOrDefault(body.take(200))
}
