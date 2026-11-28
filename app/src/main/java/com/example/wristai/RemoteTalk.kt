package com.example.wristai

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.voiceai.shared.AudioSpec
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 遠距對話（兩地即時翻譯）：兩支手機透過大D的主機（local-ai/talk.py）轉送文字與每句的原聲。
 *
 * - 自己說的話：[TalkAudio.Mic] 收音，餵手機辨識（[LiveCaptions]，照停頓切句、不要提示聲）；
 *   每句（含還在說的暫時句）送原文＋語言，說完的那句連同原聲片段一起送。
 * - 對方的話：在自己手機上翻成自己的語言（ML Kit），先播對方的原聲、再唸翻譯（[TalkAudio.Player]），像有口譯在旁邊。
 * - 通話對象：開房、加入後記住對方（[TalkStore]），之後直接打給他；App 開著時每 20 秒回報上線、看誰在線上、誰打來。
 * - 每次對話存在手機上（文字＋雙方原聲），可以回播。
 */
object RemoteTalk {
    private const val TAG = "RemoteTalk"
    private const val PRESENCE_MS = 20_000L

    data class Member(val id: String, val name: String, val lang: String, val owner: Boolean, val contact: String?)

    /** 對話的一句：[mine] = 自己說的；[translated] = 翻成自己語言的（自己說的那句顯示對方看到的語言）。 */
    data class Line(val key: String, val from: String, val name: String, val original: String, val lang: String,
                    val translated: String?, val mine: Boolean, val final: Boolean, val system: String? = null, val audio: String? = null)

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
        /** 打出去、等對方接（對方加入前顯示「撥號中」）。 */
        val calling: String? = null,
    )

    /** 來電 / 未接：[live] = 還可以接（2 分鐘內、房間還在）。 */
    data class Call(val base: String, val room: String, val code: String, val from: String, val name: String, val lang: String,
                    val live: Boolean, val at: Long)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 上線中的通話對象：「id@主機」→ 在線上。 */
    private val _online = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val online: StateFlow<Map<String, Boolean>> = _online.asStateFlow()
    private val _calls = MutableStateFlow<List<Call>>(emptyList())
    val calls: StateFlow<List<Call>> = _calls.asStateFlow()

    /** 收到的邀請連結（[MainActivity] 收到 dylan://talk 時設；對話頁看到就問要不要加入）。 */
    val pendingInvite = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** 這次連上的位址（家裡網路或外面）。 */
    private var base: String? = null
    /** 這台主機的代表位址（身分、通話對象都記在這個名下）。 */
    private var canon: String? = null
    private var lan: String? = null
    private var token: String? = null
    private var pollJob: Job? = null
    private var sendJob: Job? = null
    private var presenceJob: Job? = null
    private val translators = mutableMapOf<Pair<String, String>, Translator>()
    /** 已經送過的自己那幾句：LiveCaptions 的行號 → 最後送出的文字與是否定稿。 */
    private val sent = mutableMapOf<Long, Pair<String, Boolean>>()
    private val sentAt = mutableMapOf<Long, Long>()
    /** 自己每一句開始的樣本位置（剪原聲用）。 */
    private val lineStart = mutableMapOf<Long, Long>()
    private var lastEnd = 0L
    /** 這次對話的記錄（存檔用）。 */
    private var talk: TalkStore.Talk? = null

    private fun update(f: (State) -> State) { _state.value = f(_state.value) }
    private fun keyOf(id: String, b: String) = "$id@$b"
    fun isOnline(k: TalkStore.Contact) = _online.value[keyOf(k.id, k.base)] == true

    /** 我說的語言（辨識代碼）與我看的語言（ML Kit 代碼）。 */
    private fun mySource(c: Context) = Settings.translateSource(c)
    private fun myReading(c: Context) = TranslateLangs.mlkitOf(mySource(c)) ?: "zh"
    private fun myName(c: Context) = c.getSharedPreferences("wristai_settings", 0).getString("talk_name", null) ?: Build.MODEL.take(20)

    /** 播對方的原聲 / 唸翻譯（設定）。 */
    fun playOriginal(c: Context) = c.getSharedPreferences("wristai_settings", 0).getBoolean("talk_play_original", true)
    fun setPlayOriginal(c: Context, v: Boolean) = c.getSharedPreferences("wristai_settings", 0).edit().putBoolean("talk_play_original", v).apply()
    fun speakTranslation(c: Context) = c.getSharedPreferences("wristai_settings", 0).getBoolean("talk_speak", true)
    fun setSpeakTranslation(c: Context, v: Boolean) = c.getSharedPreferences("wristai_settings", 0).edit().putBoolean("talk_speak", v).apply()

    // ---------- HTTP ----------
    private fun http(method: String, url: String, body: JSONObject? = null, auth: String? = null, talkToken: String? = null,
                     contactToken: String? = null, readMs: Int = 15_000, raw: ByteArray? = null): Pair<Int, JSONObject> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 6_000
            readTimeout = readMs
            setRequestProperty("Content-Type", if (raw != null) "audio/mp4" else "application/json; charset=utf-8")
            auth?.let { setRequestProperty("Authorization", "Bearer $it") }
            talkToken?.let { setRequestProperty("X-Talk-Token", it) }
            contactToken?.let { setRequestProperty("X-Contact-Token", it) }
            val bytes = raw ?: body?.toString()?.toByteArray(Charsets.UTF_8)
            if (bytes != null) { doOutput = true; setFixedLengthStreamingMode(bytes.size); outputStream.use { it.write(bytes) } }
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to (runCatching { JSONObject(text) }.getOrNull() ?: JSONObject())
        } finally { conn.disconnect() }
    }

    private fun download(url: String, talkToken: String, out: File): Boolean = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000; readTimeout = 20_000; setRequestProperty("X-Talk-Token", talkToken)
        }
        try {
            if (conn.responseCode != 200) return false
            out.parentFile?.mkdirs()
            conn.inputStream.use { i -> out.outputStream().use { i.copyTo(it) } }
            true
        } finally { conn.disconnect() }
    }.getOrDefault(false)

    /** 主機回的錯誤 → 給人看的文字。舊版主機沒有遠距對話（回 404「not found」）→ 請在 Mac 上更新。 */
    private fun errorText(code: Int, j: JSONObject): String {
        val e = j.optString("error")
        if (code == 404 && (e.isBlank() || e.equals("not found", true))) return "大D的主機還沒有遠距對話功能：請在 Mac 上重新部署大D的主機（更新到最新版）".tr
        return e.ifBlank { "連線失敗（{0}）".trf(code) }
    }

    /** 主機回的身分 → 存起來（token 只有第一次給）。 */
    private fun keepIdentity(c: Context, b: String, j: JSONObject) {
        val ct = j.optJSONObject("contact") ?: return
        TalkStore.saveIdentity(c, b, ct.optString("id"), ct.optString("token").ifBlank { null })
    }

    /** 進房：[b] 代表位址、[connected] 實際連上的位址。 */
    private fun enter(c: Context, b: String, l: String?, j: JSONObject, link: String?, calling: String? = null, connected: String = b) {
        canon = b; base = connected; lan = l; token = j.optString("token")
        keepIdentity(c, b, j)
        val room = j.optString("room")
        talk = TalkStore.Talk(room + "_" + System.currentTimeMillis(), System.currentTimeMillis(), 0, emptyList(), emptyList())
        update { State(room = room, link = link, me = j.optString("member"), connected = true, calling = calling) }
        startLoops(c)
    }

    // ---------- 開房 / 加入 / 打給對象 / 離開 ----------
    /** 開一間房（要配對過大D的主機）。 */
    fun create(c: Context) {
        val ctx = c.applicationContext
        if (!com.example.wristai.ai.LocalHost.configured(ctx)) {
            update { State(error = "開遠距對話要先跟大D的主機配對（設定 → 大D的主機）".tr) }; return
        }
        update { State(busy = true) }
        scope.launch {
            val url = com.example.wristai.ai.LocalHost.url(ctx).trimEnd('/')
            val body = JSONObject().put("name", myName(ctx)).put("lang", mySource(ctx))
            TalkStore.identity(ctx, url)?.let { body.put("contact", it.token) }
            val res = withContext(Dispatchers.IO) {
                runCatching { http("POST", "$url/v1/talk", body, auth = com.example.wristai.ai.LocalHost.token(ctx)) }
            }
            res.onSuccess { (code, j) ->
                if (code != 200) { update { State(error = errorText(code, j)) }; return@onSuccess }
                val link = j.optString("link")
                enter(ctx, url, Uri.parse(link).getQueryParameter("lan"), j, link)
            }.onFailure { e -> update { State(error = "連不到大D的主機：{0}".trf(e.message)) } }
        }
    }

    /** 用邀請連結加入（先試家裡網路的位址，再試外面的）。 */
    fun join(c: Context, link: String) {
        val u = runCatching { Uri.parse(link.trim()) }.getOrNull()
        val room = u?.getQueryParameter("room"); val code = u?.getQueryParameter("code")
        val url = u?.getQueryParameter("url")?.trimEnd('/'); val l = u?.getQueryParameter("lan")?.trimEnd('/')
        if (room.isNullOrBlank() || code.isNullOrBlank() || (url == null && l == null)) { update { State(error = "邀請連結看不懂".tr) }; return }
        joinRoom(c, url ?: l!!, l, room, code)
    }

    /** 加入某台主機上的房間（[b] 是主機記住身分用的位址，[l] 是家裡網路的位址，先試）。 */
    private fun joinRoom(c: Context, b: String, l: String?, room: String, code: String) {
        val ctx = c.applicationContext
        update { State(busy = true) }
        scope.launch {
            var last: String? = null
            for (url in listOfNotNull(l, b).distinct()) {
                val body = JSONObject().put("room", room).put("code", code).put("name", myName(ctx)).put("lang", mySource(ctx))
                TalkStore.identity(ctx, b)?.let { body.put("contact", it.token) }
                val res = withContext(Dispatchers.IO) { runCatching { http("POST", "$url/v1/talk/join", body) } }
                val (status, j) = res.getOrElse { last = it.message; continue }
                if (status != 200) { update { State(error = errorText(status, j)) }; return@launch }
                enter(ctx, b, l, j, null, connected = url)
                return@launch
            }
            update { State(error = "連不到對方的主機：{0}".trf(last)) }
        }
    }

    /** 接來電。 */
    fun answer(c: Context, call: Call) {
        _calls.value = _calls.value.filterNot { it.room == call.room }
        val k = TalkStore.contacts.value.firstOrNull { it.id == call.from && it.base == call.base }
        joinRoom(c, call.base, k?.lan, call.room, call.code)
    }

    /** 看過的未接來電拿掉。 */
    fun dismiss(c: Context, call: Call) {
        _calls.value = _calls.value.filterNot { it.room == call.room }
        val id = TalkStore.identity(c, call.base) ?: return
        scope.launch(Dispatchers.IO) { runCatching { http("POST", "${call.base}/v1/talk/dismiss", JSONObject().put("room", call.room), contactToken = id.token) } }
    }

    /** 直接打給通話過的對象：開房並在對方那邊留來電。 */
    fun call(c: Context, k: TalkStore.Contact) {
        val ctx = c.applicationContext
        val id = TalkStore.identity(ctx, k.base) ?: run { update { State(error = "這台主機上沒有你的通話身分，請先用邀請連結對話一次".tr) }; return }
        update { State(busy = true) }
        scope.launch {
            var last: String? = null
            for (url in listOfNotNull(k.lan, k.base).distinct()) {
                val res = withContext(Dispatchers.IO) {
                    runCatching { http("POST", "$url/v1/talk/call", JSONObject().put("to", k.id).put("name", myName(ctx)).put("lang", mySource(ctx)), contactToken = id.token) }
                }
                val (code, j) = res.getOrElse { last = it.message; continue }
                if (code != 200) { update { State(error = errorText(code, j)) }; return@launch }
                enter(ctx, k.base, k.lan, j, null, calling = k.name, connected = url)
                return@launch
            }
            update { State(error = "連不到主機：{0}".trf(last)) }
        }
    }

    fun leave() {
        val b = base; val t = token; val r = _state.value.room
        pollJob?.cancel(); sendJob?.cancel()
        stopMic()
        TalkAudio.Player.stopAll()
        if (b != null && t != null && r != null) scope.launch(Dispatchers.IO) { runCatching { http("DELETE", "$b/v1/talk/$r", talkToken = t) } }
        base = null; canon = null; token = null; sent.clear(); sentAt.clear(); lineStart.clear()
        talk = null
        update { it.copy(connected = false, ended = true, calling = null) }
    }

    fun reset() { if (_state.value.connected) leave(); _state.value = State() }

    // ---------- 說話（麥克風開關） ----------
    /** 開始 / 暫停聽自己說話。Android 13+ 用自己的收音（可以剪原聲）；舊手機用辨識器自己的麥克風（只送文字）。 */
    fun toggleMic(c: Context) {
        if (LiveCaptions.state.value.running) { stopMic(); return }
        val other = _state.value.members.firstOrNull { it.id != _state.value.me }
        val target = other?.let { TranslateLangs.mlkitOf(it.lang) }?.takeIf { it != myReading(c) }
            ?: if (myReading(c) == "en") "zh" else "en"
        val own = LiveCaptions.canUseRecording && TalkAudio.Mic.start()
        lastEnd = TalkAudio.Mic.samples
        LiveCaptions.start(c, mySource(c), target, fromRecording = own)
    }

    private fun stopMic() {
        if (LiveCaptions.state.value.running) LiveCaptions.stop()
        TalkAudio.Mic.stop()
    }

    // ---------- 上線回報（App 開著時；對話頁、首頁都會叫） ----------
    fun startPresence(c: Context) {
        if (presenceJob?.isActive == true) return
        val ctx = c.applicationContext
        TalkStore.load(ctx)
        presenceJob = scope.launch {
            while (isActive) {
                val online = mutableMapOf<String, Boolean>()
                val calls = mutableListOf<Call>()
                for (b in TalkStore.bases(ctx)) {
                    val id = TalkStore.identity(ctx, b) ?: continue
                    val watch = TalkStore.contacts.value.filter { it.base == b }
                    val lanUrl = watch.firstNotNullOfOrNull { it.lan }
                    for (url in listOfNotNull(lanUrl, b).distinct()) {
                        val res = withContext(Dispatchers.IO) {
                            runCatching {
                                http("POST", "$url/v1/talk/presence", JSONObject().put("watch", JSONArray(watch.map { it.id }))
                                    .put("name", myName(ctx)).put("lang", mySource(ctx)), contactToken = id.token, readMs = 8_000)
                            }
                        }
                        val (code, j) = res.getOrNull() ?: continue
                        if (code != 200) break
                        j.optJSONObject("peers")?.let { p ->
                            for (k in p.keys()) {
                                val o = p.getJSONObject(k)
                                online[keyOf(k, b)] = o.optBoolean("online")
                                watch.firstOrNull { it.id == k }?.let { old ->
                                    if (o.optString("name").isNotBlank() && o.optString("name") != old.name)
                                        TalkStore.upsertContact(ctx, old.copy(name = o.optString("name"), lang = o.optString("lang").ifBlank { old.lang }))
                                }
                            }
                        }
                        j.optJSONArray("calls")?.let { a ->
                            for (i in 0 until a.length()) {
                                val o = a.getJSONObject(i)
                                calls += Call(b, o.optString("room"), o.optString("code"), o.optString("from"), o.optString("name"),
                                    o.optString("lang"), o.optBoolean("live"), (o.optDouble("at") * 1000).toLong())
                            }
                        }
                        break
                    }
                }
                _online.value = online
                // 正在通話中的那通不算來電
                _calls.value = calls.filterNot { it.room == _state.value.room }
                delay(PRESENCE_MS)
            }
        }
    }

    fun stopPresence() { presenceJob?.cancel(); presenceJob = null }

    // ---------- 背景回圈 ----------
    private fun startLoops(c: Context) {
        pollJob?.cancel(); sendJob?.cancel()
        // 收：長輪詢（最多等 25 秒），斷線就過幾秒再連
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
                if (j.optBoolean("ended")) { update { s -> s.copy(ended = true, connected = false) }; stopMic(); break }
            }
        }
        // 送：看 LiveCaptions 的句子，有新的或變了就送（暫時句最多每 0.6 秒一次）；說完的那句連同原聲
        sendJob = scope.launch {
            LiveCaptions.state.collect { s ->
                for (l in s.lines.takeLast(6)) {
                    if (!lineStart.containsKey(l.id)) {
                        // 辨識器出字比開口晚：往前抓 1.2 秒，但不早於上一句結束
                        lineStart[l.id] = maxOf(lastEnd, TalkAudio.Mic.samples - AudioSpec.SAMPLE_RATE * 12 / 10)
                    }
                    val prev = sent[l.id]
                    if (prev != null && prev.first == l.original && prev.second == !l.partial) continue
                    val now = System.currentTimeMillis()
                    if (l.partial && now - (sentAt[l.id] ?: 0L) < 600) continue
                    sentAt[l.id] = now
                    sent[l.id] = l.original to !l.partial
                    showMine(l.id, l.original, s.source, l.translated, !l.partial)
                    if (l.partial) post(l.id, l.original, s.source, false, null)
                    else finishMine(c, l.id, l.original, s.source, l.translated)
                }
            }
        }
    }

    /** 自己說完一句：剪原聲 → 上傳 → 送定稿（帶原聲）→ 存進記錄。 */
    private fun finishMine(c: Context, id: Long, text: String, lang: String, translated: String?) {
        val start = lineStart[id] ?: lastEnd
        val end = TalkAudio.Mic.samples
        lastEnd = end
        val b = base; val t = token; val r = _state.value.room; val tk = talk
        scope.launch {
            var aid: String? = null
            var file: File? = null
            if (TalkAudio.Mic.running && tk != null) {
                file = withContext(Dispatchers.IO) { TalkAudio.Mic.clip(c, start, end, File(TalkStore.dir(c, tk.id), "m$id.m4a")) }
                if (file != null && b != null && t != null && r != null) {
                    aid = withContext(Dispatchers.IO) {
                        runCatching { http("POST", "$b/v1/talk/$r/audio", talkToken = t, raw = file.readBytes()) }.getOrNull()
                            ?.takeIf { it.first == 200 }?.second?.optString("audio")
                    }
                }
            }
            post(id, text, lang, true, aid)
            record(c, TalkStore.TalkLine(true, myName(c), text, lang, translated, file?.name, System.currentTimeMillis()))
        }
    }

    private fun post(id: Long, text: String, lang: String, final: Boolean, audio: String?) {
        val b = base ?: return; val t = token ?: return; val r = _state.value.room ?: return
        scope.launch(Dispatchers.IO) {
            val body = JSONObject().put("line", "m$id").put("text", text).put("lang", lang).put("final", final)
            audio?.let { body.put("audio", it) }
            runCatching { http("POST", "$b/v1/talk/$r/msg", body, talkToken = t) }.onFailure { Log.w(TAG, "send failed: ${it.message}") }
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

    /** 存進這次對話的記錄（定稿的句子）。 */
    private fun record(c: Context, line: TalkStore.TalkLine) {
        val tk = talk ?: return
        val partners = _state.value.members.filter { it.id != _state.value.me }.map { it.name }.ifEmpty { tk.partners }
        val updated = tk.copy(endedAt = System.currentTimeMillis(), partners = partners, lines = tk.lines + line)
        talk = updated
        scope.launch(Dispatchers.IO) { runCatching { TalkStore.save(c, updated) } }
    }

    private suspend fun handle(c: Context, j: JSONObject) {
        val members = j.optJSONArray("members")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { m ->
                Member(m.optString("id"), m.optString("name"), m.optString("lang"), m.optBoolean("owner"), m.optString("contact").ifBlank { null })
            }
        } ?: _state.value.members
        val me = j.optString("me").ifBlank { _state.value.me }
        update { it.copy(members = members, me = me, connected = true, error = null,
            calling = if (members.size > 1) null else it.calling) }
        // 記住通話對象（記在這台主機的代表位址下）
        val b = base
        canon?.let { host ->
            members.filter { it.id != me && it.contact != null }.forEach { m ->
                TalkStore.upsertContact(c, TalkStore.Contact(m.contact!!, m.name, m.lang, host, lan, System.currentTimeMillis()))
            }
        }
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
            upsert(Line(key, from, m.optString("name"), text, lang, tr, false, final))
            if (!final) continue
            // 說完的一句：下載原聲 → 先播原聲、再唸翻譯；存進記錄
            var clip: File? = null
            val aid = m.optString("audio").ifBlank { null }
            val tk = talk
            if (aid != null && tk != null && b != null) {
                val out = File(TalkStore.dir(c, tk.id), "r${m.optInt("seq")}.m4a")
                if (withContext(Dispatchers.IO) { download("$b/v1/talk/${_state.value.room}/audio/$aid", token ?: "", out) }) clip = out
            }
            TalkAudio.Player.enqueue(c, clip.takeIf { playOriginal(c) }, tr.takeIf { speakTranslation(c) && it != text }, myReading(c))
            if (tr != null && Settings.captionToWatch(c)) WatchNotifier.sendCaption(c, tr, text)
            record(c, TalkStore.TalkLine(false, m.optString("name"), text, lang, tr, clip?.name, System.currentTimeMillis()))
        }
    }

    /** 對方的語言 → 我的語言（同語言就不翻）。第一次用到某個語言組合時下載 ML Kit 模型。 */
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
            TranslateFixes.apply(c, if (dst == "zh") TraditionalChinese.convert(out) else out, dst)
        }.getOrNull()
    }

    // ---------- 回播 ----------
    /** 回播一次對話：照順序播每句的原聲，對方的句子再唸翻譯。 */
    fun replay(c: Context, t: TalkStore.Talk, from: Int = 0) {
        TalkAudio.Player.stopAll()
        val dir = TalkStore.dir(c, t.id)
        for (l in t.lines.drop(from)) {
            TalkAudio.Player.enqueue(c, l.audio?.let { File(dir, it) }, if (!l.mine) l.translated?.takeIf { it != l.text } else null, myReading(c))
        }
    }

    fun replayLine(c: Context, t: TalkStore.Talk, l: TalkStore.TalkLine) {
        TalkAudio.Player.stopAll()
        TalkAudio.Player.enqueue(c, l.audio?.let { File(TalkStore.dir(c, t.id), it) }, if (!l.mine) l.translated?.takeIf { it != l.text } else null, myReading(c))
    }

    fun stopReplay() = TalkAudio.Player.stopAll()
}
