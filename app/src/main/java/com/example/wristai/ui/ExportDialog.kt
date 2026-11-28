package com.example.wristai.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.voiceai.shared.tr
import com.example.wristai.ReportExport
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 分享 / 匯出：文字、PDF、Word、Markdown。檔案在手機上產生。 */
@Composable
fun ExportDialog(title: String, markdown: () -> String, onDismiss: () -> Unit, footer: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun file(f: ReportExport.Format) {
        busy = true
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { ReportExport.write(context, title, markdown(), f, footer) } }
            busy = false
            r.onSuccess { ReportExport.share(context, it, f, "分享報告".tr); onDismiss() }
                .onFailure { Toast.makeText(context, "匯出失敗：".tr + (it.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分享 / 匯出".tr) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PDF 與 Word 在手機上產生，不會上傳。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                GhostButton("文字（貼到 LINE、Email）".tr, {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title)
                        .putExtra(Intent.EXTRA_TEXT, markdown())
                    context.startActivity(Intent.createChooser(send, "分享報告".tr)); onDismiss()
                }, Modifier.fillMaxWidth())
                GhostButton("PDF", { if (!busy) file(ReportExport.Format.PDF) }, Modifier.fillMaxWidth(), tint = Brand.Cyan)
                GhostButton("Word（.docx）", { if (!busy) file(ReportExport.Format.WORD) }, Modifier.fillMaxWidth(), tint = Brand.Cyan)
                GhostButton("Markdown（Notion、Obsidian）".tr, { if (!busy) file(ReportExport.Format.MARKDOWN) }, Modifier.fillMaxWidth())
                // 直接存到 Notion（要先在 錄音 → 設定 填 Notion 金鑰與頁面）
                GhostButton("存到 Notion".tr, {
                    if (busy) return@GhostButton
                    if (!com.example.wristai.NotionExport.configured(context)) {
                        Toast.makeText(context, "請先到 錄音 → 設定 →「存到 Notion」填金鑰與頁面連結".tr, Toast.LENGTH_LONG).show()
                        return@GhostButton
                    }
                    busy = true
                    scope.launch {
                        val r = runCatching { withContext(Dispatchers.IO) { com.example.wristai.NotionExport.save(context, title, markdown()) } }
                        busy = false
                        r.onSuccess { url ->
                            Toast.makeText(context, "已存到 Notion".tr, Toast.LENGTH_SHORT).show()
                            if (url.isNotBlank()) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                            onDismiss()
                        }.onFailure { Toast.makeText(context, it.message ?: "Notion 失敗".tr, Toast.LENGTH_LONG).show() }
                    }
                }, Modifier.fillMaxWidth())
                if (busy) Text("產生中…".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("取消".tr) } },
    )
}
