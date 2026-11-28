package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wristai.FoodPhotos
import com.example.wristai.PhoneRecorder
import com.example.wristai.PhoneRecordingService
import com.example.wristai.ui.theme.AppTheme
import com.example.wristai.ui.theme.Brand
import com.example.wristai.ui.theme.LayoutMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * 底部分页（依排布）。三大主轴（錄音報告、飲食記錄、即時翻譯）各一个分页，档案管理在「錄音」的「管理」。
 * 快錄：今天｜錄音｜（中间录音键）｜飲食｜翻譯｜對話（設定在「今天」右上的齿轮）；總覽：主頁｜錄音｜飲食｜翻譯｜對話｜設定。
 * 對話 = 遠距對話（獨立的功能，有來電、未接、好友邀請時圖示加紅點）。
 */
enum class Tab(val route: String, private val quickLabel: String, private val overviewLabel: String) {
    HOME("tab:home", "今天".tr, "主頁".tr),
    RECORDS("tab:records", "錄音".tr, "錄音".tr),
    FOOD("tab:food", "飲食".tr, "飲食".tr),
    TRANSLATE("tab:translate", "翻譯".tr, "翻譯".tr),
    TALK("tab:talk", "對話".tr, "對話".tr),
    SETTINGS("tab:settings", "設定".tr, "設定".tr);

    val label get() = when (AppTheme.layout) {
        LayoutMode.QUICK -> quickLabel
        LayoutMode.OVERVIEW -> overviewLabel
    }
}

@Composable
fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    if (AppTheme.layout == LayoutMode.OVERVIEW) { OverviewBottomNav(current, onSelect); return }
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.Outline))
        Row(
            Modifier.fillMaxWidth().background(if (AppTheme.hasArt) Brand.Surface.copy(alpha = 0.9f) else Brand.Surface)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 每格平分宽度：Fold5 封面萤幕只有约 344dp
            NavItem(Tab.HOME, current == Tab.HOME, Modifier.weight(1f)) { onSelect(Tab.HOME) }
            NavItem(Tab.RECORDS, current == Tab.RECORDS, Modifier.weight(1f)) { onSelect(Tab.RECORDS) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { RecordKey { onSelect(Tab.HOME) } }
            NavItem(Tab.FOOD, current == Tab.FOOD, Modifier.weight(1f)) { onSelect(Tab.FOOD) }
            NavItem(Tab.TRANSLATE, current == Tab.TRANSLATE, Modifier.weight(1f)) { onSelect(Tab.TRANSLATE) }
            NavItem(Tab.TALK, current == Tab.TALK, Modifier.weight(1f)) { onSelect(Tab.TALK) }
        }
    }
}

