package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import androidx.compose.ui.graphics.Brush

import androidx.compose.runtime.setValue

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.material.icons.filled.Clear

import androidx.compose.material.icons.filled.Search

import androidx.compose.material3.OutlinedTextFieldDefaults

import androidx.compose.material3.OutlinedTextField

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.clickable

import androidx.compose.foundation.border

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.horizontalScroll

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.voiceai.shared.WristAiProtocol
import com.example.wristai.Meeting
import com.example.wristai.MeetingStore
import com.example.wristai.Settings
import com.example.wristai.VoiceRecorderExporter
import com.example.wristai.ui.theme.Brand
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 目前生效的 AI 引擎（决定报告由谁产生）。 */
data class EngineInfo(val name: String, val detail: String, val ready: Boolean, val color: Color)

fun currentEngine(context: Context): EngineInfo = when {
    // 大D的主機（使用者自己的电脑）：有设定就优先
    com.example.wristai.ai.LocalHost.configured(context) -> EngineInfo(
        com.example.wristai.ai.LocalHost.label(com.example.wristai.ai.LocalHost.lastTag(context)),
        "你自己的電腦 · 免費、錄音不出你的設備；連不到時自動改用其他引擎".tr, true, Brand.Success,
    )
    com.example.wristai.ai.AiConfig.mode(context) == com.example.wristai.ai.AiConfig.MODE_PIPELINE -> {
        val missing = com.example.wristai.ai.AiConfig.pipelineMissing(context)
        EngineInfo(
            com.example.wristai.ai.AiConfig.sttKind(context).label + " ＋ ".tr + com.example.wristai.ai.AiConfig.llmKind(context).label,
            missing ?: "語音辨識 ＋ 報告模型 · 錄完自動產生報告".tr, missing == null, if (missing == null) Brand.Cyan else Brand.Warning,
        )
    }
    com.example.wristai.ai.AiConfig.explicit(context) && com.example.wristai.ai.AiConfig.mode(context) == com.example.wristai.ai.AiConfig.MODE_DEVICE ->
        EngineInfo("Galaxy AI · 裝置端".tr, "錄音存入三星錄音機，在手機上轉錄 / 摘要（不上傳）".tr, VoiceRecorderExporter.launchIntent(context) != null, Brand.Violet)
    Settings.geminiKey(context).isNotBlank() -> EngineInfo("Gemini", "免費方案 · 錄完自動產生報告".tr, true, Brand.Cyan)
    Settings.apiKey(context).isNotBlank() -> EngineInfo("Claude", "API · 錄完自動產生報告".tr, true, Brand.Violet)
    VoiceRecorderExporter.launchIntent(context) != null ->
        EngineInfo("Galaxy AI · 裝置端".tr, "錄音自動存入三星錄音機，一鍵轉錄 / 摘要".tr, true, Brand.Violet)
    else -> EngineInfo("尚未設定".tr, "設定 AI 金鑰後即可自動產生報告".tr, false, Brand.Warning)
}

/** 查询已连线的手表名称；没有就返回 null。每 10 秒更新一次。 */
@Composable
fun rememberWatchName(): String? {
    val context = LocalContext.current
    val state = produceState<String?>(initialValue = null) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val cap = Tasks.await(
                        Wearable.getCapabilityClient(context)
                            .getCapability(WristAiProtocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE),
                        5, TimeUnit.SECONDS,
                    )
                    cap.nodes.firstOrNull()?.displayName
                        ?: Tasks.await(Wearable.getNodeClient(context).connectedNodes, 5, TimeUnit.SECONDS)
                            .firstOrNull()?.displayName
                }.getOrNull()
            }
            delay(10_000)
        }
    }
    return state.value
}

fun meetingTitle(m: Meeting): String = m.title ?: (when { com.example.wristai.FoodLog.isFood(m.id) -> "飲食 ".tr; m.source == "call" -> "通話 ".tr; else -> "錄音 ".tr }) + SimpleDateFormat("M/d HH:mm", Locale.TAIWAN).format(Date(m.createdAt))
/** 清单用的短日期：今年 → 11/21 19:03；其他年 → 2025/11/21 19:03。 */
fun shortWhen(ms: Long): String {
    val sameYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) ==
        java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.YEAR)
    return SimpleDateFormat(if (sameYear) "M/d HH:mm" else "yyyy/M/d HH:mm", Locale.TAIWAN).format(Date(ms))
}
fun formatWhen(ms: Long): String = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.TAIWAN).format(Date(ms))
/**
 * 录音的起讫时间：「2026/11/19 00:13:56 – 00:14:12（0:16）」。结束 = 开始 + 长度（最后录到的时间）；
 * 跨日就把结束的日期也写出来。
 */
