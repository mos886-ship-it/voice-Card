package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.wristai.backup.BackupWorker
import com.example.wristai.backup.DriveBackup
import com.example.wristai.ui.theme.Brand
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设定页「Google 雲端硬碟備份」：只放设定——连结帐号（Google 自己的同意画面，由使用者选帐号、按允许）、
 * 备份选项（录音档、自动、只用 Wi-Fi）、取消连结。立即备份 / 从云端取回在「檔案」分页（[BackupActions]）。
 */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var linked by remember { mutableStateOf(DriveBackup.linked(context)) }
    var email by remember { mutableStateOf(DriveBackup.email(context)) }
    var audio by remember { mutableStateOf(DriveBackup.includeAudio(context)) }
    var auto by remember { mutableStateOf(DriveBackup.auto(context)) }
    var wifi by remember { mutableStateOf(DriveBackup.wifiOnly(context)) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirmUnlink by remember { mutableStateOf(false) }
    val status by DriveBackup.status.collectAsState()

    fun linkedNow(token: String) {
        scope.launch {
            withContext(Dispatchers.IO) { DriveBackup.finishLink(context, token) }
            linked = true
            email = DriveBackup.email(context)
            message = true to "已連結 Google 雲端硬碟，開始第一次備份".tr
            BackupWorker.run(context, BackupWorker.MODE_BACKUP)
        }
    }

    // Google 的同意画面（选帐号、允许存取「这个 App 建立的档案」）
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val res = runCatching {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(r.data).accessToken
        }
        val token = res.getOrNull()
        if (token != null) linkedNow(token)
        else {
            val e = res.exceptionOrNull()
            android.util.Log.w("DriveBackup", "consent result=${r.resultCode}", e)
            message = false to DriveBackup.consentError(r.resultCode, (e as? com.google.android.gms.common.api.ApiException)?.statusCode, e?.message)
        }
    }

    fun link() {
        message = null
        scope.launch {
            try {
                linkedNow(DriveBackup.token(context))
            } catch (e: DriveBackup.NeedsConsent) {
                e.result.pendingIntent?.intentSender?.let { consent.launch(IntentSenderRequest.Builder(it).build()) }
                    ?: run { message = false to "Google 沒有給授權畫面".tr }
            } catch (e: Exception) {
                message = false to (e.message ?: "連結失敗".tr)
            }
        }
    }

    SectionLabel("Google 雲端硬碟備份".tr)
    GlassCard(highlight = linked) {
        if (!linked) {
            Text("備份到你自己的 Google 雲端硬碟".tr, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
            Text(
                "錄音和報告存在你的雲端硬碟「{0}」資料夾，換手機或手機壞了也能取回。".trf(DriveBackup.FOLDER_NAME) +
                    "App 只看得到它自己建立的檔案，看不到你雲端硬碟裡的其他東西。".tr,
                style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            GradientButton("連結 Google 雲端硬碟".tr, { link() }, Modifier.fillMaxWidth())
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = Brand.Success, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("已連結 Google 雲端硬碟".tr, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
            }
            email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted) }
            Text("備份在雲端硬碟「{0}」資料夾；立即備份、從雲端取回在「檔案」分頁".trf(DriveBackup.FOLDER_NAME),
                style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
            Spacer(Modifier.height(10.dp))
            BackupToggle("備份錄音檔".tr, "關掉只備份報告和逐字稿（小很多）；錄音一小時約 22 MB".tr, audio) {
                audio = it; DriveBackup.setIncludeAudio(context, it)
            }
            BackupToggle("報告完成後自動備份".tr, null, auto) { auto = it; DriveBackup.setAuto(context, it) }
            if (auto) BackupToggle("只用 Wi-Fi 自動備份".tr, "不用行動數據".tr, wifi) { wifi = it; DriveBackup.setWifiOnly(context, it) }
            TextButton(onClick = { confirmUnlink = true }) { Text("取消連結".tr, color = Brand.TextMuted) }
        }
        message?.let { (ok, msg) ->
            Spacer(Modifier.height(8.dp))
            Text(msg, style = MaterialTheme.typography.bodySmall, color = if (ok) Brand.Success else Brand.Warning)
        }
    }

    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text("取消連結 Google 雲端硬碟？".tr) },
            text = { Text("之後不再備份。雲端硬碟裡已經備份的檔案會留著，要刪請到雲端硬碟「{0}」資料夾自己刪。".trf(DriveBackup.FOLDER_NAME)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnlink = false
                    scope.launch {
                        val t = runCatching { DriveBackup.token(context) }.getOrNull()
                        withContext(Dispatchers.IO) { DriveBackup.unlink(context, t) }
                        linked = false; email = null; message = true to "已取消連結".tr
                    }
                }) { Text("取消連結".tr, color = Brand.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text("返回".tr) } },
        )
    }
}

@Composable
private fun BackupToggle(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Brand.Text)
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint) }
        }
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Brand.Cyan))
    }
}

/**
 * 「檔案」分页上方的备份操作：立即备份、从云端取回、进度、上次备份时间。
 * 还没连结 → 一行说明＋「到設定連結」。[pending] = 还没备份 / 有更新的笔数（null = 还不知道）。
 */
@Composable
fun BackupActions(pending: Int?, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val status by DriveBackup.status.collectAsState()
    GlassCard {
        if (!DriveBackup.linked(context)) {
            Text("還沒連結 Google 雲端硬碟".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Text("連結後可以備份到雲端、換手機時取回。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            Spacer(Modifier.height(8.dp))
            GhostButton("到設定連結".tr, onOpenSettings, Modifier.fillMaxWidth(), tint = Brand.Cyan)
            return@GlassCard
        }
        val last = DriveBackup.lastBackup(context)
        Text(
            listOfNotNull(
                when {
                    pending == null -> null
                    pending == 0 -> "全部已備份".tr
                    else -> "{0} 筆還沒備份".trf(pending)
                },
                if (last > 0) "上次備份 ".tr + SimpleDateFormat("M/d HH:mm", Locale.TAIWAN).format(Date(last)) else "還沒備份過".tr,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = if (pending != null && pending > 0) Brand.Warning else Brand.TextMuted,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(if (status.running) "處理中…".tr else "立即備份".tr, {
                if (!status.running) BackupWorker.run(context, BackupWorker.MODE_BACKUP)
            }, Modifier.weight(1f), tint = Brand.Cyan)
            GhostButton("從雲端取回".tr, {
                if (!status.running) BackupWorker.run(context, BackupWorker.MODE_RESTORE)
            }, Modifier.weight(1f), tint = Brand.Cyan)
        }
        if (status.running && status.total > 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { status.done.toFloat() / status.total }, modifier = Modifier.fillMaxWidth(),
                color = Brand.Cyan, trackColor = Brand.Outline)
        }
        status.text?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (status.error) Brand.Warning else Brand.Success)
        }
    }
}
