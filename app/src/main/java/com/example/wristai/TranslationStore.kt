package com.example.wristai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 即时翻译的记录：每次「開始翻譯」到「停止」存成一笔（原文、译文、语言、时间、同时录的那段录音）。
 * 存在手机 files/translations/<id>.json，不上传。
 */
object TranslationStore {
    data class Line(val original: String, val translated: String?)
    data class Session(
        val id: Long,
        val startedAt: Long,
        val endedAt: Long,
        val source: String,
        val target: String,
        val meetingId: String?,
        val lines: List<Line>,
    ) {
        val durationSec get() = ((endedAt - startedAt) / 1000).coerceAtLeast(0)
    }

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private fun dir(c: Context) = File(c.filesDir, "translations").apply { mkdirs() }

    fun reload(c: Context) {
        _sessions.value = dir(c).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.startedAt }
    }

    fun save(c: Context, s: Session) {
        if (s.lines.isEmpty()) return
        File(dir(c), "${s.id}.json").writeText(toJson(s).toString())
        _sessions.value = (listOf(s) + _sessions.value.filter { it.id != s.id }).sortedByDescending { it.startedAt }
    }

    fun delete(c: Context, id: Long) {
        File(dir(c), "$id.json").delete()
        _sessions.value = _sessions.value.filter { it.id != id }
    }

    fun asText(s: Session): String = s.lines.joinToString("\n\n") { l -> l.original + (l.translated?.let { "\n→ $it" } ?: "") }

    fun toJson(s: Session) = JSONObject().apply {
        put("id", s.id); put("startedAt", s.startedAt); put("endedAt", s.endedAt)
        put("source", s.source); put("target", s.target); s.meetingId?.let { put("meetingId", it) }
        put("lines", JSONArray().apply {
            for (l in s.lines) put(JSONObject().apply { put("o", l.original); l.translated?.let { put("t", it) } })
        })
    }

    fun fromJson(j: JSONObject): Session {
        val arr = j.optJSONArray("lines") ?: JSONArray()
        val lines = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Line(o.optString("o"), if (o.has("t")) o.optString("t") else null)
        }
        return Session(j.getLong("id"), j.getLong("startedAt"), j.optLong("endedAt", j.getLong("startedAt")),
            j.optString("source"), j.optString("target"), j.optString("meetingId").ifEmpty { null }, lines)
    }
}
