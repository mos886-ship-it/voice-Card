package com.example.wristai.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

/**
 * 浮世繪（取代原本的深色）：和纸米黄底、墨黑字、普鲁士蓝重点、朱红录音，
 * 配色取自木刻版画（tools/ukiyoe_bg.py 的背景画同一套颜色）。
 */
val UkiyoePalette = Palette(
    bg = Color(0xFFF0E4C8), surface = Color(0xFFF8F2E4), surfaceHigh = Color(0xFFEADCBC),
    surfaceHigher = Color(0xFFE0CFA8), outline = Color(0xFFB9A57F),
    cyan = Color(0xFF1E3A6E), blue = Color(0xFF3C6AA8), violet = Color(0xFFC8402B),   // 普鲁士蓝、中蓝、朱红
    success = Color(0xFF2F5E4C), warning = Color(0xFF9A6B16), error = Color(0xFFB3261E), recording = Color(0xFFC8402B),
    text = Color(0xFF2A2522), textMuted = Color(0xFF5E5245), textFaint = Color(0xFF8C7D68),
    backdropTop = Color(0xFFF0E4C8), pink = Color(0xFFB4566B),
    accent = listOf(Color(0xFF1E3A6E), Color(0xFF1E3A6E)),
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

/** A 清晰工作台：浅灰纸底、白卡片、深蓝黑字、单一蓝色重点（不用渐层），录音用红色（跟 iOS Palette.clear 一样）。 */
val ClearPalette = Palette(
    bg = Color(0xFFF5F6F8), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFF0F2F5),
    surfaceHigher = Color(0xFFE4E7EC), outline = Color(0xFFE3E6EB),
    cyan = Color(0xFF1F5BD8), blue = Color(0xFF1F5BD8), violet = Color(0xFF6941C6),
    success = Color(0xFF1E7A4C), warning = Color(0xFFA35A00), error = Color(0xFFC4221F), recording = Color(0xFFD0312D),
    text = Color(0xFF101828), textMuted = Color(0xFF5D6675), textFaint = Color(0xFF7A8494),
    backdropTop = Color(0xFFF5F6F8), pink = Color(0xFFC11574),
    accent = listOf(Color(0xFF1F5BD8), Color(0xFF1F5BD8)),
)

/** C 暖和日常：米白底、暖灰字、珊瑚红重点（也是录音色）、鼠尾草绿给饮食与成功状态（跟 iOS Palette.warm 一样）。 */
val WarmPalette = Palette(
    bg = Color(0xFFFBF8F4), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFF5F0EA),
    surfaceHigher = Color(0xFFEDE6DD), outline = Color(0xFFEDE6DD),
    cyan = Color(0xFFC4492C), blue = Color(0xFF3B6E8F), violet = Color(0xFF8A6A3D),
    success = Color(0xFF3E7A58), warning = Color(0xFF9A6510), error = Color(0xFFB3261E), recording = Color(0xFFC4492C),
    text = Color(0xFF2B2622), textMuted = Color(0xFF6E655D), textFaint = Color(0xFF8C8279),
    backdropTop = Color(0xFFFBF8F4), pink = Color(0xFFB4566B),
    accent = listOf(Color(0xFFC4492C), Color(0xFFC4492C)),
)

/**
 * 大D 品牌色。数值来自目前的配色（[theme]：clear 清晰 / warm 暖和日常 / ukiyoe 浮世繪 / ink 水墨）；
 * [theme] 是 Compose 状态，切换配色时所有用到 Brand 的画面会自动重画。
 */
object Brand {
    var theme by mutableStateOf("clear")
    val isInk get() = theme == "ink"
    val isUkiyoe get() = theme == "ukiyoe"
    /** 单色重点、不用渐层的配色（清晰 / 暖和日常 / 浮世繪）。 */
    val isNew get() = theme == "clear" || theme == "warm" || theme == "ukiyoe"
    private val p get() = when (theme) {
        "warm" -> WarmPalette
        "ukiyoe" -> UkiyoePalette
        "ink" -> InkPalette
        else -> ClearPalette
    }
    /** 卡片圆角：清晰 16、暖和日常 22（比较圆润）、浮世繪 12（版画方正）、水墨 20。 */
    val CardRadius get() = when (theme) { "warm" -> 22.dp; "clear" -> 16.dp; "ukiyoe" -> 12.dp; else -> 20.dp }

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
    val AccentSoft: Brush get() = if (isNew) Brush.linearGradient(listOf(Cyan.copy(alpha = 0.06f), Cyan.copy(alpha = 0.06f)))
        else Brush.linearGradient(listOf(Cyan.copy(alpha = 0.18f), Violet.copy(alpha = 0.18f)))
    val Backdrop: Brush get() = Brush.verticalGradient(listOf(p.backdropTop, Bg, Bg))

    /** 逐字稿里不同说话者的颜色。 */
    val Speakers get() = listOf(Cyan, Violet, p.pink, Success, Warning, Blue)
}
