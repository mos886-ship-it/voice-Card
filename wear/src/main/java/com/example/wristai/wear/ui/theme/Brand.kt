package com.example.wristai.wear.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 手表的一组颜色。 */
class WatchPalette(
    val bg: Color, val bgCenter: Color, val surface: Color, val surfaceHigh: Color, val outline: Color,
    val cyan: Color, val blue: Color, val violet: Color,
    val success: Color, val warning: Color, val recording: Color,
    val text: Color, val textMuted: Color, val textFaint: Color,
    val accent: List<Color>, val record: List<Color>,
)

/** 深色（预设）：深空蓝 + 青→紫渐层。OLED 最省电。 */
val DarkWatch = WatchPalette(
    bg = Color(0xFF000000), bgCenter = Color(0xFF0B1630), surface = Color(0xFF0F1526), surfaceHigh = Color(0xFF161E33),
    outline = Color(0xFF26314D),
    cyan = Color(0xFF22D3EE), blue = Color(0xFF3B82F6), violet = Color(0xFF8B5CF6),
    success = Color(0xFF34D399), warning = Color(0xFFFBBF24), recording = Color(0xFFFF4D6D),
    text = Color(0xFFE8ECF5), textMuted = Color(0xFF8C97B2), textFaint = Color(0xFF5B6680),
    accent = listOf(Color(0xFF22D3EE), Color(0xFF3B82F6), Color(0xFF8B5CF6)),
    record = listOf(Color(0xFFFF6B81), Color(0xFFFF4D6D), Color(0xFFE11D48)),
)

/** 水墨：宣纸底、墨色按钮、朱红录音（跟 logo 与手机的水墨主题同一套）。 */
val InkWatch = WatchPalette(
    bg = Color(0xFFEBDFC8), bgCenter = Color(0xFFFBF6EC), surface = Color(0xFFFAF5EA), surfaceHigh = Color(0xFFEFE6D4),
    outline = Color(0xFFD3C6AE),
    cyan = Color(0xFFA8322D), blue = Color(0xFF3B4A6B), violet = Color(0xFF7A5238),
    success = Color(0xFF4A7C59), warning = Color(0xFFB7791F), recording = Color(0xFFC8102E),
    text = Color(0xFF1A1A1A), textMuted = Color(0xFF5A5246), textFaint = Color(0xFF9A8F7E),
    accent = listOf(Color(0xFF3A3A3A), Color(0xFF1F1F1F), Color(0xFF111111)),
    record = listOf(Color(0xFFD9473F), Color(0xFFB5121B), Color(0xFF8E0E15)),
)

/**
 * 手表品牌色。主题跟着手机：手机选「水墨」→ 手表也是水墨；其他（深色 / 浅色 / 自动）→ 手表用深色
 * （手表是 OLED，深色最省电，浅色在小圆屏上也太刺眼）。[isInk] 是 Compose 状态，改了画面会自动重画。
 */
object Brand {
    private const val PREFS = "wristai_watch"
    private const val KEY = "theme"
    var isInk by mutableStateOf(false)
    private val p get() = if (isInk) InkWatch else DarkWatch

    /** 开 App 时读出上次手机推来的主题。 */
    fun load(context: Context) {
        isInk = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "dark") == "ink"
    }

    /** 手机推来新主题（"ink" / "dark"）。 */
    fun apply(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, key).apply()
        isInk = key == "ink"
    }

    val Bg get() = p.bg
    val BgCenter get() = p.bgCenter
    val Surface get() = p.surface
    val SurfaceHigh get() = p.surfaceHigh
    val Outline get() = p.outline
    val Cyan get() = p.cyan
    val Blue get() = p.blue
    val Violet get() = p.violet
    val Success get() = p.success
    val Warning get() = p.warning
    val Recording get() = p.recording
    val Text get() = p.text
    val TextMuted get() = p.textMuted
    val TextFaint get() = p.textFaint

    val Accent: Brush get() = Brush.linearGradient(p.accent)
    val RecordBrush: Brush get() = Brush.linearGradient(p.record)
}
