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
    var layout by androidx.compose.runtime.mutableStateOf(LayoutMode.NEW)
}

/**
 * 配色（存在设定 theme_mode）：清晰（预设）/ 暖和日常 / 深色 / 水墨。
 * 旧值 light、system 当清晰。手表：水墨 → 水墨，其他 → 深色。
 */
enum class ThemeMode(val key: String, val label: String, val note: String) {
    CLEAR("clear", "清晰", "淺灰紙底、白卡片、藍色重點：資訊清楚，適合工作。"),
    WARM("warm", "暖和", "米白底、珊瑚紅與鼠尾草綠：溫和好讀，適合日常與家人。"),
    DARK("dark", "深色", "深空藍底、亮色漸層：省電，適合晚上與會議室。手錶也是深色。"),
    INK("ink", "水墨", "宣紙底、墨黑字、朱紅點綴，跟 logo 同一套。手錶也會換成水墨。");

    val palette get() = when (this) { CLEAR -> ClearPalette; WARM -> WarmPalette; DARK -> DarkPalette; INK -> InkPalette }

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: CLEAR
    }
}

/**
 * 排布（存在设定 layout_mode）：
 * - 新版（预设）：今天｜資料庫｜中间录音键｜飲食｜設定，档案管理在资料库的「管理」。
 * - 旧版：主頁｜錄音｜飲食｜檔案｜設定，主页是问候、快捷方块、今日统计、最新报告。
 */
enum class LayoutMode(val key: String, val label: String, val note: String) {
    NEW("new", "新版", "今天｜資料庫｜中間錄音鍵｜飲食｜設定。一按就錄，檔案管理在資料庫的「管理」。"),
    CLASSIC("classic", "舊版", "主頁｜錄音｜飲食｜檔案｜設定。主頁有問候、快捷方塊、今日統計與最新報告。");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: NEW
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
    val dark = mode == ThemeMode.DARK
    SideEffect { onDarkChanged(dark) }
    MaterialTheme(colorScheme = scheme(dark), typography = if (mode == ThemeMode.INK) InkTypography else Typography, content = content)
}
