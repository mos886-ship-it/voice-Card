package com.example.wristai.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.voiceai.shared.tr
import com.example.wristai.CallRecordingWatcher
import com.example.wristai.RemoteRecordActivity
import com.example.wristai.Settings
import com.example.wristai.VoiceRecorderExporter
import com.example.wristai.ui.theme.Brand

/*
 * 各主轴「設定」小分页用的设定卡（原本都挤在一个设定页）。都是改了就存，不用按「儲存」。
 */

@Composable
internal fun SettingNote(icon: ImageVector, text: String, color: Color = Brand.TextMuted) {
    Row {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
internal fun BrandSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked = checked, onCheckedChange = onChange,
    colors = SwitchDefaults.colors(
        checkedTrackColor = Brand.Cyan, checkedThumbColor = Brand.Bg,
        uncheckedTrackColor = Brand.SurfaceHigher, uncheckedBorderColor = Brand.Outline,
    ),
)

/** 标题＋说明＋右边开关的一张卡。 */
@Composable
internal fun SwitchCard(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, extra: @Composable () -> Unit = {}) {
    GlassCard(highlight = checked) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            }
            Spacer(Modifier.width(12.dp))
            BrandSwitch(checked, onChange)
        }
        extra()
    }
}

@Composable
internal fun brandFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Brand.Cyan, unfocusedBorderColor = Brand.Outline,
    focusedLabelColor = Brand.Cyan, unfocusedLabelColor = Brand.TextMuted,
    focusedContainerColor = Brand.SurfaceHigh, unfocusedContainerColor = Brand.SurfaceHigh,
    cursorColor = Brand.Cyan,
)

