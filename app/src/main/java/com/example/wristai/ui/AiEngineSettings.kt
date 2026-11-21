package com.example.wristai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.wristai.ai.AiConfig
import com.example.wristai.ai.AiConfig.LlmKind
import com.example.wristai.ai.AiConfig.SttKind
import com.example.wristai.ui.theme.Brand

/**
 * 设定页「AI 引擎」：选报告由谁产生——
 * Gemini 一站式 / 组合式（语音辨识 ＋ 报告模型，可混搭百炼、讯飞、OpenAI、Meta、Claude）/ 只用 Galaxy AI。
 * 除了 Gemini 与 Claude 金钥（沿用设定页的「储存」），其他栏位改了就存。
 */
@Composable
fun AiEngineSection(gemini: String, onGemini: (String) -> Unit, claude: String, onClaude: (String) -> Unit) {
    val context = LocalContext.current
    // 改了就重画（AiConfig 存在 SharedPreferences，不是 Compose 状态）
    var tick by remember { mutableIntStateOf(0) }
    fun changed() { tick++ }
    if (tick < 0) return   // 读 tick：改设定后整段重画
    val mode = AiConfig.mode(context)
    val stt = AiConfig.sttKind(context)
    val llm = AiConfig.llmKind(context)
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    SectionLabel("AI 引擎")
    GlassCard {
        Text("報告由誰產生", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
        Spacer(Modifier.height(10.dp))
        ModeOption(mode == AiConfig.MODE_GEMINI, "Google Gemini（一站式）", "直接聆聽錄音，產生報告與分說話者逐字稿；免費方案可用") {
            AiConfig.setMode(context, AiConfig.MODE_GEMINI); changed()
        }
        ModeOption(mode == AiConfig.MODE_PIPELINE, "組合：語音辨識 ＋ 報告模型", "阿里百煉、科大訊飛、OpenAI、Meta、Claude 自由搭配") {
            AiConfig.setMode(context, AiConfig.MODE_PIPELINE); changed()
        }
        ModeOption(mode == AiConfig.MODE_DEVICE, "只用 Galaxy AI（裝置端）", "錄音不上傳，存入三星錄音機後在手機上轉錄 / 摘要") {
            AiConfig.setMode(context, AiConfig.MODE_DEVICE); changed()
        }
    }

    when (mode) {
        AiConfig.MODE_GEMINI -> GlassCard(highlight = gemini.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Google Gemini", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                    Text("免費 · 直接聆聽錄音，產生報告與分說話者逐字稿", style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                }
                if (gemini.isNotBlank()) Icon(Icons.Filled.CheckCircle, "已設定", tint = Brand.Success)
            }
            Spacer(Modifier.height(12.dp))
            SecretField(gemini, onGemini, "Gemini API Key")
            TextButton(onClick = { open("https://aistudio.google.com/apikey") }) { Text("免費取得金鑰（Google 帳號登入）→", color = Brand.Cyan) }
            Hint("免費方案每日次數有限；錄音內容會被 Google 用於改進產品。機密內容建議使用 Galaxy AI（裝置端）。")
        }

        AiConfig.MODE_PIPELINE -> {
            // ---------- 语音辨识 ----------
            GlassCard(highlight = AiConfig.stt(context) != null) {
                Text("① 語音辨識", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text("把錄音轉成逐字稿，並分辨不同說話者", style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Spacer(Modifier.height(10.dp))
                Chips(SttKind.entries.map { it.label }, SttKind.entries.indexOf(stt)) { AiConfig.setSttKind(context, SttKind.entries[it]); changed() }
                Spacer(Modifier.height(6.dp))
                Text(stt.detail, style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
                Spacer(Modifier.height(10.dp))
                when (stt) {
                    SttKind.FUNASR -> {
                        DashscopeFields { changed() }
                        ModelField("funasr", "模型（預設 fun-asr，最新版 Fun-ASR）") { changed() }
                    }
                    SttKind.IFLYTEK -> {
                        PlainField("iflytek_appid", "訊飛 APPID", onChange = { changed() })
                        Spacer(Modifier.height(8.dp))
                        SecretPref("iflytek_secret", "錄音文件轉寫 SecretKey") { changed() }
                        TextButton(onClick = { open("https://console.xfyun.cn/") }) { Text("訊飛開放平台 → 錄音文件轉寫", color = Brand.Cyan) }
                    }
                    SttKind.OPENAI -> {
                        SecretPref("openai_key", "OpenAI API Key") { changed() }
                        ModelField("openai_stt", "模型（預設 gpt-4o-transcribe-diarize）") { changed() }
                        TextButton(onClick = { open("https://platform.openai.com/api-keys") }) { Text("取得 OpenAI 金鑰 →", color = Brand.Cyan) }
                    }
                }
            }

            // ---------- 报告模型 ----------
            GlassCard(highlight = AiConfig.llm(context) != null) {
                Text("② 報告模型", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text("讀逐字稿，整理摘要、說話者、重點、待辦", style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Spacer(Modifier.height(10.dp))
                Chips(LlmKind.entries.map { it.label }, LlmKind.entries.indexOf(llm)) { AiConfig.setLlmKind(context, LlmKind.entries[it]); changed() }
                Spacer(Modifier.height(6.dp))
                Text(llm.detail, style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
                Spacer(Modifier.height(10.dp))
                when (llm) {
                    LlmKind.QWEN -> {
                        if (stt == SttKind.FUNASR) Hint("跟語音辨識共用同一把百煉金鑰。")
                        else DashscopeFields { changed() }
                        ModelField("qwen", "模型（預設 qwen-plus，可改 qwen-max 等）") { changed() }
                    }
                    LlmKind.SPARK -> {
                        SecretPref("spark_password", "星火 APIPassword（HTTP 服務）") { changed() }
                        ModelField("spark", "模型（4.0Ultra / max-32k / pro-128k / lite…）") { changed() }
                        TextButton(onClick = { open("https://console.xfyun.cn/") }) { Text("訊飛開放平台 → 星火大模型 →", color = Brand.Cyan) }
                    }
                    LlmKind.LLAMA -> {
                        SecretPref("llama_key", "Llama API Key") { changed() }
                        ModelField("llama", "模型（預設 Llama-4-Maverick-17B-128E-Instruct-FP8）") { changed() }
                        PlainField("llama_base", "端點（留空用預設）", AiConfig.DEFAULT_LLAMA_BASE, onChange = { changed() })
                        Hint("也可以填其他提供 Llama 的 OpenAI 相容服務端點。")
                        TextButton(onClick = { open("https://llama.developer.meta.com/") }) { Text("Meta Llama API →", color = Brand.Cyan) }
                    }
                    LlmKind.OPENAI -> {
                        if (stt == SttKind.OPENAI) Hint("跟語音辨識共用同一把 OpenAI 金鑰。")
                        else SecretPref("openai_key", "OpenAI API Key") { changed() }
                        ModelField("openai", "模型（預設 gpt-5-mini）") { changed() }
                    }
                    LlmKind.CLAUDE -> {
                        SecretField(claude, onClaude, "Claude API Key")
                        ModelField("claude", "模型（預設 ${AiConfig.DEFAULT_MODELS["claude"]}）") { changed() }
                        TextButton(onClick = { open("https://console.anthropic.com/settings/keys") }) { Text("取得 Claude 金鑰 →", color = Brand.Cyan) }
                    }
                }
            }
            val missing = AiConfig.pipelineMissing(context)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(if (missing == null) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, Modifier.size(16.dp),
                    tint = if (missing == null) Brand.Success else Brand.Warning)
                Spacer(Modifier.width(6.dp))
                Text(missing ?: "設定完成：${stt.label} ＋ ${llm.label}", style = MaterialTheme.typography.bodySmall,
                    color = if (missing == null) Brand.Success else Brand.Warning)
            }
            Hint("錄音與逐字稿會上傳到你選的服務商處理，各家的資料政策不同。金鑰只存在這支手機。")
        }

        else -> GlassCard {
            Text("Galaxy AI · 錄音不離開手機", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
            Text("錄音存入三星「錄音機」，在錄音機用 Galaxy AI 轉錄 / 摘要（可設為僅在裝置上處理）。大D 不會產生 AI 報告。",
                style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        }
    }
}

/** 百炼：区域＋金钥（Fun-ASR 与通义千问共用）。 */
@Composable
private fun DashscopeFields(onChange: () -> Unit) {
    val context = LocalContext.current
    val region = AiConfig.dashscopeRegion(context)
    Text("百煉區域（兩區金鑰不通用）", style = MaterialTheme.typography.labelMedium, color = Brand.TextMuted)
    Spacer(Modifier.height(6.dp))
    Chips(listOf("國際（新加坡）", "中國大陸（北京）"), if (region == "cn") 1 else 0) {
        AiConfig.set(context, "dashscope_region", if (it == 1) "cn" else "intl"); onChange()
    }
    Spacer(Modifier.height(8.dp))
    SecretPref("dashscope_key", "阿里雲百煉 API Key") { onChange() }
    TextButton(onClick = {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                if (region == "cn") "https://bailian.console.aliyun.com/" else "https://modelstudio.console.alibabacloud.com/")))
        }
    }) { Text("阿里雲百煉控制台 → API Key", color = Brand.Cyan) }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
            .background(if (selected) Brand.SurfaceHigher else Brand.Surface)
            .border(1.dp, if (selected) Brand.Cyan else Brand.Outline, shape)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box2(selected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        }
    }
}

/** 单选圆点 */
@Composable
private fun Box2(selected: Boolean) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (selected) Brand.Cyan else Brand.Outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) androidx.compose.foundation.layout.Box(Modifier.size(9.dp).clip(CircleShape).background(Brand.Cyan))
    }
}

