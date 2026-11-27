package com.example.wristai

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 遠距對話（兩地即時翻譯）：兩支手機透過大D的主機（local-ai/talk.py）轉送文字。
 *
 * - 自己說的話：用即時翻譯同一套辨識（[LiveCaptions]，麦克风模式、照停顿切句、不要提示声），
 *   每一句（含还在说的暂时句）送原文＋语言到房间。
 * - 对方说的话：在自己手机上用 ML Kit 翻成自己的语言（主机不翻译、不存档），可以自动朗读（戴耳机比较好）。
 * - 开房：要跟大D的主机配对过；对方用邀请连结（dylan://talk?url=…&lan=…&room=…&code=…）加入，不用配对。
 */
object RemoteTalk {
    private const val TAG = "RemoteTalk"

    data class Member(val id: String, val name: String, val lang: String, val owner: Boolean)

    /** 对话的一句：[mine] = 自己说的；[translated] = 翻成自己语言的（自己说的那句显示对方看到的语言）。 */
    data class Line(val key: String, val from: String, val name: String, val original: String, val lang: String,
                    val translated: String?, val mine: Boolean, val final: Boolean, val system: String? = null)

    data class State(
        val room: String? = null,
        val link: String? = null,
        val me: String? = null,
        val members: List<Member> = emptyList(),
        val lines: List<Line> = emptyList(),
        val connected: Boolean = false,
        val ended: Boolean = false,
        val error: String? = null,
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 收到的邀请连结（[MainActivity] 收到 dylan://talk 时设；对话页看到就问要不要加入）。 */
    val pendingInvite = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var base: String? = null
    private var token: String? = null
    private var pollJob: Job? = null
    private var sendJob: Job? = null
    private var appContext: Context? = null
    private val translators = mutableMapOf<Pair<String, String>, Translator>()
    /** 已经送过的自己那几句：LiveCaptions 的行号 → 最后送出的文字与是否定稿。 */
    private val sent = mutableMapOf<Long, Pair<String, Boolean>>()
    private val sentAt = mutableMapOf<Long, Long>()

    private fun update(f: (State) -> State) { _state.value = f(_state.value) }

    /** 我说的语言（辨识代码）与我看的语言（ML Kit 代码）。 */
    private fun mySource(c: Context) = Settings.translateSource(c)
    private fun myReading(c: Context) = TranslateLangs.mlkitOf(mySource(c)) ?: "zh"

    // ---------- HTTP ----------
    private fun http(method: String, url: String, body: JSONObject? = null, auth: String? = null, talkToken: String? = null,
                     readMs: Int = 15_000): Pair<Int, JSONObject> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 6_000
            readTimeout = readMs
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            auth?.let { setRequestProperty("Authorization", "Bearer $it") }
            talkToken?.let { setRequestProperty("X-Talk-Token", it) }
            if (body != null) { doOutput = true; outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to (runCatching { JSONObject(text) }.getOrNull() ?: JSONObject())
        } finally { conn.disconnect() }
    }

    /** 主机回的错误 → 给人看的文字。旧版主机没有遠距對話（回 404「not found」）→ 请在 Mac 上更新。 */
    private fun errorText(code: Int, j: JSONObject): String {
        val e = j.optString("error")
        if (code == 404 && (e.isBlank() || e.equals("not found", true))) return "大D的主機還沒有遠距對話功能：請在 Mac 上重新部署大D的主機（更新到最新版）".tr
        return e.ifBlank { "連線失敗（{0}）".trf(code) }
    }

    // ---------- 开房 / 加入 / 离开 ----------
    /** 开一间房（要配对过大D的主机）。 */
    fun create(c: Context, name: String) {
        val ctx = c.applicationContext; appContext = ctx
        if (!com.example.wristai.ai.LocalHost.configured(ctx)) {
            update { it.copy(error = "開遠距對話要先跟大D的主機配對（設定 → 大D的主機）".tr) }; return
        }
        update { State(busy = true) }
        scope.launch {
            val url = com.example.wristai.ai.LocalHost.url(ctx).trimEnd('/')
            val res = withContext(Dispatchers.IO) {
                runCatching { http("POST", "$url/v1/talk", JSONObject().put("name", name).put("lang", mySource(ctx)), auth = com.example.wristai.ai.LocalHost.token(ctx)) }
            }
            res.onSuccess { (code, j) ->
                if (code != 200) { update { State(error = errorText(code, j)) }; return@onSuccess }
                base = url; token = j.optString("token")
                update { State(room = j.optString("room"), link = j.optString("link"), me = j.optString("member"), connected = true) }
                startLoops(ctx)
            }.onFailure { e -> update { State(error = "連不到大D的主機：{0}".trf(e.message)) } }
        }
    }

