package com.example.wristai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wristai.FoodLog
import com.example.wristai.Meeting
import com.example.wristai.MeetingStatus
import com.example.wristai.MeetingStore
import com.example.wristai.Settings
import com.example.wristai.ui.theme.Brand
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「飲食」分页：记录这一餐 + 今日热量 + 每天的饮食历史。 */
@Composable
fun FoodScreen(onOpen: (String) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val all by MeetingStore.meetings.collectAsState()
    val food = remember(all) { all.filter { FoodLog.isFood(it.id) }.sortedByDescending { it.createdAt } }
    val goal = Settings.dailyKcalGoal(context)
    val hasCloud = Settings.geminiKey(context).isNotBlank()
    val dayFmt = remember { SimpleDateFormat("M/d EEEE", Locale.TAIWAN) }
    val keyFmt = remember { SimpleDateFormat("yyyyMMdd", Locale.US) }
    val days = remember(food) { food.groupBy { keyFmt.format(Date(it.createdAt)) }.toList() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text("飲食", style = MaterialTheme.typography.headlineSmall, color = Brand.Text)
                Text("說出吃了什麼，AI 幫你估算熱量與營養", style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            }
        }

        if (!hasCloud) {
            item {
                GlassCard(highlight = true) {
                    Text("需要啟用雲端 AI", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                    Text("估算熱量要用 Gemini（免費）；裝置端的 Galaxy AI 無法計算熱量。",
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                    Spacer(Modifier.height(12.dp))
                    GradientButton("前往設定", onOpenSettings, Modifier.fillMaxWidth())
                }
            }
        }

        item { PhoneRecordCard(onOpenMeeting = onOpen, foodMode = true) }
        item {
            Text("手錶上也可以按「飲食記錄」直接說", style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint,
                modifier = Modifier.fillMaxWidth().padding(top = 0.dp))
        }

        item { TodayFoodCard(all, goal, alwaysShow = true) {} }

        if (food.isEmpty()) {
            item {
                GlassCard {
                    Text("還沒有飲食記錄", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                    Spacer(Modifier.height(4.dp))
                    Text("例如：「中午吃了一碗牛肉麵，加一杯大杯半糖紅茶」",
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                }
            }
        }

        for ((_, entries) in days) {
            val total = entries.sumOf { it.nutrition?.kcal ?: 0 }
            item {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dayFmt.format(Date(entries.first().createdAt)), style = MaterialTheme.typography.titleSmall,
                        color = Brand.Text, modifier = Modifier.weight(1f))
                    Text("$total 大卡", style = MaterialTheme.typography.labelLarge,
                        color = if (total > goal) Brand.Warning else Brand.Cyan)
                }
            }
            items(entries, key = { it.id }) { m -> FoodRow(m) { onOpen(m.id) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FoodRow(m: Meeting, onClick: () -> Unit) {
    val style = m.statusStyle()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.TAIWAN) }
    GlassCard(onClick = onClick, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(timeFmt.format(Date(m.createdAt)), style = MaterialTheme.typography.labelMedium, color = Brand.TextFaint,
                modifier = Modifier.width(48.dp))
            Column(Modifier.weight(1f)) {
                Text(meetingTitle(m), style = MaterialTheme.typography.titleMedium, color = Brand.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val n = m.nutrition
                if (n != null && m.status == MeetingStatus.DONE) {
                    Text(listOf("蛋白質 ${n.protein}g", "碳水 ${n.carbs}g", "脂肪 ${n.fat}g").joinToString(" · ") { nobreak(it) },
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                } else {
                    Spacer(Modifier.height(6.dp))
                    StatusChip(style)
                }
            }
            m.nutrition?.takeIf { m.status == MeetingStatus.DONE }?.let {
                Spacer(Modifier.width(8.dp))
                Text("${it.kcal}", style = MaterialTheme.typography.titleLarge, color = Brand.Cyan)
                Text(" 大卡", style = MaterialTheme.typography.labelSmall, color = Brand.TextMuted)
            }
        }
        if (style.busy) {
            Spacer(Modifier.height(10.dp))
            ScanningBar()
        }
    }
}

/** 字与字之间加「不换行」记号：换行只会发生在项目之间（例如不会把「脂肪」和「0g」拆开）。 */
private fun nobreak(s: String): String = s.replace(" ", "\u00A0").toList().joinToString("\u2060")
