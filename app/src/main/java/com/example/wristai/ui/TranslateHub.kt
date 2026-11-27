package com.example.wristai.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
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
import com.example.wristai.TranslateLangs
import com.example.wristai.TranslateModels
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.launch
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
        subtitle = if (live.running) "翻譯中：{0} → {1}".trf(TranslateLangs.shortName(live.source), TranslateLangs.shortName(live.target, true))
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
private fun srcLabel(code: String): String =
    SpeechLang.entries.firstOrNull { it.code == code }?.label ?: TranslateLangs.shortName(code)   // 旧记录存的是录音语言代码
private fun dstLabel(code: String): String = TranslateLangs.shortName(code, target = true)

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
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { TranslateLangs.refresh(context); TranslateModels.refresh() }
    val support by TranslateLangs.support.collectAsState()
    var source by remember { mutableStateOf(Settings.translateSource(context)) }
    var target by remember { mutableStateOf(Settings.translateTarget(context).ifEmpty { LiveCaptions.defaultTarget(source, com.example.voiceai.shared.I18n.lang) }) }
    var picking by remember { mutableStateOf<Boolean?>(null) }
    val srcStatus = support.status(source)
    HubPage {
        // ---------- 语言 ----------
        SectionLabel("語言".tr)
        GlassCard {
            SettingRow("說的語言".tr, TranslateLangs.name(source), TranslateLangs.regionName(source) + " · " + source) { picking = true }
            Divider(Modifier.padding(vertical = 10.dp))
            SettingRow("翻譯成".tr, TranslateLangs.name(target, target = true), null) { picking = false }
            Spacer(Modifier.height(8.dp))
            Text(
                "能選的語言直接問手機：說的 = 手機語音辨識支援、而且能翻譯的（{0} 種）；翻成 = 手機裝置端翻譯支援的（{1} 種）。翻譯頁的 ⇄ 可以對調，跟對方輪流說。".trf(
                    TranslateLangs.sources(support).size, TranslateLangs.targets.size),
                style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        }

        // ---------- 语音辨识 ----------
        SectionLabel("語音辨識".tr)
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(TranslateLangs.name(source) + "（" + TranslateLangs.regionName(source) + "）", style = MaterialTheme.typography.titleSmall,
                    color = Brand.Text, modifier = Modifier.weight(1f))
                RecogBadge(srcStatus)
            }
            Text(when (srcStatus) {
                TranslateLangs.Status.OFFLINE -> "手機已有離線語音：辨識在手機上做，不用網路。".tr
                TranslateLangs.Status.DOWNLOADABLE -> "可以下載離線語音（下載後不用網路、聲音不出手機）。".tr
                TranslateLangs.Status.ONLINE -> "只有線上辨識：聲音會傳到 Google 辨識，要有網路。".tr
                null -> if (support.queried) "手機的辨識服務沒有列出這個口音，會試著用線上辨識。".tr else "這支手機查不到辨識清單（Android 13 以下），會用 Google 語音服務。".tr
            }, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            if (srcStatus == TranslateLangs.Status.DOWNLOADABLE && android.os.Build.VERSION.SDK_INT >= 33) {
                Spacer(Modifier.height(8.dp))
                GhostButton("下載離線語音".tr, {
                    runCatching {
                        val r = android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                        r.triggerModelDownload(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, source))
                        scope.launch { kotlinx.coroutines.delay(5_000); r.destroy(); TranslateLangs.refresh(context) }
                    }
                }, Modifier.fillMaxWidth(), tint = Brand.Cyan)
            }
            Spacer(Modifier.height(10.dp))
            Text("離線已裝 {0} 種、可下載 {1} 種、線上 {2} 種".trf(support.installed.size, support.downloadable.size, support.online.size),
                style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
        }
        var allowOnline by remember { mutableStateOf(Settings.allowOnlineRecognition(context)) }
        SwitchCard("允許線上辨識".tr, "沒有離線語音的語言改用 Google 線上辨識（聲音會傳出手機）。關掉就只用離線，沒有離線語音的語言不能翻。".tr,
            allowOnline, { allowOnline = it; Settings.setAllowOnlineRecognition(context, it) })

        // ---------- 显示 ----------
        SectionLabel("顯示".tr)
        GlassCard {
            Text("字幕大小".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Spacer(Modifier.height(8.dp))
            var size by remember { mutableStateOf(Settings.captionSize(context).coerceIn(0, 3)) }
            Segmented(listOf("小".tr, "中".tr, "大".tr, "特大".tr), size) { size = it; Settings.setCaptionSize(context, it) }
            Spacer(Modifier.height(12.dp))
            var orig by remember { mutableStateOf(Settings.showOriginal(context)) }
            SettingSwitch("顯示原文".tr, "譯文上面小字顯示說的原文".tr, orig) { orig = it; Settings.setShowOriginal(context, it) }
            var screen by remember { mutableStateOf(Settings.keepScreenOn(context)) }
            SettingSwitch("翻譯時螢幕保持亮著".tr, "不會翻到一半螢幕關掉".tr, screen) { screen = it; Settings.setKeepScreenOn(context, it) }
            var quiet by remember { mutableStateOf(Settings.quietTranslate(context)) }
            SettingSwitch("不要提示聲".tr, "手機語音辨識每次開始聽會「叮」一聲；翻譯中暫時把媒體、系統、通知聲音靜音，停止就恢復（朗讀照樣聽得到）。".tr, quiet) {
                quiet = it; Settings.setQuietTranslate(context, it)
                if (com.example.wristai.LiveCaptions.state.value.running) { if (it) com.example.wristai.QuietMode.on(context) else com.example.wristai.QuietMode.off(context) }
            }
            var watch by remember { mutableStateOf(Settings.captionToWatch(context)) }
            SettingSwitch("手錶顯示字幕".tr, "每翻好一句，手錶顯示最新的一句".tr, watch) { watch = it; Settings.setCaptionToWatch(context, it) }
        }

        // ---------- 朗读 ----------
        SectionLabel("朗讀".tr)
        GlassCard {
            var speak by remember { mutableStateOf(Settings.autoSpeak(context)) }
            SettingSwitch("翻好自動朗讀".tr, "用手機的文字轉語音念譯文，讓對方聽。朗讀時聽到的聲音不會被當成新的一句；建議戴耳機或把音量調小。".tr, speak) {
                speak = it; Settings.setAutoSpeak(context, it)
            }
            Spacer(Modifier.height(8.dp))
            Text("朗讀速度".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Spacer(Modifier.height(8.dp))
            val rates = listOf(0.75f, 1.0f, 1.25f)
            var rate by remember { mutableStateOf(Settings.speakRate(context)) }
            Segmented(listOf("慢".tr, "正常".tr, "快".tr), rates.indexOfFirst { it == rate }.let { if (it < 0) 1 else it }) { rate = rates[it]; Settings.setSpeakRate(context, rate) }
            Spacer(Modifier.height(8.dp))
            var can by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(target) { repeat(10) { can = com.example.wristai.Speaker.canSpeak(context, target); if (can != null) return@LaunchedEffect; kotlinx.coroutines.delay(500) } }
            Text(when (can) {
                true -> "手機可以念{0}。".trf(TranslateLangs.shortName(target, true))
                false -> "手機沒有{0}的語音，可以在系統「文字轉語音」下載。".trf(TranslateLangs.shortName(target, true))
                null -> "手機的文字轉語音準備中…".tr
            }, style = MaterialTheme.typography.labelSmall, color = if (can == false) Brand.Warning else Brand.TextFaint)
            Spacer(Modifier.height(6.dp))
            GhostButton("試聽".tr, { com.example.wristai.Speaker.speak(context, sampleText(target), target) }, Modifier.fillMaxWidth())
        }

        // ---------- 录音与记录 ----------
        SectionLabel("錄音與記錄".tr)
        GlassCard {
            var rec by remember { mutableStateOf(Settings.translateWithRecording(context)) }
            SettingSwitch("開始翻譯時同時錄音".tr,
                if (LiveCaptions.canUseRecording) "錄下來，翻完照常產生完整報告；關掉就只翻譯、不錄音（比較省電）。".tr
                else "這支手機（Android 13 以下）不能邊錄邊翻譯，只能只翻譯。".tr, rec) { rec = it; Settings.setTranslateWithRecording(context, it) }
            var save by remember { mutableStateOf(Settings.saveTranslations(context)) }
            SettingSwitch("自動存翻譯記錄".tr, "每次翻譯的原文與譯文存在手機（「記錄」可以查看、分享、刪除）".tr, save) { save = it; Settings.setSaveTranslations(context, it) }
        }

        // ---------- 翻译模型 ----------
        SectionLabel("離線翻譯模型".tr)
        OfflineModelsCard(source, target)
        AllSettingsLink(onOpenSettings)
    }
    picking?.let { src ->
        TranslateLangSheet(source = src, current = if (src) source else target, onDismiss = { picking = null }) { code ->
            picking = null
            if (src) { source = code; Settings.setTranslateSource(context, code) } else { target = code; Settings.setTranslateTarget(context, code) }
            Settings.addRecentTranslateLang(context, code)
        }
    }
}

/** 试听用的一句（翻成的语言）。 */
private fun sampleText(lang: String) = when (lang) {
    "zh" -> "你好，這是即時翻譯的朗讀。"
    "ja" -> "こんにちは、これは翻訳の読み上げです。"
    "ko" -> "안녕하세요, 번역 읽기 테스트입니다."
    "vi" -> "Xin chào, đây là giọng đọc bản dịch."
    "th" -> "สวัสดี นี่คือเสียงอ่านคำแปล"
    "es" -> "Hola, esta es la lectura de la traducción."
    "fr" -> "Bonjour, ceci est la lecture de la traduction."
    "de" -> "Hallo, das ist die Vorlesefunktion der Übersetzung."
    else -> "Hello, this is how translations will be read aloud."
}

@Composable
private fun SettingRow(label: String, value: String, detail: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Brand.TextMuted)
            Text(value, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint) }
        }
        Text("更改 ›".tr, style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        BrandSwitch(on, onChange)
    }
}

