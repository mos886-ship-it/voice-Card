package com.example.wristai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.wristai.ai.LocalHost
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设定页「大D的主機」卡：位址、存取码（加密存，画面只显示「已設定」）、测试连线；
 * 科技模式多一个模型选单（主机上的语音辨识 / 报告模型，选了主机会在背景换）。
 */
@Composable
fun LocalHostSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(LocalHost.url(context)) }
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(LocalHost.hasToken(context)) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var models by remember { mutableStateOf<LocalHost.Models?>(null) }
    var loadingModel by remember { mutableStateOf(false) }
    val configured = url.isNotBlank() && hasToken

    fun test() {
        testing = true
        result = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val h = LocalHost.health(context)
                    val m = runCatching { LocalHost.models(context) }.getOrNull()
                    h to m
                }
            }
            testing = false
            r.onSuccess { (h, m) ->
                models = m
                loadingModel = h.state == "loading"
                result = true to when (h.state) {
                    "loading" -> "連線成功，主機正在載入模型（第一次會先下載，可能要幾分鐘）…"
                    "error" -> "連線成功，但主機的模型載入失敗：${h.error ?: "未知原因"}"
                    else -> "連線成功：" + listOfNotNull(h.asr, h.llm).joinToString("、") + if (h.busy) "（主機正在處理別的錄音）" else ""
                }
            }.onFailure { result = false to (it.message ?: "連線失敗") }
        }
    }

    // 换模型后每 3 秒问一次，直到主机载入完
    LaunchedEffect(loadingModel) {
        while (loadingModel) {
            delay(3_000)
            val m = withContext(Dispatchers.IO) { runCatching { LocalHost.models(context) }.getOrNull() } ?: continue
            models = m
            if (m.state != "loading") {
                loadingModel = false
                result = (m.state != "error") to (if (m.state == "error") "主機換模型失敗" else "已換成 " + LocalHost.label(m.model ?: LocalHost.lastTag(context)))
            }
        }
    }

    SectionLabel("大D的主機")
    GlassCard(highlight = configured) {
        Text("用你自己的電腦跑 AI", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
        Text(
            "免費、沒有額度、錄音不出你的設備，可分辨說話者。主機要開著、手機連得到（同一個 Wi-Fi 或固定 IP）。" +
                "有設定時優先使用，連不到會自動改用其他引擎。",
            style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it; LocalHost.setUrl(context, it); result = null },
            label = { Text("位址") }, placeholder = { Text("例 http://192.168.1.20:8765", color = Brand.TextFaint) },
            singleLine = true, shape = RoundedCornerShape(14.dp), colors = engineFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { v ->
                token = v
                if (v.isNotBlank()) { LocalHost.setToken(context, v); hasToken = true; result = null }
            },
            label = { Text(if (hasToken && token.isEmpty()) "存取碼已設定（要換才貼新的）" else "存取碼") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(14.dp), colors = engineFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(if (testing) "測試中…" else "測試連線", { if (!testing && configured) test() }, Modifier.weight(1f), tint = Brand.Cyan)
            if (url.isNotBlank() || hasToken) {
                TextButton(onClick = {
                    LocalHost.setUrl(context, ""); LocalHost.setToken(context, "")
                    url = ""; token = ""; hasToken = false; result = null; models = null
                }) { Text("清除", color = Brand.TextMuted) }
            }
        }
        if (!configured) {
            Spacer(Modifier.height(6.dp))
            Text("位址和存取碼都填好才會使用。存取碼在主機的 ~/dylan-local/config.json。",
                style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
        }
        result?.let { (ok, msg) ->
            Spacer(Modifier.height(8.dp))
            Text(msg, style = MaterialTheme.typography.bodySmall, color = if (ok) Brand.Success else Brand.Warning)
        }

        // ---------- 科技模式：主机上的模型 ----------
        val m = models
        if (UiMode.tech && m != null && (m.asrOptions.isNotEmpty() || m.llmOptions.isNotEmpty())) {
            Spacer(Modifier.height(14.dp))
            fun choose(asr: String?, llm: String?) {
                val a = asr ?: m.asrId ?: return
                val l = llm ?: m.llmId ?: return
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { LocalHost.select(context, a, l) } }
                    r.onSuccess {
                        models = m.copy(asrId = a, llmId = l, state = "loading")
                        loadingModel = true
                        result = true to "主機正在載入模型（第一次會先下載，可能要幾分鐘）…"
                    }.onFailure { result = false to (it.message ?: "換模型失敗") }
                }
            }
            Text("語音辨識", style = MaterialTheme.typography.labelLarge, color = Brand.TextMuted)
            Spacer(Modifier.height(6.dp))
            Chips(m.asrOptions.map { it.label }, m.asrOptions.indexOfFirst { it.id == m.asrId }) { i ->
                if (!loadingModel) choose(m.asrOptions[i].id, null)
            }
            Spacer(Modifier.height(10.dp))
            Text("報告模型", style = MaterialTheme.typography.labelLarge, color = Brand.TextMuted)
            Spacer(Modifier.height(6.dp))
            Chips(m.llmOptions.map { it.label }, m.llmOptions.indexOfFirst { it.id == m.llmId }) { i ->
                if (!loadingModel) choose(null, m.llmOptions[i].id)
            }
        } else if (UiMode.tech && configured && m == null) {
            Spacer(Modifier.height(6.dp))
            Text("按「測試連線」後可以在這裡換主機上的模型", style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
        }
    }
}
