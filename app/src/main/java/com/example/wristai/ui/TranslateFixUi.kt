package com.example.wristai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import com.example.wristai.TranslateFixes
import com.example.wristai.TranslateLangs
import com.example.wristai.TranslationStore
import com.example.wristai.ui.theme.Brand

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Brand.Text, unfocusedTextColor = Brand.Text,
    focusedBorderColor = Brand.Cyan, unfocusedBorderColor = Brand.Outline, cursorColor = Brand.Cyan,
)

/**
 * 更正一句翻譯：改這一句的譯文；也可以填「翻錯的詞 → 正確的詞」，以後的翻譯都會自動換掉。
 */
@Composable
fun FixLineDialog(s: TranslationStore.Session, index: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val line = s.lines[index]
    var text by remember { mutableStateOf(line.translated.orEmpty()) }
    var wrong by remember { mutableStateOf("") }
    var right by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更正翻譯".tr) },
        text = {
            Column {
                Text(line.original, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("這一句的譯文".tr) }, colors = fieldColors())
                Spacer(Modifier.height(12.dp))
                Text("以後都這樣翻（選填）".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                Text("例如人名、公司名、專有名詞老是翻錯：填翻錯的詞和正確的詞，之後的即時翻譯、遠距對話都會自動換掉。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(wrong, { wrong = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("翻錯的詞".tr) }, colors = fieldColors())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(right, { right = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("正確的詞".tr) }, colors = fieldColors())
            }
        },
        confirmButton = {
            TextButton({
                val fixed = if (wrong.isNotBlank()) text.replace(wrong.trim(), right.trim()) else text
                if (wrong.isNotBlank()) TranslateFixes.add(context, s.target, wrong, right)
                val lines = s.lines.toMutableList().also { it[index] = line.copy(translated = fixed.ifBlank { null }) }
                TranslationStore.save(context, s.copy(lines = lines))
                onDismiss()
            }) { Text("儲存".tr) }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消".tr) } },
    )
}

/** 設定：已存的翻譯更正（點 ✕ 刪除）。 */
@Composable
fun TranslateFixesCard() {
    val context = LocalContext.current
    var list by remember { mutableStateOf(TranslateFixes.all(context)) }
    GlassCard {
        Text("翻譯更正（{0}）".trf(list.size), style = MaterialTheme.typography.titleSmall, color = Brand.Text)
        Text("在「記錄」長按一句可以更正；存下的詞之後都會自動換掉。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        for (f in list) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${f.wrong} → ${f.right}", style = MaterialTheme.typography.bodyMedium, color = Brand.Text, modifier = Modifier.weight(1f))
            Text(TranslateLangs.shortName(f.lang, true), style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
            Text("  ✕", color = Brand.Warning, modifier = Modifier.clickable { TranslateFixes.remove(context, f); list = TranslateFixes.all(context) })
        }
    }
}
