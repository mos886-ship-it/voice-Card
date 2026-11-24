package com.example.wristai.backup

import android.content.Context
import android.util.Log
import com.example.voiceai.shared.WavFile
import com.example.wristai.Meeting
import com.example.wristai.MeetingStatus
import com.example.wristai.MeetingStore
import com.example.wristai.VoiceRecorderExporter
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份到使用者自己的 Google 云端硬碟，也能从那里取回。
 *
 * - 权限只要 `drive.file`：App 只看得到、只改得到自己建的档案，看不到使用者云端硬碟里的其他东西
 *   （也是 Google 归类为「非敏感」的权限，不用额外审核）。
 * - 云端硬碟里建一个资料夹「大D 錄音備份」，每笔录音一个 zip：`<编号> <标题>.zip`，里面是
 *   meta.json（标题、说话者名字、营养…）、report.md、transcript.txt、geo.json 等，加上（选择备份录音时）audio.m4a。
 *   录音用 16 kHz / 48 kbps AAC，一小时约 22 MB（手机上的 audio.wav 一小时约 115 MB）。
 * - 增量：每个 zip 的 appProperties 记 `dylanId`（编号）和 `dylanVer`（内容指纹），内容没变就不重传。
 * - 取回：只取回手机上没有的（不会盖掉手机上的）；录音解回 audio.wav，可以播放、重新产生报告。
 * - 手机上删掉录音不会删云端硬碟的备份（备份就是为了救回来）。
 *
 * 要先在 Google Cloud 建 OAuth 用户端（Android、套件名称＋签名 SHA-1）并开 Google Drive API，见 `交接/DRIVE_BACKUP.md`。
 */
object DriveBackup {
    private const val TAG = "DriveBackup"
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val FOLDER_NAME = "大D 錄音備份"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val PREFS = "wristai_settings"
    private const val KEY_LINKED = "drive_linked"
    private const val KEY_EMAIL = "drive_email"
    private const val KEY_AUDIO = "drive_audio"
    private const val KEY_AUTO = "drive_auto"
    private const val KEY_WIFI = "drive_wifi_only"
    private const val KEY_LAST = "drive_last_backup"
    private const val KEY_FOLDER = "drive_folder_id"

    /** 不放进备份的档案：录音原档（另外压成 audio.m4a）、处理中的暂存。 */
    private val SKIP = setOf("audio.wav", "host_job.txt", "stt_state.json")

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 设定 ----------------

    fun linked(c: Context) = p(c).getBoolean(KEY_LINKED, false)
    fun email(c: Context): String? = p(c).getString(KEY_EMAIL, null)
    /** 连录音档一起备份（预设开；关掉只备份报告与逐字稿，小很多）。 */
    fun includeAudio(c: Context) = p(c).getBoolean(KEY_AUDIO, true)
    fun setIncludeAudio(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_AUDIO, v).apply()
    /** 报告完成后自动备份。 */
    fun auto(c: Context) = p(c).getBoolean(KEY_AUTO, true)
    fun setAuto(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_AUTO, v).apply()
    /** 只用 Wi-Fi（不吃行动数据）。 */
    fun wifiOnly(c: Context) = p(c).getBoolean(KEY_WIFI, true)
    fun setWifiOnly(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_WIFI, v).apply()
    fun lastBackup(c: Context) = p(c).getLong(KEY_LAST, 0)

    fun setLinked(c: Context, email: String?) =
        p(c).edit().putBoolean(KEY_LINKED, true).putString(KEY_EMAIL, email).apply()

    // ---------------- 进度（给设定页看） ----------------

