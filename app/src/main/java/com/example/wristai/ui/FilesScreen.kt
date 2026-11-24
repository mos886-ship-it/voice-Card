package com.example.wristai.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.wristai.FoodLog
import com.example.wristai.Meeting
import com.example.wristai.MeetingStatus
import com.example.wristai.MeetingStore
import com.example.wristai.VoiceRecorderExporter
import com.example.wristai.backup.DriveBackup
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「檔案」分页（底部导览列）：画面保持简单——一行空间摘要、类别筛选、所有录音档的清单。
 * 每笔显示大小、有没有备份到 Google 云端硬碟（连结与备份设定在「設定」），
 * 点了可以打开、分享录音档、只删录音档（保留报告）、整笔删除。
 */
@Composable
fun FilesScreen(onBack: (() -> Unit)? = null, onOpen: (String) -> Unit = {}) {
    if (onBack != null) BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val meetings by MeetingStore.meetings.collectAsState()
    val backupStatus by DriveBackup.status.collectAsState()
    var sizes by remember { mutableStateOf<Map<String, FileSize>>(emptyMap()) }
    var cloud by remember { mutableStateOf<Map<String, String>?>(null) }      // 录音编号 → 云端指纹；null = 没连结 / 还没读
    var local by remember { mutableStateOf<Map<String, String>>(emptyMap()) } // 录音编号 → 本机指纹
    var kind by remember { mutableIntStateOf(0) }   // 0 全部 / 1 錄音 / 2 飲食
    var acting by remember { mutableStateOf<Meeting?>(null) }
    var confirm by remember { mutableStateOf<Pair<Meeting, Boolean>?>(null) }   // (哪一笔, true = 只删录音档)
    var note by remember { mutableStateOf<String?>(null) }
    var changed by remember { mutableIntStateOf(0) }   // 删了录音档等：清单没变但大小变了，要重算

    // 每笔的大小（录音档、其他）；录音有增减或改动就重算
    LaunchedEffect(meetings, changed) {
        sizes = withContext(Dispatchers.IO) {
            meetings.associate { it.id to sizeOf(File(MeetingStore.rootDir(context), it.id)) }
        }
    }
    // 备份状态：有连结才去云端硬碟读一次清单（备份做完再读一次）
    LaunchedEffect(meetings, changed, backupStatus.running) {
        if (!DriveBackup.linked(context) || backupStatus.running) return@LaunchedEffect
        cloud = withContext(Dispatchers.IO) {
            runCatching { DriveBackup.remoteVersions(context, DriveBackup.token(context)) }.getOrNull()
        }
        local = withContext(Dispatchers.IO) { meetings.associate { it.id to DriveBackup.localVersion(context, it.id) } }
    }

    val shown = remember(meetings, kind) {
        meetings.filter {
            when (kind) { 1 -> !FoodLog.isFood(it.id); 2 -> FoodLog.isFood(it.id); else -> true }
        }.sortedByDescending { it.createdAt }
    }
    val totalAudio = sizes.values.sumOf { it.audio }
    val totalOther = sizes.values.sumOf { it.other }

    Column(Modifier.fillMaxSize()) {
        if (onBack != null) TopBar("檔案管理", onBack)
        else Text("檔案管理", style = MaterialTheme.typography.headlineSmall, color = Brand.Text,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                // 一行摘要＋类别筛选；Google 云端硬碟的连结、备份选项都在「設定」
                Text("${meetings.size} 筆 · 錄音檔 ${bytes(totalAudio)} · 報告等 ${bytes(totalOther)}",
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Spacer(Modifier.height(8.dp))
                Chips(listOf("全部", "錄音", "飲食"), kind) { kind = it }
                note?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Success)
                }
            }
            if (shown.isEmpty()) item {
                Text("沒有檔案", style = MaterialTheme.typography.bodyMedium, color = Brand.TextMuted)
            }
            items(shown, key = { it.id }) { m ->
                FileRow(m, sizes[m.id], backupState(m, cloud, local)) { acting = m }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // ---------- 一笔的操作 ----------
    acting?.let { m ->
        val hasAudio = (sizes[m.id]?.audio ?: 0) > 0
        AlertDialog(
            onDismissRequest = { acting = null },
            title = { Text(meetingTitle(m), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    sizes[m.id]?.let {
                        Text("錄音檔 ${bytes(it.audio)} · 報告等 ${bytes(it.other)}",
                            style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                    }
                    Spacer(Modifier.height(8.dp))
                    ActionLine("打開") { acting = null; onOpen(m.id) }
                    if (hasAudio) ActionLine("分享錄音檔（m4a）") {
                        acting = null
                        scope.launch {
                            note = "準備錄音檔…"
                            val r = withContext(Dispatchers.IO) { runCatching { shareAudio(context, m) } }
                            note = r.exceptionOrNull()?.let { "分享失敗：${it.message}" }
                        }
                    }
                    if (hasAudio) ActionLine("只刪錄音檔（保留報告）", Brand.Warning) { acting = null; confirm = m to true }
                    ActionLine("刪除整筆", Brand.Error) { acting = null; confirm = m to false }
                }
            },
            confirmButton = { TextButton(onClick = { acting = null }) { Text("關閉") } },
        )
    }
    confirm?.let { (m, audioOnly) ->
        val backedUp = backupState(m, cloud, local) == BackupState.DONE
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (audioOnly) "只刪錄音檔？" else "刪除整筆？") },
            text = {
                Text(
                    (if (audioOnly) "報告和逐字稿會留著，但之後不能再播放、也不能重新產生報告。"
                    else "錄音、報告、逐字稿都會從手機刪除。") +
                        if (backedUp) "\nGoogle 雲端硬碟的備份會留著，可以再取回。" else "\n這筆還沒備份，刪除後無法復原。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (audioOnly) deleteAudio(context, m.id) else MeetingStore.delete(context, m.id)
                        }
                        changed++
                        note = if (audioOnly) "已刪除「${meetingTitle(m)}」的錄音檔" else "已刪除「${meetingTitle(m)}」"
                    }
                }) { Text("刪除", color = Brand.Error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("返回") } },
        )
    }
}

