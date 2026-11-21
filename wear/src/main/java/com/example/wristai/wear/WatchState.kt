package com.example.wristai.wear

import android.content.Context
import com.example.voiceai.shared.MeetingSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File

/** 手表端的录音状态。 */
sealed interface RecorderStatus {
    data object Idle : RecorderStatus
    /** [synced] = 已传到手机的分段数，[waiting] = 还在手表上等着传的分段数。 */
    data class Recording(val startedAt: Long, val synced: Int = 0, val waiting: Int = 0, val food: Boolean = false) : RecorderStatus
    data object Sending : RecorderStatus
    data class Sent(val recordingId: String) : RecorderStatus
    data class Failed(val message: String) : RecorderStatus
}

/** 手表 App 进程内共享的状态（Service 写、UI 读）。 */
object WatchState {
    private const val PREFS = "wristai_watch"
    private const val KEY_SUMMARY = "last_summary"
    private const val KEY_HISTORY = "summaries"
    private const val KEY_FOOD = "food_today"
    private const val KEY_LIST = "recordings"

    private val _recorder = MutableStateFlow<RecorderStatus>(RecorderStatus.Idle)
    val recorder: StateFlow<RecorderStatus> = _recorder.asStateFlow()

    /** 手机推回来的处理进度文字。 */
    private val _phoneStatus = MutableStateFlow<String?>(null)
    val phoneStatus: StateFlow<String?> = _phoneStatus.asStateFlow()

    private val _lastSummary = MutableStateFlow<MeetingSummary?>(null)
    val lastSummary: StateFlow<MeetingSummary?> = _lastSummary.asStateFlow()

    /** 手机在录音：开始时间；null = 没在录（手机推过来的状态）。 */
    private val _phoneRecordingSince = MutableStateFlow<Long?>(null)
    val phoneRecordingSince: StateFlow<Long?> = _phoneRecordingSince.asStateFlow()
    fun setPhoneRecording(since: Long?) { _phoneRecordingSince.value = since }

    /** 最近几笔摘要（新的在前，最多 5 笔），给「最近」分页。 */
    private val _summaries = MutableStateFlow<List<MeetingSummary>>(emptyList())
    val summaries: StateFlow<List<MeetingSummary>> = _summaries.asStateFlow()

    /** 今天的饮食热量（手机推来的）。[day] = 那天 0 点；不是今天就当作 0。 */
    data class FoodToday(val kcal: Int, val goal: Int, val meals: Int, val day: Long)
    private val _foodToday = MutableStateFlow<FoodToday?>(null)
    val foodToday: StateFlow<FoodToday?> = _foodToday.asStateFlow()

    fun todayStart(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** payload = "已吃/目标/餐数"。 */
    fun saveFoodToday(context: Context, payload: String) {
        val p = payload.split('/').mapNotNull { it.trim().toIntOrNull() }
        if (p.size < 3) return
        val f = FoodToday(p[0], p[1], p[2], todayStart())
        _foodToday.value = f
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FOOD, "${f.kcal}/${f.goal}/${f.meals}/${f.day}").apply()
    }

    /** 报告书清单的一笔（手机传来的）。[kind] = watch/phone/food/call/import；[status] = DONE/整理中/錄音中/失敗/已存錄音機。 */
    data class Entry(val id: String, val title: String, val recordedAt: Long, val durationSec: Double, val kind: String, val status: String)

    /** 一次查询的结果（可能只载入了前几页）。[key] = 查询代号（"" = 预设：全部、不限月份、无关键字）。[months] = 每个月几笔（新的在前）。 */
    data class ListResult(val key: String, val total: Int, val months: List<Pair<String, Int>>, val items: List<Entry>)

    /** 各查询的结果，依 [ListResult.key] 存。预设清单会存档，手机不在也能看。 */
    private val _lists = MutableStateFlow<Map<String, ListResult>>(emptyMap())
    val lists: StateFlow<Map<String, ListResult>> = _lists.asStateFlow()

