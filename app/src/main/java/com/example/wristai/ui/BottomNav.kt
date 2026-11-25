package com.example.wristai.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
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
import com.example.wristai.PhoneRecorder
import com.example.wristai.PhoneRecordingService
import com.example.wristai.ui.theme.Brand
import kotlin.math.cos
import kotlin.math.sin

/** 底部分页：今天｜資料庫｜（中间录音键）｜飲食｜設定（跟 iOS 一样）。档案管理在资料库右上的「管理」。 */
enum class Tab(val route: String, val label: String) {
    HOME("tab:home", "今天"),
    RECORDS("tab:records", "資料庫"),
    FOOD("tab:food", "飲食"),
    SETTINGS("tab:settings", "設定"),
}

@Composable
fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.Outline))
        Row(
            Modifier.fillMaxWidth().background(Brand.Surface).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 每格平分宽度：Fold5 封面萤幕只有约 344dp
            NavItem(Tab.HOME, current == Tab.HOME, Modifier.weight(1f)) { onSelect(Tab.HOME) }
            NavItem(Tab.RECORDS, current == Tab.RECORDS, Modifier.weight(1f)) { onSelect(Tab.RECORDS) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { RecordKey { onSelect(Tab.HOME) } }
            NavItem(Tab.FOOD, current == Tab.FOOD, Modifier.weight(1f)) { onSelect(Tab.FOOD) }
            NavItem(Tab.SETTINGS, current == Tab.SETTINGS, Modifier.weight(1f)) { onSelect(Tab.SETTINGS) }
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
                .semantics { contentDescription = if (recording) "停止錄音" else "開始錄音" },
            contentAlignment = Alignment.Center,
        ) {
            if (recording) Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
            else MicGlyph(Brand.Bg)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("記錄飲食") }, leadingIcon = { BowlGlyph(Brand.Text) },
                onClick = { menu = false; starter(true); onStarted() })
        }
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
                Tab.SETTINGS -> Icon(Icons.Outlined.Settings, null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(tab.label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint, maxLines = 1, softWrap = false)
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