// ---------------- 一列 ----------------

enum class BackupState { NONE, DONE, CHANGED, NO_LINK }

private fun backupState(m: Meeting, cloud: Map<String, String>?, local: Map<String, String>): BackupState = when {
    cloud == null -> BackupState.NO_LINK
    cloud[m.id] == null -> BackupState.NONE
    cloud[m.id] == local[m.id] -> BackupState.DONE
    else -> BackupState.CHANGED
}

@Composable
private fun FileRow(m: Meeting, size: FileSize?, state: BackupState, onClick: () -> Unit) {
    val fmt = remember { SimpleDateFormat("yy/M/d HH:mm", Locale.TAIWAN) }
    GlassCard(onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(meetingTitle(m), style = MaterialTheme.typography.titleSmall, color = Brand.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        fmt.format(Date(m.createdAt)),
                        if (FoodLog.isFood(m.id)) "飲食" else null,
                        m.durationSec.takeIf { it > 0 }?.let { humanDuration(it) },
                        when {
                            size == null -> null
                            size.audio == 0L -> "沒有錄音檔 · ${bytes(size.other)}"
                            else -> bytes(size.total)
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            val (label, color) = when {
                m.status != MeetingStatus.DONE && m.status != MeetingStatus.EXPORTED && m.status != MeetingStatus.FAILED ->
                    "處理中" to Brand.TextFaint
                state == BackupState.DONE -> "已備份" to Brand.Success
                state == BackupState.CHANGED -> "有更新" to Brand.Warning
                state == BackupState.NONE -> "未備份" to Brand.TextFaint
                else -> "" to Brand.TextFaint
            }
            if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable
private fun ActionLine(text: String, color: Color = Brand.Cyan, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = color, modifier = Modifier.fillMaxWidth())
    }
}

// ---------------- 档案动作 ----------------

data class FileSize(val audio: Long, val other: Long) { val total get() = audio + other }

private fun sizeOf(dir: File): FileSize {
    var audio = 0L
    var other = 0L
    dir.walkTopDown().filter { it.isFile }.forEach { f ->
        if (f.name == "audio.wav" || f.extension == "m4a") audio += f.length() else other += f.length()
    }
    return FileSize(audio, other)
}

/** 只删录音档（含手表传来的分段），报告、逐字稿、地点都留着。 */
private fun deleteAudio(c: Context, id: String) {
    MeetingStore.audioFile(c, id).delete()
    MeetingStore.segmentsDir(c, id).deleteRecursively()
    MeetingStore.reload(c)
}

/** 压成 m4a（48 kbps，一小时约 22 MB）用系统分享（存到云端、传给别人…）。 */
private fun shareAudio(c: Context, m: Meeting) {
    val dir = File(c.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    val name = meetingTitle(m).replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(40).ifEmpty { m.id }
    val out = File(dir, "$name.m4a")
    VoiceRecorderExporter.encodeM4a(MeetingStore.audioFile(c, m.id), out, outRate = 16_000, bitRate = 48_000)
    val uri = FileProvider.getUriForFile(c, c.packageName + ".files", out)
    val send = Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, meetingTitle(m)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    c.startActivity(Intent.createChooser(send, "分享錄音檔").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / (1024.0 * 1024 * 1024))
    n >= 1L shl 20 -> "%.1f MB".format(n / (1024.0 * 1024))
    else -> "${(n + 1023) / 1024} KB"
}