fun recordingRange(m: Meeting): String {
    val start = m.createdAt
    val end = start + (m.durationSec * 1000).toLong()
    val day = SimpleDateFormat("yyyy/MM/dd", Locale.TAIWAN)
    val t = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN)
    val endText = if (day.format(Date(start)) == day.format(Date(end))) t.format(Date(end)) else day.format(Date(end)) + " " + t.format(Date(end))
    return "{0} {1} – {2}（{3}）".trf(day.format(Date(start)), t.format(Date(start)), endText, formatDuration(m.durationSec))
}

/**
 * AI 模型代号 → 好读的名字：gemini-3.8-flash → Google Gemini 3.8 Flash、
 * gemini-3.5-flash-lite → Google Gemini 3.5 Flash-Lite、claude-opus-5-5 → Anthropic Claude Opus 5.5。
 */
fun aiModelLabel(model: String?): String {
    if (model.isNullOrBlank()) return "未記錄（舊報告）".tr
    // 大D的主機（使用者自己的电脑）："local-funasr|qwen3-8b"
    if (com.example.wristai.ai.LocalHost.isLocalTag(model)) return com.example.wristai.ai.LocalHost.label(model)
    // 组合式："语音辨识模型|报告模型"
    if ('|' in model) {
        val (stt, llm) = model.split('|', limit = 2)
        return sttLabel(stt) + " ＋ ".tr + aiModelLabel(llm)
    }
    val m = model.lowercase()
    fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
    return when {
        m.startsWith("gemini-") -> {
            val p = m.removePrefix("gemini-").split('-')
            val ver = p.firstOrNull().orEmpty()
            val rest = p.drop(1).joinToString("-") { cap(it) }
            "Google Gemini ${ver}" + if (rest.isNotEmpty()) " ${rest}" else ""
        }
        m.startsWith("qwen") -> "阿里通義千問 {0}".trf(model)
        m.startsWith("llama") -> "Meta ${model}"
        m.startsWith("gpt-") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") -> "OpenAI ${model}"
        m in setOf("4.0ultra", "generalv3.5", "generalv3", "max-32k", "pro-128k", "lite") || m.startsWith("spark") || m.startsWith("x1") ->
            "訊飛星火 {0}".trf(model)
        m.startsWith("claude-") -> {
            val p = m.removePrefix("claude-").split('-')
            val name = p.takeWhile { s -> s.any { it.isLetter() } }.joinToString(" ") { cap(it) }
            val ver = p.dropWhile { s -> s.any { it.isLetter() } }.joinToString(".")
            "Anthropic Claude ${name} ${ver}".trim()
        }
        else -> model
    }
}

/** 语音辨识模型代号 → 名字。 */
private fun sttLabel(id: String): String = when {
    id.startsWith("fun-asr") || id.startsWith("paraformer") || id.startsWith("qwen") -> "阿里 Fun-ASR 語音辨識（{0}）".trf(id).replace("（fun-asr）", "")
    id == "iflytek-lfasr" -> "科大訊飛 錄音轉寫".tr
    id.startsWith("gpt-") || id.startsWith("whisper") -> "OpenAI 轉錄（{0}）".trf(id)
    else -> id
}

/** 分享 / 汇出的报告：标题下面加一行「錄音時間：起 – 讫（长度）」。 */
fun withRecordingTime(report: String, m: Meeting, geo: com.example.voiceai.shared.GeoFix? = null): String {
    val line = "錄音時間：${recordingRange(m)}" + (m.importedAt?.let { "　匯入：{0}".trf(formatWhen(it)) } ?: "") +
        (geo?.let { "\n錄音地點：${it.place ?: "%.5f, %.5f".format(it.lat, it.lon)}　${it.mapUrl}" } ?: "") +
        (m.aiModel?.let { "\n報告整理：{0}".trf(aiModelLabel(it)) } ?: "")
    val lines = report.lines()
    return if (lines.firstOrNull()?.startsWith("# ") == true)
        (listOf(lines.first(), "", line) + lines.drop(1)).joinToString("\n")
    else "${line}\n\n${report}"
}