    /** 用邀请连结加入（先试家里网路的位址，再试外面的）。 */
    fun join(c: Context, link: String, name: String) {
        val ctx = c.applicationContext; appContext = ctx
        val u = runCatching { Uri.parse(link.trim()) }.getOrNull()
        val room = u?.getQueryParameter("room"); val code = u?.getQueryParameter("code")
        val urls = listOfNotNull(u?.getQueryParameter("lan"), u?.getQueryParameter("url")).map { it.trimEnd('/') }.filter { it.startsWith("http") }.distinct()
        if (room.isNullOrBlank() || code.isNullOrBlank() || urls.isEmpty()) { update { State(error = "邀請連結看不懂".tr) }; return }
        update { State(busy = true) }
        scope.launch {
            var last: String? = null
            for (url in urls) {
                val res = withContext(Dispatchers.IO) {
                    runCatching { http("POST", "$url/v1/talk/join", JSONObject().put("room", room).put("code", code).put("name", name).put("lang", mySource(ctx))) }
                }
                val (status, j) = res.getOrElse { last = it.message; continue }
                if (status != 200) { update { State(error = errorText(status, j)) }; return@launch }
                base = url; token = j.optString("token")
                update { State(room = room, me = j.optString("member"), connected = true) }
                startLoops(ctx)
                return@launch
            }
            update { State(error = "連不到對方的主機：{0}".trf(last)) }
        }
    }

    fun leave() {
        val b = base; val t = token; val r = _state.value.room
        pollJob?.cancel(); sendJob?.cancel()
        if (LiveCaptions.state.value.running) LiveCaptions.stop()
        if (b != null && t != null && r != null) scope.launch(Dispatchers.IO) { runCatching { http("DELETE", "$b/v1/talk/$r", talkToken = t) } }
        base = null; token = null; sent.clear(); sentAt.clear()
        update { it.copy(connected = false, ended = true) }
    }

    fun reset() { if (_state.value.connected) leave(); _state.value = State() }

    // ---------- 说话（麦克风开关） ----------
    /** 开始 / 暂停听自己说话（用 LiveCaptions；翻成对方的语言，自己的画面也看得到对方会看到什么）。 */
    fun toggleMic(c: Context) {
        if (LiveCaptions.state.value.running) { LiveCaptions.stop(); return }
        val other = _state.value.members.firstOrNull { it.id != _state.value.me }
        val target = other?.let { TranslateLangs.mlkitOf(it.lang) }?.takeIf { it != myReading(c) }
            ?: if (myReading(c) == "en") "zh" else "en"
        LiveCaptions.start(c, mySource(c), target, fromRecording = false)
    }

