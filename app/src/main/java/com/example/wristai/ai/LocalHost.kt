package com.example.wristai.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 大D的主機：使用者自己的电脑跑 AI（语音辨识＋分说话者＋报告），免费、录音不出使用者的设备。
 * 服务在 repo 的 `local-ai/`（Mac 上 `~/dylan-local/`），API 跟 iOS 版 `LocalAI.swift` 同一组：
 *
 * - GET  /v1/health → {ok, asr, llm, asr_id, llm_id, model, state: ready|loading|error, error, busy}
 * - GET  /v1/models → health ＋ asr_options / llm_options（[{id, label}]）
 * - POST /v1/config {"asr", "llm"} → 202，背景换模型
 * - POST /v1/process 录音（或 X-Input: text 的文字）→ 处理期间每 20 秒送空白，最后 {"text", "model"} 或 {"error"}
 *
 * text 的格式跟 Gemini 输出一样（TITLE / SUMMARY＋报告＋===TRANSCRIPT===＋逐字稿），直接用 Gemini 的解析。
 * 位址存设定档；存取码用 Android Keystore 加密后才存。
 */
object LocalHost {
    private const val PREFS = "wristai_settings"
    private const val KEY_URL = "local_ai_url"
    private const val KEY_TOKEN = "local_ai_token_enc"
    private const val KEY_MODEL = "local_ai_model"
    private const val KEYSTORE_ALIAS = "dylan_local_ai"
    const val DEFAULT_TAG = "local-funasr|qwen3-8b"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 设定 ----------------

    /** 使用者打的位址整理成「http://主机:埠」：去掉空白与结尾的「/」，没写 http:// 就补上。 */
    fun normalize(raw: String): String {
        var v = raw.trim().trimEnd('/')
        if (v.isNotEmpty() && !v.startsWith("http://", true) && !v.startsWith("https://", true)) v = "http://$v"
        return v
    }

    fun url(c: Context): String = normalize(p(c).getString(KEY_URL, "").orEmpty())
    fun setUrl(c: Context, v: String) = p(c).edit().putString(KEY_URL, normalize(v)).apply()

    fun token(c: Context): String = p(c).getString(KEY_TOKEN, null)?.let { runCatching { decrypt(it) }.getOrNull() }.orEmpty()
    fun setToken(c: Context, v: String) {
        val t = v.trim()
        p(c).edit().apply { if (t.isEmpty()) remove(KEY_TOKEN) else putString(KEY_TOKEN, encrypt(t)) }.apply()
    }
    fun hasToken(c: Context) = token(c).isNotEmpty()

    /** 位址和存取码都填了才算设定好。 */
    fun configured(c: Context) = url(c).isNotEmpty() && hasToken(c) && runCatching { URL(url(c)) }.isSuccess

    /** 最后一次从主机得知的模型代号（local-<辨识>|<报告>），主页 / 设定显示用。 */
    fun lastTag(c: Context): String = p(c).getString(KEY_MODEL, null) ?: DEFAULT_TAG
    private fun rememberTag(c: Context, tag: String?) { if (!tag.isNullOrBlank()) p(c).edit().putString(KEY_MODEL, tag).apply() }

    // ---------------- 名称 ----------------

    private val NAMES = mapOf(
        "funasr" to "FunASR", "breeze" to "Breeze-ASR-26", "text" to "文字",
        "qwen3-8b" to "Qwen3-8B", "sarc-taigi-12b" to "SARC 台語 12B",
    )

    /** 模型代号 → 「大D的主機：FunASR ＋ Qwen3-8B」。认不得的代号照原样（主机上可以自己加模型）。 */
    fun label(tag: String): String =
        "大D的主機：" + tag.removePrefix("local-").split('|').filter { it.isNotBlank() }.joinToString(" ＋ ") { NAMES[it] ?: it }

    fun isLocalTag(model: String?) = model?.startsWith("local-") == true

    // ---------------- API ----------------

    data class Health(
        val ok: Boolean, val asr: String?, val llm: String?, val model: String?,
        val state: String?, val error: String?, val busy: Boolean,
    )
    data class Option(val id: String, val label: String)
    data class Models(
        val asrId: String?, val llmId: String?, val state: String?, val model: String?,
        val asrOptions: List<Option>, val llmOptions: List<Option>,
    )

    private fun open(c: Context, base: String, token: String, path: String, method: String, readTimeoutMs: Int): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun read(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        return code to body
    }

    private fun errorOf(code: Int, body: String): String = when (code) {
        401 -> "大D的主機的存取碼不對，請到設定重新貼上"
        429 -> "存取碼錯太多次，主機暫時擋掉這支手機（一小時後或重開主機服務就解除）"
        else -> runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "大D的主機回應錯誤 $code"
    }

    private fun unreachable(e: Exception): AiException {
        android.util.Log.w("LocalHost", "unreachable: ${e.javaClass.simpleName}: ${e.message}")
        return AiException("連不到大D的主機：請確認主機開著、服務在跑、手機連得到主機（同一個 Wi-Fi 或固定 IP）")
    }

