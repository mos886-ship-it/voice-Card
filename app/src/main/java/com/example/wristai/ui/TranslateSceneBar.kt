package com.example.wristai.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import com.example.wristai.FoodPhotos
import com.example.wristai.Speaker
import com.example.wristai.TranslateLangs
import com.example.wristai.TranslateScene
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 情境選擇（一般、照護居家、商務、旅遊）。換了會改字幕設定，[onApplied] 讓即時翻譯頁重讀。 */
@Composable
fun SceneBar(onApplied: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(TranslateScene.mode(context)) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in TranslateScene.ALL) SceneChip(TranslateScene.label(m), m == mode) {
            mode = m; TranslateScene.setMode(context, m); onApplied()
            Toast.makeText(context, TranslateScene.note(m), Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun SceneChip(text: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) Brand.Text else Brand.TextMuted,
        modifier = Modifier.clip(shape).background(if (on) Brand.Cyan.copy(alpha = 0.18f) else Brand.SurfaceHigh)
            .border(1.dp, if (on) Brand.Cyan else Brand.Outline, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp))
}

/**
 * 常用片語：這個情境的片語＋自己加的（長按刪除）。點一下翻成對方的語言、放大顯示並念出來。
 * 旅遊情境多一個「拍菜單」：拍照或選照片，用 Gemini 翻成我的語言。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhraseRow(source: String, target: String, sceneVersion: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode = remember(sceneVersion) { TranslateScene.mode(context) }
    var custom by remember { mutableStateOf(TranslateScene.customPhrases(context)) }
    var open by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<MenuState?>(null) }
    var capture by remember { mutableStateOf<File?>(null) }
    val other = TranslateScene.otherLang(source, target)

    fun runMenu(uri: Uri, cleanup: File? = null) {
        menu = MenuState(busy = true)
        scope.launch {
            val staged = withContext(Dispatchers.IO) { FoodPhotos.stage(context, uri) }
            cleanup?.delete()
            if (staged == null) { menu = MenuState(error = "讀不到照片".tr); return@launch }
            val r = TranslateScene.translateMenu(context, staged, TranslateLangs.name(TranslateScene.myLang(), target = true))
            staged.delete()
            menu = r.fold({ MenuState(text = it) }, { MenuState(error = it.message ?: "翻譯失敗".tr) })
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = capture; capture = null
        if (ok && f != null && f.length() > 0) runMenu(Uri.fromFile(f), cleanup = f) else f?.delete()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { runMenu(it) } }

    val phrases = TranslateScene.phrases(mode)
    if (phrases.isEmpty() && custom.isEmpty() && mode == TranslateScene.GENERAL) {
        // 一般模式也可以自己加片語
        Row(Modifier.padding(horizontal = 20.dp)) { PhraseChip("＋ " + "常用片語".tr, accent = true) { adding = true } }
    } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (mode == TranslateScene.TRAVEL) {
            PhraseChip("📷 " + "拍菜單".tr, accent = true) {
                val (f, uri) = FoodPhotos.newCaptureUri(context)
                capture = f
                runCatching { camera.launch(uri) }
            }
            PhraseChip("🖼 " + "選照片".tr, accent = true) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        for (p in custom) PhraseChip(p, onLongClick = { TranslateScene.removeCustom(context, p); custom = TranslateScene.customPhrases(context) }) { open = p }
        for (p in phrases) PhraseChip(p) { open = p }
        PhraseChip("＋", accent = true) { adding = true }
    }

    open?.let { PhraseDialog(it, TranslateScene.myLang(), other) { open = null } }
    if (adding) AddPhraseDialog(onDismiss = { adding = false }) { t ->
        adding = false; TranslateScene.addCustom(context, t); custom = TranslateScene.customPhrases(context)
    }
    menu?.let { MenuDialog(it) { menu = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhraseChip(text: String, accent: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (accent) Brand.Cyan else Brand.Text, maxLines = 1,
        modifier = Modifier.clip(shape).background(Brand.SurfaceHigh).border(1.dp, Brand.Outline, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 12.dp, vertical = 8.dp))
}

/** 片語放大：上面我的話（小），下面翻好的（大字，給對方看），自動念一次；可以再念、翻轉 180°。 */
@Composable
private fun PhraseDialog(text: String, from: String, to: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var out by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var flip by remember { mutableIntStateOf(0) }
    val slow = if (TranslateScene.mode(context) == TranslateScene.CARE) 0.8f else null
    LaunchedEffect(text, to) {
        val r = TranslateScene.translate(context, text, from, to)
        if (r == null) failed = true else { out = r; if (!Speaker.speak(context, r, to, slow)) Toast.makeText(context,
            "手機沒有{0}的語音，可以在系統「文字轉語音」下載".trf(TranslateLangs.shortName(to, true)), Toast.LENGTH_LONG).show() }
    }
    Dialog(onDismissRequest = { Speaker.stop(); onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(Brand.CardRadius)).background(Brand.Surface).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Brand.TextMuted)
            Text(out ?: if (failed) "翻譯失敗（第一次用這個語言要先下載翻譯模型，請連上網路）".tr else "…",
                fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, color = Brand.Text,
                modifier = Modifier.rotate(flip * 180f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("🔊 " + "再念一次".tr, { out?.let { Speaker.speak(context, it, to, slow) } })
                GhostButton("⟲ " + "翻轉".tr, { flip = 1 - flip })
                GhostButton("關閉".tr, { Speaker.stop(); onClose() }, tint = Brand.Cyan)
            }
        }
    }
}

@Composable
private fun AddPhraseDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var t by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增常用片語".tr) },
        text = {
            Column {
                Text("用你的語言寫，點一下會翻成對方的語言並念出來；長按可以刪除。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(t, { t = it.take(120) }, singleLine = true,
                    placeholder = { Text("例如：我的房間是 1203".tr, color = Brand.TextFaint) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Brand.Text, unfocusedTextColor = Brand.Text,
                        focusedBorderColor = Brand.Cyan, unfocusedBorderColor = Brand.Outline, cursorColor = Brand.Cyan))
            }
        },
        confirmButton = { TextButton({ onAdd(t) }, enabled = t.isNotBlank()) { Text("新增".tr) } },
        dismissButton = { TextButton(onDismiss) { Text("取消".tr) } },
    )
}

private data class MenuState(val busy: Boolean = false, val text: String? = null, val error: String? = null)

@Composable
private fun MenuDialog(s: MenuState, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(Brand.CardRadius)).background(Brand.Surface).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("照片翻譯".tr, style = MaterialTheme.typography.titleLarge, color = Brand.Text)
            when {
                s.busy -> { Text("翻譯中…（照片會傳到 Gemini）".tr, color = Brand.TextMuted); ScanningBar(Modifier.fillMaxWidth()) }
                s.error != null -> Text(s.error, color = Brand.Warning)
                else -> Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    Text(s.text.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = Brand.Text)
                }
            }
            Text("AI 翻譯僅供參考；過敏請再跟店家確認。".tr, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
            GhostButton("關閉".tr, onClose, tint = Brand.Cyan)
        }
    }
}
