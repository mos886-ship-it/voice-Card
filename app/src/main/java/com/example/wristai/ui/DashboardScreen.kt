package com.example.wristai.ui

import android.widget.Toast
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wristai.CallRecordingWatcher
import com.example.wristai.FoodLog
import com.example.wristai.Meeting
import com.example.wristai.MeetingStatus
import com.example.wristai.MeetingStore
import com.example.wristai.PhoneRecorder
import com.example.wristai.Settings
import com.example.wristai.SpeechLang
import com.example.wristai.importAudio
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 「今天」分页（2026-11-25 重新设计，跟 iOS DashboardView 一样）：
 * 日期＋「今天」大标、「开始录音」卡（录音中 → 计时卡）、处理中（排队位置）、今天的记录（右上今日热量）。
 */
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
    val error by PhoneRecorder.error.collectAsState()
    val watch = rememberWatchName()
    val engine = remember(all) { currentEngine(context) }
    LaunchedEffect(Unit) { MeetingStore.reload(context) }

    val todayStart = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val todays = all.filter { it.createdAt >= todayStart && it.status != MeetingStatus.RECORDING }
    val todayKcal = todayFood(all).sumOf { it.nutrition?.kcal ?: 0 }
    val goal = Settings.dailyKcalGoal(context)
    val usesFood = all.any { FoodLog.isFood(it.id) }
    val busy = all.filter { it.statusStyle().busy && it.status != MeetingStatus.RECORDING }

    // 今天每笔的一行摘要（报告的「摘要」段）
    var summaries by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    LaunchedEffect(todays.map { it.id to it.status }) {
        summaries = withContext(Dispatchers.IO) {
            todays.associate { m ->
                val f = MeetingStore.reportFile(context, m.id)
                m.id to (if (f.exists()) summaryOf(f.readText()) else null)
            }
        }
    }

    val starter = rememberRecordStarter()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAudio(context, uri)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------- 日期 + 今天 + 手表 ----------
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(SimpleDateFormat("M 月 d 日 EEEE", Locale.TAIWAN).format(Date()),
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                    Text("今天", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Brand.Text)
                    // 科技模式：目前会用的 AI 引擎
                    if (UiMode.tech) Text("AI：" + (if (engine.ready) engine.name else "還沒設定"),
                        style = MaterialTheme.typography.labelMedium, color = Brand.TextFaint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // 商用模式：手表只在有连线时显示
                if (watch != null || UiMode.tech) WatchPill(watch, onOpenSettings)
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

        // ---------- 录音：一张卡放开始录音、语言、其他记录方式；录音中 → 计时卡 ----------
        item {
            if (active != null) PhoneRecordCard(onOpenMeeting = onOpen, foodMode = FoodLog.isFood(active!!.meetingId))
            else CaptureCard(
                error = error,
                record = { starter(false) },
                food = { starter(true) },
                call = {
                    if (CallRecordingWatcher.isEnabled(context)) {
                        CallRecordingWatcher.schedule(context)
                        Toast.makeText(context, "通話錄音會自動整理，新的通話結束後幾分鐘內出現在資料庫", Toast.LENGTH_LONG).show()
                    } else onOpenSettings()   // 到设定打开「自动整理通话录音」
                },
                importFile = { picker.launch(arrayOf("audio/*")) },
            )
        }

        // ---------- 处理中 ----------
        if (busy.isNotEmpty()) {
            item { SectionLabel("處理中") }
            items(busy, key = { "b_" + it.id }) { m ->
                GlassCard(onClick = { onOpen(m.id) }, contentPadding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(meetingTitle(m), style = MaterialTheme.typography.titleSmall, color = Brand.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (m.durationSec >= 1) Text(clockText(m.durationSec), style = MaterialTheme.typography.labelMedium,
                            color = Brand.TextMuted)
                    }
                    Spacer(Modifier.height(10.dp))
                    ScanningBar()
                    Spacer(Modifier.height(8.dp))
                    Text(busyText(m), style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                }
            }
        }

        // ---------- 今天的记录 ----------
        item {
            SectionLabel("今天的記錄") {
                if (usesFood) Text("$todayKcal / $goal 大卡", style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                    modifier = Modifier.clickable(onClick = onOpenFood))
            }
        }
        item {
            if (todays.isEmpty()) {
                GlassCard {
                    Text("今天還沒有記錄", style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                    Spacer(Modifier.height(4.dp))
                    Text("按「開始錄音」或手錶的錄音鍵，錄完會自動整理出重點、待辦與逐字稿。",
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                }
            } else {
                val shape = RoundedCornerShape(Brand.CardRadius)
                Column(Modifier.fillMaxWidth().clip(shape).background(Brand.Surface).border(1.dp, Brand.Outline, shape)) {
                    todays.forEachIndexed { i, m ->
                        TodayRow(m, summaries[m.id]) { onOpen(m.id) }
                        if (i < todays.size - 1) Box(Modifier.padding(start = 72.dp).fillMaxWidth().height(1.dp).background(Brand.Outline))
                    }
                }
            }
        }
        item {
            Text("所有記錄 →", style = MaterialTheme.typography.labelLarge, color = Brand.Cyan,
                modifier = Modifier.clickable(onClick = onOpenRecords).heightIn(min = 44.dp).padding(vertical = 12.dp))
        }
    }
}

/** 处理中卡片下面那一行：排队位置、或在哪一步。 */
private fun busyText(m: Meeting): String {
    val d = m.stepDetail
    if (d != null && (d.startsWith("排隊中") || d.startsWith("輪到你了"))) return d
    val cur = m.steps.getOrNull(m.step)
    if (cur != null && cur.contains("大D的主機")) return "大D的主機整理中"
    return m.statusStyle().label
}

private fun clockText(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** 「开始录音」卡：主要动作一个（红色录音键）、辨识语言、其他记录方式（饮食／通话／汇入）。 */
@Composable
private fun CaptureCard(error: String?, record: () -> Unit, food: () -> Unit, call: () -> Unit, importFile: () -> Unit) {
    GlassCard {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = record)
                .semantics { contentDescription = "開始錄音" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Brand.Recording), contentAlignment = Alignment.Center) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("開始錄音", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text("會議、課堂、訪談、靈感", style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            }
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Warning)
        }
        Spacer(Modifier.height(14.dp))
        LangChooser()
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mini("飲食", food) { BowlGlyph(Brand.Cyan) }
            Mini("通話", call) { Icon(Icons.Filled.Call, null, tint = Brand.Cyan, modifier = Modifier.size(16.dp)) }
            Mini("匯入", importFile) { Icon(Icons.Filled.Add, null, tint = Brand.Cyan, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun RowScope.Mini(title: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.weight(1f).heightIn(min = 44.dp).clip(shape).background(Brand.Surface).border(1.dp, Brand.Outline, shape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = Brand.Text, maxLines = 1)
    }
}

/** 今天的记录一列：时间、语言或「餐」标签、标题、摘要。 */
@Composable
private fun TodayRow(m: Meeting, summary: String?, onClick: () -> Unit) {
    val food = FoodLog.isFood(m.id)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date(m.createdAt)), style = MaterialTheme.typography.labelMedium,
            color = Brand.TextMuted, modifier = Modifier.width(44.dp).padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (food) "餐" else SpeechLang.of(m.lang).tag, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = if (food) Brand.Success else Brand.TextMuted,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                        .background(if (food) Brand.Success.copy(alpha = 0.12f) else Brand.SurfaceHigh)
                        .padding(horizontal = 5.dp, vertical = 1.dp))
                Spacer(Modifier.width(6.dp))
                Text(meetingTitle(m), style = MaterialTheme.typography.titleSmall, color = Brand.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val sub = m.nutrition?.takeIf { food }?.let { "${it.kcal} 大卡 · 蛋白質 ${it.protein} g · 碳水 ${it.carbs} g" }
                ?: if (m.status == MeetingStatus.FAILED) "處理失敗，點開重試" else summary
            if (!sub.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
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
