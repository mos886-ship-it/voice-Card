package com.example.wristai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 遠距對話存在手機上的東西：
 * - 通話對象（[Contact]）：對方的聯絡人 id、名字、語言、在哪台大D的主機（[Contact.base]）。
 * - 自己在每台主機的身分（[Identity]：contact id＋token；token 只有主機第一次給，要存好）。
 * - 對話記錄（[Talk]）：files/talks/<id>/talk.json＋每句的原聲片段（自己的、對方的），可以回播。
 */
object TalkStore {
    data class Contact(val id: String, val name: String, val lang: String, val base: String, val lan: String?, val lastTalk: Long,
                       /** 對方的使用者 ID（加好友用，像 K7MX-29QP）；舊的對象沒有。 */
                       val uid: String? = null)
    data class Identity(val id: String, val token: String)
    data class TalkLine(val mine: Boolean, val name: String, val text: String, val lang: String, val translated: String?,
                        val audio: String?, val at: Long)
    data class Talk(val id: String, val startedAt: Long, val endedAt: Long, val partners: List<String>, val lines: List<TalkLine>)

    private fun prefs(c: Context) = c.getSharedPreferences("talk_store", Context.MODE_PRIVATE)

    // ---------- 通話對象 ----------
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    fun load(c: Context) {
        val arr = runCatching { JSONArray(prefs(c).getString("contacts", "[]")) }.getOrDefault(JSONArray())
        _contacts.value = (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Contact(o.getString("id"), o.optString("name"), o.optString("lang"), o.getString("base"),
                o.optString("lan").ifBlank { null }, o.optLong("last"), o.optString("uid").ifBlank { null })
        }.sortedByDescending { it.lastTalk }
        reloadTalks(c)
    }

    private fun saveContacts(c: Context, list: List<Contact>) {
        val arr = JSONArray()
        list.forEach { k -> arr.put(JSONObject().put("id", k.id).put("name", k.name).put("lang", k.lang).put("base", k.base).put("lan", k.lan).put("last", k.lastTalk).put("uid", k.uid)) }
        prefs(c).edit().putString("contacts", arr.toString()).apply()
        _contacts.value = list.sortedByDescending { it.lastTalk }
    }

    fun upsertContact(c: Context, k: Contact) {
        val old = _contacts.value.firstOrNull { it.id == k.id && it.base == k.base }
        val merged = k.copy(name = k.name.ifBlank { old?.name.orEmpty() }, lang = k.lang.ifBlank { old?.lang.orEmpty() },
            lan = k.lan ?: old?.lan, lastTalk = maxOf(k.lastTalk, old?.lastTalk ?: 0), uid = k.uid ?: old?.uid)
        saveContacts(c, _contacts.value.filterNot { it.id == k.id && it.base == k.base } + merged)
    }

    fun removeContact(c: Context, k: Contact) = saveContacts(c, _contacts.value.filterNot { it.id == k.id && it.base == k.base })

    // ---------- 自己在每台主機的身分 ----------
    fun identity(c: Context, base: String): Identity? = runCatching {
        val o = JSONObject(prefs(c).getString("id:$base", null) ?: return null)
        Identity(o.getString("id"), o.getString("token"))
    }.getOrNull()

    fun saveIdentity(c: Context, base: String, id: String, token: String?) {
        val t = token ?: identity(c, base)?.token ?: return
        prefs(c).edit().putString("id:$base", JSONObject().put("id", id).put("token", t).toString()).apply()
    }

    /** 有身分的主機（上線回報要報到這些）。 */
    fun bases(c: Context): List<String> = prefs(c).all.keys.filter { it.startsWith("id:") }.map { it.removePrefix("id:") }

    // ---------- 對話記錄 ----------
    private val _talks = MutableStateFlow<List<Talk>>(emptyList())
    val talks: StateFlow<List<Talk>> = _talks.asStateFlow()

    fun dir(c: Context, id: String) = File(File(c.filesDir, "talks"), id).apply { mkdirs() }

    fun reloadTalks(c: Context) {
        val root = File(c.filesDir, "talks")
        _talks.value = (root.listFiles() ?: emptyArray()).mapNotNull { d -> runCatching { read(File(d, "talk.json")) }.getOrNull() }
            .filter { it.lines.isNotEmpty() }.sortedByDescending { it.startedAt }
    }

    private fun read(f: File): Talk {
        val o = JSONObject(f.readText())
        val arr = o.optJSONArray("lines") ?: JSONArray()
        return Talk(o.getString("id"), o.optLong("started"), o.optLong("ended"),
            (o.optJSONArray("partners") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } },
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { l ->
                TalkLine(l.optBoolean("mine"), l.optString("name"), l.optString("text"), l.optString("lang"),
                    l.optString("tr").ifBlank { null }, l.optString("audio").ifBlank { null }, l.optLong("at"))
            })
    }

    @Synchronized
    fun save(c: Context, t: Talk) {
        val arr = JSONArray()
        t.lines.forEach { l ->
            arr.put(JSONObject().put("mine", l.mine).put("name", l.name).put("text", l.text).put("lang", l.lang)
                .put("tr", l.translated).put("audio", l.audio).put("at", l.at))
        }
        File(dir(c, t.id), "talk.json").writeText(JSONObject().put("id", t.id).put("started", t.startedAt).put("ended", t.endedAt)
            .put("partners", JSONArray(t.partners)).put("lines", arr).toString())
        reloadTalks(c)
    }

    fun delete(c: Context, id: String) {
        File(File(c.filesDir, "talks"), id).deleteRecursively()
        reloadTalks(c)
    }
}
