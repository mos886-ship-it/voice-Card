package com.example.wristai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voiceai.shared.tr
import com.example.wristai.ui.theme.Brand

/**
 * 三大主轴（錄音報告、飲食記錄、即時翻譯）各自的分页里，上面一排小分页（例如 錄音｜分析｜管理｜設定）。
 * 选到哪一个记在这里：换分页再回来、从「設定」的连结跳过来都停在对的地方。
 */
object Hubs {
    var records by mutableIntStateOf(0)
    var food by mutableIntStateOf(0)
    var translate by mutableIntStateOf(0)
    /** 每个主轴的「設定」小分页是第几个（都放最后）。 */
    const val SETTINGS = 3
}

/** 主轴分页的外框：标题＋一句说明＋右上动作，下面一排小分页，再下面是内容。 */
@Composable
fun HubScaffold(
    title: String,
    subtitle: String,
    sections: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.(Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Brand.Text)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            actions()
        }
        Segmented(sections, selected, Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) { onSelect(it) }
        Column(Modifier.weight(1f).fillMaxWidth()) { content(selected) }
    }
}

/** 选项多（例如六种语言）时用：每排 [perRow] 个，平分宽度，选中的用重点色框。 */
@Composable
fun ChoiceGrid(labels: List<String>, selected: Int, perRow: Int = 3, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.indices.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in row) {
                    val on = i == selected
                    val shape = RoundedCornerShape(12.dp)
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(shape)
                            .background(if (on) Brand.SurfaceHigher else Brand.Surface)
                            .border(if (on) 2.dp else 1.dp, if (on) Brand.Cyan else Brand.Outline, shape)
                            .clickable { onSelect(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(labels[i], style = MaterialTheme.typography.labelLarge, color = if (on) Brand.Text else Brand.TextMuted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 可以捲动的一页（分析、設定这类）：左右留白一致。 */
@Composable
fun HubPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        Spacer(Modifier.height(24.dp))
    }
}

/** 一排统计小格（平分宽度）。 */
@Composable
fun StatRow(vararg tiles: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((v, l) in tiles) StatTile(v, l, Modifier.weight(1f))
    }
}

/**
 * 长条图：[values] 每根的值，[labels] 下面的字（可以留空字串），[goal] 画一条虚线（例如每日热量目标），
 * 超过目标的长条用 [overColor]。
 */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    goal: Float? = null,
    color: Color = Brand.Cyan,
    overColor: Color = Brand.Warning,
    height: Dp = 120.dp,
) {
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(goal ?: 0f).coerceAtLeast(1f) * 1.1f
    val track = Brand.SurfaceHigher
    val line = Brand.TextFaint
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val w = (slot * 0.6f).coerceAtMost(28.dp.toPx())
            val r = CornerRadius(4.dp.toPx())
            values.forEachIndexed { i, v ->
                val x = i * slot + (slot - w) / 2
                drawRoundRect(track, Offset(x, 0f), Size(w, size.height), r)
                val h = size.height * (v / max)
                if (h > 0f) drawRoundRect(if (goal != null && v > goal) overColor else color, Offset(x, size.height - h), Size(w, h), r)
            }
            if (goal != null && goal > 0f) {
                val y = size.height * (1 - goal / max)
                drawLine(line, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            for (l in labels) Text(l, Modifier.weight(1f), fontSize = 10.sp, color = Brand.TextFaint, textAlign = TextAlign.Center,
                maxLines = 1, softWrap = false)
        }
    }
}

/** 一行横向比例条：名称、数值、占多少（0..1）。 */
@Composable
fun ShareBar(label: String, value: String, fraction: Float, color: Color = Brand.Cyan) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Brand.Text, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.labelLarge, color = Brand.TextMuted)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Brand.SurfaceHigher)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

/** 分析页没有资料时的一句话。 */
@Composable
fun EmptyNote(text: String) {
    GlassCard {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Brand.TextMuted)
    }
}

/** 「全部設定」：主轴的设定页底下，连到共用的设定（介面、AI 引擎、大D的主機、備份…）。 */
@Composable
fun AllSettingsLink(onOpenSettings: () -> Unit) {
    GhostButton("全部設定（介面、AI 引擎、大D的主機、備份）→".tr, onOpenSettings, Modifier.fillMaxWidth())
}

/** 最近 [days] 天每天的起点（毫秒，由旧到新）。 */
fun dayStarts(days: Int, now: Long = System.currentTimeMillis()): List<Long> {
    val c = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    val out = ArrayList<Long>(days)
    for (i in 0 until days) {
        out += c.timeInMillis
        c.add(java.util.Calendar.DAY_OF_MONTH, -1)
    }
    return out.reversed()
}

/** 长条图下面的日期字：7 天写星期，更多天只写几个日期。 */
fun dayLabels(starts: List<Long>): List<String> {
    val wk = java.text.SimpleDateFormat("EEEEE", com.example.voiceai.shared.I18n.locale)
    val md = java.text.SimpleDateFormat("M/d", java.util.Locale.US)
    return starts.mapIndexed { i, t ->
        when {
            starts.size <= 7 -> wk.format(java.util.Date(t))
            i % 7 == starts.size % 7 || i == starts.size - 1 -> md.format(java.util.Date(t))
            else -> ""
        }
    }
}