/**
 * 离线翻译模型：已下载的（可以删，英文内建）、这次用到但还没下载的（下载），加其他语言（选单）。
 * 只用 Wi-Fi 下载的开关。
 */
@Composable
private fun OfflineModelsCard(source: String, target: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val have by TranslateModels.downloaded.collectAsState()
    val busy by TranslateModels.busy.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val needed = listOfNotNull(TranslateLangs.mlkitOf(source), target).distinct()
    val shown = (needed + (have ?: emptySet())).distinct().sortedBy { (if (it in needed) "0" else "1") + TranslateLangs.shortName(it, true) }
    GlassCard {
        Text("翻譯在手機上做，每個語言第一次用要下載（約 30 MB）。出門前先下載，沒網路也能翻。".tr,
            style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        Spacer(Modifier.height(8.dp))
        for (code in shown) {
            val ok = have?.contains(code) == true
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(TranslateLangs.name(code, true), style = MaterialTheme.typography.bodyMedium, color = Brand.Text)
                    if (code in needed) Text("目前使用".tr, style = MaterialTheme.typography.labelSmall, color = Brand.Cyan)
                }
                when {
                    have == null -> Text("…", color = Brand.TextFaint)
                    code in busy -> Text("處理中…".tr, style = MaterialTheme.typography.labelLarge, color = Brand.TextFaint)
                    code == "en" -> Text("內建".tr, style = MaterialTheme.typography.labelLarge, color = Brand.Success)
                    ok -> TextButton({ scope.launch { TranslateModels.delete(code).onFailure { error = it.message } } }) {
                        Text("已下載 · 刪除".tr, color = Brand.TextMuted)
                    }
                    else -> TextButton({ scope.launch { TranslateModels.download(context, code).onFailure { error = it.message } } }) {
                        Text("下載".tr, color = Brand.Cyan)
                    }
                }
            }
        }
        error?.let { Text("失敗：{0}".trf(it), style = MaterialTheme.typography.labelSmall, color = Brand.Warning) }
        Spacer(Modifier.height(6.dp))
        GhostButton("下載其他語言…".tr, { adding = true }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        var wifi by remember { mutableStateOf(Settings.translateModelsWifiOnly(context)) }
        SettingSwitch("只用 Wi-Fi 下載".tr, "用行動網路時不下載翻譯模型（先只顯示原文）".tr, wifi) { wifi = it; Settings.setTranslateModelsWifiOnly(context, it) }
    }
    if (adding) TranslateLangSheet(source = false, current = target, onDismiss = { adding = false }) { code ->
        adding = false
        scope.launch { TranslateModels.download(context, code).onFailure { error = it.message } }
    }
}
