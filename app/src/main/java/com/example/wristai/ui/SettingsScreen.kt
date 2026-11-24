package com.example.wristai.ui

import androidx.compose.ui.draw.clip

import androidx.compose.foundation.clickable

import androidx.compose.foundation.background

import androidx.compose.foundation.border

import androidx.compose.material.icons.filled.Warning

import androidx.activity.result.contract.ActivityResultContracts

import androidx.activity.compose.rememberLauncherForActivityResult

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.wristai.CallRecordingWatcher
import com.example.wristai.RemoteRecordActivity
import com.example.wristai.Settings
import com.example.wristai.VoiceRecorderExporter
import com.example.wristai.ui.theme.Brand
import com.example.wristai.ui.theme.AppTheme
import com.example.wristai.ui.theme.ThemeMode

@Composable
/** [onBack] = null 时是底部分页（没有返回键）。 */
fun SettingsScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    var gemini by remember { mutableStateOf(Settings.geminiKey(context)) }
    var claude by remember { mutableStateOf(Settings.apiKey(context)) }
    var lang by remember { mutableStateOf(Settings.language(context)) }
    var export by remember { mutableStateOf(Settings.exportToVoiceRecorder(context)) }
    var saved by remember { mutableStateOf(false) }
    var kcalGoal by remember { mutableStateOf(Settings.dailyKcalGoal(context).toString()) }
    val hasRecorder = remember { VoiceRecorderExporter.launchIntent(context) != null }
    var callOn by remember { mutableStateOf(CallRecordingWatcher.isEnabled(context) && CallRecordingWatcher.hasPermission(context)) }
    val callPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { CallRecordingWatcher.setEnabled(context, true); callOn = true }
    }
    val dialer = remember {
        context.packageManager.getLaunchIntentForPackage("com.samsung.android.dialer")
            ?: context.packageManager.getLaunchIntentForPackage("com.google.android.dialer")
    }
    // 从系统设定回来时要更新，所以定时检查
    var remoteOk by remember { mutableStateOf(RemoteRecordActivity.canStartFromBackground(context)) }
    // 录音地点：定位权限（使用时 / 一律允许），从系统设定回来会自动更新
    var geoFg by remember { mutableStateOf(com.example.voiceai.shared.GeoFix.hasPermission(context)) }
    var geoBg by remember { mutableStateOf(com.example.voiceai.shared.GeoFix.hasBackgroundPermission(context)) }
    val geoFgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        geoFg = com.example.voiceai.shared.GeoFix.hasPermission(context)
    }
    val geoBgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        geoBg = com.example.voiceai.shared.GeoFix.hasBackgroundPermission(context)
    }
    LaunchedEffect(Unit) {
        while (true) {
            geoFg = com.example.voiceai.shared.GeoFix.hasPermission(context)
            geoBg = com.example.voiceai.shared.GeoFix.hasBackgroundPermission(context)
            kotlinx.coroutines.delay(1_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) { remoteOk = RemoteRecordActivity.canStartFromBackground(context); kotlinx.coroutines.delay(1_000) }
    }

    // 「关于与授权」页（设定里的子页面）
    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) { AboutScreen { showAbout = false }; return }

    fun save() {
        Settings.setGeminiKey(context, gemini)
        Settings.setApiKey(context, claude)
        Settings.setLanguage(context, lang)
        Settings.setExportToVoiceRecorder(context, export)
        kcalGoal.toIntOrNull()?.let { Settings.setDailyKcalGoal(context, it) }
        saved = true
    }

    Column(Modifier.fillMaxSize()) {
        if (onBack != null) TopBar("設定", onBack)
        else Text("設定", style = MaterialTheme.typography.headlineSmall, color = Brand.Text,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 介面模式：商用（简洁）/ 科技（细节） ----------
            SectionLabel("介面模式")
            GlassCard {
                Chips(listOf("商用", "科技"), if (UiMode.tech) 1 else 0) { i -> UiMode.set(context, i == 1) }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (UiMode.tech) "科技：顯示 AI 模型、耗時、編號、引擎切換原因與主機模型選單"
                    else "商用：簡潔，只顯示結果與重點",
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                )
            }

            SystemStatusCard()

            // ---------- 外观 ----------
            SectionLabel("外觀")
            GlassCard {
                Text("主題", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text("深色省電、適合會議室；淺色在戶外好讀；水墨是宣紙與墨色，和 logo 同一套風格", style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Spacer(Modifier.height(12.dp))
                ThemePicker(AppTheme.mode) { m ->
                    AppTheme.mode = m
                    Settings.setThemeMode(context, m.key)
                    com.example.wristai.WatchNotifier.broadcastTheme(context)
                }
            }

            // ---------- 三星录音机 ----------
            SectionLabel("裝置端 AI（推薦）")
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Galaxy AI · 錄音不離開手機", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                        Text(
                            if (hasRecorder) "每段錄音都存一份到「錄音機」，可用 Galaxy AI 轉錄 / 摘要"
                            else "此手機沒有三星錄音機，此功能不會生效",
                            style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = export, onCheckedChange = { export = it; saved = false },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Brand.Cyan, checkedThumbColor = Brand.Bg,
                            uncheckedTrackColor = Brand.SurfaceHigher, uncheckedBorderColor = Brand.Outline,
                        ),
                    )
                }
            }

            // ---------- 大D的主機（使用者自己的电脑跑 AI） ----------
            LocalHostSection()

            // ---------- AI 引擎：Gemini 一站式 / 组合式（百炼、讯飞、OpenAI、Meta、Claude）/ 只用 Galaxy AI ----------
            AiEngineSection(
                // 金钥打了就存（跟其他 AI 金钥一样，不用再按「储存设定」）
                gemini = gemini, onGemini = { gemini = it; Settings.setGeminiKey(context, it) },
                claude = claude, onClaude = { claude = it; Settings.setApiKey(context, it) },
            )

            // ---------- 饮食记录 ----------
            SectionLabel("飲食記錄")
            GlassCard {
                Text("每日熱量目標", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text("說出吃了什麼，AI 會估算熱量與蛋白質、碳水、脂肪（需要雲端 AI）",
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = kcalGoal, onValueChange = { v -> kcalGoal = v.filter { it.isDigit() }.take(4); saved = false },
                    label = { Text("大卡 / 天") }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Note(Icons.Filled.Info, "熱量為 AI 依口述估算，僅供參考，不能取代營養師或醫療建議。")
            }

            // ---------- 通话录音 ----------
            SectionLabel("通話錄音")
            GlassCard(highlight = callOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自動整理通話錄音", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                        Text("電話 App 錄好的通話，自動產生通話報告（重點、待辦、逐字稿）",
                            style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = callOn,
                        onCheckedChange = { on ->
                            if (on && !CallRecordingWatcher.hasPermission(context)) callPermLauncher.launch(CallRecordingWatcher.audioPermission())
                            else { CallRecordingWatcher.setEnabled(context, on); callOn = on }
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Brand.Cyan, checkedThumbColor = Brand.Bg,
                            uncheckedTrackColor = Brand.SurfaceHigher, uncheckedBorderColor = Brand.Outline,
                        ),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Note(Icons.Filled.Info, "一般電話：請在三星「電話」App → 設定 →「錄製通話」開啟自動錄音，大D 會自動接手整理。")
                if (dialer != null) {
                    TextButton(onClick = { context.startActivity(dialer) }) { Text("開啟電話 App →", color = Brand.Cyan) }
                }
                Note(Icons.Filled.Warning, "LINE、WhatsApp、Telegram、WeChat 等網路通話：Android 不允許任何 App 錄製（通話中其他 App 的麥克風會被靜音）。請開擴音，改用手錶錄音。")
                Note(Icons.Filled.Lock, "錄音前請告知對方。各地法規不同，請自行確認錄音的合法性。")
            }

            // ---------- 录音地点（GPS 戳记） ----------
            SectionLabel("錄音地點")
            GlassCard(highlight = geoBg) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("用手機記錄錄音地點", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                        Text(
                            when {
                                geoBg -> "已開啟：手錶或手機開始錄音時，用手機定位當 GPS 戳記"
                                geoFg -> "只在使用 App 時：手機錄音會記錄；手錶錄音時手機多半在背景，會改用手錶 GPS"
                                else -> "未開啟：手錶錄音改用手錶 GPS；手機錄音不記錄地點"
                            },
                            style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    if (geoBg) Icon(Icons.Filled.CheckCircle, "已開啟", tint = Brand.Success)
                }
                if (!geoBg) {
                    Spacer(Modifier.height(10.dp))
                    GhostButton(
                        if (!geoFg) "允許定位" else "改成「一律允許」",
                        {
                            if (!geoFg) geoFgLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                            else if (android.os.Build.VERSION.SDK_INT >= 29) geoBgLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        },
                        Modifier.fillMaxWidth(),
                    )
                    if (geoFg) Note(Icons.Filled.Info, "系統會開啟定位權限頁，請選「一律允許」。大D 只在開始錄音的那一刻定位一次，不會持續追蹤。")
                }
                Note(Icons.Filled.Lock, "地點只存在你的手機，用來標示報告與搜尋；手機連不上時才用手錶的 GPS。")
            }

            // ---------- 手表遥控 ----------
            SectionLabel("手錶遙控")
            GlassCard(highlight = remoteOk) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("手錶遠端啟動手機錄音", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                        Text(
                            if (remoteOk) "已開啟：在手錶點「用手機錄音」，手機鎖屏時也會直接開始"
                            else "未開啟：從手錶啟動時，需要在手機點一下通知才會開始",
                            style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                        )
                    }
                    if (remoteOk) Icon(Icons.Filled.CheckCircle, "已開啟", tint = Brand.Success)
                }
                if (!remoteOk) {
                    Spacer(Modifier.height(12.dp))
                    GhostButton("前往開啟（允許「顯示在其他應用程式上層」）", {
                        context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")))
                    }, Modifier.fillMaxWidth(), tint = Brand.Cyan)
                    Note(Icons.Filled.Info, "Android 規定 App 在背景時不能直接開麥克風。開啟這個權限後，大D 只會在你從手錶按下錄音時，短暫開啟一個透明畫面來啟動錄音。")
                }
            }

            // ---------- 隐私 ----------
            SectionLabel("隱私與安全")
            GlassCard {
                Note(Icons.Filled.Lock, "金鑰只儲存在這支手機，只用來呼叫對應的 AI 服務。上傳給 Gemini 的錄音在產生報告後立即刪除；錄音與報告都保存在本機。")
            }
            GhostButton("關於與授權（開放原始碼、模型、條款）", { showAbout = true }, Modifier.fillMaxWidth())
            // 版本号读 App 本身（build.gradle 自动编号，例如 1.1.63），不要写死
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
            }
            Text("大D · Dylan $version", style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint,
                modifier = Modifier.padding(vertical = 8.dp))
        }
        Box(Modifier.padding(20.dp)) {
            GradientButton(if (saved) "已儲存 ✓" else "儲存設定", ::save, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconCircle(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.Text, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun KeyField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        shape = RoundedCornerShape(14.dp), colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Brand.Cyan, unfocusedBorderColor = Brand.Outline,
    focusedLabelColor = Brand.Cyan, unfocusedLabelColor = Brand.TextMuted,
    focusedContainerColor = Brand.SurfaceHigh, unfocusedContainerColor = Brand.SurfaceHigh,
    cursorColor = Brand.Cyan,
)

@Composable
private fun Note(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color = Brand.TextMuted) {
    Row {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** 三选一的主题切换（胶囊分段）。 */
@Composable
private fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(Brand.SurfaceHigh).border(1.dp, Brand.Outline, shape).padding(4.dp)) {
        for (m in ThemeMode.entries) {
            val on = m == selected
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                    .then(if (on) Modifier.background(Brand.Accent) else Modifier)
                    .clickable { onSelect(m) },
                contentAlignment = Alignment.Center,
            ) {
                Text(m.label, style = MaterialTheme.typography.labelLarge, color = if (on) androidx.compose.ui.graphics.Color.White else Brand.TextMuted)
            }
        }
    }
}

