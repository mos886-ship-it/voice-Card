package com.example.wristai.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import com.example.wristai.LiveCaptions
import com.example.wristai.MeetingStore
import com.example.wristai.Settings
import com.example.wristai.SpeechLang
import com.example.wristai.TranslationStore
import com.example.wristai.ui.theme.Brand
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「翻譯」分页：翻譯（即时字幕）｜記錄｜分析｜設定。 */
@Composable
fun TranslateHub(onOpen: (String) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TranslationStore.reload(context) }
    val sessions by TranslationStore.sessions.collectAsState()
    val live by LiveCaptions.state.collectAsState()
    HubScaffold(
        title = "即時翻譯".tr,
        subtitle = if (live.running) "翻譯中：{0} → {1}".trf(live.source.label, live.target.label)
            else "邊錄邊翻譯，在手機上完成、不上傳".tr,
        sections = listOf("翻譯".tr, "記錄".tr, "分析".tr, "設定".tr),
        selected = Hubs.translate,
        onSelect = { Hubs.translate = it },
    ) { s ->
        when (s) {
            0 -> LiveTranslateScreen()
            1 -> TranslateRecords(sessions, onOpen)
            2 -> TranslateAnalysis(sessions)
            else -> TranslateSettings(onOpenSettings)
        }
    }
}

/** 说的语言用辨识语言的名字（國語、英語…），翻成的语言用翻译目标的名字（English、繁體中文…）。 */
private fun srcLabel(code: String): String = SpeechLang.entries.firstOrNull { it.code == code }?.label ?: code
private fun dstLabel(code: String): String = LiveCaptions.Target.of(code)?.label ?: code

