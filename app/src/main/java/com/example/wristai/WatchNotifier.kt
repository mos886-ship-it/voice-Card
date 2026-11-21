package com.example.wristai

import android.content.Context
import android.util.Log
import com.example.voiceai.shared.MeetingSummary
import com.example.voiceai.shared.WristAiProtocol
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch

/** 手机 → 手表：推送进度文字与最终摘要。失败只记日志，不影响手机端流程。 */
object WatchNotifier {
    private const val TAG = "WatchNotifier"

    fun sendStatus(context: Context, nodeId: String?, text: String) {
        send(context, nodeId, WristAiProtocol.PATH_STATUS, text.toByteArray(Charsets.UTF_8))
    }

    fun sendSummary(context: Context, nodeId: String?, summary: MeetingSummary) {
        // 补上录音时间与长度（手表要分开显示「录音时间」与「报告时间」）
        val m = MeetingStore.get(context, summary.recordingId)
        val full = if (m != null) summary.copy(recordedAt = m.createdAt, durationSec = m.durationSec) else summary
        send(context, nodeId, WristAiProtocol.PATH_SUMMARY, full.toBytes())
        if (nodeId != null) sendList(context, nodeId)   // 清单也更新（新的这笔排到最上面）
    }

    /**
     * 把外观主题推给所有连着的手表：手机选「水墨」→ 手表水墨；其他 → 手表深色（OLED 省电）。
     */
    fun broadcastTheme(context: Context) {
        val key = if (Settings.themeMode(context) == "ink") "ink" else "dark"
        broadcast(context, WristAiProtocol.PATH_THEME, key)
    }

    /** 把「今天吃了多少大卡 / 目标 / 几餐」推给手表的饮食分页。 */
    fun broadcastFoodToday(context: Context) {
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (MeetingStore.meetings.value.isEmpty()) MeetingStore.reload(context)
        val today = MeetingStore.meetings.value.filter {
            FoodLog.isFood(it.id) && it.createdAt >= start && it.status == MeetingStatus.DONE && it.nutrition != null
        }
        broadcast(context, WristAiProtocol.PATH_FOOD_TODAY,
            "${today.sumOf { it.nutrition!!.kcal }}/${Settings.dailyKcalGoal(context)}/${today.size}")
    }

