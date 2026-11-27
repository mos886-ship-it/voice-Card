package com.example.wristai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import com.example.wristai.ui.theme.Brand
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「今天」时间轴上的非录音项目：即时翻译、远距对话（照时间跟录音、饮食排在一起）。 */
data class TimelineExtra(val at: Long, val tag: String, val title: String, val sub: String?, val onClick: () -> Unit)

@Composable
fun TimelineExtraRow(e: TimelineExtra) {
    Row(Modifier.fillMaxWidth().clickable(onClick = e.onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date(e.at)), style = MaterialTheme.typography.labelMedium,
            color = Brand.TextMuted, modifier = Modifier.width(44.dp).padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.tag, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Brand.Cyan,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Brand.Cyan.copy(alpha = 0.12f))
                        .padding(horizontal = 5.dp, vertical = 1.dp))
                Spacer(Modifier.width(6.dp))
                Text(e.title, style = MaterialTheme.typography.titleSmall, color = Brand.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!e.sub.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(e.sub, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 今日回顧（晚上 9 点后）：几场录音、热量与蛋白质达标、还没做的待办。 */
data class DayRecap(
    val recordings: Int, val translations: Int, val kcal: Int, val kcalGoal: Int, val protein: Int, val proteinGoal: Int,
    val usesFood: Boolean, val openTodos: List<String>,
)

@Composable
fun DayRecapCard(r: DayRecap, onOpenRecords: () -> Unit, onOpenFood: () -> Unit) {
    GlassCard(highlight = true) {
        Text("今日回顧".tr, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RecapStat("{0} 場".trf(r.recordings), "錄音整理".tr, Modifier.weight(1f).clickable(onClick = onOpenRecords))
            if (r.usesFood) {
                RecapStat(pct(r.kcal, r.kcalGoal), "熱量 {0} / {1}".trf(r.kcal, r.kcalGoal), Modifier.weight(1f).clickable(onClick = onOpenFood))
                RecapStat(pct(r.protein, r.proteinGoal), "蛋白質 {0} / {1} g".trf(r.protein, r.proteinGoal), Modifier.weight(1f).clickable(onClick = onOpenFood))
            } else RecapStat("{0} 次".trf(r.translations), "翻譯".tr, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        if (r.openTodos.isEmpty()) {
            Text("今天的待辦都處理完了，早點休息。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        } else {
            Text("明天記得：".tr, style = MaterialTheme.typography.labelLarge, color = Brand.TextMuted)
            for (t in r.openTodos.take(3)) Text("・$t", style = MaterialTheme.typography.bodySmall, color = Brand.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.openTodos.size > 3) Text("還有 {0} 項".trf(r.openTodos.size - 3), style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
        }
    }
}

private fun pct(v: Int, goal: Int) = if (goal <= 0) "—" else "${(v * 100 / goal)}%"

@Composable
private fun RecapStat(big: String, small: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Brand.SurfaceHigh).padding(10.dp)) {
        Text(big, style = MaterialTheme.typography.titleLarge, color = Brand.Text, fontWeight = FontWeight.SemiBold)
        Text(small, style = MaterialTheme.typography.labelSmall, color = Brand.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
