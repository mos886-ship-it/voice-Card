package com.example.wristai

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

enum class MeetingStatus(val label: String) {
    /** 手表还在录（长会议边录边传，分段陆续进来） */
    RECORDING("錄音中".tr),
    RECEIVED("已接收".tr),
    EXPORTED("已存入三星錄音機".tr),
    TRANSCRIBING("語音辨識中".tr),
    SUMMARIZING("AI 整理報告中".tr),
    DONE("完成".tr),
    FAILED("失敗".tr),
}

data class Meeting(
    val id: String,
    val status: MeetingStatus,
    val createdAt: Long,
    val durationSec: Double,
    val watchNodeId: String?,
    val title: String?,
    val error: String?,
    /** 语音识别进度：下一个要处理的分段序号 / 总分段数 */
    val chunkDone: Int = 0,
    val chunkTotal: Int = 0,
    /** 存进三星录音机后的 MediaStore Uri（null = 还没存） */
    val exportedUri: String? = null,
    /** 说话者代号 → 名字（AI 从录音听出来的，或使用者手动改的） */
    val speakerNames: Map<String, String> = emptyMap(),
    /** 边录边传：已接进 audio.wav 的分段数 / 总分段数（-1 = 还不知道，最后一段还没到） */
    val segmentsAppended: Int = 0,
    val segmentsTotal: Int = -1,
    /** 来源："call" = 自动汇入的通话录音（null = 手表 / 手机录音 / 手动汇入） */
    val source: String? = null,
    /** 饮食记录的营养估算（只有饮食记录才有） */
    val nutrition: Nutrition? = null,
    /** 汇入的时间（null = 不是汇入的）。[createdAt] 永远是「录音时间」，汇入的档案会从档名 / 档案资料推回原本录音的时间。 */
    val importedAt: Long? = null,
    /** 整理这份报告的 AI 模型（例如 "gemini-3.8-flash"）；null = 旧报告没记录。 */
    val aiModel: String? = null,
    /** 这次处理的步骤（例如 接收錄音、存入錄音機、上傳音檔、AI 聆聽整理），给画面显示进度。 */
    val steps: List<String> = emptyList(),
    /** 目前在第几步（steps 的索引）；= steps.size 表示全部完成。 */
    val step: Int = -1,
    /** 目前这步的补充（例如「Fun-ASR · 2/5 段」）。 */
    val stepDetail: String? = null,
    val stepStartedAt: Long = 0,
    /** 这次处理的开始 / 结束时间（算总耗时）。 */
    val processStartedAt: Long = 0,
    val processEndedAt: Long = 0,
    /** 为什么改用别的引擎（例「大D的主機連不到（…），改用 Gemini」）；科技模式显示。 */
    val engineNote: String? = null,
    /** 辨识语言：zh（国语）/ nan（台语・国台语）；null（旧资料）＝国语。见 [SpeechLang]。 */
    val lang: String? = null,
    /** 客语腔调（lang = hak 时）：sixian / hailu / dapu / raoping / zhaoan / nansixian。见 [HakkaDialect]。 */
    val dialect: String? = null,
    /** 大D的主机处理后的清理结果（例「主機上的錄音與報告已刪除」），报告「基本資料」的「隱私」栏；没给主机处理 → null。 */
    val hostCleanup: String? = null,
    /** 录音用的麦克风（[MicRoute]）：phone / car:装置名:取样率；手表、汇入的 → null。 */
    val mic: String? = null,
) {
    fun toJson(): String = JSONObject()
        .put("id", id).put("status", status.name).put("createdAt", createdAt)
        .put("durationSec", durationSec).put("watchNodeId", watchNodeId)
        .put("title", title).put("error", error)
        .put("chunkDone", chunkDone).put("chunkTotal", chunkTotal)
        .put("exportedUri", exportedUri)
        .put("speakerNames", JSONObject(speakerNames as Map<*, *>))
        .put("segmentsAppended", segmentsAppended).put("segmentsTotal", segmentsTotal)
        .put("source", source)
        .put("nutrition", nutrition?.toJson())
        .put("importedAt", importedAt)
        .put("aiModel", aiModel)
        .put("steps", org.json.JSONArray(steps)).put("step", step).put("stepDetail", stepDetail)
        .put("stepStartedAt", stepStartedAt).put("processStartedAt", processStartedAt).put("processEndedAt", processEndedAt)
        .put("engineNote", engineNote)
        .put("lang", lang)
        .put("dialect", dialect)
        .put("hostCleanup", hostCleanup)
        .put("mic", mic)
        .toString(2)

    companion object {
        fun fromJson(text: String): Meeting {
            val j = JSONObject(text)
            fun JSONObject.optStringOrNull(k: String) = if (isNull(k)) null else optString(k)
            return Meeting(
                id = j.getString("id"),
                status = runCatching { MeetingStatus.valueOf(j.getString("status")) }
                    .getOrDefault(MeetingStatus.FAILED),
                createdAt = j.optLong("createdAt"),
                durationSec = j.optDouble("durationSec", 0.0),
                watchNodeId = j.optStringOrNull("watchNodeId"),
                title = j.optStringOrNull("title"),
                error = j.optStringOrNull("error"),
                chunkDone = j.optInt("chunkDone"),
                chunkTotal = j.optInt("chunkTotal"),
                exportedUri = j.optStringOrNull("exportedUri"),
                speakerNames = j.optJSONObject("speakerNames")?.let { o ->
                    o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotBlank() }
                } ?: emptyMap(),
                segmentsAppended = j.optInt("segmentsAppended"),
                segmentsTotal = j.optInt("segmentsTotal", -1),
                source = j.optStringOrNull("source"),
                nutrition = Nutrition.fromJson(j.optJSONObject("nutrition")),
                importedAt = if (j.has("importedAt") && !j.isNull("importedAt")) j.optLong("importedAt") else null,
                aiModel = j.optStringOrNull("aiModel")?.takeIf { it.isNotBlank() },
                steps = j.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                step = j.optInt("step", -1),
                stepDetail = j.optStringOrNull("stepDetail")?.takeIf { it.isNotBlank() },
                stepStartedAt = j.optLong("stepStartedAt"),
                processStartedAt = j.optLong("processStartedAt"),
                processEndedAt = j.optLong("processEndedAt"),
                engineNote = j.optStringOrNull("engineNote")?.takeIf { it.isNotBlank() },
                lang = j.optStringOrNull("lang")?.takeIf { it.isNotBlank() },
                dialect = j.optStringOrNull("dialect")?.takeIf { it.isNotBlank() },
                hostCleanup = j.optStringOrNull("hostCleanup")?.takeIf { it.isNotBlank() },
                mic = j.optStringOrNull("mic")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * 每场会议一个文件夹：filesDir/meetings/<id>/
 *   audio.wav       手表传来的原始录音
 *   meta.json       状态
 *   transcript.txt  逐字稿（语音识别结果，分段累加）
 *   report.md       Claude 整理的报告
 */
object MeetingStore {
    private val _meetings = MutableStateFlow<List<Meeting>>(emptyList())
    val meetings: StateFlow<List<Meeting>> = _meetings.asStateFlow()

    private fun root(context: Context) = File(context.filesDir, "meetings").apply { mkdirs() }
    /** 所有录音的资料夹（备份 / 取回用；不会建出某一笔的资料夹）。 */
    fun rootDir(context: Context) = root(context)
    fun dir(context: Context, id: String) = File(root(context), id).apply { mkdirs() }
    fun audioFile(context: Context, id: String) = File(dir(context, id), "audio.wav")
    fun transcriptFile(context: Context, id: String) = File(dir(context, id), "transcript.txt")
    fun reportFile(context: Context, id: String) = File(dir(context, id), "report.md")
    /** 手表传来的 AAC 分段（档名 = 序号_样本数.m4a），存进录音机后删除。 */
    fun segmentsDir(context: Context, id: String) = File(dir(context, id), "segments")
    /** 长会议分段送 Gemini 的中间结果（chunk_0.txt…），可以断点续做。 */
    fun geminiDir(context: Context, id: String) = File(dir(context, id), "gemini")
    /** 录音的 GPS 戳记（开始录音时的位置，手表或手机定位），独立档案，不跟 meta.json 抢写。 */
    fun geoFile(context: Context, id: String) = File(dir(context, id), "geo.json")
    fun geo(context: Context, id: String): com.example.voiceai.shared.GeoFix? =
        File(File(root(context), id), "geo.json").takeIf { it.exists() }?.let { com.example.voiceai.shared.GeoFix.fromJson(it.readText()) }

    /**
     * 地址各层（大 → 小）组成地名：去掉重复（不分大小写、忽略空白，例如 "Khan Sen Sok" 与 "Khan Sensok"）；
     * 中文直接接起来（台南市官田區），外文用逗号隔开（Phnom Penh, Khan Sen Sok）。
     */
    internal fun placeName(parts: List<String>): String {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val kept = mutableListOf<String>()
        for (p in parts.map { it.trim() }.filter { it.isNotEmpty() }) {
            val n = norm(p)
            if (n.isEmpty() || kept.any { norm(it).let { k -> k == n || k.contains(n) || n.contains(k) } }) continue
            kept += p
        }
        val cjk = kept.all { s -> s.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }
        return kept.joinToString(if (cjk) "" else ", ")
    }

    /** 存 GPS 戳记，顺便用 Geocoder 反查地名（要网路；查不到就只有座标）。请在背景执行。 */
    fun saveGeo(context: Context, id: String, fix: com.example.voiceai.shared.GeoFix) {
        // 手机（联动的主要来源）的位置优先：已经有手机定位，就不让手表的盖掉
        if (fix.source == "watch" && geo(context, id)?.source == "phone") return
        val place = fix.place ?: runCatching {
            if (!android.location.Geocoder.isPresent()) null
            else @Suppress("DEPRECATION") android.location.Geocoder(context, java.util.Locale.TAIWAN)
                .getFromLocation(fix.lat, fix.lon, 1)?.firstOrNull()?.let { a ->
                    placeName(listOfNotNull(a.adminArea, a.subAdminArea, a.locality, a.subLocality, a.thoroughfare))
                        .ifBlank { a.getAddressLine(0) }
                }
        }.getOrNull()
        geoFile(context, id).writeText(fix.copy(place = place).toJson())
        reload(context)
    }

    private fun metaFile(context: Context, id: String) = File(dir(context, id), "meta.json")

    @Synchronized
    fun get(context: Context, id: String): Meeting? =
        metaFile(context, id).takeIf { it.exists() }
            ?.let { runCatching { Meeting.fromJson(it.readText()) }.getOrNull() }

    @Synchronized
    fun save(context: Context, meeting: Meeting) {
        metaFile(context, meeting.id).writeText(meeting.toJson())
        reload(context)
    }

    @Synchronized
    fun update(context: Context, id: String, change: (Meeting) -> Meeting): Meeting? {
        val current = get(context, id) ?: return null
        val updated = change(current)
        save(context, updated)
        // 报告刚做完 → 自动备份到 Google 云端硬碟（有连结、有开自动备份才会排）
        if (current.status != MeetingStatus.DONE && updated.status == MeetingStatus.DONE)
            runCatching { com.example.wristai.backup.BackupWorker.scheduleAuto(context) }
        // 饮食记录做完（或营养数字改了）→ 同步到健康（有开才会写）
        if (updated.status == MeetingStatus.DONE && updated.nutrition != null &&
            (current.status != MeetingStatus.DONE || current.nutrition != updated.nutrition))
            HealthSync.push(context, updated)
        return updated
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        File(root(context), id).deleteRecursively()
        reload(context)
        HealthSync.remove(context, id)
    }

    @Synchronized
    fun reload(context: Context) {
        _meetings.value = root(context).listFiles()
            ?.mapNotNull { d -> File(d, "meta.json").takeIf { it.exists() } }
            ?.mapNotNull { runCatching { Meeting.fromJson(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }
}
