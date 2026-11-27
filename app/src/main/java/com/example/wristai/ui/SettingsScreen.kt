package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
fun SettingsScreen(onBack: (() -> Unit)? = null, onOpenPillar: (Tab) -> Unit = {}) {
    val context = LocalContext.current
    var gemini by remember { mutableStateOf(Settings.geminiKey(context)) }
    var claude by remember { mutableStateOf(Settings.apiKey(context)) }
    // 「关于与授权」页（设定里的子页面）
    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) { AboutScreen { showAbout = false }; return }

    Column(Modifier.fillMaxSize()) {
        if (onBack != null) TopBar("設定".tr, onBack)
        else Text("設定".tr, style = MaterialTheme.typography.headlineSmall, color = Brand.Text,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SystemStatusCard()

            // ---------- 三大主轴各自的设定（在各分页的「設定」） ----------
            SectionLabel("功能設定".tr)
            GlassCard {
                PillarLink("錄音報告".tr, "錄音語言、報告語言、通話、地點、手錶遙控、Galaxy AI".tr) { onOpenPillar(Tab.RECORDS) }
                Divider(Modifier.padding(vertical = 10.dp))
                PillarLink("飲食記錄".tr, "每日目標、健康同步".tr) { onOpenPillar(Tab.FOOD) }
                Divider(Modifier.padding(vertical = 10.dp))
                PillarLink("即時翻譯".tr, "預設語言、字幕大小、手錶字幕、離線翻譯模型".tr) { onOpenPillar(Tab.TRANSLATE) }
            }

            // ---------- 介面：一张卡放三个各自独立的选择——排布（版面）、資訊量（商用 / 科技）、配色 ----------
            SectionLabel("介面".tr)
            GlassCard {
                // 语言：介面（个别 App 语言）与报告（AI 写报告用的语言）分开选
                Text("介面語言".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                Spacer(Modifier.height(8.dp))
                val uiLang = remember { com.example.wristai.AppLocale.setting(context) }
                val uiOpts = com.example.wristai.AppLocale.OPTIONS
                Segmented(uiOpts.map { it.second ?: "跟系統".tr }, uiOpts.indexOfFirst { it.first == uiLang }.coerceAtLeast(0)) { i ->
                    (context as? android.app.Activity)?.let { com.example.wristai.AppLocale.set(it, uiOpts[i].first) }
                }
                InterfaceDivider()
                Text("報告語言".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                Spacer(Modifier.height(8.dp))
                var reportLang by remember { mutableStateOf(com.example.wristai.ReportLang.setting(context)) }
                val rOpts = listOf(com.example.wristai.ReportLang.AUTO to "跟介面".tr) + com.example.wristai.ReportLang.entries.map { it.code to it.label }
                Segmented(rOpts.map { it.second }, rOpts.indexOfFirst { it.first == reportLang }.coerceAtLeast(0)) { i ->
                    reportLang = rOpts[i].first
                    com.example.wristai.ReportLang.set(context, reportLang)
                }
                Spacer(Modifier.height(6.dp))
                Text("AI 的標題、摘要與報告用這個語言寫；逐字稿照錄音原本的語言。現在：".tr +
                    com.example.wristai.ReportLang.current(context).label + "。已經做好的報告可以在詳情頁「重新產生」。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)

                InterfaceDivider()
                // 排布：快錄 / 總覽（分页与主页的样子；之后会再加）
                Text("排布".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                Spacer(Modifier.height(8.dp))
                Segmented(com.example.wristai.ui.theme.LayoutMode.entries.map { it.label },
                    com.example.wristai.ui.theme.LayoutMode.entries.indexOf(AppTheme.layout)) { i ->
                    val l = com.example.wristai.ui.theme.LayoutMode.entries[i]
                    AppTheme.layout = l
                    Settings.setLayoutMode(context, l.key)
                }
                Spacer(Modifier.height(6.dp))
                Text(AppTheme.layout.note, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)

                // 資訊量：商用（简洁）/ 科技（细节），哪个排布都适用
                InterfaceDivider()
                Text("資訊量".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf("商用".tr, "科技".tr), if (UiMode.tech) 1 else 0) { i -> UiMode.set(context, i == 1) }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (UiMode.tech) "顯示 AI 模型、耗時、編號、引擎切換原因與主機模型選單".tr
                    else "簡潔，只顯示結果與重點".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                )

                // 配色：清晰 / 暖和 / 深色 / 水墨
                InterfaceDivider()
                Text("配色".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                Spacer(Modifier.height(8.dp))
                ThemePicker(AppTheme.mode) { m ->
                    AppTheme.mode = m
                    Settings.setThemeMode(context, m.key)
                    com.example.wristai.WatchNotifier.broadcastTheme(context)
                }
                Spacer(Modifier.height(6.dp))
                Text(AppTheme.mode.note, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)

                // 台灣才有：背景画（島嶼電路 / 晶圓 / 夜城 / 無）
                if (AppTheme.mode == com.example.wristai.ui.theme.ThemeMode.TAIWAN) {
                    InterfaceDivider()
                    Text("背景畫".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                    Spacer(Modifier.height(8.dp))
                    val tbs = com.example.wristai.ui.theme.TaiwanBackground.entries
                    Segmented(tbs.map { it.label }, tbs.indexOf(AppTheme.taiwanBg)) { i ->
                        AppTheme.taiwanBg = tbs[i]
                        Settings.setTaiwanBackground(context, tbs[i].key)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(when (AppTheme.taiwanBg) {
                        com.example.wristai.ui.theme.TaiwanBackground.ISLAND -> "島嶼電路：點陣組成的台灣、北回歸線與五座城市的連線。".tr
                        com.example.wristai.ui.theme.TaiwanBackground.WAFER -> "晶圓：一片晶圓與往上延伸的電路，點綴梅花。".tr
                        com.example.wristai.ui.theme.TaiwanBackground.CITY -> "夜城：夜晚的城市天際線、遠山與河面倒影。".tr
                        com.example.wristai.ui.theme.TaiwanBackground.NONE -> "不放背景畫，只有夜藍底。".tr
                    }, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                }

                // 浮世繪才有：背景画（藝伎武士 / 靈鶴 / 大阪城 / 無）
                if (AppTheme.mode == com.example.wristai.ui.theme.ThemeMode.UKIYOE) {
                    InterfaceDivider()
                    Text("背景畫".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                    Spacer(Modifier.height(8.dp))
                    val ubs = com.example.wristai.ui.theme.UkiyoeBackground.entries
                    Segmented(ubs.map { it.label }, ubs.indexOf(AppTheme.ukiyoeBg)) { i ->
                        AppTheme.ukiyoeBg = ubs[i]
                        Settings.setUkiyoeBackground(context, ubs[i].key)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(when (AppTheme.ukiyoeBg) {
                        com.example.wristai.ui.theme.UkiyoeBackground.GEISHA -> "藝伎武士：朱紅拱橋上，撐紙傘的藝伎與佩雙刀的武士。".tr
                        com.example.wristai.ui.theme.UkiyoeBackground.CRANE -> "靈鶴：三隻丹頂鶴、朱紅的日與松枝。".tr
                        com.example.wristai.ui.theme.UkiyoeBackground.CASTLE -> "大阪城：白牆、銅綠屋頂的天守，石垣、護城河與櫻花。".tr
                        com.example.wristai.ui.theme.UkiyoeBackground.NONE -> "不放背景畫，只有和紙底。".tr
                    }, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                }

                // 水墨才有：背景画（孤舟 / 荷塘 / 魚蝦 / 無）
                if (AppTheme.mode == com.example.wristai.ui.theme.ThemeMode.INK) {
                    InterfaceDivider()
                    Text("背景畫".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                    Spacer(Modifier.height(8.dp))
                    val bgs = com.example.wristai.ui.theme.InkBackground.entries
                    Segmented(bgs.map { it.label }, bgs.indexOf(AppTheme.inkBg)) { i ->
                        AppTheme.inkBg = bgs[i]
                        Settings.setInkBackground(context, bgs[i].key)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(when (AppTheme.inkBg) {
                        com.example.wristai.ui.theme.InkBackground.BOAT -> "孤舟寒江：淡月、遠岸、一葉小舟與蘆葦。".tr
                        com.example.wristai.ui.theme.InkBackground.LOTUS -> "荷塘清趣：荷葉、淡荷花與三尾小魚。".tr
                        com.example.wristai.ui.theme.InkBackground.SHRIMP -> "魚蝦戲水：荷葉一角、水草，小蝦與小魚。".tr
                        com.example.wristai.ui.theme.InkBackground.NONE -> "不放背景畫，只有宣紙底。".tr
                    }, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                }
            }

            // ---------- 大D的主機（使用者自己的电脑跑 AI） ----------
            LocalHostSection()

            // ---------- Google 云端硬碟备份 / 取回（档案清单在「檔案」分页） ----------
            BackupSection()


            // ---------- AI 引擎：Gemini 一站式 / 组合式（百炼、讯飞、OpenAI、Meta、Claude）/ 只用 Galaxy AI ----------
            AiEngineSection(
                // 金钥打了就存（跟其他 AI 金钥一样，不用再按「储存设定」）
                gemini = gemini, onGemini = { gemini = it; Settings.setGeminiKey(context, it) },
                claude = claude, onClaude = { claude = it; Settings.setApiKey(context, it) },
            )

            // ---------- 隐私 ----------
            SectionLabel("隱私與安全".tr)
            GlassCard {
                Note(Icons.Filled.Lock, "金鑰只儲存在這支手機，只用來呼叫對應的 AI 服務。上傳給 Gemini 的錄音在產生報告後立即刪除；錄音與報告都保存在本機。".tr)
            }
            GhostButton("關於與授權（開放原始碼、模型、條款）".tr, { showAbout = true }, Modifier.fillMaxWidth())
            // 版本号读 App 本身（build.gradle 自动编号，例如 1.1.63），不要写死
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
            }
            Text("大D · Dylan {0}".trf(version), style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint,
                modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun PillarLink(title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Brand.TextFaint)
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconCircle(Icons.AutoMirrored.Filled.ArrowBack, "返回".tr, onBack)
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

/** 「介面」卡里三个选择之间的分隔线。 */
@Composable
private fun InterfaceDivider() {
    Spacer(Modifier.height(16.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.Outline))
    Spacer(Modifier.height(16.dp))
}

/** 配色四选一（两行两格）：每格一个小色块（底色＋重点色圆点）＋名字，选中的有重点色框。 */
@Composable
private fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { for (row in ThemeMode.entries.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in row) {
            val on = m == selected
            val shape = RoundedCornerShape(12.dp)
            val p = m.palette
            Row(
                Modifier.weight(1f).height(52.dp).clip(shape).background(Brand.Surface)
                    .border(if (on) 2.dp else 1.dp, if (on) Brand.Cyan else Brand.Outline, shape)
                    .clickable { onSelect(m) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(p.bg).border(1.dp, p.outline, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(p.cyan))
                }
                Spacer(Modifier.width(10.dp))
                Text(m.label, style = MaterialTheme.typography.labelLarge, color = if (on) Brand.Text else Brand.TextMuted)
            }
        }
    } }
}

