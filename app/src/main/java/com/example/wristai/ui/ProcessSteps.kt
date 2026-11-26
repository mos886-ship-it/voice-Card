package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.wristai.Meeting
import com.example.wristai.MeetingStatus
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.delay

/** 每一步在做什么（画面上的说明）。 */
private val STEP_INFO = mapOf(
    "接收錄音" to "錄音已傳到手機".tr,
    "存入錄音機" to "另存一份到三星錄音機，之後也能用 Galaxy AI".tr,
    "上傳音檔" to "壓縮錄音後上傳到 AI 服務".tr,
    "AI 聆聽整理" to "AI 聆聽錄音、分辨說話者，寫出逐字稿與報告".tr,
    "分段分析" to "長錄音分段聆聽（每段約 30 分鐘），一段一段整理".tr,
    "整合報告" to "把各段的重點整合成一份完整報告".tr,
    "語音辨識" to "把錄音轉成逐字稿，並分辨不同說話者".tr,
    "報告整理" to "讀逐字稿，整理摘要、說話者、重點與待辦".tr,
    "估算熱量" to "辨認吃了什麼與份量，估算熱量與營養素".tr,
)

/** 「1 分 12 秒」「45 秒」「1 小時 3 分」 */
fun durationWords(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s >= 3600 -> "{0} 小時 {1} 分".trf(s / 3600, s / 60 % 60)
        s >= 60 -> "{0} 分 {1} 秒".trf(s / 60, s % 60)
        else -> "{0} 秒".trf(s)
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * AI 处理的步骤清单：完成打勾、进行中闪烁＋这步已经多久、失败打叉、还没到的空心圈。
 * 让使用者看得出做到哪、是不是还在动。
 */
@Composable
fun ProcessSteps(m: Meeting, onCancel: (() -> Unit)? = null) {
    val failed = m.status == MeetingStatus.FAILED
    val running = !failed && m.step in m.steps.indices
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); delay(1000) } }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when { failed -> "處理過程".tr; running -> "AI 處理中".tr; else -> "處理完成".tr },
                style = MaterialTheme.typography.titleMedium, color = Brand.Text, modifier = Modifier.weight(1f),
            )
            val total = when {
                running && m.processStartedAt > 0 -> "已經過 ".tr + clock(now - m.processStartedAt)
                !running && m.processEndedAt > m.processStartedAt && m.processStartedAt > 0 -> "共 ".tr + durationWords(m.processEndedAt - m.processStartedAt)
                else -> null
            }
            total?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Brand.TextMuted) }
        }
        Spacer(Modifier.height(12.dp))
        m.steps.forEachIndexed { i, name ->
            val state = when {
                i < m.step -> 0                       // 完成
                i == m.step && failed && m.error == com.example.wristai.ProcessMeetingWorker.CANCELED -> 4   // 中断
                i == m.step && failed -> 3            // 失败
                i == m.step && running -> 1           // 进行中
                else -> 2                             // 还没到
            }
            Row {
                // 左边：图示＋接到下一步的线
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StepIcon(state)
                    if (i < m.steps.lastIndex) Box(
                        Modifier.width(2.dp).height(if (state == 1 || state == 3) 44.dp else 22.dp)
                            .background(if (state == 0) Brand.Success.copy(alpha = 0.5f) else Brand.Outline),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(top = 1.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name.tr, style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (state == 1 || state == 3) FontWeight.Bold else FontWeight.Normal,
                            color = when (state) { 1 -> Brand.Cyan; 2 -> Brand.TextFaint; 3 -> Brand.Error; 4 -> Brand.Warning; else -> Brand.Text },
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (state == 1 && m.stepStartedAt > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(clock(now - m.stepStartedAt), style = MaterialTheme.typography.labelSmall, color = Brand.TextMuted)
                        }
                    }
                    if (state == 1 || state == 3) {
                        m.stepDetail?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Brand.TextMuted) }
                        STEP_INFO[name]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint) }
                    }
                }
            }
        }
        if (running) {
            Spacer(Modifier.height(10.dp))
            Text("可以先離開這個畫面，完成後會通知你，並把摘要推送到手錶。".tr,
                style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
            if (onCancel != null) {
                Spacer(Modifier.height(12.dp))
                GhostButton("中斷處理".tr, onCancel, Modifier.fillMaxWidth(), tint = Brand.Error)
            }
        }
    }
}

@Composable
private fun StepIcon(state: Int) {
    val size = 22.dp
    when (state) {
        0 -> Box(Modifier.size(size).clip(CircleShape).background(Brand.Success), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = Brand.Bg)
        }
        1 -> Box(Modifier.size(size).clip(CircleShape).border(2.dp, Brand.Cyan, CircleShape), contentAlignment = Alignment.Center) {
            PulseDot(Brand.Cyan, 10.dp)
        }
        3 -> Box(Modifier.size(size).clip(CircleShape).background(Brand.Error), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, null, Modifier.size(14.dp), tint = Brand.Bg)
        }
        4 -> Box(Modifier.size(size).clip(CircleShape).border(2.dp, Brand.Warning, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).background(Brand.Warning))   // 停止方块
        }
        else -> Box(Modifier.size(size).clip(CircleShape).border(2.dp, Brand.Outline, CircleShape))
    }
}
