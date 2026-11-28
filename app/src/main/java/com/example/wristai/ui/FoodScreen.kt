package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.wristai.FoodLog
import com.example.wristai.Meeting
import com.example.wristai.MeetingStatus
import com.example.wristai.MeetingStore
import com.example.wristai.Settings
import com.example.wristai.ui.theme.Brand
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「飲食」分页：今天｜記錄｜分析｜設定。 */
@Composable
fun FoodHub(onOpen: (String) -> Unit, onOpenSettings: () -> Unit) {
    val all by MeetingStore.meetings.collectAsState()
    val food = remember(all) { all.filter { FoodLog.isFood(it.id) }.sortedByDescending { it.createdAt } }
    HubScaffold(
        title = "飲食記錄".tr,
        subtitle = "說出吃了什麼，AI 幫你估算熱量與營養".tr,
        sections = listOf("今天".tr, "記錄".tr, "分析".tr, "設定".tr),
        selected = Hubs.food,
        onSelect = { Hubs.food = it },
    ) { s ->
        when (s) {
            0 -> FoodToday(all, food, onOpen, onOpenSettings)
            1 -> FoodHistory(food, onOpen)
            2 -> FoodAnalysis(food)
            else -> HubPage {
                FoodGoalsCard()
                FoodCountryCard()
                HealthSection()
                SectionLabel("拍照地點".tr)
                RecordLocationCard()
                EngineSummaryCard(onOpenSettings)
                AllSettingsLink(onOpenSettings)
            }
        }
    }
}

