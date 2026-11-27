package com.example.wristai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.voiceai.shared.tr
import com.example.wristai.Settings
import com.example.wristai.TranslateLangs
import com.example.wristai.TranslateModels
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.launch

/** 辨识状态的小标签：离线（绿）/ 可下载离线（青）/ 只有线上（灰）。 */
@Composable
fun RecogBadge(status: TranslateLangs.Status?) {
    val (text, color) = when (status) {
        TranslateLangs.Status.OFFLINE -> "離線" to Brand.Success
        TranslateLangs.Status.DOWNLOADABLE -> "可下載離線" to Brand.Cyan
        TranslateLangs.Status.ONLINE -> "線上" to Brand.TextMuted
        null -> return
    }
    Text(text.tr, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp))
}

/**
 * 选语言（底部弹出）：上面搜寻，「最近使用」，下面全部。
 * [source] = true：说的语言（手机辨识得了的，每个语言可以再选地区口音，标离线 / 线上）；
 * false：翻成的语言（ML Kit 全部，标「已下載」）。[current] 说的是辨识代码、翻成的是 ML Kit 代码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateLangSheet(source: Boolean, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val support by TranslateLangs.support.collectAsState()
    val models by TranslateModels.downloaded.collectAsState()
    var query by remember { mutableStateOf("") }
    val currentMl = if (source) TranslateLangs.mlkitOf(current) else current
    var expanded by remember { mutableStateOf(currentMl) }
    val sources = remember(support) { TranslateLangs.sources(support) }
    val all = remember(support) { if (source) sources.keys.toList() else TranslateLangs.targets }
    // 依介面语言的名字排序；搜寻比对介面名、自己的名字、代码
    val named = remember(all) { all.map { it to TranslateLangs.name(it, target = !source) }.sortedBy { it.second } }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) named else named.filter { (c, n) -> n.lowercase().contains(q) || c.contains(q) }
    val recent = Settings.recentTranslateLangs(context).mapNotNull { r ->
        val ml = TranslateLangs.mlkitOf(r) ?: r
        if (ml in all) ml else null
    }.distinct().take(6)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 20.dp)) {
            Text(if (source) "說的語言".tr else "翻譯成".tr, style = MaterialTheme.typography.titleLarge, color = Brand.Text)
            Text(
                if (source) (if (support.queried) "手機語音辨識支援、而且能翻譯的語言：{0} 種".tr else "常見語言（這支手機查不到辨識清單）：{0} 種".tr).replace("{0}", "${all.size}")
                else "手機裝置端翻譯支援 {0} 種；第一次用要下載模型（約 30 MB）".tr.replace("{0}", "${all.size}"),
                style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜尋語言（中文、English、日本語…）".tr, color = Brand.TextFaint) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = Brand.TextMuted) },
                shape = RoundedCornerShape(14.dp), colors = brandFieldColors())
            Spacer(Modifier.height(8.dp))
            if (q.isEmpty() && recent.isNotEmpty()) {
                Text("最近使用".tr, style = MaterialTheme.typography.labelLarge, color = Brand.TextMuted)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in recent) Chip(TranslateLangs.shortName(r, target = !source), r == currentMl) {
                        if (source) onPick(if (r == currentMl) current else TranslateLangs.defaultTag(r, support, Settings.recentTranslateLangs(context))) else onPick(r)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.first }) { (code, name) ->
                    val on = code == currentMl
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (on) Brand.SurfaceHigher else Color.Transparent)
                        .clickable {
                            if (!source) onPick(code)
                            else {
                                val tags = sources[code].orEmpty()
                                if (tags.size <= 1) onPick(tags.firstOrNull() ?: TranslateLangs.defaultTag(code, support, Settings.recentTranslateLangs(context)))
                                else expanded = if (expanded == code) null else code
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.bodyLarge, color = Brand.Text, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (source) {
                                val tags = sources[code].orEmpty()
                                val best = tags.mapNotNull { support.status(it) }.minByOrNull { it.ordinal }
                                RecogBadge(best)
                                if (tags.size > 1) Text("  {0} 種口音 ▾".tr.replace("{0}", "${tags.size}"), style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                            } else if (models?.contains(code) == true) {
                                Text("已下載".tr, style = MaterialTheme.typography.labelSmall, color = Brand.Success)
                            }
                            if (on) { Spacer(Modifier.width(8.dp)); Box(Modifier.size(8.dp).clip(CircleShape).background(Brand.Cyan)) }
                        }
                        // 地区口音（例如 英文：美國、英國、澳洲、印度…）
                        if (source && expanded == code && sources[code].orEmpty().size > 1) {
                            Spacer(Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (tag in sources[code].orEmpty()) {
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .border(if (TranslateLangs.norm(tag) == TranslateLangs.norm(current)) 2.dp else 1.dp,
                                            if (TranslateLangs.norm(tag) == TranslateLangs.norm(current)) Brand.Cyan else Brand.Outline, RoundedCornerShape(10.dp))
                                        .clickable { onPick(tag) }.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text(TranslateLangs.regionName(tag), style = MaterialTheme.typography.bodyMedium, color = Brand.Text, modifier = Modifier.weight(1f))
                                        Text(tag, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                                        Spacer(Modifier.width(8.dp))
                                        RecogBadge(support.status(tag))
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) Brand.Text else Brand.TextMuted,
        modifier = Modifier.clip(shape).background(if (on) Brand.SurfaceHigher else Brand.SurfaceHigh)
            .border(1.dp, if (on) Brand.Cyan else Brand.Outline, shape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp))
}

/** 页面回到前景（例如从系统的「下载离线语音」回来）就重新查辨识清单与翻译模型，标签才会跟着变。 */
@Suppress("DEPRECATION")
@Composable
fun RefreshLangsOnResume() {
    val context = LocalContext.current
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) scope.launch { TranslateLangs.refresh(context); TranslateModels.refresh() }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}
