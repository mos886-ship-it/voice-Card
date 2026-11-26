package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wristai.Meeting
import com.example.wristai.MeetingStatus
import com.example.wristai.ui.theme.Brand
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 报告的一行摘要：取「摘要」那一段（去掉 Markdown 符号）；没有就 null。
 * 「無」「未知」这种空值也当没有。
 */
fun summaryOf(report: String?): String? {
    if (report.isNullOrBlank()) return null
    val lines = report.lines()
    val start = lines.indexOfFirst { it.trimStart().startsWith("#") && it.contains("摘要") }
    if (start < 0) return null
    val body = lines.drop(start + 1).takeWhile { !it.trimStart().startsWith("#") }
        .joinToString(" ") { it.trim().removePrefix("-").removePrefix("*").trim() }
        .replace(Regex("[*_`>|]+"), "").replace(Regex("\\s+"), " ").trim()
    return body.takeIf { it.isNotEmpty() && it !in setOf("無", "未知", "（無）", "None", "없음") }
}

/** 清单左边的来源图示（手錶 / 手機 / 通話 / 匯入）。 */
@Composable
fun SourceIcon(origin: Origin, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    if (origin == Origin.CALL) {
        Icon(Icons.Filled.Call, null, tint = color, modifier = modifier)
        return
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        when (origin) {
            Origin.WATCH -> {
                // 表带＋圆角表面
                drawLine(color, Offset(w * 0.5f, 0f), Offset(w * 0.5f, h * 0.2f), strokeWidth = w * 0.26f)
                drawLine(color, Offset(w * 0.5f, h * 0.8f), Offset(w * 0.5f, h), strokeWidth = w * 0.26f)
                drawRoundRect(color, Offset(w * 0.2f, h * 0.18f), Size(w * 0.6f, h * 0.64f), CornerRadius(w * 0.16f), style = s)
                drawCircle(Brand.Error, w * 0.08f, Offset(w * 0.5f, h * 0.5f))
            }
            Origin.PHONE -> {
                drawRoundRect(color, Offset(w * 0.26f, h * 0.06f), Size(w * 0.48f, h * 0.88f), CornerRadius(w * 0.1f), style = s)
                drawCircle(Brand.Error, w * 0.08f, Offset(w * 0.5f, h * 0.5f))
            }
            else -> {
                // 汇入：箭头放进托盘
                drawLine(color, Offset(w * 0.5f, h * 0.08f), Offset(w * 0.5f, h * 0.6f), strokeWidth = s.width, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.3f, h * 0.42f), Offset(w * 0.5f, h * 0.62f), strokeWidth = s.width, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.7f, h * 0.42f), Offset(w * 0.5f, h * 0.62f), strokeWidth = s.width, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.14f, h * 0.66f), Offset(w * 0.14f, h * 0.88f), strokeWidth = s.width, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.14f, h * 0.88f), Offset(w * 0.86f, h * 0.88f), strokeWidth = s.width, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.86f, h * 0.88f), Offset(w * 0.86f, h * 0.66f), strokeWidth = s.width, cap = StrokeCap.Round)
            }
        }
    }
}

/**
 * 录音清单的一列（跟 iOS 版一样）：来源图示、标题、右上时间、一行摘要；
 * 状态只在处理中 / 失败时显示；长按 → 打开 / 删除（要确认）。
 * 科技模式：摘要两行＋一行细节（来源 · 长度 · AI · 耗时）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordRow(
    m: Meeting, summary: String?, snippet: AnnotatedString?,
    onOpen: () -> Unit, onDelete: () -> Unit,
) {
    val style = m.statusStyle()
    val origin = m.origin()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val time = remember(m.createdAt) { SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date(m.createdAt)) }
    val failed = m.status == MeetingStatus.FAILED
    val showStatus = style.busy || failed
    val tech = UiMode.tech

    Box {
        GlassCard(Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true }), contentPadding = 14.dp) {
            Row(verticalAlignment = Alignment.Top) {
                GlyphTile({ SourceIcon(origin, if (style.busy) Brand.Cyan else Brand.Text) })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(meetingTitle(m), style = MaterialTheme.typography.titleMedium, color = Brand.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(time, style = MaterialTheme.typography.labelMedium, color = Brand.TextFaint)
                    }
                    Spacer(Modifier.height(3.dp))
                    // 还没有报告：商用显示「来源 · 长度」；科技模式下面的细节行已经有了，不重复
                    val line = summary ?: if (tech) null else "${origin.label} · ${humanDuration(m.durationSec)}"
                    if (line != null) Text(line, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                        maxLines = if (tech) 2 else 1, overflow = TextOverflow.Ellipsis)
                    if (tech) {
                        val details = listOfNotNull(
                            origin.label, humanDuration(m.durationSec),
                            m.aiModel?.let { aiModelLabel(it).removePrefix("Google ").removePrefix("Anthropic ") },
                            processSeconds(m.processStartedAt, m.processEndedAt)?.takeIf { it > 0 }?.let { "耗時 {0} 秒".trf(it) },
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(details.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // 搜寻时：在哪里找到＋前后文（关键字标亮）
                    if (snippet != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(snippet, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (showStatus) {
                        Spacer(Modifier.height(8.dp))
                        StatusChip(style)
                    }
                }
            }
            if (style.busy) {
                Spacer(Modifier.height(12.dp))
                ScanningBar()
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("打開".tr) }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text("刪除".tr, color = Brand.Error) }, onClick = { menu = false; confirm = true })
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("刪除「{0}」？".trf(meetingTitle(m))) },
            text = { Text("錄音、報告與逐字稿都會刪除，刪除後無法復原。".tr) },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("刪除".tr, color = Brand.Error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消".tr) } },
        )
    }
}
