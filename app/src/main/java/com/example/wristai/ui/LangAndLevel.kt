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
import com.example.wristai.AudioLevel
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
                Text(l.label, style = MaterialTheme.typography.labelLarge,
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