@Composable
private fun FoodToday(all: List<Meeting>, food: List<Meeting>, onOpen: (String) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val goal = Settings.dailyKcalGoal(context)
    val hasCloud = Settings.geminiKey(context).isNotBlank() || com.example.wristai.ai.LocalHost.active(context)
    val start = remember { dayStarts(1).first() }
    val today = food.filter { it.createdAt >= start }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!hasCloud) item {
            GlassCard(highlight = true) {
                Text("需要啟用雲端 AI".tr, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text("估算熱量要用 Gemini（免費）；裝置端的 Galaxy AI 無法計算熱量。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Spacer(Modifier.height(12.dp))
                GradientButton("前往設定".tr, onOpenSettings, Modifier.fillMaxWidth())
            }
        }
        item { PhoneRecordCard(onOpenMeeting = onOpen, foodMode = true) }
        item {
            Text("手錶上也可以按「飲食記錄」直接說".tr, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint,
                modifier = Modifier.fillMaxWidth())
        }
        item { TodayFoodCard(all, goal, alwaysShow = true) {} }
        // 控糖：今天的碳水 / 上限
        val cap = com.example.wristai.FoodGoals.carbCap(context)
        if (cap > 0) item {
            val carbs = today.sumOf { it.nutrition?.carbs ?: 0 }
            Text("今天碳水 {0} / {1} g".trf(carbs, cap), style = MaterialTheme.typography.bodyMedium,
                color = if (carbs > cap) Brand.Warning else Brand.TextMuted)
        }
        // 常吃的：一鍵再記一次（不用再問 AI）
        val favs = com.example.wristai.FoodRepeat.favorites(food)
        if (favs.isNotEmpty()) {
            item { SectionLabel("常吃的（點一下再記一次）".tr) }
            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(favs, key = { "f" + it.first.id }) { (m, n) ->
                        GlassCard(onClick = {
                            com.example.wristai.FoodRepeat.repeat(context, m)
                            android.widget.Toast.makeText(context, "已記錄：{0}（{1} 大卡）".trf(com.example.wristai.FoodRepeat.mealNow().tr + "：" +
                                com.example.wristai.FoodRepeat.dish(m.title), m.nutrition?.kcal ?: 0), android.widget.Toast.LENGTH_SHORT).show()
                        }, contentPadding = 12.dp) {
                            Text("＋ " + com.example.wristai.FoodRepeat.dish(m.title), style = MaterialTheme.typography.titleSmall, color = Brand.Text,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp))
                            Text("{0} 大卡 · 記過 {1} 次".trf(m.nutrition?.kcal ?: 0, n), style = MaterialTheme.typography.labelSmall, color = Brand.TextMuted)
                        }
                    }
                }
            }
        }
        if (food.isEmpty()) item {
            GlassCard {
                Text("還沒有飲食記錄".tr, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Spacer(Modifier.height(4.dp))
                Text("例如：「中午吃了一碗牛肉麵，加一杯大杯半糖紅茶」".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            }
        }
        if (today.isNotEmpty()) item { SectionLabel("今天吃的".tr) }
        items(today, key = { it.id }) { m -> FoodRow(m) { onOpen(m.id) } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 全部记录：每天一组（当天热量与三大营养），可以汇出 CSV。 */
@Composable
private fun FoodHistory(food: List<Meeting>, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val goal = Settings.dailyKcalGoal(context)
    val dayFmt = remember { SimpleDateFormat("M/d EEEE", com.example.voiceai.shared.I18n.locale) }
    val keyFmt = remember { SimpleDateFormat("yyyyMMdd", Locale.US) }
    val days = remember(food) { food.groupBy { keyFmt.format(Date(it.createdAt)) }.toList() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("共 {0} 筆 · {1} 天".trf(food.size, days.size), style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                    modifier = Modifier.weight(1f))
                if (food.isNotEmpty()) GhostButton("匯出 CSV".tr, { shareFoodCsv(context, food) }, icon = Icons.Filled.Share, tint = Brand.Cyan)
            }
        }
        if (food.isEmpty()) item { EmptyNote("還沒有飲食記錄".tr) }
        for ((_, entries) in days) {
            val ns = entries.mapNotNull { it.nutrition.takeIf { _ -> it.status == MeetingStatus.DONE } }
            val total = ns.sumOf { it.kcal }
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(dayFmt.format(Date(entries.first().createdAt)), style = MaterialTheme.typography.titleSmall,
                            color = Brand.Text, modifier = Modifier.weight(1f))
                        Text("{0} 大卡".trf(total), style = MaterialTheme.typography.labelLarge,
                            color = if (total > goal) Brand.Warning else Brand.Cyan)
                    }
                    if (ns.isNotEmpty()) Text(
                        listOf("蛋白質 {0}g".trf(ns.sumOf { it.protein }), "碳水 {0}g".trf(ns.sumOf { it.carbs }),
                            "脂肪 {0}g".trf(ns.sumOf { it.fat })).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                }
            }
            items(entries, key = { it.id }) { m -> FoodRow(m) { onOpen(m.id) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 饮食分析：平均热量、达标天数、每天长条（目标虚线）、营养平均对目标、热量来源比例、餐别。 */
@Composable
private fun FoodAnalysis(food: List<Meeting>) {
    val context = LocalContext.current
    var range by rememberSaveable { mutableStateOf(0) }
    val days = if (range == 0) 7 else 30
    val starts = remember(days) { dayStarts(days) }
    val goal = Settings.dailyKcalGoal(context)
    val pGoal = Settings.proteinGoal(context)
    val fGoal = Settings.fiberGoal(context)
    val done = food.filter { it.status == MeetingStatus.DONE && it.nutrition != null && it.createdAt >= starts.first() }
    HubPage {
        Segmented(listOf("近 7 天".tr, "近 30 天".tr), range) { range = it }
        if (done.isEmpty()) { EmptyNote("這段期間沒有飲食記錄。".tr); return@HubPage }
        // 就醫週報：這段期間整理成 PDF / Word，給醫師、營養師或家人看
        var report by remember { mutableStateOf(false) }
        GhostButton("就醫週報（PDF / Word）".tr, { report = true }, Modifier.fillMaxWidth(), icon = Icons.Filled.Share, tint = Brand.Cyan)
        if (report) {
            val mode = com.example.wristai.FoodGoals.mode(context)
            val g = com.example.wristai.FoodReport.Goals(goal, pGoal, fGoal, com.example.wristai.FoodGoals.carbCap(context),
                com.example.wristai.FoodGoals.label(mode))
            ExportDialog(com.example.wristai.FoodReport.title(days), markdown = { com.example.wristai.FoodReport.markdown(food, days, g) },
                onDismiss = { report = false }, footer = "大D")
        }

        val perDay = starts.map { s -> done.filter { it.createdAt in s until s + 86_400_000L }.mapNotNull { it.nutrition } }
        val logged = perDay.filter { it.isNotEmpty() }
        val kcals = perDay.map { d -> d.sumOf { it.kcal } }
        val avgKcal = logged.map { d -> d.sumOf { it.kcal } }.average().toInt()
        val within = logged.count { d -> d.sumOf { it.kcal } <= goal }
        StatRow(
            "$avgKcal" to "平均大卡 / 天".tr,
            "$within/${logged.size}" to "未超標天數".tr,
            "${logged.size}/$days" to "有記錄的天".tr,
        )

        GlassCard {
            Text("每天熱量（虛線 = 目標 {0}）".trf(goal), style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Spacer(Modifier.height(10.dp))
            BarChart(kcals.map { it.toFloat() }, dayLabels(starts), goal = goal.toFloat())
        }

        // 营养平均（只算有记录的天）对目标
        fun avg(f: (com.example.wristai.Nutrition) -> Int) = logged.map { d -> d.sumOf(f) }.average().toInt()
        val p = avg { it.protein }; val c = avg { it.carbs }; val f = avg { it.fat }; val fib = avg { it.fiber }
        GlassCard {
            Text("每天平均營養".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Spacer(Modifier.height(6.dp))
            ShareBar("蛋白質".tr, "{0}g / 目標 {1}g".trf(p, pGoal), p.toFloat() / pGoal, if (p >= pGoal) Brand.Success else Brand.Cyan)
            ShareBar("纖維".tr, "{0}g / 目標 {1}g".trf(fib, fGoal), fib.toFloat() / fGoal, if (fib >= fGoal) Brand.Success else Brand.Cyan)
            ShareBar("碳水".tr, "{0}g".trf(c), 1f, Brand.TextFaint)
            ShareBar("脂肪".tr, "{0}g".trf(f), 1f, Brand.TextFaint)
        }

        // 热量来自哪里：蛋白质 4、碳水 4、脂肪 9 大卡/克
        val e = (p * 4 + c * 4 + f * 9).coerceAtLeast(1)
        GlassCard {
            Text("熱量來源".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Text("一般建議：蛋白質 10–35%、碳水 45–65%、脂肪 20–35%".tr, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
            Spacer(Modifier.height(6.dp))
            ShareBar("蛋白質".tr, "${p * 4 * 100 / e}%", p * 4f / e, Brand.Cyan)
            ShareBar("碳水".tr, "${c * 4 * 100 / e}%", c * 4f / e, Brand.Violet)
            ShareBar("脂肪".tr, "${f * 9 * 100 / e}%", f * 9f / e, Brand.Warning)
        }

        // 餐别：几次、平均热量
        val meals = done.groupBy { it.nutrition!!.meal.trim().ifEmpty { "其他".tr } }.entries.sortedByDescending { it.value.size }.take(6)
        GlassCard {
            Text("餐別".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Spacer(Modifier.height(6.dp))
            val top = meals.first().value.size.toFloat()
            for ((meal, list) in meals) ShareBar(meal.tr, "{0} 次 · 平均 {1} 大卡".trf(list.size, list.sumOf { it.nutrition!!.kcal } / list.size), list.size / top)
        }
        Text("熱量為 AI 依口述估算，僅供參考，不能取代營養師或醫療建議。".tr, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
    }
}

/** 汇出全部饮食记录（CSV，Excel / 試算表打得开），用系统分享。 */
private fun shareFoodCsv(c: Context, food: List<Meeting>) {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    val rows = buildList {
        add(listOf("時間".tr, "標題".tr, "餐別".tr, "熱量(kcal)".tr, "蛋白質(g)".tr, "碳水(g)".tr, "脂肪(g)".tr, "纖維(g)".tr).joinToString(",") { q(it) })
        for (m in food.sortedBy { it.createdAt }) {
            val n = m.nutrition ?: continue
            add(listOf(fmt.format(Date(m.createdAt)), meetingTitle(m), n.meal).joinToString(",") { q(it) } +
                ",${n.kcal},${n.protein},${n.carbs},${n.fat},${n.fiber}")
        }
    }
    val dir = File(c.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    val out = File(dir, "food-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.csv")
    out.writeText("﻿" + rows.joinToString("\r\n"))   // BOM：Excel 才认得中文
    val uri = FileProvider.getUriForFile(c, c.packageName + ".files", out)
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "飲食記錄".tr).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    c.startActivity(Intent.createChooser(send, "匯出飲食記錄".tr).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
                    Text((listOf("蛋白質 {0}g".trf(n.protein), "碳水 {0}g".trf(n.carbs), "脂肪 {0}g".trf(n.fat)) + (if (n.fiber > 0) listOf("纖維 {0}g".trf(n.fiber)) else emptyList())).joinToString(" · ") { nobreak(it) },
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                } else {
                    Spacer(Modifier.height(6.dp))
                    StatusChip(style)
                }
            }
            m.nutrition?.takeIf { m.status == MeetingStatus.DONE }?.let {
                Spacer(Modifier.width(8.dp))
                Text("${it.kcal}", style = MaterialTheme.typography.titleLarge, color = Brand.Cyan)
                Text(" 大卡".tr, style = MaterialTheme.typography.labelSmall, color = Brand.TextMuted)
            }
        }
        if (style.busy) {
            Spacer(Modifier.height(10.dp))
            ScanningBar()
        }
    }
}

/** 字与字之间加「不换行」记号：换行只会发生在项目之间（例如不会把「脂肪」和「0g」拆开）。 */
private fun nobreak(s: String): String = s.replace(" ", " ").toList().joinToString("⁠")