fun formatDuration(sec: Double): String {
    val s = sec.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * 「資料庫」分页：搜寻 + 来源筛选 + 完整列表（录音用底部中间的录音键；今天的总览在「今天」，饮食在「飲食」分页）。
 * 右上「管理」= 档案管理（原本的「檔案」分页）。
 */
@Composable
fun HomeScreen(onOpen: (String) -> Unit, onOpenSettings: () -> Unit, onImport: (Context, Uri) -> Unit, onManage: () -> Unit = {}, overview: Boolean = false, embedded: Boolean = false) {
    val context = LocalContext.current
    val all by MeetingStore.meetings.collectAsState()
    val meetings = remember(all) { all.filter { it.origin() != Origin.FOOD } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(context, uri)
    }
    LaunchedEffect(Unit) { MeetingStore.reload(context) }

    // 搜寻（SearchIndex：标题、日期、地点、说话者、报告、逐字稿；关键字空白分隔、全部都要出现）
    var query by rememberSaveable { mutableStateOf("") }
    var origin by rememberSaveable { mutableStateOf<String?>(null) }
    // 打字停 250ms 才搜，避免每个字都重算
    var debounced by remember { mutableStateOf(query) }
    LaunchedEffect(query) { kotlinx.coroutines.delay(250); debounced = query }
    val terms = remember(debounced) { com.example.wristai.SearchIndex.terms(debounced) }
    var docs by remember { mutableStateOf<Map<String, com.example.wristai.SearchIndex.Doc>>(emptyMap()) }
    LaunchedEffect(meetings) {
        docs = withContext(Dispatchers.IO) { meetings.associate { it.id to com.example.wristai.SearchIndex.doc(context, it) } }
    }
    val byOrigin = meetings.filter { m -> origin == null || m.origin().name == origin }
    val shown = if (terms.isEmpty()) byOrigin else byOrigin.filter { m ->
        val d = docs[m.id]
        if (d != null) com.example.wristai.SearchIndex.matches(d, terms)
        else terms.all { meetingTitle(m).lowercase().contains(it) }   // 索引还没建好：先比标题
    }
    val snippets = remember(shown, terms, docs) {
        if (terms.isEmpty()) emptyMap()
        else shown.mapNotNull { m -> docs[m.id]?.let { d -> com.example.wristai.SearchIndex.snippet(d, terms)?.let { m.id to it } } }.toMap()
    }
    // 一行摘要（报告的「摘要」段，从搜寻索引拿，不另外读档）
    val summaries = remember(docs) {
        docs.mapValues { (_, d) -> summaryOf(d.parts.firstOrNull { it.first == "報告" }?.second) }
    }
    // 依日期分组：今天 / 昨天 / 11月20日 週四
    val groups = remember(shown) { shown.groupBy { dayLabel(it.createdAt) }.toList() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = if (embedded) 0.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------- 标题 ----------
        if (!embedded) item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (overview) "錄音".tr else "資料庫".tr, style = MaterialTheme.typography.headlineSmall, color = Brand.Text)
                    Text(if (meetings.isEmpty()) "會議、課堂、訪談、通話、靈感筆記".tr else "共 {0} 段 · {1}".trf(meetings.size, humanDuration(meetings.sumOf { it.durationSec })),
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                }
                if (!overview) {
                    GhostButton("管理".tr, onManage, tint = Brand.Cyan)
                    Spacer(Modifier.width(8.dp))
                }
                GhostButton("匯入".tr, { picker.launch(arrayOf("audio/*")) }, icon = Icons.Filled.Add, tint = Brand.Cyan)
            }
        }

        // ---------- 總覽排布：手机录音卡（快錄用底部中间的录音键） ----------
        if (overview) item { PhoneRecordCard(onOpenMeeting = onOpen) }

        // ---------- 列表 ----------
        if (meetings.isNotEmpty()) {
            item { SearchField(query) { query = it } }
            val origins = Origin.entries.filter { o -> o != Origin.FOOD && meetings.any { it.origin() == o } }
            if (origins.size > 1) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("全部".tr, origin == null) { origin = null }
                    for (o in origins) {
                        val n = meetings.count { it.origin() == o }
                        FilterChip("${o.label} ${n}", origin == o.name) { origin = if (origin == o.name) null else o.name }
                    }
                }
            }
        }
        if (meetings.isEmpty()) {
            item { EmptyState() }
        } else if (shown.isEmpty()) {
            item {
                Text("找不到符合「{0}」的錄音".trf(debounced.trim()) + if (origin != null) "（目前只看「{0}」）".trf(Origin.valueOf(origin!!).label) else "",
                    style = MaterialTheme.typography.bodyMedium, color = Brand.TextFaint,
                    modifier = Modifier.padding(vertical = 24.dp))
            }
        } else if (terms.isNotEmpty()) {
            item {
                Text("找到 {0} 筆".trf(shown.size), style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
            }
        }
        for ((day, list) in groups) {
            item(key = "d_${day}") {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(day, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                    Spacer(Modifier.width(8.dp))
                    Text("{0} 筆".trf(list.size), style = MaterialTheme.typography.labelMedium, color = Brand.TextFaint)
                }
            }
            items(list, key = { it.id }) { m ->
                val sn = snippets[m.id]?.let { highlight(if (it.text.isEmpty()) "符合：{0}".trf(it.where.tr) else "{0}：{1}".trf(it.where.tr, it.text), terms) }
                RecordRow(m, summaries[m.id], sn, onOpen = { onOpen(m.id) }, onDelete = { MeetingStore.delete(context, m.id) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 顶栏右边的手表状态：绿点 = 已连线；点一下到设定。 */
@Composable
internal fun WatchPill(watch: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).background(Brand.Surface).border(1.dp, Brand.Outline, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (watch != null) Brand.Success else Brand.TextFaint))
        Spacer(Modifier.width(6.dp))
        Text(if (watch != null) "手錶已連線".tr else "手錶未連線".tr, style = MaterialTheme.typography.labelMedium,
            color = if (watch != null) Brand.Text else Brand.TextMuted)
    }
}

/** 设定分页最上面：AI 引擎与手表状态。 */
@Composable
fun SystemStatusCard() {
    val context = LocalContext.current
    // 设定页改了金钥 / 主机，状态马上跟着变
    val engine by produceState(currentEngine(context)) { while (true) { delay(2_000); value = currentEngine(context) } }
    val watch = rememberWatchName()
    GlassCard(highlight = true) {
        Text("系統狀態".tr, style = MaterialTheme.typography.labelSmall, color = Brand.TextMuted)
        Spacer(Modifier.height(12.dp))
        StatusRow("AI 報告引擎".tr, engine.name, engine.detail, if (engine.ready) Brand.Success else engine.color)
        // 商用模式：手表只在有连线时显示；科技模式一律显示
        if (watch != null || UiMode.tech) {
            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            StatusRow(
                "智慧手錶".tr, watch ?: "未連線".tr,
                if (watch != null) "在手錶按下錄音即可開始".tr else "請確認手錶已透過 Galaxy Wearable 配對".tr,
                if (watch != null) Brand.Success else Brand.TextFaint,
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, detail: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Brand.TextMuted)
            Text(value, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
        }
    }
}

/** 把文字里的关键字标亮（不分大小写）。 */
private fun highlight(text: String, terms: List<String>): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        val low = text.lowercase()
        for (t in terms) {
            var i = low.indexOf(t)
            while (t.isNotEmpty() && i >= 0) {
                addStyle(androidx.compose.ui.text.SpanStyle(color = Brand.Cyan, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), i, i + t.length)
                i = low.indexOf(t, i + t.length)
            }
        }
    }

@Composable
private fun EmptyState() {
    GlassCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GlyphTile({ Waveform(Modifier.size(28.dp), Color.White) }, size = 64.dp, filled = true)
            Spacer(Modifier.height(16.dp))
            Text("還沒有錄音".tr, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
            Spacer(Modifier.height(4.dp))
            Text("在手錶按下「錄音」，結束後會自動傳到這裡分析".tr,
                style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        }
    }
}

/** 会议来源（首页筛选、列表标示用）。 */
enum class Origin(val label: String) { WATCH("手錶".tr), PHONE("手機".tr), CALL("通話".tr), FOOD("飲食".tr), IMPORT("匯入".tr) }

fun Meeting.origin(): Origin = when {
    com.example.wristai.FoodLog.isFood(id) -> Origin.FOOD
    source == "call" -> Origin.CALL
    source == "phone" -> Origin.PHONE
    id.startsWith("imp_") -> Origin.IMPORT
    else -> Origin.WATCH
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text("搜尋：關鍵字、人名、地點、日期（空白分隔）".tr, color = Brand.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = Brand.TextMuted) },
        trailingIcon = if (value.isNotEmpty()) ({
            Icon(Icons.Filled.Clear, "清除".tr, tint = Brand.TextMuted, modifier = Modifier.clickable { onChange("") })
        }) else null,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Brand.Text, unfocusedTextColor = Brand.Text,
            focusedBorderColor = Brand.Cyan, unfocusedBorderColor = Brand.Outline, cursorColor = Brand.Cyan,
            focusedContainerColor = Brand.Surface, unfocusedContainerColor = Brand.Surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .background(if (selected) Brand.Accent else Brush.linearGradient(listOf(Brand.Surface, Brand.Surface)))
            .border(1.dp, if (selected) Color.Transparent else Brand.Outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.White else Brand.TextMuted)
    }
}
