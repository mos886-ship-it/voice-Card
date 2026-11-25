package com.example.wristai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.wristai.HealthSync
import com.example.wristai.Settings
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设定 →「健康同步」：饮食写进健康。
 * Android 写 Health Connect，一次涵盖 Google 与 Samsung Health（Samsung Health 跟 Health Connect 同步）；
 * Apple 健康只在 iPhone 版。
 */
@Composable
fun HealthSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(Settings.healthSync(context)) }
    var status by remember { mutableStateOf(HealthSync.status(context)) }
    var granted by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(Settings.healthSyncLast(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val hasSamsung = remember { HealthSync.hasSamsungHealth(context) }

    fun syncAll() {
        busy = true
        scope.launch {
            val n = runCatching { HealthSync.syncAll(context) }
            busy = false
            last = Settings.healthSyncLast(context)
            message = n.fold({ if (it == 0) "沒有要同步的飲食記錄" else "已同步 $it 筆飲食記錄" }, { "同步失敗：${it.message ?: "未知錯誤"}" })
        }
    }

    val permLauncher = rememberLauncherForActivityResult(HealthSync.permissionContract()) { result ->
        granted = result.containsAll(HealthSync.PERMISSIONS)
        if (granted) {
            on = true; Settings.setHealthSync(context, true); syncAll()
        } else message = "沒有取得寫入權限，同步沒有打開"
    }

    // 从系统设定 / Health Connect 回来时要更新
    LaunchedEffect(Unit) {
        while (true) {
            status = HealthSync.status(context)
            granted = status == "available" && HealthSync.hasPermission(context)
            kotlinx.coroutines.delay(1_500)
        }
    }

    SectionLabel("健康同步")
    GlassCard(highlight = on && granted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("飲食同步到健康", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text("每筆飲食記錄做完，自動寫入熱量與蛋白質、碳水、脂肪；刪除記錄也會一起刪除",
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = on && granted,
                enabled = status == "available" && !busy,
                onCheckedChange = { want ->
                    message = null
                    if (!want) { on = false; Settings.setHealthSync(context, false) }
                    else if (granted) { on = true; Settings.setHealthSync(context, true); syncAll() }
                    else permLauncher.launch(HealthSync.PERMISSIONS)
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Brand.Cyan, checkedThumbColor = Brand.Bg,
                    uncheckedTrackColor = Brand.SurfaceHigher, uncheckedBorderColor = Brand.Outline,
                ),
            )
        }

        HealthDivider()

        // ---------- 三个去处 ----------
        Target(
            name = "Google · Health Connect",
            state = when {
                status == "none" -> "這支手機不支援"
                status == "update" -> "需要安裝或更新 Health Connect"
                granted -> "已授權寫入飲食"
                else -> "尚未授權"
            },
            ok = status == "available" && granted,
            action = when (status) {
                "update" -> "安裝"
                "available" -> "開啟"
                else -> null
            },
            onAction = { HealthSync.openHealthConnect(context) },
        )
        Target(
            name = "Samsung Health",
            state = if (hasSamsung) "經 Health Connect 同步：在 Samsung Health → 設定 → Health Connect 開啟「飲食」"
                else "這支手機沒有安裝 Samsung Health",
            ok = null,
            action = if (hasSamsung) "開啟" else null,
            onAction = { HealthSync.openSamsungHealth(context) },
        )
        Target(name = "Apple 健康", state = "只在 iPhone 版（寫入「健康」App）", ok = null, action = null, onAction = {})

        if (on && granted) {
            HealthDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (last.first > 0) "上次同步 " + SimpleDateFormat("M/d HH:mm", Locale.TAIWAN).format(Date(last.first)) + " · ${last.second} 筆"
                    else "還沒同步過",
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { message = null; syncAll() }, enabled = !busy) {
                    Text(if (busy) "同步中…" else "同步過去的記錄", color = Brand.Cyan)
                }
            }
        }
        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        }
        Spacer(Modifier.height(8.dp))
        Text("只寫入飲食，不讀取任何健康資料。熱量為 AI 估算，僅供參考。",
            style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
    }
}

@Composable
private fun Target(name: String, state: String, ok: Boolean?, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(
            when (ok) { true -> Brand.Success; false -> Brand.Warning; null -> Brand.Outline }, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Text(state, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action, color = Brand.Cyan) }
    }
}

@Composable
private fun HealthDivider() {
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.Outline))
    Spacer(Modifier.height(8.dp))
}
