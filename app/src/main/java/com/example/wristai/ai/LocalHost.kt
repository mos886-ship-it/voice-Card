package com.example.wristai.ai

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

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
 * - GET  /v1/health → {ok, asr, llm, asr_id, llm_id, model, state: ready|loading|error, error, busy,
 *                      admin, jobs, queue, ahead, wait_sec}（后面几个是新版主机才有）
 * - GET  /v1/models → health ＋ asr_options / llm_options（[{id, label}]）
 * - POST /v1/config {"asr", "llm"} → 202，背景换模型（只有管理者，不是 → 403）
 * - 工作伫列（多人共用一台主机，新版）：POST /v1/jobs 送出马上拿到编号 → GET /v1/jobs/<id> 查排队 / 结果 → DELETE 删掉
 * - POST /v1/process 录音（或 X-Input: text 的文字）→ 处理期间每 20 秒送空白，最后 {"text", "model"} 或 {"error"}（旧主机用）
 *
 * text 的格式跟 Gemini 输出一样（TITLE / SUMMARY＋报告＋===TRANSCRIPT===＋逐字稿），直接用 Gemini 的解析。
 * 位址存设定档；存取码用 Android Keystore 加密后才存。
 */
object LocalHost {
    private const val PREFS = "wristai_settings"
    private const val KEY_URL = "local_ai_url"
    private const val KEY_TOKEN = "local_ai_token_enc"
    private const val KEY_MODEL = "local_ai_model"
    private const val KEY_DEVICE_ID = "local_ai_device_id"
    private const val KEYSTORE_ALIAS = "dylan_local_ai"
    const val DEFAULT_TAG = "local-funasr|qwen3-8b"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 设定 ----------------

    /** 使用者打的位址整理成「http://主机:埠」：去掉空白与结尾的「/」，没写 http:// 就补上。 */
    fun normalize(raw: String): String {
        var v = raw.trim().trimEnd('/')
        if (v.isNotEmpty() && !v.startsWith("http://", true) && !v.startsWith("https://", true)) v = "http://${v}"
        return v
    }

    fun url(c: Context): String = normalize(p(c).getString(KEY_URL, "").orEmpty())
    fun setUrl(c: Context, v: String) = p(c).edit().putString(KEY_URL, normalize(v)).apply()

    fun token(c: Context): String = p(c).getString(KEY_TOKEN, null)?.let { runCatching { decrypt(it) }.getOrNull() }.orEmpty()
    /** 存存取码。手动贴主存取码时 [deviceId] = null（不是配对来的，没有装置 id）。 */
    fun setToken(c: Context, v: String, deviceId: String? = null) {
        val t = v.trim()
        p(c).edit().apply {
            if (t.isEmpty()) remove(KEY_TOKEN) else putString(KEY_TOKEN, encrypt(t))
            if (deviceId == null) remove(KEY_DEVICE_ID) else putString(KEY_DEVICE_ID, deviceId)
        }.apply()
    }
    /** 配对得到的这台装置 id（手动贴主存取码 → null）。 */
    fun deviceId(c: Context): String? = p(c).getString(KEY_DEVICE_ID, null)
    fun hasToken(c: Context) = token(c).isNotEmpty()

    /** 位址和存取码都填了才算设定好。 */
    fun configured(c: Context) = url(c).isNotEmpty() && hasToken(c) && runCatching { URL(url(c)) }.isSuccess

    /** 最后一次从主机得知的模型代号（local-<辨识>|<报告>），主页 / 设定显示用。 */
    fun lastTag(c: Context): String = p(c).getString(KEY_MODEL, null) ?: DEFAULT_TAG
    private fun rememberTag(c: Context, tag: String?) { if (!tag.isNullOrBlank()) p(c).edit().putString(KEY_MODEL, tag).apply() }

    // ---------------- 名称 ----------------

