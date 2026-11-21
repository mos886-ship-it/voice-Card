package com.example.wristai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.wristai.FoodLog
import com.example.wristai.Meeting
import com.example.wristai.MeetingStatus
import com.example.wristai.MeetingStore
import com.example.wristai.PhoneRecorder
import com.example.wristai.PhoneRecordingService
import com.example.wristai.Settings
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 「主頁」分页：只放总览与快捷动作，列表在各自的分页。 */
@Composable
fun DashboardScreen(
    onOpen: (String) -> Unit,
    onOpenRecords: () -> Unit,
    onOpenFood: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val all by MeetingStore.meetings.collectAsState()
    val active by PhoneRecorder.active.collectAsState()
    val watch = rememberWatchName()
    val engine = remember(all) { currentEngine(context) }
    LaunchedEffect(Unit) { MeetingStore.reload(context) }

    val todayStart = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val recordings = all.filter { !FoodLog.isFood(it.id) }
    val todayRec = recordings.filter { it.createdAt >= todayStart }
    val todayKcal = todayFood(all).sumOf { it.nutrition?.kcal ?: 0 }
    val goal = Settings.dailyKcalGoal(context)
    val usesFood = all.any { FoodLog.isFood(it.id) }
    val busy = all.filter { it.statusStyle().busy && it.status != MeetingStatus.RECORDING }
    val latest = recordings.firstOrNull { it.status == MeetingStatus.DONE }

    val starter = rememberRecordStarter()
    fun start(food: Boolean) = starter(food)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---------- 问候 + 手表 ----------
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(greeting(), style = MaterialTheme.typography.headlineSmall, color = Brand.Text)
                    Text(SimpleDateFormat("M 月 d 日 EEEE", Locale.TAIWAN).format(Date()),
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                }
                WatchPill(watch, onOpenSettings)
            }
        }

        if (!engine.ready) {
            item {
                GlassCard(highlight = true) {
                    Text("還沒設定 AI", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                    Text(engine.detail, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                    Spacer(Modifier.height(12.dp))
                    GradientButton("前往設定", onOpenSettings, Modifier.fillMaxWidth())
                }
            }
        }

        // ---------- 快捷动作（录音中 → 显示计时与停止） ----------
        item {
            val a = active
            if (a != null) {
                PhoneRecordCard(onOpenMeeting = onOpen, foodMode = FoodLog.isFood(a.meetingId))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuickTile("開始錄音", "會議、課堂、訪談、靈感", filled = true, modifier = Modifier.weight(1f)) { start(false) }
                    QuickTile("記錄飲食", "說出吃了什麼", filled = false, modifier = Modifier.weight(1f)) { start(true) }
                }
            }
        }

        // ---------- 今天 ----------
        item {
            GlassCard {
                Text("今天", style = MaterialTheme.typography.labelSmall, color = Brand.TextMuted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("${todayRec.size}", "段錄音", Modifier.weight(1f).clickable(onClick = onOpenRecords))
                    StatTile(formatMinutes(todayRec.sumOf { it.durationSec }), "錄音時長", Modifier.weight(1f).clickable(onClick = onOpenRecords))
                    if (usesFood) StatTile("$todayKcal/$goal", "大卡", Modifier.weight(1f).clickable(onClick = onOpenFood))
                }
            }
        }

        // ---------- 处理中 ----------
        if (busy.isNotEmpty()) {
            item { SectionLabel("處理中") }
            items(busy, key = { it.id }) { m ->
                GlassCard(onClick = { onOpen(m.id) }, contentPadding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(meetingTitle(m), style = MaterialTheme.typography.titleSmall, color = Brand.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        StatusChip(m.statusStyle())
                    }
                    Spacer(Modifier.height(10.dp))
                    ScanningBar()
                }
            }
        }

        // ---------- 最新报告 ----------
        if (latest != null) {
            item { SectionLabel("最新報告") }
            item { LatestReportCard(latest) { onOpen(latest.id) } }
        } else if (recordings.isEmpty()) {
            item {
                GlassCard {
                    Text("開始第一段錄音", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                    Spacer(Modifier.height(4.dp))
                    Text("按上面的「開始錄音」，或在手錶按下錄音。錄完會自動整理出重點、待辦與逐字稿。",
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "早安"
    in 11..13 -> "午安"
    in 14..17 -> "下午好"
    else -> "晚安"
}

private fun formatMinutes(sec: Double): String {
    val m = (sec / 60).toInt()
    return when {
        m >= 60 -> "${m / 60}h ${m % 60}m"
        m >= 1 -> "${m}m"
        sec >= 1 -> "${sec.toInt()}s"
        else -> "0"
    }
}

/** 快捷按钮：大方块，主要的用渐层，次要的用外框。 */
@Composable
private fun QuickTile(title: String, sub: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.clip(shape)
            .background(if (filled) Brand.Accent else Brush.linearGradient(listOf(Brand.Surface, Brand.Surface)))
            .border(1.dp, if (filled) Color.Transparent else Brand.Outline, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (filled) Color.White.copy(alpha = 0.18f) else Brand.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            RecordDot(20.dp)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (filled) Color.White else Brand.Text)
        Text(sub, style = MaterialTheme.typography.bodySmall,
            color = if (filled) Color.White.copy(alpha = 0.8f) else Brand.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 最新报告：标题 + 摘要前几句。 */
@Composable
private fun LatestReportCard(m: Meeting, onClick: () -> Unit) {
    val context = LocalContext.current
    var summary by remember(m.id) { mutableStateOf("") }
    LaunchedEffect(m.id) {
        summary = withContext(Dispatchers.IO) {
            val f = MeetingStore.reportFile(context, m.id)
            if (!f.exists()) "" else reportSummary(f.readText())
        }
    }
    GlassCard(onClick = onClick) {
        Text(meetingTitle(m), style = MaterialTheme.typography.titleMedium, color = Brand.Text,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${m.origin().label} · ${shortWhen(m.createdAt)} · ${formatDuration(m.durationSec)}",
            style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
        if (summary.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = Brand.TextMuted,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Text("查看完整報告 →", style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
    }
}

/** 报告的「摘要」（旧版「會議摘要」）第一段。 */
fun reportSummary(markdown: String): String {
    val lines = markdown.lines()
    val start = lines.indexOfFirst { val t = it.trim(); t.startsWith("##") && t.contains("摘要") }
    if (start < 0) return ""
    return lines.drop(start + 1).takeWhile { !it.trim().startsWith("#") }
        .map { it.trim().removePrefix("- ").replace("**", "") }.filter { it.isNotBlank() }.joinToString(" ")
}
