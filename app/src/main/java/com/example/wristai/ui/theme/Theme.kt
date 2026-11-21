package com.example.wristai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** 目前选的主题（Compose 状态：设定页一改，整个 App 马上换）。 */
object AppTheme {
    var mode by androidx.compose.runtime.mutableStateOf(ThemeMode.DARK)
}

/** 主题选项（存在设定里）。 */
enum class ThemeMode(val key: String, val label: String) {
    DARK("dark", "深色"),
    LIGHT("light", "淺色"),
    INK("ink", "水墨"),
    SYSTEM("system", "自動");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: DARK
    }
}

private fun scheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Brand.Cyan, onPrimary = Brand.Bg,
        primaryContainer = Brand.SurfaceHigher, onPrimaryContainer = Brand.Text,
        secondary = Brand.Violet, onSecondary = Brand.Text, tertiary = Brand.Blue,
        background = Brand.Bg, onBackground = Brand.Text,
        surface = Brand.Surface, onSurface = Brand.Text,
        surfaceVariant = Brand.SurfaceHigh, onSurfaceVariant = Brand.TextMuted,
        surfaceContainer = Brand.Surface, surfaceContainerHigh = Brand.SurfaceHigh, surfaceContainerHighest = Brand.SurfaceHigher,
        outline = Brand.Outline, outlineVariant = Brand.Outline,
        error = Brand.Error, onError = Brand.Bg,
    )
}

/**
 * 品牌主题（不用系统的动态取色，各品牌手机外观一致）。
 * [mode] 决定深 / 浅色；[onDarkChanged] 让 Activity 跟着换状态列图示颜色。
 */
@Composable
fun WristAI_ProjectTheme(mode: ThemeMode = ThemeMode.DARK, onDarkChanged: (Boolean) -> Unit = {}, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT, ThemeMode.INK -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    if (Brand.isDark != dark) Brand.isDark = dark
    val ink = mode == ThemeMode.INK
    if (Brand.isInk != ink) Brand.isInk = ink
    SideEffect { onDarkChanged(dark) }
    MaterialTheme(colorScheme = scheme(dark), typography = if (ink) InkTypography else Typography, content = content)
}
