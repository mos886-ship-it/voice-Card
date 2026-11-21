package com.example.wristai.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 一组主题颜色。 */
class Palette(
    val bg: Color, val surface: Color, val surfaceHigh: Color, val surfaceHigher: Color, val outline: Color,
    val cyan: Color, val blue: Color, val violet: Color,
    val success: Color, val warning: Color, val error: Color, val recording: Color,
    val text: Color, val textMuted: Color, val textFaint: Color,
    val backdropTop: Color, val pink: Color,
    /** 主要按钮 / 选中状态的渐层 */
    val accent: List<Color> = listOf(Color(0xFF22D3EE), Color(0xFF3B82F6), Color(0xFF8B5CF6)),
)

/** 深空蓝（预设，跟手表一致）。 */
val DarkPalette = Palette(
    bg = Color(0xFF070B16), surface = Color(0xFF0F1526), surfaceHigh = Color(0xFF161E33),
    surfaceHigher = Color(0xFF1D2740), outline = Color(0xFF26314D),
    cyan = Color(0xFF22D3EE), blue = Color(0xFF3B82F6), violet = Color(0xFF8B5CF6),
    success = Color(0xFF34D399), warning = Color(0xFFFBBF24), error = Color(0xFFF87171), recording = Color(0xFFFF4D6D),
    text = Color(0xFFE8ECF5), textMuted = Color(0xFF8C97B2), textFaint = Color(0xFF5B6684),
    backdropTop = Color(0xFF0B1224), pink = Color(0xFFF472B6),
)

/** 浅色：白底 + 同一组品牌渐层，文字与强调色加深，户外 / 白天比较好读。 */
val LightPalette = Palette(
    bg = Color(0xFFF4F6FB), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFF0F3F9),
    surfaceHigher = Color(0xFFE3E8F2), outline = Color(0xFFD6DDE9),
    cyan = Color(0xFF0891B2), blue = Color(0xFF2563EB), violet = Color(0xFF7C3AED),
    success = Color(0xFF059669), warning = Color(0xFFD97706), error = Color(0xFFDC2626), recording = Color(0xFFE11D48),
    text = Color(0xFF0F172A), textMuted = Color(0xFF475569), textFaint = Color(0xFF94A3B8),
    backdropTop = Color(0xFFE8EEFA), pink = Color(0xFFDB2777),
)

/** 水墨：宣纸底、墨黑字、朱红点缀（跟 logo 同一套），按钮是墨色渐层。 */
val InkPalette = Palette(
    bg = Color(0xFFF2EADB), surface = Color(0xFFFAF5EA), surfaceHigh = Color(0xFFEFE6D4),
    surfaceHigher = Color(0xFFE4D9C3), outline = Color(0xFFD3C6AE),
    cyan = Color(0xFFA8322D), blue = Color(0xFF3B4A6B), violet = Color(0xFF7A5238),   // 朱红、黛蓝、赭石
    success = Color(0xFF4A7C59), warning = Color(0xFFB7791F), error = Color(0xFFA61B1B), recording = Color(0xFFC8102E),
    text = Color(0xFF1A1A1A), textMuted = Color(0xFF5A5246), textFaint = Color(0xFF9A8F7E),
    backdropTop = Color(0xFFEDE2CC), pink = Color(0xFFB4566B),
    accent = listOf(Color(0xFF3A3A3A), Color(0xFF1F1F1F), Color(0xFF111111)),
)

/**
 * WristAI 品牌色。数值来自目前的主题（[isDark]）；[isDark] 是 Compose 状态，
 * 切换主题时所有用到 Brand 的画面会自动重画，不用每个画面改写法。
 */
object Brand {
    var isDark by mutableStateOf(true)
    /** 水墨主题（浅色的一种） */
    var isInk by mutableStateOf(false)
    private val p get() = when { isInk -> InkPalette; isDark -> DarkPalette; else -> LightPalette }

    val Bg get() = p.bg
    val Surface get() = p.surface
    val SurfaceHigh get() = p.surfaceHigh
    val SurfaceHigher get() = p.surfaceHigher
    val Outline get() = p.outline
    val OutlineSoft get() = p.outline.copy(alpha = 0.2f)

    val Cyan get() = p.cyan
    val Blue get() = p.blue
    val Violet get() = p.violet

    val Success get() = p.success
    val Warning get() = p.warning
    val Error get() = p.error
    val Recording get() = p.recording

    val Text get() = p.text
    val TextMuted get() = p.textMuted
    val TextFaint get() = p.textFaint

    /** 渐层用两种主题都好看的亮色（按钮上是白字）。 */
    val Accent: Brush get() = Brush.linearGradient(p.accent)
    val AccentSoft: Brush get() = Brush.linearGradient(listOf(Cyan.copy(alpha = 0.18f), Violet.copy(alpha = 0.18f)))
    val Backdrop: Brush get() = Brush.verticalGradient(listOf(p.backdropTop, Bg, Bg))

    /** 逐字稿里不同说话者的颜色。 */
    val Speakers get() = listOf(Cyan, Violet, p.pink, Success, Warning, Blue)
}