    /** 测试连线 / 送出前的检查（5 秒）。[url]、[token] 给设定页测还没存的值。 */
    fun health(c: Context, url: String = url(c), token: String = token(c)): Health {
        val base = normalize(url)
        if (base.isEmpty() || runCatching { URL(base) }.isFailure) throw AiException("位址格式不對，例如 http://192.168.1.20:8765")
        val (code, body) = try {
            val conn = open(c, base, token, "/v1/health", "GET", 5_000)
            try { read(conn) } finally { conn.disconnect() }
        } catch (e: Exception) { throw unreachable(e) }
        if (code != 200) throw AiException(errorOf(code, body))
        val j = runCatching { JSONObject(body) }.getOrElse { throw AiException("大D的主機回應看不懂") }
        return Health(
            ok = j.optBoolean("ok"), asr = j.optString("asr").ifBlank { null }, llm = j.optString("llm").ifBlank { null },
            model = j.optString("model").ifBlank { null }, state = j.optString("state").ifBlank { null },
            error = j.optString("error").ifBlank { null }, busy = j.optBoolean("busy"),
        ).also { if (it.state != "loading") rememberTag(c, it.model) }
    }

    /** 主机上可选的模型（设定页科技模式的选单）。 */
    fun models(c: Context): Models {
        val (code, body) = try {
            val conn = open(c, url(c), token(c), "/v1/models", "GET", 10_000)
            try { read(conn) } finally { conn.disconnect() }
        } catch (e: Exception) { throw unreachable(e) }
        if (code != 200) throw AiException(errorOf(code, body))
        val j = JSONObject(body)
        fun opts(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { i ->
            a!!.getJSONObject(i).let { Option(it.optString("id"), it.optString("label").ifBlank { it.optString("id") }) }
        }
        return Models(
            asrId = j.optString("asr_id").ifBlank { null }, llmId = j.optString("llm_id").ifBlank { null },
            state = j.optString("state").ifBlank { null }, model = j.optString("model").ifBlank { null },
            asrOptions = opts(j.optJSONArray("asr_options")), llmOptions = opts(j.optJSONArray("llm_options")),
        ).also { if (it.state != "loading") rememberTag(c, it.model) }
    }

    /** 换模型：主机存起来并在背景载入（第一次要下载），之后用 [models] 看 state 何时变回 ready。 */
    fun select(c: Context, asr: String, llm: String) {
        val (code, body) = try {
            val conn = open(c, url(c), token(c), "/v1/config", "POST", 10_000).apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(JSONObject().put("asr", asr).put("llm", llm).toString().toByteArray()) }
                read(conn)
            } finally { conn.disconnect() }
        } catch (e: Exception) { throw unreachable(e) }
        if (code != 200 && code != 202) throw AiException(errorOf(code, body))
    }

    data class Result(val text: String, val model: String)

    /**
     * 送录音（[audio]）或文字（[text]）给主机产生报告。[kind] = meeting / food。
     * [recordedAt] 只能是 ASCII（例 2026/11/23 21:20）。会等很久：主机每 20 秒送一个空白防逾时。
     */
    fun process(c: Context, kind: String, recordedAt: String, durationSec: Double, audio: File? = null, text: String? = null,
                onUploaded: () -> Unit = {}): Result {
        val bytes = text?.toByteArray(Charsets.UTF_8)
        val (code, body) = try {
            val conn = open(c, url(c), token(c), "/v1/process", "POST", readTimeoutMs = 3 * 60_000).apply {
                connectTimeout = 15_000
                doOutput = true
                setRequestProperty("X-Kind", kind)
                setRequestProperty("X-Recorded-At", recordedAt.filter { it.code in 32..126 })
                setRequestProperty("X-Duration", durationSec.toInt().toString())
                if (bytes != null) {
                    setRequestProperty("X-Input", "text")
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    setFixedLengthStreamingMode(bytes.size)
                } else {
                    setRequestProperty("Content-Type", "audio/m4a")
                    setFixedLengthStreamingMode(audio!!.length())
                }
            }
            try {
                conn.outputStream.use { out -> if (bytes != null) out.write(bytes) else audio!!.inputStream().use { it.copyTo(out, 64 * 1024) } }
                onUploaded()
                read(conn)
            } finally { conn.disconnect() }
        } catch (e: AiException) { throw e } catch (e: Exception) { throw unreachable(e) }
        if (code != 200) throw AiException(errorOf(code, body))
        val j = runCatching { JSONObject(body.trim()) }.getOrElse { throw AiException("大D的主機回應看不懂：${body.trim().take(120)}") }
        j.optString("error").takeIf { it.isNotBlank() }?.let { throw AiException(it) }
        val out = j.optString("text").takeIf { it.isNotBlank() } ?: throw AiException("大D的主機沒有回傳報告")
        val tag = j.optString("model").ifBlank { DEFAULT_TAG }
        rememberTag(c, tag)
        return Result(out, tag)
    }

    // ---------------- 存取码加密（Android Keystore AES-GCM） ----------------

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, enc) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(cipher.doFinal(enc), Charsets.UTF_8)
    }
}