    private val NAMES = mapOf(
        "funasr" to "FunASR", "breeze" to "Breeze-ASR-26", "hakka" to "客語 Whisper".tr, "taiwan-tongues" to "Taiwan Tongues", "text" to "文字".tr,
        "qwen3-8b" to "Qwen3-8B", "sarc-taigi-12b" to "SARC 台語 12B".tr,
    )

    /** 模型代号 → 「大D的主機：FunASR ＋ Qwen3-8B」。认不得的代号照原样（主机上可以自己加模型）。 */
    fun label(tag: String): String =
        "大D的主機：".tr + tag.removePrefix("local-").split('|').filter { it.isNotBlank() }.joinToString(" ＋ ".tr) { NAMES[it] ?: it }

    fun isLocalTag(model: String?) = model?.startsWith("local-") == true

    // ---------------- API ----------------

    data class Health(
        val ok: Boolean, val asr: String?, val llm: String?, val model: String?,
        val state: String?, val error: String?, val busy: Boolean,
        /** 这台是不是主机的管理者（旧主机没有这栏 → 当成是，跟以前一样什么都能改）。 */
        val admin: Boolean = true,
        /** 主机有工作伫列（POST /v1/jobs）；旧主机 → false，改用 /v1/process。 */
        val jobs: Boolean = false,
        /** 现在送的话前面有几笔、大约要等几秒。 */
        val ahead: Int = 0, val waitSec: Int = 0,
        /** 主机能辨识的语言（health 的 langs；旧主机没有这栏 → 国语、台语、客语）。 */
        val langs: Set<String> = BASE_LANGS,
    )
    val BASE_LANGS = setOf("zh", "nan", "hak")
    data class Option(val id: String, val label: String)
    data class Models(
        val asrId: String?, val llmId: String?, val state: String?, val model: String?,
        val asrOptions: List<Option>, val llmOptions: List<Option>,
        val admin: Boolean = true,
    )

