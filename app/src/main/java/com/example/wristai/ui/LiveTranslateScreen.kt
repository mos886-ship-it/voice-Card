package com.example.wristai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.voiceai.shared.I18n
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import com.example.wristai.LiveCaptions
import com.example.wristai.PhoneRecorder
import com.example.wristai.PhoneRecordingService
import com.example.wristai.Settings
import com.example.wristai.Speaker
import com.example.wristai.TranslateLangs
import com.example.wristai.ui.theme.Brand

/** 别的画面要打开即时翻译页（主页的「翻譯」、录音中的「即時翻譯」）。 */
object TranslatePage {
    var requested by mutableStateOf(false)
    fun open() { requested = true }
}

/** 字幕大小：小、中、大、特大。 */
private val CAPTION_SP = listOf(16, 20, 26, 34)

/**
 * 即时翻译页：上面「说的 ⇄ 翻成」（点语言就能换，任何手机支援的语言），一个开始键，
 * 下面字幕（新的在下面，自动捲到最新）。字幕列可以调字大小、开关原文、朗读、放大给对方看。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveTranslateScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val s by LiveCaptions.state.collectAsState()
    val active by PhoneRecorder.active.collectAsState()
    val recording = active != null && !com.example.wristai.FoodLog.isFood(active!!.meetingId)
    LaunchedEffect(Unit) { TranslateLangs.refresh(context); com.example.wristai.TranslateModels.refresh() }
    val support by TranslateLangs.support.collectAsState()

    var source by remember { mutableStateOf(if (s.running) s.source else Settings.translateSource(context)) }
    var target by remember {
        mutableStateOf(if (s.running) s.target
            else Settings.translateTarget(context).ifEmpty { LiveCaptions.defaultTarget(source, I18n.lang) })
    }
    // 翻译中被对调（⇄）或换了语言：跟着状态
    LaunchedEffect(s.source, s.target, s.sessionId) { if (s.sessionId != 0L) { source = s.source; target = s.target } }
    var size by remember { mutableIntStateOf(Settings.captionSize(context).coerceIn(0, 3)) }
    var original by remember { mutableStateOf(Settings.showOriginal(context)) }
    var speak by remember { mutableStateOf(Settings.autoSpeak(context)) }
    var withRec by remember { mutableStateOf(Settings.translateWithRecording(context)) }
    var picking by remember { mutableStateOf<Boolean?>(null) }   // true = 说的、false = 翻成的
    var big by remember { mutableStateOf(false) }
    val starter = rememberRecordStarter()

    // 翻译中萤幕保持亮着
    val view = LocalView.current
    val keepOn = s.running && Settings.keepScreenOn(context)
    DisposableEffect(keepOn) { view.keepScreenOn = keepOn; onDispose { view.keepScreenOn = false } }

    fun begin() {
        Settings.setTranslateSource(context, source); Settings.setTranslateTarget(context, target)
        when {
            recording -> LiveCaptions.start(context, source, target, fromRecording = LiveCaptions.canUseRecording)
            withRec && LiveCaptions.canUseRecording -> { starter(false); LiveCaptions.start(context, source, target, fromRecording = true) }
            else -> LiveCaptions.start(context, source, target, fromRecording = false)
        }
    }
    fun restartIfRunning() { if (s.running) LiveCaptions.start(context, source, target, s.fromRecording) }
    val regions = TranslateLangs.sources(support)[TranslateLangs.mlkitOf(source)]?.size ?: 0

    Column(Modifier.fillMaxSize()) {
        if (onBack != null) TopBar("即時翻譯".tr, onBack)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---------- 说的 ⇄ 翻成 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                LangButton("說的".tr, TranslateLangs.shortName(source), TranslateLangs.regionName(source).takeIf { regions > 1 },
                    support.status(source), Modifier.weight(1f)) { picking = true }
                Box(Modifier.padding(horizontal = 6.dp).size(40.dp).clip(CircleShape).background(Brand.SurfaceHigh)
                    .border(1.dp, Brand.Outline, CircleShape)
                    .clickable {
                        if (s.running) LiveCaptions.swap(context)
                        else {
                            val ns = TranslateLangs.defaultTag(target, preferred = Settings.recentTranslateLangs(context)); val nt = TranslateLangs.mlkitOf(source) ?: target
                            source = ns; target = nt
                            Settings.setTranslateSource(context, ns); Settings.setTranslateTarget(context, nt)
                        }
                    }, contentAlignment = Alignment.Center) {
                    Text("⇄", fontSize = 20.sp, color = Brand.Cyan)
                }
                LangButton("翻成".tr, TranslateLangs.shortName(target, target = true), null, null, Modifier.weight(1f)) { picking = false }
            }
            // ---------- 开始 / 停止 ----------
            if (s.running) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("停止翻譯".tr, { LiveCaptions.stop(); Speaker.stop() }, Modifier.weight(1f))
                if (recording) GradientButton("停止錄音".tr, { PhoneRecordingService.stop(context) }, Modifier.weight(1f))
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                GradientButton(
                    when { recording -> "翻譯目前的錄音".tr; withRec && LiveCaptions.canUseRecording -> "錄音並翻譯".tr; else -> "開始翻譯".tr },
                    { begin() }, Modifier.weight(1f), recordDot = withRec || recording)
                if (!recording && LiveCaptions.canUseRecording) {
                    Spacer(Modifier.width(8.dp))
                    ToggleChip("同時錄音".tr, withRec) { withRec = it; Settings.setTranslateWithRecording(context, it) }
                }
            }
            (s.error ?: s.status)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (s.error != null) Brand.Warning else Brand.TextMuted)
            }
        }
        Spacer(Modifier.height(8.dp))

        // ---------- 字幕工具列：字大小、原文、朗读、放大、清除 ----------
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolChip("A−", size > 0) { size = (size - 1).coerceAtLeast(0); Settings.setCaptionSize(context, size) }
            ToolChip("A+", size < 3) { size = (size + 1).coerceAtMost(3); Settings.setCaptionSize(context, size) }
            ToggleChip("原文".tr, original) { original = it; Settings.setShowOriginal(context, it) }
            ToggleChip("朗讀".tr, speak) { speak = it; Settings.setAutoSpeak(context, it); if (!it) Speaker.stop() }
            ToolChip("⛶ " + "放大".tr, s.lines.isNotEmpty()) { big = true }
            ToolChip("清除".tr, s.lines.isNotEmpty() && !s.running) { LiveCaptions.clear() }
        }
        Spacer(Modifier.height(8.dp))

        // ---------- 字幕：新的在下面，自动捲到最新 ----------
        val list = rememberLazyListState()
        LaunchedEffect(s.lines.size, s.lines.lastOrNull()?.translated, s.lines.lastOrNull()?.original) {
            if (s.lines.isNotEmpty()) list.animateScrollToItem(s.lines.size)
        }
        val tStyle = TextStyle(fontSize = CAPTION_SP[size].sp, lineHeight = (CAPTION_SP[size] * 1.3f).sp, fontWeight = FontWeight.Medium)
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(Brand.CardRadius)).background(Brand.Surface),
        ) {
            if (s.lines.isEmpty()) {
                Text(
                    if (s.running) "開始說話，字幕會出現在這裡…".tr
                    else "點上面的語言可以換成手機支援的任何語言；⇄ 對調方向（跟對方輪流說）。辨識與翻譯在手機上做，沒有離線語音的語言會用 Google 線上辨識。".tr,
                    style = MaterialTheme.typography.bodyMedium, color = Brand.TextFaint, modifier = Modifier.padding(18.dp),
                )
            } else LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                items(s.lines, key = { it.id }) { l ->
                    Column(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = {
                        val text = l.original + (l.translated?.let { "\n$it" } ?: "")
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("caption", text))
                        Toast.makeText(context, "已複製".tr, Toast.LENGTH_SHORT).show()
                    })) {
                        if (original || l.partial || l.translated == null) Text(l.original, style = MaterialTheme.typography.bodySmall,
                            color = Brand.TextMuted, fontStyle = if (l.partial) FontStyle.Italic else FontStyle.Normal)
                        // 还在说的那行：边说边翻的暂时译文（斜体、淡色），说完会再翻一次定下来
                        if (l.partial) l.translated?.let { Text(it, style = tStyle, color = Brand.TextMuted, fontStyle = FontStyle.Italic) }
                        if (!l.partial) Row(verticalAlignment = Alignment.Top) {
                            Text(l.translated ?: "…", style = tStyle, color = if (l.translated == null) Brand.TextFaint else Brand.Text,
                                modifier = Modifier.weight(1f))
                            if (l.translated != null) Text("🔊", fontSize = 16.sp,
                                modifier = Modifier.padding(start = 6.dp, top = 4.dp).clickable {
                                    if (!Speaker.speak(context, l.translated, s.target))
                                        Toast.makeText(context, "手機沒有{0}的語音，可以在系統「文字轉語音」下載".trf(TranslateLangs.shortName(s.target, true)), Toast.LENGTH_LONG).show()
                                })
                        }
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
        Text(
            "長按一句可以複製。手機內建辨識＋裝置端翻譯，僅供參考。".tr,
            style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }

    picking?.let { src ->
        TranslateLangSheet(source = src, current = if (src) source else target, onDismiss = { picking = null }) { code ->
            picking = null
            if (src) { source = code; Settings.setTranslateSource(context, code) }
            else { target = code; Settings.setTranslateTarget(context, code) }
            Settings.addRecentTranslateLang(context, code)
            restartIfRunning()
        }
    }
    if (big) BigCaption(onClose = { big = false })
}

/** 放大：整个萤幕只放最新一句（给对方看）；可以翻转 180°（对坐时对方看正的），⇄ 换对方说。 */
@Composable
private fun BigCaption(onClose: () -> Unit) {
    val context = LocalContext.current
    val s by LiveCaptions.state.collectAsState()
    var flip by remember { mutableStateOf(false) }
    val last = s.lines.lastOrNull { !it.partial }
    val partial = s.lines.lastOrNull()?.takeIf { it.partial }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Brand.Bg)) {
            Column(Modifier.fillMaxSize().padding(24.dp).rotate(if (flip) 180f else 0f), verticalArrangement = Arrangement.Center) {
                Text(TranslateLangs.shortName(s.target, true), style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
                Spacer(Modifier.height(12.dp))
                Text(partial?.translated ?: last?.translated ?: last?.original ?: "…", fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold,
                    color = Brand.Text)
                Spacer(Modifier.height(20.dp))
                Text((partial ?: last)?.original.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Brand.TextMuted,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GlyphCircle("↻", "翻轉".tr) { flip = !flip }
                GlyphCircle("⇄", "對調".tr) { LiveCaptions.swap(context) }
                last?.translated?.let { t -> GlyphCircle("🔊", "朗讀".tr) { Speaker.speak(context, t, s.target) } }
                IconCircle(Icons.Filled.Close, "關閉".tr, onClose)
            }
        }
    }
}