@Composable
private fun TranslateRecords(sessions: List<TranslationStore.Session>, onOpen: (String) -> Unit) {
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val open = sessions.firstOrNull { it.id == openId }
    if (open != null) { SessionDetail(open, onBack = { openId = null }, onOpen = onOpen); return }
    val dayFmt = remember { SimpleDateFormat("M/d EEEE", com.example.voiceai.shared.I18n.locale) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val keyFmt = remember { SimpleDateFormat("yyyyMMdd", Locale.US) }
    val days = remember(sessions) { sessions.groupBy { keyFmt.format(Date(it.startedAt)) }.toList() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (sessions.isEmpty()) item {
            EmptyNote("還沒有翻譯記錄。每次「開始翻譯」到「停止」會自動存成一筆（可以在「設定」關掉）。".tr)
        }
        for ((_, list) in days) {
            item { Text(dayFmt.format(Date(list.first().startedAt)), style = MaterialTheme.typography.titleSmall, color = Brand.Text,
                modifier = Modifier.padding(top = 6.dp)) }
            items(list, key = { it.id }) { s ->
                GlassCard(onClick = { openId = s.id }, contentPadding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(timeFmt.format(Date(s.startedAt)), style = MaterialTheme.typography.labelMedium, color = Brand.TextFaint,
                            modifier = Modifier.width(48.dp))
                        Column(Modifier.weight(1f)) {
                            Text("{0} → {1}".trf(srcLabel(s.source), dstLabel(s.target)), style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
                            Text(s.lines.firstOrNull()?.translated ?: s.lines.firstOrNull()?.original.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium, color = Brand.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("{0} 句 · {1}".trf(s.lines.size, formatDuration(s.durationSec.toDouble())),
                                style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SessionDetail(s: TranslationStore.Session, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    var confirm by remember { mutableStateOf(false) }
    val meeting = s.meetingId?.let { id -> MeetingStore.meetings.value.firstOrNull { it.id == id } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("{0} → {1}".trf(srcLabel(s.source), dstLabel(s.target)), style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text(SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(s.startedAt)) + " · " + formatDuration(s.durationSec.toDouble()),
                    style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
            }
            IconCircle(Icons.Filled.Share, "分享".tr, {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, TranslationStore.asText(s))
                context.startActivity(Intent.createChooser(send, "分享翻譯".tr).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            Spacer(Modifier.width(8.dp))
            IconCircle(Icons.Filled.Delete, "刪除".tr, { confirm = true }, tint = Brand.Warning)
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("← 返回記錄".tr, onBack)
            if (meeting != null) GhostButton("打開錄音報告".tr, { onOpen(meeting.id) }, tint = Brand.Cyan)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(s.lines) { l ->
                Column {
                    Text(l.original, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                    l.translated?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Brand.Text) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("刪除這筆翻譯記錄？".tr) },
        text = { Text("錄音與報告不受影響。".tr) },
        confirmButton = { TextButton({ confirm = false; TranslationStore.delete(context, s.id); onBack() }) { Text("刪除".tr, color = Brand.Warning) } },
        dismissButton = { TextButton({ confirm = false }) { Text("取消".tr) } },
    )
}

@Composable
private fun TranslateAnalysis(sessions: List<TranslationStore.Session>) {
    var range by rememberSaveable { mutableStateOf(0) }
    val days = if (range == 0) 7 else 30
    val starts = remember(days) { dayStarts(days) }
    val inRange = sessions.filter { it.startedAt >= starts.first() }
    HubPage {
        Segmented(listOf("近 7 天".tr, "近 30 天".tr), range) { range = it }
        if (inRange.isEmpty()) { EmptyNote("這段期間沒有翻譯記錄。".tr); return@HubPage }
        val secs = inRange.sumOf { it.durationSec }
        val lines = inRange.sumOf { it.lines.size }
        val translated = inRange.sumOf { s -> s.lines.count { it.translated != null } }
        StatRow(
            "${inRange.size}" to "次翻譯".tr,
            humanDuration(secs.toDouble()) to "總時間".tr,
            "$lines" to "句".tr,
        )
        GlassCard {
            Text("每天翻譯（分鐘）".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Spacer(Modifier.height(10.dp))
            BarChart(starts.map { st -> inRange.filter { it.startedAt in st until st + 86_400_000L }.sumOf { it.durationSec }.toFloat() / 60f }, dayLabels(starts))
        }
        GlassCard {
            Text("語言組合".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Spacer(Modifier.height(6.dp))
            val pairs = inRange.groupBy { it.source to it.target }.entries.sortedByDescending { it.value.size }
            for ((p, list) in pairs) ShareBar("{0} → {1}".trf(srcLabel(p.first), dstLabel(p.second)),
                "{0} 次 · {1} 句".trf(list.size, list.sumOf { it.lines.size }), list.size.toFloat() / inRange.size)
        }
        GlassCard {
            Text("翻譯完成率".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            ShareBar("已翻譯 / 辨識到的句子".tr, "$translated / $lines", if (lines == 0) 0f else translated.toFloat() / lines,
                if (translated == lines) Brand.Success else Brand.Cyan)
            Text("沒翻到的多半是翻譯模型還在下載，或句子太短。".tr, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
        }
    }
}

@Composable
private fun TranslateSettings(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    HubPage {
        GlassCard {
            Text("預設翻譯成".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Spacer(Modifier.height(8.dp))
            val opts = listOf("" to "跟介面".tr) + LiveCaptions.Target.entries.map { it.code to it.label }
            var t by remember { mutableStateOf(Settings.translateTarget(context)) }
            ChoiceGrid(opts.map { it.second }, opts.indexOfFirst { it.first == t }.coerceAtLeast(0)) { i ->
                t = opts[i].first; Settings.setTranslateTarget(context, t)
            }
            Spacer(Modifier.height(6.dp))
            Text("說的語言跟錄音的辨識語言是同一個設定（在「翻譯」頁或「錄音」的設定選）。".tr,
                style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            Spacer(Modifier.height(16.dp))
            Text("字幕大小".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Spacer(Modifier.height(8.dp))
            var size by remember { mutableStateOf(Settings.captionSize(context)) }
            Segmented(listOf("小".tr, "中".tr, "大".tr), size) { size = it; Settings.setCaptionSize(context, it) }
        }
        var watch by remember { mutableStateOf(Settings.captionToWatch(context)) }
        SwitchCard("手錶顯示字幕".tr, "每翻好一句，手錶顯示最新的一句".tr, watch, { watch = it; Settings.setCaptionToWatch(context, it) })
        var save by remember { mutableStateOf(Settings.saveTranslations(context)) }
        SwitchCard("自動存翻譯記錄".tr, "每次翻譯的原文與譯文存在手機（「記錄」可以查看、分享、刪除）".tr, save,
            { save = it; Settings.setSaveTranslations(context, it) })
        OfflineModelsCard()
        AllSettingsLink(onOpenSettings)
    }
}

/** 离线翻译模型（ML Kit，每个语言约 30 MB）：看有哪些、先下载、删掉省空间。英文内建不能删。 */
@Composable
private fun OfflineModelsCard() {
    val scope = rememberCoroutineScope()
    val manager = remember { RemoteModelManager.getInstance() }
    var have by remember { mutableStateOf<Set<String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun refresh() {
        have = runCatching { manager.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet() }.getOrDefault(emptySet())
    }
    LaunchedEffect(Unit) { refresh() }
    val langs = listOf(
        TranslateLanguage.CHINESE to "中文", TranslateLanguage.ENGLISH to "English", TranslateLanguage.KOREAN to "한국어",
        TranslateLanguage.JAPANESE to "日本語", TranslateLanguage.VIETNAMESE to "Tiếng Việt", TranslateLanguage.THAI to "ไทย",
        TranslateLanguage.INDONESIAN to "Bahasa Indonesia",
    )
    GlassCard {
        Text("離線翻譯模型".tr, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
        Text("翻譯在手機上做，每個語言第一次用要下載（約 30 MB）。出門前先下載，沒網路也能翻。".tr,
            style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        Spacer(Modifier.height(8.dp))
        for ((code, name) in langs) {
            val ok = have?.contains(code) == true
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = Brand.Text, modifier = Modifier.weight(1f))
                when {
                    have == null -> Text("…", color = Brand.TextFaint)
                    busy == code -> Text("處理中…".tr, style = MaterialTheme.typography.labelLarge, color = Brand.TextFaint)
                    code == TranslateLanguage.ENGLISH -> Text("內建".tr, style = MaterialTheme.typography.labelLarge, color = Brand.Success)
                    ok -> TextButton({
                        busy = code
                        scope.launch {
                            runCatching { manager.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await() }
                                .onFailure { error = it.message }
                            refresh(); busy = null
                        }
                    }) { Text("已下載 · 刪除".tr, color = Brand.TextMuted) }
                    else -> TextButton({
                        busy = code
                        scope.launch {
                            runCatching { manager.download(TranslateRemoteModel.Builder(code).build(), DownloadConditions.Builder().build()).await() }
                                .onFailure { error = it.message }
                            refresh(); busy = null
                        }
                    }) { Text("下載".tr, color = Brand.Cyan) }
                }
            }
        }
        error?.let { Text("失敗：{0}".trf(it), style = MaterialTheme.typography.labelSmall, color = Brand.Warning) }
        Spacer(Modifier.height(6.dp))
        Text("語音辨識：手機沒有那個語言的離線語音時會改用 Google 線上辨識；可以在系統「語音輸入 → 離線語音辨識」下載。".tr,
            style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
    }
}