    data class Status(
        val running: Boolean = false,
        /** 「備份中 3/20」「取回中 1/4」「已備份 20 筆」… */
        val text: String? = null,
        val error: Boolean = false,
        val done: Int = 0, val total: Int = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    internal fun report(s: Status) { _status.value = s }

    // ---------------- 授权（Google 帐号） ----------------

    class NeedsConsent(val result: AuthorizationResult) : Exception("需要使用者同意")

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /**
     * 拿存取权杖。之前同意过就直接给（背景也可以）；还没同意 → [NeedsConsent]（设定页拿去开 Google 的同意画面）。
     * 没有设定 OAuth 用户端时 Google 会回 DEVELOPER_ERROR → 这里转成看得懂的错误。
     */
    suspend fun token(c: Context): String {
        val r = try {
            Identity.getAuthorizationClient(c).authorize(request()).await()
        } catch (e: com.google.android.gms.common.api.ApiException) {
            throw BackupException(
                if (e.statusCode == 10) "這個 App 還沒在 Google Cloud 登記（OAuth 用戶端），見 交接/DRIVE_BACKUP.md"
                else "Google 帳號授權失敗（${e.statusCode}）",
            )
        }
        if (r.hasResolution()) throw NeedsConsent(r)
        return r.accessToken ?: throw BackupException("Google 沒有給存取權杖")
    }

    /**
     * Google 同意画面没成功时给使用者看的原因（[resultCode] = 画面的结果，[status] = Google 的错误代码）。
     * 最常见：App 还没在 Google Cloud 登记（10 DEVELOPER_ERROR）→ 选完帐号就直接失败。
     */
    fun consentError(resultCode: Int, status: Int?): String = when {
        status == 10 -> "這個 App 還沒在 Google Cloud 登記（OAuth 用戶端、測試使用者），Google 不給授權。設定步驟見 交接/DRIVE_BACKUP.md"
        status == 16 || status == 12501 || (status == null && resultCode == android.app.Activity.RESULT_CANCELED) ->
            "已取消 Google 授權" + if (status == null) "（如果你沒有按取消：多半是這個 App 還沒在 Google Cloud 登記，見 交接/DRIVE_BACKUP.md）" else ""
        status == 7 -> "連不到 Google，請確認網路"
        status != null -> "Google 授權失敗（代碼 $status）"
        else -> "沒有完成 Google 授權"
    }

    /** 同意后：记住已连结、存 email（显示用）。 */
    fun finishLink(c: Context, token: String) {
        val email = runCatching {
            JSONObject(get(token, "$API/about?fields=user(emailAddress)")).optJSONObject("user")?.optString("emailAddress")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        setLinked(c, email)
    }

    /** 取消连结：撤销这个 App 的云端硬碟权限（云端硬碟里的备份不会删）。 */
    fun unlink(c: Context, token: String?) {
        if (token != null) runCatching {
            val conn = (URL("https://oauth2.googleapis.com/revoke?token=" + URLEncoder.encode(token, "UTF-8"))
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 10_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            try { conn.responseCode } finally { conn.disconnect() }
        }
        p(c).edit().remove(KEY_LINKED).remove(KEY_EMAIL).remove(KEY_FOLDER).apply()
    }

    // ---------------- 哪些录音要备份 ----------------

    /** 做完的才备份（录音中、处理中的等做完）。 */
    fun eligible(m: Meeting) = m.status == MeetingStatus.DONE || m.status == MeetingStatus.EXPORTED || m.status == MeetingStatus.FAILED

    /** 要放进 zip 的档案（录音资料夹里第一层的一般档案，略过 [SKIP]）。 */
    fun filesOf(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name !in SKIP && !it.name.endsWith(".tmp") }?.sortedBy { it.name }.orEmpty()

    /**
     * 内容指纹：档名＋大小＋修改时间，加上录音（有没有、多大）。有变就重传。
     * 只是用来比对「跟上次备份一样吗」，不是加密用途。
     */
    fun fingerprint(dir: File, audio: Boolean): String {
        val wav = File(dir, "audio.wav")
        val parts = filesOf(dir).map { "${it.name}:${it.length()}:${it.lastModified() / 2000}"   /* zip 的时间只精确到 2 秒 */ } +
            (if (audio && wav.exists()) listOf("audio:${wav.length()}") else emptyList())
        val md = java.security.MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray())
        return md.take(12).joinToString("") { "%02x".format(it) }
    }

    /** 云端硬碟上的档名：「20261124_225933 Q3 預算週會.zip」（档名不能有的字换掉）。 */
    fun zipName(m: Meeting): String {
        val title = (m.title ?: "").replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(40)
        return if (title.isEmpty()) "${m.id}.zip" else "${m.id} $title.zip"
    }

    // ---------------- zip ----------------

    /** 打包一笔录音（[m4a] = 压好的录音，null = 不带录音）。 */
    fun pack(dir: File, m4a: File?, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (f in filesOf(dir)) {
                zip.putNextEntry(ZipEntry(f.name).apply { time = f.lastModified() })   // 取回后保留时间，指纹才对得上
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            if (m4a != null) {
                zip.putNextEntry(ZipEntry("audio.m4a"))
                m4a.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * 解开到 [dir]。只接受单纯的档名（没有路径、不是 .. ）——zip 是从网路下载的，不能让它写到资料夹外面。
     * 回传解出来的档名。
     */
    fun unpack(input: InputStream, dir: File): List<String> {
        dir.mkdirs()
        val names = mutableListOf<String>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                val ok = !e.isDirectory && name.isNotBlank() && !name.contains('/') && !name.contains('\\') &&
                    name != "." && name != ".." && name !in SKIP
                if (ok) {
                    File(dir, name).apply { outputStream().use { zip.copyTo(it) }; if (e.time > 0) setLastModified(e.time) }
                    names += name
                }
                zip.closeEntry()
            }
        }
        return names
    }

    // ---------------- Drive REST ----------------

    class BackupException(message: String) : Exception(message)

    private fun conn(token: String, url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun body(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull().orEmpty()
            throw BackupException(when (code) {
                401 -> "Google 授權過期，請到設定重新連結 Google 雲端硬碟"
                403 -> if (msg.contains("storage", true) || msg.contains("quota", true)) "Google 雲端硬碟空間不夠" else "Google 雲端硬碟拒絕存取：$msg"
                else -> "Google 雲端硬碟錯誤 $code：$msg"
            })
        }
        return text
    }

    private fun get(token: String, url: String): String {
        val c = conn(token, url, "GET")
        try { return body(c) } finally { c.disconnect() }
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    /** 找（或建）「大D 錄音備份」资料夹。用 appProperties 认，使用者改资料夹名字也找得到。 */
    fun folder(c: Context, token: String): String {
        p(c).getString(KEY_FOLDER, null)?.let { id ->
            val ok = runCatching { JSONObject(get(token, "$API/files/$id?fields=id,trashed")).optBoolean("trashed").not() }.getOrDefault(false)
            if (ok) return id
        }
        val query = "mimeType='application/vnd.google-apps.folder' and appProperties has { key='dylan' and value='root' } and trashed=false"
        val found = JSONObject(get(token, "$API/files?q=${q(query)}&fields=files(id)&spaces=drive"))
            .optJSONArray("files")?.optJSONObject(0)?.optString("id")
        val id = found ?: run {
            val meta = JSONObject().put("name", FOLDER_NAME).put("mimeType", "application/vnd.google-apps.folder")
                .put("appProperties", JSONObject().put("dylan", "root"))
            val cn = conn(token, "$API/files?fields=id", "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                cn.outputStream.use { it.write(meta.toString().toByteArray()) }
                JSONObject(body(cn)).getString("id")
            } finally { cn.disconnect() }
        }
        p(c).edit().putString(KEY_FOLDER, id).apply()
        return id
    }

    /** 云端硬碟上的一笔备份。 */
    data class Remote(val fileId: String, val meetingId: String, val ver: String, val name: String, val size: Long)

    /** 列出资料夹里的备份（依 appProperties 的 dylanId）。 */
    fun list(token: String, folderId: String): List<Remote> {
        val out = mutableListOf<Remote>()
        var page: String? = null
        do {
            val url = "$API/files?q=${q("'$folderId' in parents and trashed=false")}&pageSize=1000" +
                "&fields=${q("nextPageToken,files(id,name,size,appProperties)")}" + (page?.let { "&pageToken=${q(it)}" } ?: "")
            val j = JSONObject(get(token, url))
            out += parseList(j)
            page = j.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out
    }

    /** Drive files.list 的回应 → [Remote]（没有 dylanId 的档案略过：不是 App 放的）。 */
    fun parseList(j: JSONObject): List<Remote> {
        val a = j.optJSONArray("files") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val f = a.getJSONObject(i)
            val props = f.optJSONObject("appProperties")
            val mid = props?.optString("dylanId").orEmpty()
            if (mid.isBlank()) null
            else Remote(f.getString("id"), mid, props?.optString("dylanVer").orEmpty(), f.optString("name"), f.optString("size").toLongOrNull() ?: 0)
        }
    }

    /** 上传（新档）或更新（[fileId] 有给）。用可续传上传：先送说明拿到上传网址，再送档案内容。 */
    fun upload(token: String, folderId: String, fileId: String?, name: String, meetingId: String, ver: String, zip: File) {
        val meta = JSONObject().put("name", name).put("mimeType", "application/zip")
            .put("appProperties", JSONObject().put("dylanId", meetingId).put("dylanVer", ver))
        if (fileId == null) meta.put("parents", org.json.JSONArray().put(folderId))
        val start = conn(token, if (fileId == null) "$UPLOAD/files?uploadType=resumable" else "$UPLOAD/files/$fileId?uploadType=resumable",
            if (fileId == null) "POST" else "PATCH").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Upload-Content-Type", "application/zip")
            setRequestProperty("X-Upload-Content-Length", zip.length().toString())
        }
        val location = try {
            start.outputStream.use { it.write(meta.toString().toByteArray()) }
            body(start)
            start.getHeaderField("Location") ?: throw BackupException("Google 雲端硬碟沒有給上傳網址")
        } finally { start.disconnect() }
        val put = (URL(location).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/zip")
            setFixedLengthStreamingMode(zip.length())
        }
        try {
            put.outputStream.use { out -> zip.inputStream().use { it.copyTo(out, 256 * 1024) } }
            body(put)
        } finally { put.disconnect() }
    }

    fun download(token: String, fileId: String, out: File) {
        val c = conn(token, "$API/files/$fileId?alt=media", "GET").apply { readTimeout = 120_000 }
        try {
            if (c.responseCode !in 200..299) body(c)
            c.inputStream.use { input -> out.outputStream().use { input.copyTo(it, 256 * 1024) } }
        } finally { c.disconnect() }
    }

    // ---------------- 备份 / 取回 ----------------

    /**
     * 把手机上做完的录音备份上去（内容没变的略过）。回传这次传了几笔。
     * [onProgress](第几笔, 共几笔)。
     */
    fun backup(c: Context, token: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val folderId = folder(c, token)
        val remote = list(token, folderId).associateBy { it.meetingId }
        val audio = includeAudio(c)
        val todo = MeetingStore.meetings.value.filter(::eligible).mapNotNull { m ->
            val dir = File(MeetingStore.rootDir(c), m.id)
            if (!File(dir, "meta.json").exists()) return@mapNotNull null
            val ver = fingerprint(dir, audio)
            if (remote[m.id]?.ver == ver) null else Triple(m, dir, ver)
        }
        todo.forEachIndexed { i, (m, dir, ver) ->
            onProgress(i, todo.size)
            val zip = File(c.cacheDir, "backup_${m.id}.zip")
            val m4a = File(c.cacheDir, "backup_${m.id}.m4a")
            try {
                val wav = File(dir, "audio.wav")
                val withAudio = audio && wav.exists() && wav.length() > 1024
                if (withAudio) VoiceRecorderExporter.encodeM4a(wav, m4a, outRate = 16_000, bitRate = 48_000)
                pack(dir, if (withAudio) m4a else null, zip)
                upload(token, folderId, remote[m.id]?.fileId, zipName(m), m.id, ver, zip)
                Log.d(TAG, "backed up ${m.id} (${zip.length() / 1024} KB)")
            } finally {
                zip.delete(); m4a.delete()
            }
        }
        onProgress(todo.size, todo.size)
        p(c).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        return todo.size
    }

    /**
     * m4a（16 kHz 单声道）→ PCM，边解边写（[AacCodec.decodeToPcm] 会整段放进记忆体，6 小时的录音放不下）。
     */
    fun decodeStreaming(m4a: File, out: java.io.OutputStream) {
        val ex = android.media.MediaExtractor()
        ex.setDataSource(m4a.absolutePath)
        val track = (0 until ex.trackCount).first {
            ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        check(format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) == com.example.voiceai.shared.AudioSpec.SAMPLE_RATE &&
            format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT) == 1) { "備份裡的錄音格式不對" }
        val codec = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = android.media.MediaCodec.BufferInfo()
        var inDone = false
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    var left = info.size
                    while (left > 0) {
                        val k = minOf(left, buf.size)
                        ob.get(buf, 0, k); out.write(buf, 0, k); left -= k
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
    }

    /** 云端硬碟上有、手机上没有的备份。 */
    fun missing(c: Context, token: String): List<Remote> {
        val local = MeetingStore.meetings.value.map { it.id }.toSet()
        return list(token, folder(c, token)).filter { it.meetingId !in local }
    }

    /** 取回手机上没有的录音（不盖掉手机上已有的）。回传取回几笔。 */
    fun restore(c: Context, token: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val todo = missing(c, token)
        todo.forEachIndexed { i, r ->
            onProgress(i, todo.size)
            // 编号只能是安全的资料夹名字（从网路来的）
            if (!r.meetingId.matches(Regex("[A-Za-z0-9_\\-]{1,64}"))) return@forEachIndexed
            val zip = File(c.cacheDir, "restore_${r.meetingId}.zip")
            val tmp = File(c.cacheDir, "restore_${r.meetingId}")
            try {
                download(token, r.fileId, zip)
                tmp.deleteRecursively()
                val names = zip.inputStream().use { unpack(it, tmp) }
                if ("meta.json" !in names) return@forEachIndexed
                File(tmp, "audio.m4a").takeIf { it.exists() }?.let { m4a ->
                    // 解回 audio.wav（播放、重新产生报告都用它）
                    val wav = File(tmp, "audio.wav")
                    wav.outputStream().buffered().use { out ->
                        WavFile.writePlaceholderHeader(out)
                        decodeStreaming(m4a, out)
                    }
                    WavFile.finalizeHeader(wav)
                    m4a.delete()
                }
                val dest = File(MeetingStore.rootDir(c), r.meetingId)
                if (dest.exists()) return@forEachIndexed   // 期间手机上出现同一笔：不盖掉
                if (!tmp.renameTo(dest)) { tmp.copyRecursively(dest); tmp.deleteRecursively() }
            } finally {
                zip.delete(); tmp.deleteRecursively()
            }
        }
        onProgress(todo.size, todo.size)
        MeetingStore.reload(c)
        return todo.size
    }
}