/** 数字栏位：打了就存（[onValue] 拿到合法的数字才呼叫）。 */
@Composable
internal fun NumberField(initial: Int, label: String, min: Int, max: Int, modifier: Modifier = Modifier, onValue: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    OutlinedTextField(
        value = text, onValueChange = { v ->
            text = v.filter { it.isDigit() }.take(max.toString().length)
            text.toIntOrNull()?.takeIf { it in min..max }?.let(onValue)
        },
        label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = brandFieldColors(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

// ---------------- 錄音報告 ----------------

/** 新录音的语言＋报告语言。 */
@Composable
fun RecordingLanguageCard() {
    val context = LocalContext.current
    GlassCard {
        Text("錄音語言".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
        Spacer(Modifier.height(8.dp))
        LangChooser()
        Spacer(Modifier.height(16.dp))
        Text("報告語言".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
        Spacer(Modifier.height(8.dp))
        var reportLang by remember { mutableStateOf(com.example.wristai.ReportLang.setting(context)) }
        val opts = listOf(com.example.wristai.ReportLang.AUTO to "跟介面".tr) + com.example.wristai.ReportLang.entries.map { it.code to it.label }
        Segmented(opts.map { it.second }, opts.indexOfFirst { it.first == reportLang }.coerceAtLeast(0)) { i ->
            reportLang = opts[i].first
            com.example.wristai.ReportLang.set(context, reportLang)
        }
        Spacer(Modifier.height(6.dp))
        Text("AI 的標題、摘要與報告用這個語言寫；逐字稿照錄音原本的語言。現在：".tr +
            com.example.wristai.ReportLang.current(context).label + "。已經做好的報告可以在詳情頁「重新產生」。".tr,
            style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
    }
}

/** 现在用哪个 AI 整理报告；细节在「全部設定」。 */
@Composable
fun EngineSummaryCard(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val e = remember { currentEngine(context) }
    GlassCard(onClick = onOpenSettings) {
        Text("整理報告的 AI".tr, style = MaterialTheme.typography.labelMedium, color = Brand.TextMuted)
        Text(e.name, style = MaterialTheme.typography.titleMedium, color = e.color)
        Text(e.detail, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        Spacer(Modifier.height(6.dp))
        Text("變更 AI 引擎、大D的主機 →".tr, style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
    }
}

@Composable
fun GalaxyExportCard() {
    val context = LocalContext.current
    val hasRecorder = remember { VoiceRecorderExporter.launchIntent(context) != null }
    var export by remember { mutableStateOf(Settings.exportToVoiceRecorder(context)) }
    SwitchCard(
        "Galaxy AI · 錄音不離開手機".tr,
        if (hasRecorder) "每段錄音都存一份到「錄音機」，可用 Galaxy AI 轉錄 / 摘要".tr else "此手機沒有三星錄音機，此功能不會生效".tr,
        export, { export = it; Settings.setExportToVoiceRecorder(context, it) },
    )
}

@Composable
fun CallRecordingCard() {
    val context = LocalContext.current
    var callOn by remember { mutableStateOf(CallRecordingWatcher.isEnabled(context) && CallRecordingWatcher.hasPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { CallRecordingWatcher.setEnabled(context, true); callOn = true }
    }
    val dialer = remember {
        context.packageManager.getLaunchIntentForPackage("com.samsung.android.dialer")
            ?: context.packageManager.getLaunchIntentForPackage("com.google.android.dialer")
    }
    SwitchCard("自動整理通話錄音".tr, "電話 App 錄好的通話，自動產生通話報告（重點、待辦、逐字稿）".tr, callOn, { on ->
        if (on && !CallRecordingWatcher.hasPermission(context)) launcher.launch(CallRecordingWatcher.audioPermission())
        else { CallRecordingWatcher.setEnabled(context, on); callOn = on }
    }) {
        Spacer(Modifier.height(10.dp))
        SettingNote(Icons.Filled.Info, "一般電話：請在三星「電話」App → 設定 →「錄製通話」開啟自動錄音，大D 會自動接手整理。".tr)
        if (dialer != null) TextButton(onClick = { context.startActivity(dialer) }) { Text("開啟電話 App →".tr, color = Brand.Cyan) }
        SettingNote(Icons.Filled.Warning, "LINE、WhatsApp、Telegram、WeChat 等網路通話：Android 不允許任何 App 錄製（通話中其他 App 的麥克風會被靜音）。請開擴音，改用手錶錄音。".tr)
        SettingNote(Icons.Filled.Lock, "錄音前請告知對方。各地法規不同，請自行確認錄音的合法性。".tr)
    }
}

/** 录音地点（GPS 戳记）：饮食拍照也用同一个权限。 */
@Composable
fun RecordLocationCard() {
    val context = LocalContext.current
    var geoFg by remember { mutableStateOf(com.example.voiceai.shared.GeoFix.hasPermission(context)) }
    var geoBg by remember { mutableStateOf(com.example.voiceai.shared.GeoFix.hasBackgroundPermission(context)) }
    val fgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        geoFg = com.example.voiceai.shared.GeoFix.hasPermission(context)
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        geoBg = com.example.voiceai.shared.GeoFix.hasBackgroundPermission(context)
    }
    // 从系统设定回来要更新
    LaunchedEffect(Unit) {
        while (true) {
            geoFg = com.example.voiceai.shared.GeoFix.hasPermission(context)
            geoBg = com.example.voiceai.shared.GeoFix.hasBackgroundPermission(context)
            kotlinx.coroutines.delay(1_000)
        }
    }
    GlassCard(highlight = geoBg) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("用手機記錄地點".tr, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text(
                    when {
                        geoBg -> "已開啟：手錶或手機開始錄音時，用手機定位當 GPS 戳記".tr
                        geoFg -> "只在使用 App 時：手機錄音會記錄；手錶錄音時手機多半在背景，會改用手錶 GPS".tr
                        else -> "未開啟：手錶錄音改用手錶 GPS；手機錄音不記錄地點".tr
                    },
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (geoBg) Icon(Icons.Filled.CheckCircle, "已開啟".tr, tint = Brand.Success)
        }
        if (!geoBg) {
            Spacer(Modifier.height(10.dp))
            GhostButton(
                if (!geoFg) "允許定位".tr else "改成「一律允許」".tr,
                {
                    if (!geoFg) fgLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                    else if (android.os.Build.VERSION.SDK_INT >= 29) bgLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                },
                Modifier.fillMaxWidth(),
            )
            if (geoFg) SettingNote(Icons.Filled.Info, "系統會開啟定位權限頁，請選「一律允許」。大D 只在開始錄音的那一刻定位一次，不會持續追蹤。".tr)
        }
        SettingNote(Icons.Filled.Lock, "地點只存在你的手機，用來標示報告與搜尋；手機連不上時才用手錶的 GPS。".tr)
    }
}

@Composable
fun WatchRemoteCard() {
    val context = LocalContext.current
    var remoteOk by remember { mutableStateOf(RemoteRecordActivity.canStartFromBackground(context)) }
    LaunchedEffect(Unit) {
        while (true) { remoteOk = RemoteRecordActivity.canStartFromBackground(context); kotlinx.coroutines.delay(1_000) }
    }
    GlassCard(highlight = remoteOk) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("手錶遠端啟動手機錄音".tr, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text(
                    if (remoteOk) "已開啟：在手錶點「用手機錄音」，手機鎖屏時也會直接開始".tr
                    else "未開啟：從手錶啟動時，需要在手機點一下通知才會開始".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                )
            }
            if (remoteOk) Icon(Icons.Filled.CheckCircle, "已開啟".tr, tint = Brand.Success)
        }
        if (!remoteOk) {
            Spacer(Modifier.height(12.dp))
            GhostButton("前往開啟（允許「顯示在其他應用程式上層」）".tr, {
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")))
            }, Modifier.fillMaxWidth(), tint = Brand.Cyan)
            SettingNote(Icons.Filled.Info, "Android 規定 App 在背景時不能直接開麥克風。開啟這個權限後，大D 只會在你從手錶按下錄音時，短暫開啟一個透明畫面來啟動錄音。".tr)
        }
    }
}

// ---------------- 飲食記錄 ----------------

/** 每日目标：热量、蛋白质、膳食纤维。 */
@Composable
fun FoodGoalsCard() {
    val context = LocalContext.current
    GlassCard {
        Text("每日目標".tr, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
        Text("今天的進度、分析頁的達標天數都依這裡計算".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        Spacer(Modifier.height(12.dp))
        NumberField(Settings.dailyKcalGoal(context), "大卡 / 天".tr, 800, 6000, Modifier.fillMaxWidth()) { Settings.setDailyKcalGoal(context, it) }
        Spacer(Modifier.height(8.dp))
        Row {
            NumberField(Settings.proteinGoal(context), "蛋白質 g".tr, 10, 400, Modifier.weight(1f)) { Settings.setProteinGoal(context, it) }
            Spacer(Modifier.width(8.dp))
            NumberField(Settings.fiberGoal(context), "纖維 g".tr, 5, 100, Modifier.weight(1f)) { Settings.setFiberGoal(context, it) }
        }
        Spacer(Modifier.height(8.dp))
        SettingNote(Icons.Filled.Info, "熱量為 AI 依口述估算，僅供參考，不能取代營養師或醫療建議。".tr)
    }
}
