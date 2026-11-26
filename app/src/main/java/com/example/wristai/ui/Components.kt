package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.wristai.Meeting
import com.example.wristai.MeetingStatus
import com.example.wristai.ui.theme.Brand

/** 品牌标志：渐层圆角方块 + 声波条。 */
@Composable
fun BrandMark(size: Dp = 36.dp) {
    // dw 圓形徽章（res/drawable/ic_logo.xml，與 App 圖示同一組圖形）
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(com.example.wristai.R.drawable.ic_logo), "大D".tr,
        Modifier.size(size),
    )
}

/** 声波条图形（静态）。 */
@Composable
fun Waveform(modifier: Modifier, color: Color, bars: Int = 5) {
    val heights = listOf(0.35f, 0.7f, 1f, 0.6f, 0.4f, 0.8f, 0.5f)
    Canvas(modifier) {
        val gap = size.width / (bars * 2 - 1)
        for (i in 0 until bars) {
            val h = size.height * heights[i % heights.size]
            drawRoundRect(
                color = color,
                topLeft = Offset(i * gap * 2, (size.height - h) / 2),
                size = Size(gap, h),
                cornerRadius = CornerRadius(gap / 2),
            )
        }
    }
}

/** 统一的卡片：深色面 + 细边框 + 大圆角。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlight: Boolean = false,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Brand.CardRadius)
    Surface(
        modifier = modifier.fillMaxWidth().clip(shape).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = shape,
        // 水墨背景画：卡片稍微透明，画隐约透出来（字照样清楚）
        color = if (com.example.wristai.ui.theme.AppTheme.hasArt) Brand.Surface.copy(alpha = 0.86f) else Brand.Surface,
        border = if (highlight) BorderStroke(1.dp, Brand.Cyan.copy(alpha = 0.35f)) else BorderStroke(1.dp, Brand.Outline),
    ) {
        Box(if (highlight) Modifier.background(Brand.AccentSoft) else Modifier) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** 小节标题（新设计：一般字、不转大写、不加字距，跟 iOS PlainLabel 一样）。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Brand.TextMuted, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** 主要按钮：渐层底。 */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, recordDot: Boolean = false) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(if (enabled) Brand.Accent else Brush.linearGradient(listOf(Brand.SurfaceHigher, Brand.SurfaceHigher)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (recordDot) { RecordDot(18.dp); Spacer(Modifier.width(8.dp)) }
        icon?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else Brand.TextFaint)
    }
}

/** 录音键的红点（白色细框，放在任何底色上都看得清楚）。所有「开始录音 / 记录」的按钮都用它，不用播放三角形。 */
@Composable
fun RecordDot(size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        drawCircle(Color.White, radius = this.size.minDimension / 2)
        drawCircle(Brand.Recording, radius = this.size.minDimension / 2 * 0.72f)
    }
}

/** 次要按钮：描边。 */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = Brand.Text) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .height(48.dp)
            .clip(shape)
            .border(1.dp, Brand.Outline, shape)
            .background(Brand.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(it, null, tint = tint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** 圆形图标按钮（顶栏用）。 */
@Composable
fun IconCircle(icon: ImageVector, contentDescription: String, onClick: () -> Unit, tint: Color = Brand.Text) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Brand.SurfaceHigh).border(1.dp, Brand.Outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(20.dp)) }
}

/** 状态标签的配色 / 文字。 */
data class StatusStyle(val label: String, val color: Color, val busy: Boolean)

fun Meeting.statusStyle(): StatusStyle {
    val base = baseStatusStyle()
    val cur = steps.getOrNull(step)
    if (!base.busy || status == MeetingStatus.RECORDING || cur == null) return base
    // 「语音辨识 2/5」「上传音档」：比「AI 分析中」清楚现在做到哪
    val progress = stepDetail?.let { Regex("""\d+/\d+""").find(it)?.value }
    return base.copy(label = cur + (progress?.let { " ${it}" } ?: ""))
}