/** 中间的录音键：任何分页一按就录（录音卡在「今天」），录音中变停止键；长按可以选「記錄飲食」。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordKey(onStarted: () -> Unit) {
    val context = LocalContext.current
    val active by PhoneRecorder.active.collectAsState()
    val starter = rememberRecordStarter()
    var menu by remember { mutableStateOf(false) }
    /** 拍照记录：null = 没开；false = 只拍照；true = 拍照＋口述 */
    var photo by remember { mutableStateOf<Boolean?>(null) }
    val recording = active != null
    Box {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(if (recording) Brand.Recording else Brand.Text)
                .combinedClickable(
                    onClick = {
                        if (recording) PhoneRecordingService.stop(context) else starter(false)
                        onStarted()
                    },
                    onLongClick = { if (!recording) menu = true },
                )
                .semantics { contentDescription = if (recording) "停止錄音".tr else "開始錄音".tr },
            contentAlignment = Alignment.Center,
        ) {
            if (recording) Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
            else MicGlyph(Brand.Bg)
        }
        // 长按：记录饮食的三种方式——说 / 拍 / 拍＋说
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("說：記錄飲食".tr) }, leadingIcon = { BowlGlyph(Brand.Text) },
                onClick = { menu = false; FoodPhotos.pendingForVoice = null; starter(true); onStarted() })
            DropdownMenuItem(text = { Text("拍：拍照記錄".tr) }, leadingIcon = { BowlGlyph(Brand.Text) },
                onClick = { menu = false; photo = false })
            DropdownMenuItem(text = { Text("拍＋說：拍照再補一句".tr) }, leadingIcon = { BowlGlyph(Brand.Text) },
                onClick = { menu = false; photo = true })
        }
    }
    photo?.let { voice ->
        FoodPhotoCapture(withVoice = voice, onDismiss = { photo = null },
            onStartVoice = { starter(true) }, onFinished = { photo = null; onStarted() })
    }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // 选中的分页：图示与字用重点色（新设计不用渐层胶囊）
    val tint = if (selected) Brand.Cyan else Brand.TextMuted
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) {
            when (tab) {
                Tab.HOME -> SunGlyph(tint)
                Tab.RECORDS -> ListGlyph(tint)
                Tab.FOOD -> BowlGlyph(tint)
                Tab.TRANSLATE -> TranslateGlyph(tint)
                Tab.TALK -> TalkGlyph(tint)
                Tab.SETTINGS -> Icon(Icons.Outlined.Settings, null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(tab.label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint, maxLines = 1, softWrap = false)
    }
}

/** 總覽排布的分页列：五个分页平分宽度，选中的图示后面一个渐层胶囊。 */
@Composable
private fun OverviewBottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.Outline))
        Row(Modifier.fillMaxWidth().background(if (AppTheme.hasArt) Brand.Bg.copy(alpha = 0.9f) else Brand.Bg)
            .padding(horizontal = 4.dp, vertical = 8.dp)) {
            for (t in Tab.entries) {
                val selected = t == current
                val tint = if (selected) Color.White else Brand.TextMuted
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(t) }
                        .padding(2.dp)
                        .semantics { this.selected = selected },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.widthIn(max = 56.dp).fillMaxWidth().height(30.dp).clip(RoundedCornerShape(15.dp))
                            .then(if (selected) Modifier.background(Brand.Accent) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (t) {
                            Tab.HOME -> Icon(Icons.Filled.Home, null, tint = tint, modifier = Modifier.size(20.dp))
                            Tab.RECORDS -> Waveform(Modifier.size(width = 20.dp, height = 16.dp), tint)
                            Tab.FOOD -> BowlGlyph(tint)
                            Tab.TRANSLATE -> TranslateGlyph(tint)
                            Tab.TALK -> TalkGlyph(tint)
                            Tab.SETTINGS -> Icon(Icons.Filled.Settings, null, tint = tint, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (selected) Brand.Text else Brand.TextMuted,
                        maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** 對話：兩個對話泡泡（跟「通話」錄音的電話圖示分開）；有來電、未接或好友邀請時右上紅點。 */
@Composable
internal fun TalkGlyph(color: Color, size: androidx.compose.ui.unit.Dp = 20.dp, badge: Boolean = true) {
    val calls by com.example.wristai.RemoteTalk.calls.collectAsState()
    val requests by com.example.wristai.TalkFriends.requests.collectAsState()
    Box {
        Canvas(Modifier.size(size)) {
            val w = this.size.width; val h = this.size.height; val s = 1.7.dp.toPx()
            // 左上的泡泡（實線）＋尾巴
            drawRoundRect(color, Offset(w * 0.04f, h * 0.10f), Size(w * 0.60f, h * 0.44f), CornerRadius(w * 0.14f), style = Stroke(s))
            drawLine(color, Offset(w * 0.18f, h * 0.54f), Offset(w * 0.12f, h * 0.70f), s, StrokeCap.Round)
            drawLine(color, Offset(w * 0.12f, h * 0.70f), Offset(w * 0.32f, h * 0.54f), s, StrokeCap.Round)
            // 右下的泡泡（實心）＋尾巴
            drawRoundRect(color, Offset(w * 0.40f, h * 0.40f), Size(w * 0.56f, h * 0.40f), CornerRadius(w * 0.13f))
            drawLine(color, Offset(w * 0.80f, h * 0.78f), Offset(w * 0.88f, h * 0.94f), s, StrokeCap.Round)
        }
        // 通話還在進行（切到別的分頁）：綠點；有來電、未接、好友邀請：紅點
        val inCall by com.example.wristai.RemoteTalk.state.collectAsState()
        if (badge && inCall.connected)
            Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(Brand.Success))
        else if (badge && (calls.isNotEmpty() || requests.isNotEmpty()))
            Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(Brand.Error))
    }
}

/** 翻译：「文A」两个字（中文→外文）。 */
@Composable
internal fun TranslateGlyph(color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("文", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
        Text("A", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** 今天：太阳（圆＋八道光）。 */
@Composable
private fun SunGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val c = center
        val s = 1.7.dp.toPx()
        val d = size.minDimension
        drawCircle(color, d * 0.2f, c, style = Stroke(s))
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val r1 = d * 0.34f
            val r2 = d * 0.47f
            drawLine(color, Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()),
                Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()), s, StrokeCap.Round)
        }
    }
}

/** 资料库：圆角方框里三行清单。 */
@Composable
private fun ListGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val s = 1.7.dp.toPx()
        drawRoundRect(color, Offset(w * 0.06f, h * 0.14f), Size(w * 0.88f, h * 0.72f), CornerRadius(3.dp.toPx()), style = Stroke(s))
        for (y in listOf(0.36f, 0.5f, 0.64f)) {
            drawCircle(color, s * 0.7f, Offset(w * 0.26f, h * y))
            drawLine(color, Offset(w * 0.38f, h * y), Offset(w * 0.76f, h * y), s, StrokeCap.Round)
        }
    }
}

/** 麦克风（录音键上）。 */
@Composable
private fun MicGlyph(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val s = 2.dp.toPx()
        drawRoundRect(color, Offset(w * 0.34f, h * 0.06f), Size(w * 0.32f, h * 0.52f), CornerRadius(w * 0.16f))
        drawArc(color, 0f, 180f, false, Offset(w * 0.2f, h * 0.24f), Size(w * 0.6f, h * 0.5f), style = Stroke(s, cap = StrokeCap.Round))
        drawLine(color, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.5f, h * 0.92f), s, StrokeCap.Round)
    }
}

/** 饮食图示：一个碗 + 两缕热气。 */
@Composable
internal fun BowlGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val s = 2.dp.toPx()
        // 碗
        drawArc(color, 0f, 180f, true, Offset(w * 0.08f, h * 0.30f), Size(w * 0.84f, h * 0.62f))
        drawLine(color, Offset(w * 0.02f, h * 0.61f), Offset(w * 0.98f, h * 0.61f), s, StrokeCap.Round)
        // 热气
        for (x in listOf(0.38f, 0.62f)) {
            drawArc(color, 200f, 140f, false, Offset(w * (x - 0.08f), h * 0.02f), Size(w * 0.16f, h * 0.26f), style = Stroke(s * 0.8f, cap = StrokeCap.Round))
        }
    }
}
