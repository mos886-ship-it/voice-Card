package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

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
import androidx.compose.material.icons.outlined.Settings
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
    // 时间轴：今天的即时翻译、远距对话也排进来
    LaunchedEffect(Unit) { com.example.wristai.TranslationStore.reload(context); com.example.wristai.TalkStore.load(context) }
    val sessions by com.example.wristai.TranslationStore.sessions.collectAsState()
    val talks by com.example.wristai.TalkStore.talks.collectAsState()
    val extras = remember(sessions, talks, todayStart) {
        sessions.filter { it.startedAt >= todayStart && it.meetingId == null }.map { t ->
            TimelineExtra(t.startedAt, "譯".tr, com.example.wristai.TranslateLangs.shortName(com.example.wristai.TranslateLangs.mlkitOf(t.source) ?: t.source) + " → " +
                com.example.wristai.TranslateLangs.shortName(t.target, true), "{0} 句".trf(t.lines.size)) {
                Hubs.translate = 1; TranslatePage.open()
            }
        } + talks.filter { it.startedAt >= todayStart }.map { t ->
            TimelineExtra(t.startedAt, "話".tr, "遠距對話".tr + "：" + t.partners.joinToString("、"), "{0} 句".trf(t.lines.size)) {
                TalkPage.open = true; TranslatePage.open()
            }
        }
    }
    // 今日回顧：晚上 9 点后（到清晨 4 点）
    val nowHour = rememberNowMinute().get(Calendar.HOUR_OF_DAY)
    var recap by remember { mutableStateOf<DayRecap?>(null) }
    LaunchedEffect(nowHour >= 21 || nowHour < 4, todays.size) {
        recap = if (nowHour >= 21 || nowHour < 4) withContext(Dispatchers.IO) {
            val food = todayFood(all)
            DayRecap(todays.count { !FoodLog.isFood(it.id) }, sessions.count { it.startedAt >= todayStart },
                food.sumOf { it.nutrition?.kcal ?: 0 }, goal, food.sumOf { it.nutrition?.protein ?: 0 }, Settings.proteinGoal(context),
                usesFood, runCatching { com.example.wristai.CarMode.todayTodos(context) }.getOrDefault(emptyList()))
        } else null
    }

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
                    Text(SimpleDateFormat("M 月 d 日 EEEE".tr, com.example.voiceai.shared.I18n.locale).format(Date()),
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                    // 有温度的问候（依时段、星期、今天记了什么）
                    val now = rememberNowMinute()
                    val g = remember(now.timeInMillis / 60_000, todays.size, todayKcal) {
                        val meals = todayFood(all).mapNotNull { it.nutrition?.meal }
                        greetingFor(now, todays.count { !FoodLog.isFood(it.id) },
                            ateLunch = meals.any { "午" in it }, ateDinner = meals.any { "晚" in it },
                            todayKcal = todayKcal, goal = goal, usesFood = usesFood)
                    }
                    Text(g.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Brand.Text)
                    Text(g.line, style = MaterialTheme.typography.bodyMedium, color = Brand.TextMuted)
                    // 科技模式：目前会用的 AI 引擎
                    if (UiMode.tech) Text("AI：".tr + (if (engine.ready) engine.name else "還沒設定".tr),
                        style = MaterialTheme.typography.labelMedium, color = Brand.TextFaint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // 商用模式：手表只在有连线时显示
                if (watch != null || UiMode.tech) WatchPill(watch, onOpenSettings)
                // 快錄的分页列没有「設定」（三大主轴各占一格）：放在这里
                Spacer(Modifier.width(8.dp))
                IconCircle(Icons.Outlined.Settings, "設定".tr, onOpenSettings)
            }
        }

        recap?.let { r -> item { DayRecapCard(r, onOpenRecords, onOpenFood) } }

        if (!engine.ready) {
            item {
                GlassCard(highlight = true) {
                    Text("還沒設定 AI".tr, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                    Text(engine.detail, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                    Spacer(Modifier.height(12.dp))
                    GradientButton("前往設定".tr, onOpenSettings, Modifier.fillMaxWidth())
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
                        Toast.makeText(context, "通話錄音會自動整理，新的通話結束後幾分鐘內出現在資料庫".tr, Toast.LENGTH_LONG).show()
                    } else onOpenSettings()   // 到设定打开「自动整理通话录音」
                },
                importFile = { picker.launch(arrayOf("audio/*")) },
            )
        }

        // ---------- 处理中 ----------
        if (busy.isNotEmpty()) {
            item { SectionLabel("處理中".tr) }
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
            SectionLabel("今天的記錄".tr) {
                if (usesFood) Text("{0} / {1} 大卡".trf(todayKcal, goal), style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                    modifier = Modifier.clickable(onClick = onOpenFood))
            }
        }
        item {
            if (todays.isEmpty() && extras.isEmpty()) {
                GlassCard {
                    Text("今天還沒有記錄".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                    Spacer(Modifier.height(4.dp))
                    Text("按「開始錄音」或手錶的錄音鍵，錄完會自動整理出重點、待辦與逐字稿。".tr,
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                }
            } else {
                val shape = RoundedCornerShape(Brand.CardRadius)
                Column(Modifier.fillMaxWidth().clip(shape).background(Brand.Surface).border(1.dp, Brand.Outline, shape)) {
                    // 录音、饮食、翻译、远距对话照时间排（新的在上）
                    val rows: List<Pair<Long, @Composable () -> Unit>> =
                        todays.map { m -> m.createdAt to @Composable { TodayRow(m, summaries[m.id]) { onOpen(m.id) } } } +
                            extras.map { e -> e.at to @Composable { TimelineExtraRow(e) } }
                    val sorted = rows.sortedByDescending { it.first }
                    sorted.forEachIndexed { i, (_, row) ->
                        row()
                        if (i < sorted.size - 1) Box(Modifier.padding(start = 72.dp).fillMaxWidth().height(1.dp).background(Brand.Outline))
                    }
                }
            }
        }
        item {
            Text("所有記錄 →".tr, style = MaterialTheme.typography.labelLarge, color = Brand.Cyan,
                modifier = Modifier.clickable(onClick = onOpenRecords).heightIn(min = 44.dp).padding(vertical = 12.dp))
        }
    }
}

/** 处理中卡片下面那一行：排队位置、或在哪一步。 */
private fun busyText(m: Meeting): String {
    val d = m.stepDetail
    if (d != null && (d.startsWith("排隊中") || d.startsWith("輪到你了"))) return d
    val cur = m.steps.getOrNull(m.step)
    if (cur != null && cur.contains("大D的主機")) return "大D的主機整理中".tr
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
                .semantics { contentDescription = "開始錄音".tr },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Brand.Recording), contentAlignment = Alignment.Center) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("開始錄音".tr, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text("會議、課堂、訪談、靈感".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
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
            Mini("飲食".tr, food) { BowlGlyph(Brand.Cyan) }
            Mini("通話".tr, call) { Icon(Icons.Filled.Call, null, tint = Brand.Cyan, modifier = Modifier.size(16.dp)) }
            Mini("匯入".tr, importFile) { Icon(Icons.Filled.Add, null, tint = Brand.Cyan, modifier = Modifier.size(18.dp)) }
            Mini("翻譯".tr, { TranslatePage.open() }) { Text("文A", color = Brand.Cyan, style = MaterialTheme.typography.labelMedium) }
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
                Text(if (food) "餐".tr else if (com.example.wristai.TranslationReport.isTranslation(m)) "譯".tr else SpeechLang.of(m.lang).tag, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = if (food) Brand.Success else Brand.TextMuted,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                        .background(if (food) Brand.Success.copy(alpha = 0.12f) else Brand.SurfaceHigh)
                        .padding(horizontal = 5.dp, vertical = 1.dp))
                Spacer(Modifier.width(6.dp))
                Text(meetingTitle(m), style = MaterialTheme.typography.titleSmall, color = Brand.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val sub = m.nutrition?.takeIf { food }?.let { "{0} 大卡 · 蛋白質 {1} g · 碳水 {2} g".trf(it.kcal, it.protein, it.carbs) }
                ?: if (m.status == MeetingStatus.FAILED) "處理失敗，點開重試".tr else summary
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
