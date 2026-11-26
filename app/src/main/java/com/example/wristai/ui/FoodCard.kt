package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wristai.FoodLog
import com.example.wristai.Meeting
import com.example.wristai.MeetingStatus
import com.example.wristai.ui.theme.Brand
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 今天的饮食记录（已估算完成的）。 */
fun todayFood(meetings: List<Meeting>): List<Meeting> {
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return meetings.filter { FoodLog.isFood(it.id) && it.createdAt >= start && it.status == MeetingStatus.DONE && it.nutrition != null }
        .sortedBy { it.createdAt }
}

/**
 * 首页「今日飲食」卡：热量 / 目标的圆环 + 三大营养素 + 今天每一餐。
 * 有任何饮食记录才显示（没用这个功能的人不会看到）。
 */
@Composable
fun TodayFoodCard(meetings: List<Meeting>, goalKcal: Int, alwaysShow: Boolean = false, onClick: () -> Unit) {
    if (!alwaysShow && meetings.none { FoodLog.isFood(it.id) }) return
    val today = todayFood(meetings)
    val kcal = today.sumOf { it.nutrition!!.kcal }
    val protein = today.sumOf { it.nutrition!!.protein }
    val carbs = today.sumOf { it.nutrition!!.carbs }
    val fat = today.sumOf { it.nutrition!!.fat }
    val ratio = if (goalKcal > 0) kcal.toFloat() / goalKcal else 0f
    val over = ratio > 1f

    GlassCard(onClick = if (alwaysShow) null else onClick) {
        Text("今日飲食".tr, style = MaterialTheme.typography.labelSmall, color = Brand.TextMuted)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                RingProgress(ratio.coerceIn(0f, 1f), Modifier.size(92.dp), stroke = 8.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${kcal}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (over) Brand.Warning else Brand.Text)
                    Text("/ {0} 大卡".trf(goalKcal), style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Macro("蛋白質".tr, protein, Brand.Cyan)
                Macro("碳水".tr, carbs, Brand.Blue)
                Macro("脂肪".tr, fat, Brand.Violet)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                today.isEmpty() -> "今天還沒有記錄 · 吃完說一下吃了什麼就好".tr
                over -> "已超過今日目標 {0} 大卡".trf(kcal - goalKcal)
                else -> "還可以吃約 {0} 大卡".trf(goalKcal - kcal)
            },
            style = MaterialTheme.typography.bodySmall, color = if (over) Brand.Warning else Brand.TextMuted,
        )
        if (today.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Divider()
            val fmt = SimpleDateFormat("HH:mm", Locale.TAIWAN)
            for (m in today) {
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt.format(Date(m.createdAt)), style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint,
                        modifier = Modifier.width(48.dp))
                    Text(meetingTitle(m), style = MaterialTheme.typography.bodyMedium, color = Brand.Text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("{0} 大卡".trf(m.nutrition!!.kcal), style = MaterialTheme.typography.labelMedium, color = Brand.Cyan)
                }
            }
        }
    }
}

@Composable
private fun Macro(label: String, grams: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted, modifier = Modifier.weight(1f))
        Text("${grams} g", style = MaterialTheme.typography.labelLarge, color = Brand.Text)
    }
}

/** 饮食记录详情页上方：这一餐的热量与营养素。 */
@Composable
fun NutritionHeader(m: Meeting) {
    val n = m.nutrition ?: return
    GlassCard(highlight = true) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${n.kcal}", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Brand.Text)
            Spacer(Modifier.width(6.dp))
            Text("大卡{0}".trf(if (n.meal.isNotBlank()) " · ${n.meal}" else ""), style = MaterialTheme.typography.titleMedium,
                color = Brand.TextMuted, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${n.protein}g", "蛋白質".tr, Modifier.weight(1f))
            StatTile("${n.carbs}g", "碳水".tr, Modifier.weight(1f))
            StatTile("${n.fat}g", "脂肪".tr, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text("AI 依口述估算，僅供參考".tr, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
    }
}
