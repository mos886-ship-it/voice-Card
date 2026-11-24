package com.example.wristai.ui

import androidx.compose.foundation.layout.size

import androidx.compose.material.icons.filled.Edit

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.wristai.MeetingStatus
import com.example.wristai.MeetingStore
import com.example.wristai.ProcessMeetingWorker
import com.example.wristai.Settings
import com.example.wristai.Speakers
import com.example.wristai.VoiceRecorderExporter
import com.example.wristai.ui.theme.Brand

@Composable
fun DetailScreen(id: String, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val meetings by MeetingStore.meetings.collectAsState()
    val meeting = meetings.firstOrNull { it.id == id }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 换辨识语言（已有报告时要先确认，会用新语言重新产生）
    var pendingLang by remember { mutableStateOf<com.example.wristai.SpeechLang?>(null) }
    var pendingDialect by remember { mutableStateOf<com.example.wristai.HakkaDialect?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var editingTitle by remember { mutableStateOf(false) }

    if (meeting == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val names = meeting.speakerNames
    val report = remember(meeting) {
        MeetingStore.reportFile(context, id).takeIf { it.exists() }?.readText()?.let { Speakers.apply(it, names) }
    }
    val transcript = remember(meeting) { MeetingStore.transcriptFile(context, id).takeIf { it.exists() }?.readText()?.ifBlank { null } }
    val style = meeting.statusStyle()
    // 录音中不播放（档案还在写）；其他状态有录音档就可以播
    val audioFile = remember(meeting.status) {
        MeetingStore.audioFile(context, id).takeIf { it.exists() && it.length() > 1024 && meeting.status != MeetingStatus.RECORDING }
    }
    val player = audioFile?.let { rememberPlayerState(it) }
    val recorder = remember { VoiceRecorderExporter.launchIntent(context) }
    val canReport = com.example.wristai.ai.AiConfig.canReport(context)

    fun regenerate() {
        // 长会议的分段结果一起清掉（说话者代号要整场重新编）
        MeetingStore.geminiDir(context, id).deleteRecursively()
        MeetingStore.update(context, id) {
            it.copy(status = MeetingStatus.RECEIVED, error = null, chunkDone = 0, chunkTotal = 0,
                steps = emptyList(), step = -1, stepDetail = null, processStartedAt = 0, processEndedAt = 0)
        }
        ProcessMeetingWorker.enqueue(context, id)
    }

    Column(Modifier.fillMaxSize()) {
        // GPS 戳记（手表传来的可能晚一点到，等一下再读）
        var geo by remember(meeting.id) { mutableStateOf<com.example.voiceai.shared.GeoFix?>(null) }
        LaunchedEffect(meeting.id) {
            repeat(20) {
                geo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MeetingStore.geo(context, meeting.id) }
                if (geo != null) return@LaunchedEffect
                kotlinx.coroutines.delay(3000)
            }
        }
        TopBar("錄音詳情", onBack) {
            if (report != null) {
                IconCircle(Icons.Filled.Share, "分享", {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, meetingTitle(meeting))
                        .putExtra(Intent.EXTRA_TEXT, buildString {
                            append(withRecordingTime(report, meeting, geo))
                            if (transcript != null) append("\n\n## 逐字稿\n").append(Speakers.apply(transcript, names))
                        })
                    context.startActivity(Intent.createChooser(share, "分享報告"))
                })
                Spacer(Modifier.width(8.dp))
            }
            IconCircle(Icons.Filled.Delete, "刪除", { confirmDelete = true }, tint = Brand.Error)
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------- 标题区 ----------
            Column {
                Row(Modifier.clickable { editingTitle = true }, verticalAlignment = Alignment.CenterVertically) {
                    Text(meetingTitle(meeting), style = MaterialTheme.typography.headlineSmall, color = Brand.Text,
                        modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Edit, "改標題", Modifier.size(16.dp), tint = Brand.TextFaint)
                }
                Spacer(Modifier.height(6.dp))
                // 录音时间与汇入时间分开显示（汇入的档案 createdAt 是推回来的原始录音时间）
                // 录音起讫（含最后录到的时间）；汇入的另外显示汇入时间
                Text("錄音  ${recordingRange(meeting)}",
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                meeting.importedAt?.let {
                    Text("匯入  ${formatWhen(it)}", style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
                }
                Spacer(Modifier.height(10.dp))
                StatusChip(style)
            }

            // ---------- 辨识语言：可以换语言重新产生，方便对照 ----------
            if (!com.example.wristai.FoodLog.isFood(meeting.id)) {
                val lang = com.example.wristai.SpeechLang.of(meeting.lang)
                val canChange = !style.busy && meeting.status != MeetingStatus.RECORDING
                val hasReport = report != null || meeting.status == MeetingStatus.DONE
                LangPicker(lang, enabled = canChange) { l ->
                    if (hasReport) { pendingDialect = null; pendingLang = l }
                    else MeetingStore.update(context, id) {
                        it.copy(lang = l.code,
                            dialect = if (l == com.example.wristai.SpeechLang.HAK) it.dialect ?: com.example.wristai.HakkaDialect.currentCode(context) else null)
                    }
                }
                if (lang == com.example.wristai.SpeechLang.HAK) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("辨識語言：" + com.example.wristai.SpeechLang.display(meeting.lang, meeting.dialect) + "腔",
                            style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted, modifier = Modifier.weight(1f))
                        DialectMenu(com.example.wristai.HakkaDialect.of(meeting.dialect), enabled = canChange) { d ->
                            if (hasReport) { pendingLang = lang; pendingDialect = d }
                            else MeetingStore.update(context, id) { it.copy(dialect = d.code) }
                        }
                    }
                }
            }

            // ---------- 饮食记录的营养 ----------
            if (meeting.nutrition != null) NutritionHeader(meeting)

            // ---------- 状态卡 ----------
            when {
                meeting.status == MeetingStatus.RECORDING -> GlassCard(highlight = true) {
                    Text(if (meeting.source == "phone") "手機正在錄音" else "手錶正在錄音", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已同步 ${formatDuration(meeting.durationSec)} 到手機（每 5 分鐘一段）。" +
                            if (canReport) "AI 會先分析已錄好的部分，錄音結束後很快就有完整報告。" else "錄音結束後會存入三星錄音機。",
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                    )
                    if (meeting.chunkDone > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text("已預先分析 ${meeting.chunkDone} 段（每段 ${Settings.geminiChunkSec(context) / 60} 分鐘）",
                            style = MaterialTheme.typography.labelMedium, color = Brand.Cyan)
                    }
                    Spacer(Modifier.height(14.dp))
                    ScanningBar()
                }
                style.busy && meeting.steps.isNotEmpty() -> GlassCard(highlight = true) {
                    ProcessSteps(meeting, onCancel = { com.example.wristai.ProcessMeetingWorker.cancel(context, id) })
                }
                style.busy -> GlassCard(highlight = true) {
                    Text("AI 正在分析這段錄音", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (meeting.chunkTotal > 1)
                            "長錄音分成 ${meeting.chunkTotal} 段分析，最後整合成一份報告。完成後會通知你，並把摘要推送到手錶。"
                        else "聆聽錄音、分辨每位說話者、整理重點與待辦事項。完成後會通知你，並把摘要推送到手錶。",
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                    Spacer(Modifier.height(14.dp))
                    ScanningBar()
                    Spacer(Modifier.height(12.dp))
                    GhostButton("中斷處理", { com.example.wristai.ProcessMeetingWorker.cancel(context, id) },
                        Modifier.fillMaxWidth(), tint = Brand.Error)
                }
                meeting.status == MeetingStatus.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AlertCard(
                        title = if (meeting.error == com.example.wristai.ProcessMeetingWorker.CANCELED) "已中斷處理"
                            else meeting.steps.getOrNull(meeting.step)?.let { "「$it」這一步失敗" } ?: "報告產生失敗",
                        body = if (meeting.error == com.example.wristai.ProcessMeetingWorker.CANCELED) "按「重試」可以接著處理。"
                            else meeting.error ?: "未知錯誤",
                        action = "重試" to ::regenerate,
                    )
                    if (meeting.steps.isNotEmpty()) GlassCard { ProcessSteps(meeting) }
                }
                meeting.status == MeetingStatus.EXPORTED && !canReport -> GlassCard {
                    Text(if (meeting.source == "call") "通話錄音已匯入" else "已存入三星錄音機", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                    Spacer(Modifier.height(4.dp))
                    Text("可在錄音機用 Galaxy AI 手動轉錄與摘要；或啟用免費的 Gemini，讓 大D 自動產生完整報告。",
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientButton("啟用自動報告", onOpenSettings, Modifier.weight(1f))
                        if (recorder != null) GhostButton("開啟錄音機", { context.startActivity(recorder) }, Modifier.weight(1f))
                    }
                }
            }

            // 这次为什么改用别的引擎（例：大D的主機连不到）；还没有报告时两种模式都显示
            meeting.engineNote?.takeIf { report == null || UiMode.tech }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Warning)
            }

            // ---------- 操作 ----------
            if (!style.busy && canReport && meeting.status != MeetingStatus.FAILED) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GradientButton(
                        if (report == null) "產生 AI 報告" else "重新產生",
                        ::regenerate, Modifier.weight(1f),
                        icon = if (report == null) Icons.Filled.PlayArrow else Icons.Filled.Refresh,
                    )
                    if (meeting.exportedUri != null && recorder != null) {
                        GhostButton("錄音機", { context.startActivity(recorder) }, Modifier.weight(1f))
                    }
                }
            }

            // ---------- 内容 ----------
            if (report != null || transcript != null) {
                audioFile?.let { AudioPlayerBar(player!!) }
                Segmented(listOf("報告", "逐字稿"), tab) { tab = it }
                GlassCard {
                    if (tab == 0) {
                        // 报告的基本资料栏位：时间、地点、来源（不靠 AI 写，旧报告也有）
                        ReportFacts(meeting, geo, onOpenMap = { g ->
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse("geo:${g.lat},${g.lon}?q=${g.lat},${g.lon}")))
                            }.onFailure {
                                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(g.mapUrl)))
                            }
                        })
                        Spacer(Modifier.height(12.dp))
                        if (report != null) ReportView(report, names = names, onRenameSpeaker = { renaming = it }) else Empty("尚未產生報告")
                    } else {
                        if (transcript != null) TranscriptView(
                            transcript, names = names, onRename = { renaming = it },
                            onSeek = player?.let { p -> { ms: Int -> p.seekTo(ms, play = true) } },
                            currentMs = player?.takeIf { it.playing || it.positionMs > 0 }?.positionMs ?: -1,
                        ) else Empty("尚無逐字稿")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (editingTitle) {
        var value by remember { mutableStateOf(meeting.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingTitle = false },
            containerColor = Brand.SurfaceHigh,
            title = { Text("標題", color = Brand.Text) },
            text = {
                OutlinedTextField(
                    value, { value = it }, singleLine = true,
                    placeholder = { Text(meetingTitle(meeting), color = Brand.TextFaint) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Brand.Text, unfocusedTextColor = Brand.Text,
                        focusedBorderColor = Brand.Cyan, unfocusedBorderColor = Brand.Outline, cursorColor = Brand.Cyan,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    MeetingStore.update(context, id) { it.copy(title = value.trim().ifBlank { null }) }
                    editingTitle = false
                }) { Text("儲存", color = Brand.Cyan) }
            },
            dismissButton = { TextButton(onClick = { editingTitle = false }) { Text("取消", color = Brand.TextMuted) } },
        )
    }

    renaming?.let { label ->
        var value by remember(label) { mutableStateOf(names[label].orEmpty()) }
        fun save(newName: String) {
            MeetingStore.update(context, id) { m ->
                m.copy(speakerNames = if (newName.isBlank()) m.speakerNames - label else m.speakerNames + (label to newName.trim()))
            }
            renaming = null
        }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = Brand.SurfaceHigh,
            title = { Text("$label 是誰？", color = Brand.Text) },
            text = {
                Column {
                    Text("改名後，報告、逐字稿和分享內容都會換成這個名字。", style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value, { value = it }, singleLine = true,
                        placeholder = { Text("例如：王經理", color = Brand.TextFaint) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Brand.Text, unfocusedTextColor = Brand.Text,
                            focusedBorderColor = Brand.Cyan, unfocusedBorderColor = Brand.Outline, cursorColor = Brand.Cyan,
                        ),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { save(value) }) { Text("儲存", color = Brand.Cyan) } },
            dismissButton = {
                TextButton(onClick = { if (names.containsKey(label)) save("") else renaming = null }) {
                    Text(if (names.containsKey(label)) "還原代號" else "取消", color = Brand.TextMuted)
                }
            },
        )
    }

    /** 换成客语时用的腔调：刚选的，或这笔原本的，或目前设定。 */
    fun hakDialect(l: com.example.wristai.SpeechLang): com.example.wristai.HakkaDialect? =
        if (l != com.example.wristai.SpeechLang.HAK) null
        else pendingDialect ?: com.example.wristai.HakkaDialect.of(
            MeetingStore.get(context, id)?.dialect ?: com.example.wristai.HakkaDialect.currentCode(context))

    pendingLang?.let { l ->
        AlertDialog(
            onDismissRequest = { pendingLang = null; pendingDialect = null },
            containerColor = Brand.SurfaceHigh,
            title = { Text("改用「${l.label}${hakDialect(l)?.let { "・" + it.label + "腔" } ?: ""}」重新產生？", color = Brand.Text) },
            text = {
                Text(
                    (when (l) {
                        com.example.wristai.SpeechLang.NAN -> "台語・國台語辨識比較慢（50 秒的錄音約 1 分鐘）。"
                        com.example.wristai.SpeechLang.HAK -> "客語辨識比較慢；大D的主機還沒裝客語模型時會改用 Gemini。"
                        else -> ""
                    }) +
                        "目前的報告和逐字稿會被新的取代。",
                    color = Brand.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val d = hakDialect(l)
                    pendingLang = null; pendingDialect = null
                    MeetingStore.update(context, id) { it.copy(lang = l.code, dialect = d?.code) }
                    regenerate()
                }) { Text("重新產生", color = Brand.Cyan) }
            },
            dismissButton = { TextButton(onClick = { pendingLang = null }) { Text("取消", color = Brand.TextMuted) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Brand.SurfaceHigh,
            title = { Text("刪除這段錄音？", color = Brand.Text) },
            text = { Text("會刪除 大D 裡的錄音、報告與逐字稿，無法復原。已存入三星錄音機的檔案不受影響。", color = Brand.TextMuted) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; MeetingStore.delete(context, id); onBack() }) {
                    Text("刪除", color = Brand.Error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = Brand.TextMuted) } },
        )
    }
}

/** 胶囊式分段切换。 */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(Brand.Surface).border(1.dp, Brand.Outline, shape).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) Brand.Accent else androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else Brand.TextMuted)
            }
        }
    }
}

@Composable
private fun AlertCard(title: String, body: String, action: Pair<String, () -> Unit>) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Brand.Error.copy(alpha = 0.08f))
            .border(1.dp, Brand.Error.copy(alpha = 0.4f), shape).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = Brand.Error)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
        }
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        Spacer(Modifier.height(12.dp))
        GhostButton(action.first, action.second, icon = Icons.Filled.Refresh, tint = Brand.Text)
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Brand.TextFaint, modifier = Modifier.padding(vertical = 12.dp))
}