    /** 所有已载入的项目（明细页拿标题 / 时间当备援）。 */
    fun findEntry(id: String): Entry? = _lists.value.values.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.id == id }

    private fun parseEntry(j: org.json.JSONObject) = Entry(j.getString("id"), j.optString("title"), j.optLong("recordedAt"),
        j.optDouble("durationSec", 0.0), j.optString("kind", "watch"), j.optString("status", "DONE"))

    /** 回传 (结果, offset)。也读得懂旧版（纯阵列）的格式。 */
    private fun parseList(json: String): Pair<ListResult, Int>? = runCatching {
        if (json.trimStart().startsWith("[")) {
            val a = org.json.JSONArray(json)
            val items = (0 until a.length()).map { parseEntry(a.getJSONObject(it)) }
            ListResult("", items.size, emptyList(), items) to 0
        } else {
            val j = org.json.JSONObject(json)
            val a = j.optJSONArray("items") ?: org.json.JSONArray()
            val m = j.optJSONArray("months") ?: org.json.JSONArray()
            ListResult(
                key = j.optString("key"),
                total = j.optInt("total"),
                months = (0 until m.length()).map { m.getJSONObject(it).let { o -> o.optString("ym") to o.optInt("count") } },
                items = (0 until a.length()).map { parseEntry(a.getJSONObject(it)) },
            ) to j.optInt("offset")
        }
    }.getOrNull()

    fun saveList(context: Context, json: String) {
        val (res, offset) = parseList(json) ?: return
        val cur = _lists.value[res.key]
        // 「顯示更多」：接在已载入的后面
        val merged = if (offset > 0 && cur != null) res.copy(items = cur.items.take(offset) + res.items) else res
        _lists.value = _lists.value + (res.key to merged)
        if (res.key.isEmpty() && offset == 0)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LIST, json).apply()
    }

    /** 手机回报「这笔录音拿到手机定位了没」：录音 id → true / false。 */
    private val _geoAcks = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    fun setGeoAck(payload: String) {
        val p = payload.split('|'); if (p.size != 2) return
        _geoAcks.value = _geoAcks.value + (p[0] to (p[1] == "1"))
    }
    /** 等手机回报，最多 [timeoutMs]；没回 = null。 */
    suspend fun awaitGeoAck(id: String, timeoutMs: Long): Boolean? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { _geoAcks.first { id in it }[id] }

    /** 从手机取回的完整报告（看明细用）。整理好的会存档，手机不在旁边也能再看。 */
    data class Detail(val id: String, val title: String, val status: String, val markdown: String, val durationSec: Double, val createdAt: Long,
                      val geo: com.example.voiceai.shared.GeoFix? = null)
    private val _details = MutableStateFlow<Map<String, Detail>>(emptyMap())
    val details: StateFlow<Map<String, Detail>> = _details.asStateFlow()

    private fun detailFile(context: Context, id: String) =
        File(File(context.filesDir, "details").apply { mkdirs() }, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private fun parseDetail(json: String): Detail? = runCatching {
        val j = org.json.JSONObject(json)
        Detail(j.getString("id"), j.optString("title"), j.optString("status"), j.optString("markdown"),
            j.optDouble("durationSec", 0.0), j.optLong("createdAt"),
            j.optJSONObject("geo")?.let { com.example.voiceai.shared.GeoFix.fromJson(it.toString()) })
    }.getOrNull()

    /** 先读存档（有的话），画面马上有内容；之后手机回传再更新。 */
    fun loadDetail(context: Context, id: String) {
        if (id in _details.value) return
        val f = detailFile(context, id)
        if (f.exists()) parseDetail(f.readText())?.let { _details.value = _details.value + (id to it) }
    }

    fun saveDetail(context: Context, json: String) {
        val d = parseDetail(json) ?: return
        if (d.status == "DONE") runCatching { detailFile(context, d.id).writeText(json) }
        // 手机说「还在整理 / 找不到」时，不要盖掉之前存的完整版
        val old = _details.value[d.id]
        if (d.status != "DONE" && old?.status == "DONE") return
        _details.value = _details.value + (d.id to d)
        // 旧摘要没有录音时间：用明细里的补上（之后清单也会显示录音时间）
        if (d.createdAt > 0) _summaries.value.firstOrNull { it.recordingId == d.id && it.recordedAt == 0L }?.let { s ->
            val list = _summaries.value.map { if (it.recordingId == d.id) it.copy(recordedAt = d.createdAt, durationSec = d.durationSec) else it }
            _summaries.value = list
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_HISTORY, org.json.JSONArray(list.map { it.toBytes().toString(Charsets.UTF_8) }).toString()).apply()
        }
    }

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    fun setRecorder(status: RecorderStatus) {
        _recorder.value = status
    }

    fun setPhoneStatus(text: String?) {
        _phoneStatus.value = text
    }

    fun load(context: Context) {
        if (_lastSummary.value == null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SUMMARY, null)
                ?.let { runCatching { MeetingSummary.fromBytes(it.toByteArray()) }.getOrNull() }
                ?.let { _lastSummary.value = it }
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (_summaries.value.isEmpty()) {
            _summaries.value = runCatching {
                val a = org.json.JSONArray(prefs.getString(KEY_HISTORY, "[]"))
                (0 until a.length()).map { MeetingSummary.fromBytes(a.getString(it).toByteArray()) }
            }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(_lastSummary.value) }
        }
        if (_lists.value.isEmpty()) prefs.getString(KEY_LIST, null)?.let(::parseList)?.let { (r, _) -> _lists.value = mapOf(r.key to r) }
        prefs.getString(KEY_FOOD, null)?.split('/')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 4 }?.let {
            _foodToday.value = FoodToday(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3])
        }
        refreshPending(context)
    }

    fun saveSummary(context: Context, summary: MeetingSummary) {
        _lastSummary.value = summary
        // 同一笔（重新产生）就取代，新的放最前面，最多留 5 笔
        val list = (listOf(summary) + _summaries.value.filter { it.recordingId != summary.recordingId }).take(5)
        _summaries.value = list
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SUMMARY, summary.toBytes().toString(Charsets.UTF_8))
            .putString(KEY_HISTORY, org.json.JSONArray(list.map { it.toBytes().toString(Charsets.UTF_8) }).toString())
            .apply()
    }

    /** 还没成功传到手机的录音（分段 m4a / 旧版 WAV）放这里，会自动或手动重送。 */
    fun pendingDir(context: Context): File = File(context.filesDir, "pending").apply { mkdirs() }

    fun refreshPending(context: Context) {
        _pendingCount.value = pendingDir(context).listFiles { f -> f.extension == "wav" || f.extension == "m4a" }?.size ?: 0
    }
}