private fun Meeting.baseStatusStyle(): StatusStyle = when (status) {
    MeetingStatus.RECORDING -> StatusStyle(if (source == "phone") "手機錄音中".tr else "手錶錄音中".tr, Brand.Recording, true)
    MeetingStatus.RECEIVED -> StatusStyle("排隊中".tr, Brand.Cyan, true)
    MeetingStatus.TRANSCRIBING -> StatusStyle(
        if (chunkTotal > 0) "辨識中 {0}/{1}".trf(chunkDone, chunkTotal) else "辨識中".tr, Brand.Cyan, true)
    MeetingStatus.SUMMARIZING -> StatusStyle(
        if (chunkTotal > 1) "AI 分析中 {0}/{1}".trf(minOf(chunkDone + 1, chunkTotal), chunkTotal) else "AI 分析中".tr, Brand.Cyan, true)
    MeetingStatus.DONE -> StatusStyle("報告完成".tr, Brand.Success, false)
    // 通话录音本来就在手机的通话录音里，没有另存到录音机
    MeetingStatus.EXPORTED -> StatusStyle(if (source == "call") "待整理".tr else "已存錄音機".tr, Brand.Violet, false)
    MeetingStatus.FAILED -> if (error == com.example.wristai.ProcessMeetingWorker.CANCELED) StatusStyle("已中斷".tr, Brand.Warning, false)
        else StatusStyle("處理失敗".tr, Brand.Error, false)
}

@Composable
fun StatusChip(style: StatusStyle, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(style.color.copy(alpha = 0.12f))
            .border(1.dp, style.color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (style.busy) PulseDot(style.color) else Box(Modifier.size(6.dp).clip(CircleShape).background(style.color))
        Spacer(Modifier.width(6.dp))
        Text(style.label, style = MaterialTheme.typography.labelMedium, color = style.color)
    }
}

/** 呼吸闪烁的圆点（处理中）。 */
@Composable
fun PulseDot(color: Color, size: Dp = 6.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = a)))
}

/** 不定进度条：一道渐层光在轨道上来回扫。 */
@Composable
fun ScanningBar(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "scan")
    val x by t.animateFloat(-0.4f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    BoxWithConstraints(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Brand.SurfaceHigher)) {
        val w = maxWidth
        Box(
            Modifier
                .padding(start = (w * x.coerceAtLeast(0f)))
                .width(w * 0.4f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brand.Accent)
        )
    }
}

/** 进度圈（手表 / 统计用）。 */
@Composable
fun RingProgress(progress: Float, modifier: Modifier, stroke: Dp = 6.dp, track: Color = Brand.SurfaceHigher) {
    Canvas(modifier) {
        val s = stroke.toPx()
        val arcSize = Size(size.width - s, size.height - s)
        drawArc(track, 0f, 360f, false, Offset(s / 2, s / 2), arcSize, style = Stroke(s))
        drawArc(Brand.Accent, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(s / 2, s / 2), arcSize,
            style = Stroke(s, cap = StrokeCap.Round))
    }
}

/** 键值资讯小格（统计）。 */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Brand.SurfaceHigh).border(1.dp, Brand.Outline, RoundedCornerShape(16.dp))
            .padding(horizontal = if (value.length > 5) 10.dp else 14.dp, vertical = 12.dp)
    ) {
        // 长数字（例如 1635/2000）用小一号字，不会被切掉
        Text(value, style = if (value.length > 5) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = Brand.Text, maxLines = 1, softWrap = false)
        // 一行就好：三格一样高
        Text(label, style = MaterialTheme.typography.labelMedium, color = Brand.TextMuted, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** 渐层图示块（列表前缀）。 */
@Composable
fun GlyphTile(content: @Composable () -> Unit, size: Dp = 44.dp, filled: Boolean = false) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp))
            .background(if (filled) Brand.Accent else Brand.AccentSoft)
            .border(1.dp, Brand.Outline, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Brand.Outline))
}