    private fun open(c: Context, base: String, token: String, path: String, method: String, readTimeoutMs: Int): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer ${token}")
        }

    private fun read(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        return code to body
    }

    private fun errorOf(code: Int, body: String): String = when (code) {
        401 -> "大D的主機不認得這台手機（配對可能被取消了）：請到設定按「取消配對」再重新配對".tr
        429 -> "存取碼錯太多次，主機暫時擋掉這支手機（一小時後或重開主機服務就解除）".tr
        else -> runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "大D的主機回應錯誤 {0}".trf(code)
    }

    private fun unreachable(e: Exception): AiException {
        android.util.Log.w("LocalHost", "unreachable: ${e.javaClass.simpleName}: ${e.message}")
        return AiException("連不到大D的主機：請確認主機開著、服務在跑、手機連得到主機（同一個 Wi-Fi 或固定 IP）".tr)
    }

    /** 测试连线 / 送出前的检查（5 秒）。[url]、[token] 给设定页测还没存的值。 */
    fun health(c: Context, url: String = url(c), token: String = token(c)): Health {
        val base = normalize(url)
        if (base.isEmpty() || runCatching { URL(base) }.isFailure) throw AiException("位址格式不對，例如 http://192.168.1.20:8765".tr)
        val (code, body) = try {
            val conn = open(c, base, token, "/v1/health", "GET", 5_000)
            try { read(conn) } finally { conn.disconnect() }
        } catch (e: Exception) { throw unreachable(e) }
        if (code != 200) throw AiException(errorOf(code, body))
        val j = runCatching { JSONObject(body) }.getOrElse { throw AiException("大D的主機回應看不懂".tr) }
        return Health(
            ok = j.optBoolean("ok"), asr = j.optString("asr").ifBlank { null }, llm = j.optString("llm").ifBlank { null },
            model = j.optString("model").ifBlank { null }, state = j.optString("state").ifBlank { null },
            error = j.optString("error").ifBlank { null }, busy = j.optBoolean("busy"),
            admin = j.optBoolean("admin", true), jobs = j.optBoolean("jobs"),
            ahead = j.optInt("ahead"), waitSec = j.optInt("wait_sec"),
            langs = j.optJSONArray("langs")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }?.takeIf { it.isNotEmpty() } ?: BASE_LANGS,
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
            admin = j.optBoolean("admin", true),
        ).also { if (it.state != "loading") rememberTag(c, it.model) }
    }

    /** 连不到主机（跟「主机回了错误」分开：QR 配对时连不到对外位址要改试区网位址）。 */
    class Unreachable(message: String) : Exception(message)

    /** 送 JSON（[token] 空 = 不带存取码，配对用）。连不到 → [Unreachable]。 */
    private fun postJson(c: Context, base: String, token: String, path: String, json: JSONObject): Pair<Int, String> = try {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer ${token}")
        }
        try {
            conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            read(conn)
        } finally { conn.disconnect() }
    } catch (e: Exception) { throw Unreachable(unreachable(e).message!!) }

    /** 换模型：主机存起来并在背景载入（第一次要下载），之后用 [models] 看 state 何时变回 ready。 */
    fun select(c: Context, asr: String, llm: String) {
        val (code, body) = try { postJson(c, url(c), token(c), "/v1/config", JSONObject().put("asr", asr).put("llm", llm)) }
            catch (e: Unreachable) { throw AiException(e.message!!) }
        if (code != 200 && code != 202) throw AiException(errorOf(code, body))
    }

    // ---------------- 配对（不用手动贴存取码） ----------------

    private fun jsonError(body: String, fallback: String): String =
        runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    private fun authedPost(c: Context, path: String, json: JSONObject): Pair<Int, String> =
        try { postJson(c, url(c), token(c), path, json) } catch (e: Unreachable) { throw AiException(e.message!!) }

    /**
     * 用配对密码或 QR 配对码向主机换这台装置自己的存取码，连同位址一起存起来。
     * 主机回的错误（密码不对、QR 过期、错太多次）照原文给使用者看。连不到 → [Unreachable]。
     */
    fun pair(c: Context, url: String, password: String? = null, code: String? = null) {
        val base = normalize(url)
        if (base.isEmpty() || runCatching { URL(base) }.isFailure) throw AiException("位址格式不對，例如 http://192.168.1.20:8765".tr)
        val json = JSONObject().put("name", deviceLabel(c))
        if (password != null) json.put("password", password)
        if (code != null) json.put("code", code)
        val (status, body) = postJson(c, base, "", "/v1/pair", json)
        if (status != 200) throw AiException(jsonError(body, "配對失敗（{0}）".trf(status)))
        val j = runCatching { JSONObject(body) }.getOrElse { throw AiException("大D的主機回應看不懂".tr) }
        val token = j.optString("token").takeIf { it.isNotBlank() } ?: throw AiException("大D的主機沒有給存取碼".tr)
        setUrl(c, base)
        setToken(c, token, j.optString("id").ifBlank { null })
    }

    data class PairLink(val urls: List<String>, val code: String)

    /** 解析 dylan://pair?url=…&lan=…&code=…；不是配对连结 → null。 */
    fun parseLink(link: String): PairLink? {
        val uri = runCatching { java.net.URI(link.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "dylan" || uri.host != "pair") return null
        val q = (uri.rawQuery ?: "").split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
        }.toMap()
        val code = q["code"]?.takeIf { it.isNotBlank() } ?: return null
        return PairLink(listOfNotNull(q["url"], q["lan"]).filter { it.isNotBlank() }.distinct(), code)
    }

    /** 扫到的 QR / 点到的连结：先试对外位址，连不到再试区网位址（配对码还没用掉）。回传用的位址。 */
    fun pairLink(c: Context, link: String): String {
        val l = parseLink(link) ?: throw AiException("這不是大D的主機的配對 QR 碼".tr)
        if (l.urls.isEmpty()) throw AiException("QR 碼裡沒有位址".tr)
        var last = "連不到大D的主機".tr
        for (u in l.urls) {
            try {
                pair(c, u, code = l.code)
                return normalize(u)
            } catch (e: Unreachable) {
                last = e.message ?: last
            }
        }
        throw AiException(last)
    }

    data class Device(val id: String, val name: String, val created: Long, val lastSeen: Long?, val via: String?, val current: Boolean,
                      val admin: Boolean = false)
    /** [admin] = 这台是不是管理者（一般装置只看得到自己，不能改密码、不能取消别台）。 */
    data class Devices(val devices: List<Device>, val passwordSet: Boolean, val admin: Boolean = true)

    fun devices(c: Context): Devices {
        val (code, body) = try {
            val conn = open(c, url(c), token(c), "/v1/devices", "GET", 10_000)
            try { read(conn) } finally { conn.disconnect() }
        } catch (e: Exception) { throw unreachable(e) }
        if (code != 200) throw AiException(errorOf(code, body))
        val j = JSONObject(body)
        val a = j.optJSONArray("devices") ?: JSONArray()
        return Devices((0 until a.length()).map { i ->
            a.getJSONObject(i).let {
                Device(it.optString("id"), it.optString("name"), it.optLong("created"),
                    if (it.isNull("last_seen")) null else it.optLong("last_seen"), it.optString("via").ifBlank { null }, it.optBoolean("current"),
                    it.optBoolean("admin"))
            }
        }, j.optBoolean("password_set"), j.optBoolean("admin", true))
    }

    /** 指定 / 取消别台当管理者（只有管理者能做；至少要留一台管理者，主机会挡）。 */
    fun setAdmin(c: Context, id: String, admin: Boolean) {
        val (code, body) = authedPost(c, "/v1/devices/admin", JSONObject().put("id", id).put("admin", admin))
        if (code != 200) throw AiException(if (code == 401) errorOf(code, body) else jsonError(body, errorOf(code, body)))
    }

    fun revoke(c: Context, id: String) {
        val (code, body) = authedPost(c, "/v1/devices/revoke", JSONObject().put("id", id))
        if (code != 200) throw AiException(if (code == 401) errorOf(code, body) else jsonError(body, errorOf(code, body)))
    }

    /** 设定 / 更换配对密码（至少 8 个字，主机检查）。 */
    fun setPassword(c: Context, password: String) {
        val (code, body) = authedPost(c, "/v1/password", JSONObject().put("password", password))
        if (code != 200) throw AiException(if (code == 401) errorOf(code, body) else jsonError(body, errorOf(code, body)))
    }

    /** 取消这台的配对：通知主机作废这台的存取码（连不到也照样清掉本机的）。 */
    fun unpair(c: Context) {
        deviceId(c)?.let { id -> runCatching { revoke(c, id) } }
        setToken(c, "")
    }

    // ---------------- 装置名字（给主机的装置清单看） ----------------

    /** 「Dylan 的 Fold5（Galaxy Z Fold5）」；读不到名字就只有型号。 */
    fun deviceLabel(c: Context): String {
        val model = modelName(android.os.Build.MODEL)
        val name = runCatching {
            android.provider.Settings.Global.getString(c.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { android.provider.Settings.Secure.getString(c.contentResolver, "bluetooth_name") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        return if (name == null || name == model || name == android.os.Build.MODEL) model else "{0}（{1}）".trf(name, model)
    }

    /** 型号代码 → 好读的名字（常见的三星机型；认不得照原样）。 */
    fun modelName(code: String): String {
        val c = code.uppercase().removePrefix("SAMSUNG-")
        val table = listOf(
            "SM-F946" to "Galaxy Z Fold5", "SM-F956" to "Galaxy Z Fold6", "SM-F966" to "Galaxy Z Fold7",
            "SM-F936" to "Galaxy Z Fold4", "SM-F731" to "Galaxy Z Flip5", "SM-F741" to "Galaxy Z Flip6", "SM-F766" to "Galaxy Z Flip7",
            "SM-S928" to "Galaxy S24 Ultra", "SM-S926" to "Galaxy S24+", "SM-S921" to "Galaxy S24",
            "SM-S938" to "Galaxy S25 Ultra", "SM-S936" to "Galaxy S25+", "SM-S931" to "Galaxy S25",
            "SM-S918" to "Galaxy S23 Ultra", "SM-S916" to "Galaxy S23+", "SM-S911" to "Galaxy S23",
        )
        return table.firstOrNull { c.startsWith(it.first) }?.second ?: code
    }


    data class Result(val text: String, val model: String)

    // ---------------- 工作伫列（新版主机：多人共用时排队、轮流） ----------------

    /** 主机上一笔工作的状态。state = queued / running / done / error。 */
    data class Job(
        val id: String, val state: String, val ahead: Int = 0, val waitSec: Int = 0,
        val text: String? = null, val model: String? = null, val error: String? = null,
        /** 主机上的录音（含转档）已经删掉，只留报告文字。 */
        val purged: Boolean = false,
    )

    /** 主机找不到这笔工作（过期被删、被取消、主机换了）→ 要重新送。 */
    class JobGone : Exception()

    private fun parseJob(body: String): Job {
        val j = runCatching { JSONObject(body.trim()) }.getOrElse { throw AiException("大D的主機回應看不懂：{0}".trf(body.trim().take(120))) }
        return Job(
            id = j.optString("id"), state = j.optString("state"), ahead = j.optInt("ahead"), waitSec = j.optInt("wait_sec"),
            text = j.optString("text").ifBlank { null }, model = j.optString("model").ifBlank { null },
            error = j.optString("error").ifBlank { null }, purged = j.optBoolean("purged"),
        )
    }

    /** 录音 / 文字的上传连线（/v1/jobs 跟旧的 /v1/process 一样的标头）。 */
    private fun uploadConn(c: Context, path: String, kind: String, recordedAt: String, durationSec: Double,
                           audio: File?, bytes: ByteArray?, lang: String?, dialect: String?, readTimeoutMs: Int): HttpURLConnection =
        open(c, url(c), token(c), path, "POST", readTimeoutMs).apply {
            connectTimeout = 15_000
            doOutput = true
            setRequestProperty("X-Kind", kind)
            setRequestProperty("X-Recorded-At", recordedAt.filter { it.code in 32..126 })
            setRequestProperty("X-Duration", durationSec.toInt().toString())
            // 报告语言（zh / en / ko）：新主机照这个写报告，旧主机不认得就照旧中文
            setRequestProperty("X-Report-Lang", com.example.wristai.ReportLang.active.code)
            // 领域专家模式：auto / off / legal / medical …（旧主机不认得就照旧）
            setRequestProperty("X-Domain", com.example.wristai.ReportDomain.active)
            // 饮食：这一餐所在的国家（主机照当地份量估算；旧主机不认得就照旧台湾）
            if (kind == "food") setRequestProperty("X-Food-Country", com.example.wristai.FoodRegion.active)
            if (bytes != null) {
                setRequestProperty("X-Input", "text")
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                setFixedLengthStreamingMode(bytes.size)
            } else {
                // 国语 → FunASR；台语・国台语 → Breeze-ASR-26；客语 → Taiwan Tongues / 阳明交大客语 Whisper（加腔调）
                lang?.let { setRequestProperty("X-Lang", it) }
                dialect?.let { setRequestProperty("X-Dialect", it) }
                setRequestProperty("Content-Type", "audio/m4a")
                setFixedLengthStreamingMode(audio!!.length())
            }
        }

    /** 送进主机的伫列：传完马上回来（不等处理），拿到工作编号和前面还有几笔。 */
    fun submit(c: Context, kind: String, recordedAt: String, durationSec: Double, audio: File? = null, text: String? = null,
               lang: String? = null, dialect: String? = null): Job {
        val bytes = text?.toByteArray(Charsets.UTF_8)
        val (code, body) = try {
            val conn = uploadConn(c, "/v1/jobs", kind, recordedAt, durationSec, audio, bytes, lang, dialect, 60_000)
            try {
                conn.outputStream.use { out -> if (bytes != null) out.write(bytes) else audio!!.inputStream().use { it.copyTo(out, 64 * 1024) } }
                read(conn)
            } finally { conn.disconnect() }
        } catch (e: AiException) { throw e } catch (e: Exception) { throw unreachable(e) }
        if (code != 202 && code != 200) throw AiException(errorOf(code, body))
        return parseJob(body).also { if (it.id.isBlank()) throw AiException("大D的主機沒有給工作編號".tr) }
    }

    /** 查一笔工作。找不到 → [JobGone]；连不到 → [Unreachable]（暂时的，等一下再查）。 */
    fun job(c: Context, id: String): Job {
        val (code, body) = try {
            val conn = open(c, url(c), token(c), "/v1/jobs/${id}", "GET", 15_000)
            try { read(conn) } finally { conn.disconnect() }
        } catch (e: Exception) { throw Unreachable(unreachable(e).message!!) }
        if (code == 404) throw JobGone()
        if (code != 200) throw AiException(errorOf(code, body))
        return parseJob(body).also { j -> if (j.state == "done") rememberTag(c, j.model) }
    }

    /** 删掉主机上的这笔（拿到结果后、或使用者中断时）。回传主机上是不是已经没有了（找不到也算）。
     *  失败不要紧，主机 24 小时后会自己删。 */
    fun deleteJob(c: Context, id: String): Boolean = runCatching {
        val conn = open(c, url(c), token(c), "/v1/jobs/${id}", "DELETE", 10_000)
        try { read(conn).first } finally { conn.disconnect() }
    }.getOrNull().let { it == 200 || it == 404 }

    /** 「前面還有 2 筆，約 6 分鐘」：给处理步骤和手表看。 */
    fun queueText(ahead: Int, waitSec: Int): String {
        val wait = when {
            waitSec < 60 -> "不到 1 分鐘".tr
            waitSec < 3600 -> "約 {0} 分鐘".trf((waitSec + 59) / 60)
            else -> "約 {0} 小時 {1} 分".trf(waitSec / 3600, (waitSec % 3600 + 59) / 60)
        }
        return if (ahead <= 0) "輪到你了，{0}".trf(wait) else "排隊中：前面還有 {0} 筆，{1}".trf(ahead, wait)
    }

    /**
     * 送录音（[audio]）或文字（[text]）给主机产生报告。[kind] = meeting / food。
     * [recordedAt] 只能是 ASCII（例 2026/11/23 21:20）。会等很久：主机每 20 秒送一个空白防逾时。
     */
    fun process(c: Context, kind: String, recordedAt: String, durationSec: Double, audio: File? = null, text: String? = null,
                lang: String? = null, dialect: String? = null,
                onUploaded: () -> Unit = {}): Result {
        val bytes = text?.toByteArray(Charsets.UTF_8)
        val (code, body) = try {
            val conn = uploadConn(c, "/v1/process", kind, recordedAt, durationSec, audio, bytes, lang, dialect, 3 * 60_000)
            try {
                conn.outputStream.use { out -> if (bytes != null) out.write(bytes) else audio!!.inputStream().use { it.copyTo(out, 64 * 1024) } }
                onUploaded()
                read(conn)
            } finally { conn.disconnect() }
        } catch (e: AiException) { throw e } catch (e: Exception) { throw unreachable(e) }
        if (code != 200) throw AiException(errorOf(code, body))
        val j = runCatching { JSONObject(body.trim()) }.getOrElse { throw AiException("大D的主機回應看不懂：{0}".trf(body.trim().take(120))) }
        j.optString("error").takeIf { it.isNotBlank() }?.let { throw AiException(it) }
        val out = j.optString("text").takeIf { it.isNotBlank() } ?: throw AiException("大D的主機沒有回傳報告".tr)
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