    /**
     * 回应手表「看明细」：把那笔录音的报告（套用使用者改过的说话者名字）传回去。
     * 还没整理好就回目前状态；找不到就回 status = "找不到"。报告通常只有几 KB，保险起见截在 80 KB。
     */
    fun sendDetail(context: Context, nodeId: String, id: String) {
        val m = MeetingStore.get(context, id)
        val report = MeetingStore.reportFile(context, id).takeIf { it.exists() }?.readText()
        val md = report?.let { Speakers.apply(it, m?.speakerNames ?: emptyMap()) }?.let {
            if (it.toByteArray().size > 80_000) it.take(25_000) + "\n\n（內容較長，完整內容請在手機查看）" else it
        }
        val json = org.json.JSONObject()
            .put("id", id)
            .put("title", m?.title ?: "")
            .put("status", when { m == null -> "找不到"; m.status == MeetingStatus.DONE -> "DONE"; else -> m.status.label })
            .put("markdown", md ?: "")
            .put("durationSec", m?.durationSec ?: 0.0)
            .put("createdAt", m?.createdAt ?: 0L)
            .put("geo", MeetingStore.geo(context, id)?.let { org.json.JSONObject(it.toJson()) })
        send(context, nodeId, WristAiProtocol.PATH_DETAIL, json.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * 手表「報告書」的清单查询（报告上百笔也找得到）。请求 payload = JSON：
     *   q     关键字（空白分隔，每个字都要出现；比对标题、报告内容、逐字稿、说话者名字）
     *   kind  all / rec（录音：手表、手机、通话、汇入）/ food（饮食）
     *   month "2026-11"（空 = 不限）
     *   offset / limit  分页（一次最多 60 笔）
     *   key   手表自己的查询代号，原样带回，手表用来丢掉过时的回应
     * 回应 = JSON {key, total, offset, months:[{ym, count}], items:[{id,title,recordedAt,durationSec,kind,status}]}，新的在前。
     * months 是「同样的关键字 / 类别」下每个月有几笔，给手表「依月份」浏览用。
     */
    fun sendList(context: Context, nodeId: String, request: String = "") {
        val r = runCatching { org.json.JSONObject(request) }.getOrNull() ?: org.json.JSONObject()
        val terms = r.optString("q").lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val kind = r.optString("kind", "all")
        val month = r.optString("month")
        val offset = r.optInt("offset", 0).coerceAtLeast(0)
        val limit = r.optInt("limit", 30).coerceIn(1, 60)
        val ym = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US)

        MeetingStore.reload(context)
        var list = MeetingStore.meetings.value.sortedByDescending { it.createdAt }
        list = when (kind) {
            "food" -> list.filter { FoodLog.isFood(it.id) }
            "rec" -> list.filterNot { FoodLog.isFood(it.id) }
            else -> list
        }
        // 同一份搜寻索引（标题、日期、地点、说话者、报告、逐字稿；有快取）
        if (terms.isNotEmpty()) list = list.filter { m -> SearchIndex.matches(SearchIndex.doc(context, m), terms) }
        val months = list.groupingBy { ym.format(java.util.Date(it.createdAt)) }.eachCount()
        if (month.isNotBlank()) list = list.filter { ym.format(java.util.Date(it.createdAt)) == month }

        val items = org.json.JSONArray()
        list.drop(offset).take(limit).forEach { m ->
            items.put(org.json.JSONObject()
                .put("id", m.id)
                .put("title", com.example.wristai.ui.meetingTitle(m))
                .put("recordedAt", m.createdAt)
                .put("durationSec", m.durationSec)
                .put("kind", kindOf(m))
                .put("status", when (m.status) {
                    MeetingStatus.DONE -> "DONE"
                    MeetingStatus.FAILED -> if (m.error == ProcessMeetingWorker.CANCELED) "已中斷" else "失敗"
                    MeetingStatus.RECORDING -> "錄音中"
                    MeetingStatus.EXPORTED -> "已存錄音機"   // 装置端模式：在三星录音机用 Galaxy AI 整理，不是卡住
                    else -> "整理中"
                }))
        }
        val monthsArr = org.json.JSONArray()
        months.entries.sortedByDescending { it.key }.forEach { monthsArr.put(org.json.JSONObject().put("ym", it.key).put("count", it.value)) }
        val json = org.json.JSONObject()
            .put("key", r.optString("key"))
            .put("total", list.size)
            .put("offset", offset)
            .put("months", monthsArr)
            .put("items", items)
        send(context, nodeId, WristAiProtocol.PATH_LIST, json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun kindOf(m: Meeting): String = when {
        FoodLog.isFood(m.id) -> "food"
        m.source == "call" -> "call"
        m.source == "phone" -> "phone"
        m.id.startsWith("imp_") -> "import"
        else -> "watch"
    }

    /** 推给所有连着的手表（失败只记日志）。 */
    private fun broadcast(context: Context, path: String, text: String) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                val nodes = com.google.android.gms.tasks.Tasks.await(
                    Wearable.getCapabilityClient(context)
                        .getCapability(WristAiProtocol.CAPABILITY_WATCH, com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE)
                ).nodes
                for (n in nodes) com.google.android.gms.tasks.Tasks.await(
                    Wearable.getMessageClient(context).sendMessage(n.id, path, text.toByteArray()))
            }.onFailure { Log.w(TAG, "broadcast $path failed: ${it.message}") }
        }
    }

    private fun send(context: Context, nodeId: String?, path: String, data: ByteArray) {
        if (nodeId.isNullOrBlank()) return
        Wearable.getMessageClient(context).sendMessage(nodeId, path, data)
            .addOnFailureListener { Log.w(TAG, "sendMessage $path failed", it) }
    }
}
