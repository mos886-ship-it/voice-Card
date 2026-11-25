package com.example.wristai.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** 目前选的主题（Compose 状态：设定页一改，整个 App 马上换）。 */
object AppTheme {
    var mode by androidx.compose.runtime.mutableStateOf(ThemeMode.CLEAR)
}

/**
 * 外观主题（存在设定 theme_mode，跟 iOS ThemeMode 一样）：清晰（预设）/ 暖和日常。
 * 2026-11-25 重新设计：旧的深色 / 浅色 / 水墨 / 自动拿掉，旧设定值一律当「清晰」。手表维持深色。
 */
enum class ThemeMode(val key: String, val label: String, val note: String) {
    CLEAR("clear", "清晰", "淺灰紙底、白卡片、藍色重點：資訊清楚，適合工作。手錶維持深色。"),
    WARM("warm", "暖和日常", "米白底、珊瑚紅與鼠尾草綠：溫和好讀，適合日常與家人。手錶維持深色。");

    val palette get() = if (this == WARM) WarmPalette else ClearPalette

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: CLEAR
    }
}

private fun scheme(): ColorScheme = lightColorScheme().copy(
    primary = Brand.Cyan, onPrimary = Brand.Surface,
    primaryContainer = Brand.SurfaceHigher, onPrimaryContainer = Brand.Text,
    secondary = Brand.Violet, onSecondary = Brand.Surface, tertiary = Brand.Blue,
    background = Brand.Bg, onBackground = Brand.Text,
    surface = Brand.Surface, onSurface = Brand.Text,
    surfaceVariant = Brand.SurfaceHigh, onSurfaceVariant = Brand.TextMuted,
    surfaceContainer = Brand.Surface, surfaceContainerHigh = Brand.SurfaceHigh, surfaceContainerHighest = Brand.SurfaceHigher,
    outline = Brand.Outline, outlineVariant = Brand.Outline,
    error = Brand.Error, onError = Brand.Surface,
)

/**
 * 品牌主题（不用系统的动态取色，各品牌手机外观一致）。两个主题都是浅色；
 * [onDarkChanged] 让 Activity 跟着换状态列图示颜色。
 */
@Composable
fun WristAI_ProjectTheme(mode: ThemeMode = ThemeMode.CLEAR, onDarkChanged: (Boolean) -> Unit = {}, content: @Composable () -> Unit) {
    if (Brand.theme != mode.key) Brand.theme = mode.key
    SideEffect { onDarkChanged(false) }
    MaterialTheme(colorScheme = scheme(), typography = Typography, content = content)
}