@Composable
private fun Chips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val shape = RoundedCornerShape(50)
            Text(
                label, style = MaterialTheme.typography.labelLarge, color = if (on) Brand.Bg else Brand.TextMuted,
                modifier = Modifier.clip(shape).background(if (on) Brand.Text else Brand.Surface)
                    .border(1.dp, if (on) Brand.Text else Brand.Outline, shape)
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Row(Modifier.padding(top = 6.dp)) {
        Icon(Icons.Filled.Info, null, Modifier.size(14.dp).padding(top = 2.dp), tint = Brand.TextFaint)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
    }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        shape = RoundedCornerShape(14.dp), colors = engineFieldColors(), modifier = Modifier.fillMaxWidth(),
    )
}

/** 存在 AiConfig 的金钥（改了就存）。 */
@Composable
private fun SecretPref(key: String, label: String, onChange: () -> Unit) {
    val context = LocalContext.current
    var v by remember(key) { mutableStateOf(AiConfig.get(context, key)) }
    SecretField(v, { v = it; AiConfig.set(context, key, it); onChange() }, label)
}

@Composable
private fun PlainField(key: String, label: String, placeholder: String? = null, onChange: () -> Unit) {
    val context = LocalContext.current
    var v by remember(key) { mutableStateOf(AiConfig.get(context, key)) }
    OutlinedTextField(
        value = v, onValueChange = { v = it; AiConfig.set(context, key, it); onChange() }, label = { Text(label) },
        placeholder = placeholder?.let { p -> { Text(p, color = Brand.TextFaint) } },
        singleLine = true, shape = RoundedCornerShape(14.dp), colors = engineFieldColors(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

/** 模型名称（留空 = 预设值；新版本出来可以自己改）。[label] 只当说明，栏位标签一律「模型」。 */
@Composable
private fun ModelField(slot: String, @Suppress("UNUSED_PARAMETER") label: String, onChange: () -> Unit) =
    PlainField("model_$slot", "模型（留空用預設）", AiConfig.DEFAULT_MODELS[slot], onChange)

@Composable
private fun engineFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Brand.Text, unfocusedTextColor = Brand.Text,
    focusedBorderColor = Brand.Cyan, unfocusedBorderColor = Brand.Outline, cursorColor = Brand.Cyan,
    focusedLabelColor = Brand.Cyan, unfocusedLabelColor = Brand.TextMuted,
)

