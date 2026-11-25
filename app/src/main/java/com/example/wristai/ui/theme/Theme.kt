package com.example.wristai.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** 目前选的配色与排布（Compose 状态：设定页一改，整个 App 马上换）。 */
object AppTheme {
    var mode by androidx.compose.runtime.mutableStateOf(ThemeMode.CLEAR)
    /** 水墨配色的背景画（存在设定 ink_bg）。 */
    var inkBg by androidx.compose.runtime.mutableStateOf(InkBackground.BOAT)
    /** 浮世繪配色的背景画（存在设定 ukiyoe_bg）。 */
    var ukiyoeBg by androidx.compose.runtime.mutableStateOf(UkiyoeBackground.GEISHA)
    /** 现在要铺的背景画（null = 没有）。 */
    val art: Int? get() = when (mode) {
        ThemeMode.INK -> inkBg.res
        ThemeMode.UKIYOE -> ukiyoeBg.res
        else -> null
    }
    /** 现在有没有背景画：卡片、分页列会稍微透明，让画隐约透出来。 */
    val hasArt get() = art != null
    var layout by androidx.compose.runtime.mutableStateOf(LayoutMode.QUICK)
}

/**
 * 配色（存在设定 theme_mode）：清晰（预设）/ 暖和日常 / 深色 / 水墨。
 * 旧值 light、system 当清晰。手表：水墨 → 水墨，其他 → 深色。
 */
enum class ThemeMode(val key: String, val label: String, val note: String) {
    CLEAR("clear", "清晰", "淺灰紙底、白卡片、藍色重點：資訊清楚，適合工作。"),
    WARM("warm", "暖和", "米白底、珊瑚紅與鼠尾草綠：溫和好讀，適合日常與家人。"),
    UKIYOE("ukiyoe", "浮世繪", "和紙底、普魯士藍與朱紅，木刻版畫的平塗色塊與墨線，可選背景畫。手錶維持深色。"),
    INK("ink", "水墨", "宣紙底、墨黑字、朱紅點綴，跟 logo 同一套。手錶也會換成水墨。");

    val palette get() = when (this) { CLEAR -> ClearPalette; WARM -> WarmPalette; UKIYOE -> UkiyoePalette; INK -> InkPalette }

    companion object {
        /** 旧的「深色」（dark）已改成浮世繪；light、system 当清晰。 */
        fun from(key: String?) = if (key == "dark") UKIYOE else entries.firstOrNull { it.key == key } ?: CLEAR
    }
}

/**
 * 排布（存在设定 layout_mode；之后还会加别的排布，名字照用途取，不用「新 / 旧」）：
 * - 快錄 quick（预设）：今天｜資料庫｜中间录音键｜飲食｜設定，档案管理在资料库的「管理」。
 * - 總覽 overview：主頁｜錄音｜飲食｜檔案｜設定，主页是问候、快捷方块、今日统计、最新报告。
 */
enum class LayoutMode(val key: String, val label: String, val note: String) {
    QUICK("quick", "快錄", "今天｜資料庫｜中間錄音鍵｜飲食｜設定。任何分頁一按就錄，檔案管理在資料庫的「管理」。"),
    OVERVIEW("overview", "總覽", "主頁｜錄音｜飲食｜檔案｜設定。主頁有問候、快捷方塊、今日統計與最新報告。");

    companion object {
        /** 1.1.87 存的是 new / classic，照样认得。 */
        fun from(key: String?) = when (key) {
            "new" -> QUICK
            "classic" -> OVERVIEW
            else -> entries.firstOrNull { it.key == key } ?: QUICK
        }
    }
}

/**
 * 水墨配色的背景画（原创，tools/ink_bg.py 产生，设计/水墨背景 有原图；res/drawable-nodpi 是 1080×2400 WebP，各约 20 KB）。
 * 画都放在边角与下方，日期、标题、卡片的位置留白。
 */
/**
 * 浮世繪配色的背景画（原创，tools/ukiyoe_bg.py 产生，設計/浮世繪背景 有原图；res/drawable-nodpi 是 1080×2400 WebP）。
 * 最上面一道普鲁士蓝渐层（只在状态列后面）、右上题签与朱红落款，画都在下半部。
 */
enum class UkiyoeBackground(val key: String, val label: String, val res: Int?) {
    GEISHA("geisha", "藝伎武士", com.example.wristai.R.drawable.ukiyoe_bg_geisha),
    CRANE("crane", "靈鶴", com.example.wristai.R.drawable.ukiyoe_bg_crane),
    CASTLE("castle", "大阪城", com.example.wristai.R.drawable.ukiyoe_bg_castle),
    NONE("none", "無", null);

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: GEISHA
    }
}

enum class InkBackground(val key: String, val label: String, val res: Int?) {
    BOAT("boat", "孤舟", com.example.wristai.R.drawable.ink_bg_boat),
    LOTUS("lotus", "荷塘", com.example.wristai.R.drawable.ink_bg_lotus),
    SHRIMP("shrimp", "魚蝦", com.example.wristai.R.drawable.ink_bg_shrimp),
    NONE("none", "無", null);

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: BOAT
    }
}

private fun scheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Brand.Cyan, onPrimary = if (dark) Brand.Bg else Brand.Surface,
        primaryContainer = Brand.SurfaceHigher, onPrimaryContainer = Brand.Text,
        secondary = Brand.Violet, onSecondary = if (dark) Brand.Text else Brand.Surface, tertiary = Brand.Blue,
        background = Brand.Bg, onBackground = Brand.Text,
        surface = Brand.Surface, onSurface = Brand.Text,
        surfaceVariant = Brand.SurfaceHigh, onSurfaceVariant = Brand.TextMuted,
        surfaceContainer = Brand.Surface, surfaceContainerHigh = Brand.SurfaceHigh, surfaceContainerHighest = Brand.SurfaceHigher,
        outline = Brand.Outline, outlineVariant = Brand.Outline,
        error = Brand.Error, onError = if (dark) Brand.Bg else Brand.Surface,
    )
}

/**
 * 品牌主题（不用系统的动态取色，各品牌手机外观一致）。
 * [onDarkChanged] 让 Activity 跟着换状态列图示颜色。
 */
@Composable
fun WristAI_ProjectTheme(mode: ThemeMode = ThemeMode.CLEAR, onDarkChanged: (Boolean) -> Unit = {}, content: @Composable () -> Unit) {
    if (Brand.theme != mode.key) Brand.theme = mode.key
    // 现在所有配色都是浅色；浮世繪有背景画时，最上面是一道深靛蓝（版画的天空），状态列要用浅色图示
    val statusOnDark = mode == ThemeMode.UKIYOE && AppTheme.hasArt
    SideEffect { onDarkChanged(statusOnDark) }
    MaterialTheme(colorScheme = scheme(false),
        typography = if (mode == ThemeMode.INK || mode == ThemeMode.UKIYOE) InkTypography else Typography, content = content)
}