/** 圆形的字按钮（下面一行小字说明）。 */
@Composable
private fun GlyphCircle(glyph: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Brand.SurfaceHigh).border(1.dp, Brand.Outline, CircleShape)
            .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(glyph, fontSize = 22.sp, color = Brand.Cyan)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.TextMuted)
    }
}

@Composable
private fun LangButton(label: String, name: String, region: String?, status: TranslateLangs.Status?, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(Brand.SurfaceHigh).border(1.dp, Brand.Outline, shape).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint, modifier = Modifier.weight(1f))
            RecogBadge(status)
        }
        Text("$name ▾", style = MaterialTheme.typography.titleMedium, color = Brand.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (region != null) Text(region, style = MaterialTheme.typography.labelSmall, color = Brand.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToolChip(text: String, enabled: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).background(Brand.SurfaceHigh).border(1.dp, Brand.Outline, shape)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, tint = if (enabled) Brand.Text else Brand.TextFaint, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Brand.Text else Brand.TextFaint)
    }
}

@Composable
internal fun ToggleChip(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).background(if (on) Brand.Cyan.copy(alpha = 0.18f) else Brand.SurfaceHigh)
        .border(1.dp, if (on) Brand.Cyan else Brand.Outline, shape)
        .clickable { onChange(!on) }.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Brand.Cyan else Color.Transparent)
            .border(1.dp, if (on) Brand.Cyan else Brand.TextFaint, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) Brand.Text else Brand.TextMuted)
    }
}
