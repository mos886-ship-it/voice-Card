package com.example.wristai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 层次分明的字级：大标题紧凑、内文行高宽松（中文阅读）、标签字距略开（科技感）。 */
val Typography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),   // 新设计：不用等宽字、不加字距
)

/** 水墨配色：标题改用衬线（宋体 / 明体），跟书法 logo 比较搭；内文维持无衬线好读。 */
val InkTypography = Typography.copy(
    headlineMedium = Typography.headlineMedium.copy(fontFamily = FontFamily.Serif),
    headlineSmall = Typography.headlineSmall.copy(fontFamily = FontFamily.Serif),
    titleLarge = Typography.titleLarge.copy(fontFamily = FontFamily.Serif),
    titleMedium = Typography.titleMedium.copy(fontFamily = FontFamily.Serif),
    titleSmall = Typography.titleSmall.copy(fontFamily = FontFamily.Serif),
)