    // ---------- 背景回圈 ----------
    private fun startLoops(c: Context) {
        pollJob?.cancel(); sendJob?.cancel()
        // 收：长轮询（最多等 25 秒），断线就过几秒再连
        pollJob = scope.launch {
            var after = 0
            var fails = 0
            while (isActive) {
                val b = base ?: break; val t = token ?: break; val r = _state.value.room ?: break
                val res = withContext(Dispatchers.IO) { runCatching { http("GET", "$b/v1/talk/$r?after=$after&wait=25", talkToken = t, readMs = 35_000) } }
                val (code, j) = res.getOrElse {
                    fails++; update { s -> s.copy(error = if (fails >= 2) "連線中斷，重新連線中…".tr else s.error) }
                    delay(minOf(10_000L, 1_500L * fails)); null
                } ?: continue
                if (code != 200) {
                    update { s -> s.copy(error = errorText(code, j), connected = code != 404 && code != 401 && s.connected) }
                    if (code == 404 || code == 401) break
                    delay(3_000); continue
                }
                fails = 0
                after = j.optInt("seq", after)
                handle(c, j)
                if (j.optBoolean("ended")) { update { s -> s.copy(ended = true, connected = false) }; break }
            }
        }
        // 送：看 LiveCaptions 的句子，有新的或变了就送（暂时句最多每 0.6 秒一次）
        sendJob = scope.launch {
            LiveCaptions.state.collect { s ->
                for (l in s.lines.takeLast(6)) {
                    val prev = sent[l.id]
                    if (prev != null && prev.first == l.original && prev.second == !l.partial) continue
                    // 暂时句：同一句最多每 0.6 秒送一次（说完的定稿一定送）
                    val now = System.currentTimeMillis()
                    if (l.partial && now - (sentAt[l.id] ?: 0L) < 600) continue
                    sentAt[l.id] = now
                    sent[l.id] = l.original to !l.partial
                    post(l.id, l.original, s.source, !l.partial)
                    showMine(l.id, l.original, s.source, l.translated, !l.partial)
                }
            }
        }
    }

    private fun post(id: Long, text: String, lang: String, final: Boolean) {
        val b = base ?: return; val t = token ?: return; val r = _state.value.room ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { http("POST", "$b/v1/talk/$r/msg", JSONObject().put("line", "m$id").put("text", text).put("lang", lang).put("final", final), talkToken = t) }
                .onFailure { Log.w(TAG, "send failed: ${it.message}") }
        }
    }

    private fun showMine(id: Long, text: String, lang: String, translated: String?, final: Boolean) {
        val me = _state.value.me ?: return
        upsert(Line("$me:m$id", me, "", text, lang, translated, mine = true, final = final))
    }

    private fun upsert(line: Line) = update { s ->
        val i = s.lines.indexOfFirst { it.key == line.key }
        val lines = if (i >= 0) s.lines.toMutableList().also { it[i] = line } else s.lines + line
        s.copy(lines = lines.takeLast(300))
    }

    private suspend fun handle(c: Context, j: JSONObject) {
        val members = j.optJSONArray("members")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { m -> Member(m.optString("id"), m.optString("name"), m.optString("lang"), m.optBoolean("owner")) }
        } ?: _state.value.members
        update { it.copy(members = members, me = j.optString("me").ifBlank { it.me }, connected = true, error = null) }
        val me = _state.value.me
        val msgs = j.optJSONArray("msgs") ?: return
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            val from = m.optString("from")
            if (m.has("sys")) {
                if (from != me) upsert(Line("sys${m.optInt("seq")}", from, m.optString("name"), "", "", null, false, true,
                    system = if (m.optString("sys") == "join") "{0} 加入了對話".trf(m.optString("name")) else "{0} 離開了對話".trf(m.optString("name"))))
                continue
            }
            if (from == me) continue
            val key = "$from:${m.optString("line")}"
            val text = m.optString("text"); val lang = m.optString("lang"); val final = m.optBoolean("final")
            upsert(Line(key, from, m.optString("name"), text, lang, _state.value.lines.firstOrNull { it.key == key }?.translated, false, final))
            val tr = translate(c, text, lang)
            if (tr != null) {
                upsert(Line(key, from, m.optString("name"), text, lang, tr, false, final))
                if (final && Settings.autoSpeak(c)) Speaker.speak(c, tr, myReading(c))
                if (final && Settings.captionToWatch(c)) WatchNotifier.sendCaption(c, tr)
            }
        }
    }

    /** 对方的语言 → 我的语言（同语言就不翻）。第一次用到某个语言组合时下载 ML Kit 模型。 */
    private suspend fun translate(c: Context, text: String, lang: String): String? {
        val src = TranslateLangs.mlkitOf(lang) ?: return null
        val dst = myReading(c)
        if (src == dst || text.isBlank()) return text
        val t = translators.getOrPut(src to dst) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
        }
        return runCatching {
            val cond = DownloadConditions.Builder().apply { if (Settings.translateModelsWifiOnly(c)) requireWifi() }.build()
            t.downloadModelIfNeeded(cond).await()
            val out = t.translate(text).await()
            if (dst == "zh") TraditionalChinese.convert(out) else out
        }.getOrNull()
    }
}
