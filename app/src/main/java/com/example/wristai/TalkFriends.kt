package com.example.wristai

import android.content.Context
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 遠距對話的使用者 ID 與好友：
 * - 自己的 ID（像 K7MX-29QP）：在大D的主機註冊拿到，給朋友看。
 * - 輸入對方的 ID → 對方收到好友邀請；對方接受後雙方都在「通話對象」裡，可以直接撥打。
 * 身分跟著主機走（[TalkStore.identity]）；沒配對的人先用邀請連結加入過一次對話就有身分，也能拿 ID。
 */
object TalkFriends {
    data class Request(val base: String, val id: String, val name: String, val lang: String, val uid: String)

    private val _myUid = MutableStateFlow<String?>(null)
    val myUid: StateFlow<String?> = _myUid.asStateFlow()
    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()
    /** 我加了、對方還沒接受：「id@主機」。 */
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    /** 用哪台主機：配對過的那台；沒配對就用有身分的第一台。 */
    fun base(c: Context): String? =
        com.example.wristai.ai.LocalHost.url(c).trimEnd('/').takeIf { com.example.wristai.ai.LocalHost.configured(c) }
            ?: TalkStore.bases(c).firstOrNull()

    fun cachedUid(c: Context, base: String): String? = c.getSharedPreferences("talk_store", 0).getString("uid:$base", null)

    private fun error(code: Int, j: JSONObject) = j.optString("error").ifBlank {
        if (code == 404) "大D的主機還沒有加好友功能：請在 Mac 上重新部署大D的主機".tr else "連線失敗（{0}）".trf(code)
    }

    /** 取得（或第一次註冊）自己的 ID。回傳 ID 或丟出錯誤訊息。 */
    suspend fun register(c: Context, name: String, lang: String): String = withContext(Dispatchers.IO) {
        val b = base(c) ?: throw IllegalStateException("要先跟大D的主機配對（設定 → AI 引擎 → 私有主機），或先用邀請連結加入過一次對話".tr)
        val id = TalkStore.identity(c, b)
        val auth = if (com.example.wristai.ai.LocalHost.configured(c)) com.example.wristai.ai.LocalHost.token(c) else null
        val (code, j) = RemoteTalk.http("POST", "$b/v1/talk/register", JSONObject().put("name", name).put("lang", lang),
            auth = auth.takeIf { id == null }, contactToken = id?.token)
        if (code != 200) throw IllegalStateException(error(code, j))
        j.optJSONObject("contact")?.let { ct -> TalkStore.saveIdentity(c, b, ct.optString("id"), ct.optString("token").ifBlank { null }) }
        val uid = j.optString("uid")
        c.getSharedPreferences("talk_store", 0).edit().putString("uid:$b", uid).apply()
        _myUid.value = uid
        uid
    }

    /** 輸入對方 ID 加好友。回傳給人看的結果。 */
    suspend fun add(c: Context, uid: String, name: String, lang: String): String = withContext(Dispatchers.IO) {
        val b = base(c) ?: throw IllegalStateException("要先跟大D的主機配對".tr)
        if (TalkStore.identity(c, b) == null) register(c, name, lang)
        val id = TalkStore.identity(c, b) ?: throw IllegalStateException("沒有通話身分".tr)
        val (code, j) = RemoteTalk.http("POST", "$b/v1/talk/friend", JSONObject().put("id", uid), contactToken = id.token)
        if (code != 200) throw IllegalStateException(error(code, j))
        val f = j.getJSONObject("friend")
        val k = TalkStore.Contact(f.getString("id"), f.optString("name"), f.optString("lang"), b, null, System.currentTimeMillis(),
            f.optString("uid").ifBlank { null })
        TalkStore.upsertContact(c, k)
        if (j.optString("status") == "pending") {
            _pending.value = _pending.value + "${k.id}@$b"
            "已送出好友邀請給 {0}，對方接受後就能互相撥打".trf(k.name)
        } else "{0} 已經是你的好友".trf(k.name)
    }

    suspend fun answer(c: Context, r: Request, accept: Boolean) = withContext(Dispatchers.IO) {
        val id = TalkStore.identity(c, r.base) ?: return@withContext
        val (code, j) = RemoteTalk.http("POST", "${r.base}/v1/talk/friend/answer", JSONObject().put("from", r.id).put("accept", accept),
            contactToken = id.token)
        if (code != 200) throw IllegalStateException(error(code, j))
        _requests.value = _requests.value.filterNot { it.id == r.id && it.base == r.base }
        if (accept) TalkStore.upsertContact(c, TalkStore.Contact(r.id, r.name, r.lang, r.base, null, System.currentTimeMillis(), r.uid.ifBlank { null }))
    }

    /** 刪除好友（主機上雙方都拿掉；手機上的對話記錄留著）。 */
    suspend fun remove(c: Context, k: TalkStore.Contact) = withContext(Dispatchers.IO) {
        TalkStore.identity(c, k.base)?.let { id ->
            runCatching { RemoteTalk.http("POST", "${k.base}/v1/talk/unfriend", JSONObject().put("id", k.id), contactToken = id.token) }
        }
        TalkStore.removeContact(c, k)
    }

    /** 上線回報的結果（[RemoteTalk.startPresence] 每 20 秒）：自己的 ID、收到的邀請、還沒被接受的。 */
    internal fun onPresence(c: Context, base: String, j: JSONObject, pendingIds: Set<String>, requests: List<Request>) {
        j.optString("uid").takeIf { it.isNotBlank() }?.let { uid ->
            c.getSharedPreferences("talk_store", 0).edit().putString("uid:$base", uid).apply()
            if (base == base(c)) _myUid.value = uid
        }
        _pending.value = _pending.value.filterNot { it.endsWith("@$base") }.toSet() + pendingIds.map { "$it@$base" }
        _requests.value = _requests.value.filterNot { it.base == base } + requests
    }

    fun loadCached(c: Context) { base(c)?.let { b -> _myUid.value = cachedUid(c, b) } }
}
