package com.example.wristai.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import com.example.wristai.ActionItems
import com.example.wristai.Meeting
import com.example.wristai.MeetingStatus
import com.example.wristai.MeetingStore
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 「錄音」分页：錄音（清单）｜分析｜管理（档案）｜設定。 */
@Composable
fun RecordsHub(
    onOpen: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onImport: (Context, Uri) -> Unit,
    overview: Boolean,
) {
    val context = LocalContext.current
    val all by MeetingStore.meetings.collectAsState()
    val meetings = remember(all) { all.filter { it.origin() != Origin.FOOD } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(context, uri)
    }
    HubScaffold(
        title = "錄音報告".tr,
        subtitle = if (meetings.isEmpty()) "會議、課堂、訪談、通話、靈感筆記".tr
            else "共 {0} 段 · {1}".trf(meetings.size, humanDuration(meetings.sumOf { it.durationSec })),
        sections = listOf("錄音".tr, "分析".tr, "管理".tr, "設定".tr),
        selected = Hubs.records,
        onSelect = { Hubs.records = it },
        actions = { GhostButton("匯入".tr, { picker.launch(arrayOf("audio/*")) }, icon = Icons.Filled.Add, tint = Brand.Cyan) },
    ) { s ->
        when (s) {
            0 -> HomeScreen(onOpen = onOpen, onOpenSettings = onOpenSettings, onImport = onImport, overview = overview, embedded = true)
            1 -> RecordsAnalysis(meetings, onOpen)
            2 -> FilesScreen(onBack = null, onOpen = onOpen, onOpenSettings = onOpenSettings, embedded = true)
            else -> HubPage {
                RecordingLanguageCard()
                EngineSummaryCard(onOpenSettings)
                SectionLabel("自動整理".tr)
                CallRecordingCard()
                GalaxyExportCard()
                SectionLabel("地點與手錶".tr)
                RecordLocationCard()
                WatchRemoteCard()
                AllSettingsLink(onOpenSettings)
            }
        }
    }
}

/** 錄音的分析：数量、时长、每天长条、来源、常出现的人、待办总览、需要处理的。 */
@Composable
private fun RecordsAnalysis(meetings: List<Meeting>, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var range by rememberSaveable { mutableStateOf(0) }   // 0 = 7 天, 1 = 30 天
    val days = if (range == 0) 7 else 30
    val starts = remember(days) { dayStarts(days) }
    val inRange = remember(meetings, days) { meetings.filter { it.createdAt >= starts.first() && it.status != MeetingStatus.RECORDING } }

    // 待办：读这段期间做好的报告
    var items by remember { mutableStateOf<List<ActionItems.Item>>(emptyList()) }
    var done by remember { mutableStateOf(ActionItems.done(context)) }
    LaunchedEffect(inRange.map { it.id to it.status }) {
        items = withContext(Dispatchers.IO) {
            inRange.filter { it.status == MeetingStatus.DONE }.sortedByDescending { it.createdAt }.flatMap { m ->
                val f = MeetingStore.reportFile(context, m.id)
                if (f.exists()) ActionItems.parse(m.id, f.readText()) else emptyList()
            }
        }
    }

    HubPage {
        Segmented(listOf("近 7 天".tr, "近 30 天".tr), range) { range = it }
        if (inRange.isEmpty()) { EmptyNote("這段期間沒有錄音。".tr); return@HubPage }

        val total = inRange.sumOf { it.durationSec }
        StatRow(
            "${inRange.size}" to "段錄音".tr,
            humanDuration(total) to "總長度".tr,
            formatDuration(total / inRange.size) to "平均".tr,
        )

        // 每天录了几分钟
        GlassCard {
            Text("每天錄音（分鐘）".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Spacer(Modifier.height(10.dp))
            val mins = starts.map { s -> inRange.filter { it.createdAt in s until s + 86_400_000L }.sumOf { it.durationSec }.toFloat() / 60f }
            BarChart(mins, dayLabels(starts))
        }

        // 待办总览
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("待辦事項".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text, modifier = Modifier.weight(1f))
                val open = items.count { it.key !in done }
                Text("未完成 {0} / {1}".trf(open, items.size), style = MaterialTheme.typography.labelLarge, color = if (open > 0) Brand.Cyan else Brand.TextFaint)
            }
            if (items.isEmpty()) Text("報告裡沒有待辦事項。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                modifier = Modifier.padding(top = 6.dp))
            val byId = meetings.associateBy { it.id }
            // 未完成的在前面
            for (it in items.sortedBy { i -> i.key in done }.take(30)) {
                val m = byId[it.meetingId]
                val checked = it.key in done
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Checkbox(checked, { on -> ActionItems.setDone(context, it, on); done = ActionItems.done(context) },
                        colors = CheckboxDefaults.colors(checkedColor = Brand.Cyan, uncheckedColor = Brand.TextMuted))
                    Column(Modifier.weight(1f).padding(top = 12.dp).clickable { m?.let { mm -> onOpen(mm.id) } }) {
                        Text(it.task, style = MaterialTheme.typography.bodyMedium, color = if (checked) Brand.TextFaint else Brand.Text,
                            textDecoration = if (checked) TextDecoration.LineThrough else null)
                        val owner = it.owner?.let { o -> m?.speakerNames?.entries?.firstOrNull { e -> o.contains(e.key) }?.value ?: o }
                        val meta = listOfNotNull(owner, it.due, m?.let { mm -> meetingTitle(mm) }).joinToString(" · ")
                        Text(meta, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // 来源
        GlassCard {
            Text("來源".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Spacer(Modifier.height(6.dp))
            for (o in Origin.entries.filter { it != Origin.FOOD }) {
                val list = inRange.filter { it.origin() == o }
                if (list.isEmpty()) continue
                ShareBar(o.label, "{0} 段 · {1}".trf(list.size, humanDuration(list.sumOf { it.durationSec })), list.size.toFloat() / inRange.size)
            }
        }

        // 常出现的人（AI 听出来或手动改的说话者名字）
        val people = inRange.flatMap { it.speakerNames.values }.filter { it.isNotBlank() && !com.example.wristai.ReportLang.isSpeakerCode(it) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6)
        if (people.isNotEmpty()) GlassCard {
            Text("常出現的人".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Spacer(Modifier.height(6.dp))
            val top = people.first().value.toFloat()
            for ((name, n) in people) ShareBar(name, "{0} 段".trf(n), n / top, Brand.Violet)
        }

        // 需要处理：失败的
        val failed = inRange.filter { it.status == MeetingStatus.FAILED }
        if (failed.isNotEmpty()) GlassCard(highlight = true) {
            Text("需要處理（{0}）".trf(failed.size), style = MaterialTheme.typography.titleSmall, color = Brand.Warning)
            for (m in failed) Row(Modifier.fillMaxWidth().clickable { onOpen(m.id) }.padding(vertical = 6.dp)) {
                Text(meetingTitle(m), style = MaterialTheme.typography.bodyMedium, color = Brand.Text, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text("打開重試 →".tr, style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
            }
        }

        // 科技模式：整理报告用了哪些 AI
        if (UiMode.tech) {
            val models = inRange.mapNotNull { it.aiModel }.groupingBy { aiModelLabel(it) }.eachCount().entries.sortedByDescending { it.value }
            if (models.isNotEmpty()) GlassCard {
                Text("整理報告的 AI".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                Spacer(Modifier.height(6.dp))
                for ((name, n) in models) ShareBar(name, "{0} 段".trf(n), n.toFloat() / inRange.size, Brand.Violet)
            }
        }
    }
}
