package com.example.wristai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.wristai.FoodLog
import com.example.wristai.MeetingStore
import com.example.wristai.backup.DriveBackup
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「檔案管理」页（设定里的子页面，不跟设定挤在同一页）：
 * 手机上的录音占多少空间，以及 Google 云端硬碟备份 / 取回（[BackupSection]）。
 */
@Composable
fun FilesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val meetings by MeetingStore.meetings.collectAsState()
    var usage by remember { mutableStateOf<Usage?>(null) }
    LaunchedEffect(meetings.size) { usage = withContext(Dispatchers.IO) { usage(MeetingStore.rootDir(context)) } }

    Column(Modifier.fillMaxSize()) {
        TopBar("檔案管理", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("手機上的檔案")
            GlassCard {
                val food = meetings.count { FoodLog.isFood(it.id) }
                UsageRow("錄音", "${meetings.size - food} 筆")
                UsageRow("飲食記錄", "$food 筆")
                UsageRow("錄音檔", usage?.let { bytes(it.audio) } ?: "計算中…")
                UsageRow("報告、逐字稿等", usage?.let { bytes(it.other) } ?: "計算中…")
                Spacer(Modifier.height(6.dp))
                Text("錄音存在這支手機的 App 資料裡；刪掉 App 或換手機前，先備份到 Google 雲端硬碟。",
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
            }

            BackupSection()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 设定页里的入口：「檔案管理 ›」，显示备份状态。 */
@Composable
fun FilesEntry(onOpen: () -> Unit) {
    val context = LocalContext.current
    val status by DriveBackup.status.collectAsState()
    SectionLabel("檔案管理")
    GlassCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("檔案管理與雲端備份", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text(
                    when {
                        status.running -> status.text ?: "備份中…"
                        DriveBackup.linked(context) -> "已連結 Google 雲端硬碟" + (DriveBackup.email(context)?.let { "（$it）" } ?: "")
                        else -> "手機上的空間、備份到 Google 雲端硬碟、從雲端取回"
                    },
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                )
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = Brand.TextFaint)
        }
    }
}

private data class Usage(val audio: Long, val other: Long)

private fun usage(root: File): Usage {
    var audio = 0L
    var other = 0L
    root.walkTopDown().filter { it.isFile }.forEach { f ->
        if (f.name == "audio.wav" || f.extension == "m4a") audio += f.length() else other += f.length()
    }
    return Usage(audio, other)
}

private fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / (1024.0 * 1024 * 1024))
    n >= 1L shl 20 -> "%.0f MB".format(n / (1024.0 * 1024))
    else -> "${(n + 1023) / 1024} KB"
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Brand.TextMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Brand.Text)
    }
}
