package com.example.wristai.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import com.example.wristai.LinkImport
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「匯入」：選檔案（音檔、影片）或貼連結（YouTube、Podcast…）。回傳打開選單的函式。
 */
@Composable
fun rememberImportChooser(onImport: (Context, Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(context, uri)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("匯入".tr) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("選檔案（音檔、影片）".tr, { open = false; picker.launch(arrayOf("audio/*", "video/*")) },
                    Modifier.fillMaxWidth(), tint = Brand.Cyan)
                Text("或貼上連結".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("https://…", color = Brand.TextFaint) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Brand.Text, unfocusedTextColor = Brand.Text,
                        focusedBorderColor = Brand.Cyan, unfocusedBorderColor = Brand.Outline, cursorColor = Brand.Cyan))
                Text("音檔連結（.mp3、.m4a…）手機直接下載；YouTube 等網頁由大D的主機抓音訊。請只匯入你有權使用的內容。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            }
        },
        confirmButton = {
            TextButton({ open = false; importLink(context, link) }, enabled = LinkImport.normalize(link) != null) { Text("從連結匯入".tr) }
        },
        dismissButton = { TextButton({ open = false }) { Text("取消".tr) } },
    )
    return {
        // 剪貼簿有連結就先填好
        link = runCatching {
            context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
        }.getOrNull()?.let { LinkImport.normalize(it) }.orEmpty()
        open = true
    }
}

fun importLink(context: Context, raw: String) {
    val app = context.applicationContext
    fun toast(s: String) = CoroutineScope(Dispatchers.Main).launch { Toast.makeText(app, s, Toast.LENGTH_LONG).show() }
    CoroutineScope(Dispatchers.IO).launch {
        val msg = runCatching { LinkImport.import(app, raw) { toast(it) } }
            .fold({ "已匯入，開始處理".tr }, { "匯入失敗：{0}".trf(it.message) })
        withContext(Dispatchers.Main) { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
    }
}
