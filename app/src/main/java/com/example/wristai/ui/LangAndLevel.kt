package com.example.wristai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.voiceai.shared.AudioLevel
import com.example.wristai.SpeechLang
import com.example.wristai.ui.theme.Brand
import kotlin.math.max
import kotlin.math.min

/** 辨识语言二选一（胶囊分段）：主页 / 录音页录音键上方、录音详情页都用。 */
@Composable
fun LangPicker(value: SpeechLang, modifier: Modifier = Modifier, enabled: Boolean = true, onPick: (SpeechLang) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.fillMaxWidth().clip(shape).background(Brand.SurfaceHigh).border(1.dp, Brand.Outline, shape).padding(4.dp)
            .semantics { contentDescription = "辨識語言" },
    ) {
        for (l in SpeechLang.entries) {
            val on = l == value
            Box(
                Modifier.weight(1f).clip(shape)
                    .background(if (on) Brand.Accent else androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable(enabled = enabled && !on) { onPick(l) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(l.label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    color = if (on) Color.White else if (enabled) Brand.TextMuted else Brand.TextFaint)
            }
        }
    }
}

private val Amber = Color(0xFFFFC759)
private val Ok = Color(0xFF73FFB3)

/**
 * 录音卡上的音量条：左右对称（以中线上下长）的圆角细柱，最新在右，越新越亮；
 * 太大声时最新 6 根变琥珀色。下面一行收音状态；科技模式右边显示 dB。
 */
@Composable
fun LevelMeter(modifier: Modifier = Modifier, height: Dp = 40.dp, barColor: Color = Brand.Text, textColor: Color = Brand.TextMuted) {
    val s by AudioLevel.state.collectAsState()
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = s.history.size
            // 自动增益：最近 5 秒最大声的那根拉到约满格（但安静时不放大杂音）
            val gain = 0.92f / max(s.history.maxOrNull() ?: 0f, 0.3f)
            val gap = 2.dp.toPx()
            val w = max(1.5f, (size.width - gap * (n - 1)) / n)
            val mid = size.height / 2
            s.history.forEachIndexed { i, v ->
                val lv = min(1f, v * gain)
                val h = max(3.dp.toPx(), lv * size.height)
                val age = i.toFloat() / max(1, n - 1)
                val hot = s.loud && i >= n - 6
                val alpha = 0.28f + 0.72f * age * (0.45f + 0.55f * lv)
                drawRoundRect(
                    color = (if (hot) Amber else barColor).copy(alpha = alpha),
                    topLeft = Offset(i * (w + gap), mid - h / 2), size = Size(w, h), cornerRadius = CornerRadius(w / 2),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (text, color) = when {
                s.loud -> "聲音太大，請離遠一點" to Amber
                s.quiet -> "幾乎沒有聲音，請靠近一點" to textColor.copy(alpha = 0.6f)
                else -> "收音正常" to Ok
            }
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = textColor, modifier = Modifier.weight(1f))
            if (UiMode.tech) Text("%.0f dB".format(s.db), style = MaterialTheme.typography.labelMedium, color = textColor.copy(alpha = 0.8f))
        }
    }
}

/** 客语腔调选单（小胶囊「四縣腔 ▾」）。 */
@Composable
fun DialectMenu(value: com.example.wristai.HakkaDialect, enabled: Boolean = true, onPick: (com.example.wristai.HakkaDialect) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box {
        Text(
            value.label + "腔 ▾", style = MaterialTheme.typography.labelLarge, color = if (enabled) Brand.Cyan else Brand.TextFaint,
            modifier = Modifier.clip(shape).background(Brand.SurfaceHigh).border(1.dp, Brand.Outline, shape)
                .clickable(enabled = enabled) { open = true }.padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { contentDescription = "客語腔調：" + value.label },
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (d in com.example.wristai.HakkaDialect.entries) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text((if (d == value) "✓ " else "　") + d.label + "腔") },
                    onClick = { open = false; if (d != value) onPick(d) },
                )
            }
        }
    }
}

/** 新录音的语言选择：三选一＋下面一行说明（选客语时右边多腔调选单）。主页、录音页共用。 */
@Composable
fun LangChooser(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lang = SpeechLang.current
    Column(modifier) {
        LangPicker(lang) { SpeechLang.set(context, it) }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(lang.hint, style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint,
                modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (lang == SpeechLang.HAK) DialectMenu(com.example.wristai.HakkaDialect.current) { com.example.wristai.HakkaDialect.set(context, it) }
        }
    }
}
