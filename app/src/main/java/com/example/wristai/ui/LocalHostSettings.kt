package com.example.wristai.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wristai.ai.LocalHost
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 从别的地方（相机扫到的连结、点到的 dylan://pair）送进来、等设定页处理的配对连结。 */
object PendingPair {
    var link by mutableStateOf<String?>(null)
}

/**
 * 设定页「大D的主機」卡（跟 iOS 一样）：
 * - 还没连：扫 QR 码，或填位址＋配对密码 → 主机发给这台自己的存取码。科技模式另有「直接贴主存取码」。
 * - 已连上：显示「已連上大D的主機」、测试连线、取消配对、设定 / 更换配对密码；
 *   科技模式多主机模型选单、已配对的装置（可个别取消）。
 */
@Composable
fun LocalHostSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(LocalHost.url(context)) }
    var hasToken by remember { mutableStateOf(LocalHost.hasToken(context)) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var models by remember { mutableStateOf<LocalHost.Models?>(null) }
    var loadingModel by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<LocalHost.Devices?>(null) }
    var pairPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var showPasswordField by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var mainToken by remember { mutableStateOf("") }
    var confirmUnpair by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<LocalHost.Device?>(null) }

    /** 背景跑 [work]，成功显示 [ok] 的文字，失败显示错误。 */
    fun run(work: () -> String?, after: () -> Unit = {}) {
        busy = true
        result = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(work) }
            busy = false
            r.onSuccess { msg -> msg?.let { result = true to it } }.onFailure { result = false to (it.message ?: "失敗") }
            if (hasToken != LocalHost.hasToken(context) || r.isSuccess) url = LocalHost.url(context).ifBlank { url }
            hasToken = LocalHost.hasToken(context)
            // 只有成功才接着做（失败时保留错误讯息和使用者打的字）
            if (r.isSuccess) after()
        }
    }

    fun refresh() {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val h = LocalHost.health(context)
                    Triple(h, runCatching { LocalHost.models(context) }.getOrNull(), runCatching { LocalHost.devices(context) }.getOrNull())
                }
            }
            busy = false
            r.onSuccess { (h, m, d) ->
                models = m
                devices = d
                loadingModel = h.state == "loading"
                result = true to when (h.state) {
                    "loading" -> "連線成功，主機正在載入模型（第一次會先下載，可能要幾分鐘）…"
                    "error" -> "連線成功，但主機的模型載入失敗：${h.error ?: "未知原因"}"
                    else -> "連線成功：" + listOfNotNull(h.asr, h.llm).joinToString("、") + if (h.busy) "（主機正在處理別的錄音）" else ""
                }
            }.onFailure { result = false to (it.message ?: "連線失敗") }
        }
    }

    fun test() { busy = true; result = null; refresh() }

    val scanner = remember { { act: Activity ->
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(
            act,
            com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build(),
        ).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let { PendingPair.link = it } }
            .addOnFailureListener { e -> result = false to "沒辦法開啟掃描：${e.message ?: "請確認手機有 Google Play 服務"}" }
    } }

    // 扫到的 QR / 点到的 dylan://pair 连结 → 配对
    LaunchedEffect(PendingPair.link) {
        val link = PendingPair.link ?: return@LaunchedEffect
        PendingPair.link = null
        run({ val used = LocalHost.pairLink(context, link); "已連上大D的主機（$used）" }) { refresh() }
    }

    // 已连上 → 进设定页时顺便读一次状态（模型、装置清单）
    LaunchedEffect(hasToken) { if (hasToken && LocalHost.configured(context)) refresh() }

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

    val connected = hasToken && url.isNotBlank()

    SectionLabel("大D的主機")
    GlassCard(highlight = connected) {
        if (connected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = Brand.Success, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("已連上大D的主機", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
            }
            Text(url, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            Text("錄音和飲食記錄優先交給你自己的電腦處理；連不到時自動改用其他引擎。",
                style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(if (busy) "處理中…" else "測試連線", { if (!busy) test() }, Modifier.weight(1f), tint = Brand.Cyan)
                TextButton(onClick = { confirmUnpair = true }) { Text("取消配對", color = Brand.TextMuted) }
            }
        } else {
            Text("用你自己的電腦跑 AI", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
            Text(
                "免費、沒有額度、錄音不出你的設備，可分辨說話者。在家裡的 Wi-Fi 用瀏覽器打開主機的配對頁" +
                    "（例 http://Dylan-Mac-mini.local:8765/pair）掃 QR 碼；或填位址和配對密碼。",
                style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            GradientButton(if (busy) "配對中…" else "掃描主機上的 QR 碼", {
                if (!busy) context.findActivity()?.let(scanner)
            }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Text("或用配對密碼", style = MaterialTheme.typography.labelLarge, color = Brand.TextMuted)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it; result = null },
                label = { Text("位址") }, placeholder = { Text("例 http://192.168.1.20:8765", color = Brand.TextFaint) },
                singleLine = true, shape = RoundedCornerShape(14.dp), colors = engineFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pairPassword, onValueChange = { pairPassword = it; result = null },
                label = { Text("配對密碼") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(14.dp), colors = engineFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            GhostButton(if (busy) "配對中…" else "配對", {
                if (!busy && url.isNotBlank() && pairPassword.isNotBlank()) {
                    val u = url; val pw = pairPassword
                    run({ LocalHost.pair(context, u, password = pw); "已連上大D的主機" }) { pairPassword = ""; refresh() }
                }
            }, Modifier.fillMaxWidth(), tint = Brand.Cyan)

            // 科技模式：直接贴主机 config.json 的主存取码（旧方法）
            if (UiMode.tech) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "收起進階" else "進階：直接貼主存取碼", color = Brand.TextMuted)
                }
                if (advanced) {
                    OutlinedTextField(
                        value = mainToken, onValueChange = { mainToken = it },
                        label = { Text("主存取碼（主機的 ~/dylan-local/config.json）") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(14.dp), colors = engineFieldColors(), modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    GhostButton("儲存並測試", {
                        if (url.isNotBlank() && mainToken.isNotBlank()) {
                            LocalHost.setUrl(context, url); LocalHost.setToken(context, mainToken)
                            mainToken = ""; url = LocalHost.url(context); hasToken = true; test()
                        }
                    }, Modifier.fillMaxWidth())
                }
            }
        }

        result?.let { (ok, msg) ->
            Spacer(Modifier.height(8.dp))
            Text(msg, style = MaterialTheme.typography.bodySmall, color = if (ok) Brand.Success else Brand.Warning)
        }

        if (connected) {
            // ---------- 配对密码（给之后的装置用） ----------
            val pwSet = devices?.passwordSet == true
            // 读不到主机状态（连不到 / 存取码失效）就不显示密码列，免得误显示「還沒設定」
            if (devices != null) Spacer(Modifier.height(14.dp))
            if (devices != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("配對密碼", style = MaterialTheme.typography.labelLarge, color = Brand.Text)
                    Text(if (pwSet) "已設定：其他手機或平板輸入位址和密碼就能連上" else "還沒設定：設定後，其他裝置人在外面也能配對",
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                }
                TextButton(onClick = { showPasswordField = !showPasswordField; newPassword = "" }) {
                    Text(if (showPasswordField) "取消" else if (pwSet) "更換" else "設定", color = Brand.Cyan)
                }
            }
            if (showPasswordField && devices != null) {
                OutlinedTextField(
                    value = newPassword, onValueChange = { newPassword = it },
                    label = { Text("新的配對密碼（至少 8 個字）") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(14.dp), colors = engineFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                GhostButton("儲存密碼", {
                    if (newPassword.length >= 8) {
                        val pw = newPassword
                        run({ LocalHost.setPassword(context, pw); "配對密碼已儲存（已配對的裝置不受影響）" }) {
                            showPasswordField = false; newPassword = ""; refresh()
                        }
                    } else result = false to "密碼至少 8 個字"
                }, Modifier.fillMaxWidth(), tint = Brand.Cyan)
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
            }

            // ---------- 科技模式：已配对的装置 ----------
            val d = devices
            if (UiMode.tech && d != null && d.devices.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("已配對的裝置", style = MaterialTheme.typography.labelLarge, color = Brand.TextMuted)
                val fmt = remember { SimpleDateFormat("yy/M/d HH:mm", Locale.TAIWAN) }
                for (dev in d.devices) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(dev.name + if (dev.current) "（這台）" else "", style = MaterialTheme.typography.bodyMedium,
                                color = Brand.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    "配對 " + fmt.format(Date(dev.created * 1000)),
                                    dev.lastSeen?.let { "最後使用 " + fmt.format(Date(it * 1000)) },
                                    when (dev.via) { "qr" -> "QR 碼"; "password" -> "密碼"; else -> null },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint,
                            )
                        }
                        TextButton(onClick = { if (dev.current) confirmUnpair = true else revokeTarget = dev }) {
                            Text("取消", color = Brand.Error)
                        }
                    }
                }
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("取消這台的配對？") },
            text = { Text("這台手機的存取碼會作廢，錄音改用其他引擎。之後可以再掃 QR 碼或用配對密碼連回來。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    run({ LocalHost.unpair(context); "已取消配對" }) { models = null; devices = null }
                }) { Text("取消配對", color = Brand.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("返回") } },
        )
    }
    revokeTarget?.let { dev ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("取消「${dev.name}」的配對？") },
            text = { Text("那台裝置的存取碼會作廢，要重新配對才能再用大D的主機。") },
            confirmButton = {
                TextButton(onClick = {
                    revokeTarget = null
                    run({ LocalHost.revoke(context, dev.id); "已取消「${dev.name}」" }) { refresh() }
                }) { Text("取消配對", color = Brand.Error) }
            },
            dismissButton = { TextButton(onClick = { revokeTarget = null }) { Text("返回") } },
        )
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
