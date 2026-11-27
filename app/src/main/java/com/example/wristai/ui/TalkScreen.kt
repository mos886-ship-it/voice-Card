package com.example.wristai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import com.example.wristai.LiveCaptions
import com.example.wristai.RemoteTalk
import com.example.wristai.Settings
import com.example.wristai.TalkStore
import com.example.wristai.TranslateLangs
import com.example.wristai.ui.theme.Brand
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 要不要打開遠距對話頁（收到邀請連結、翻譯頁按「遠距對話」）。 */
object TalkPage {
    var open by mutableStateOf(false)
}

private fun langName(code: String) = TranslateLangs.shortName(TranslateLangs.mlkitOf(code) ?: code)

/**
 * 遠距對話：兩個人在不同地方、用各自的手機、說各自的語言。
 * - 沒在通話時：來電（接聽）、未接、通話對象（綠點 = 上線中，可以直接打）、開新對話邀請、貼邀請連結、以前的對話（回播）。
 * - 通話中：自己說的話在右邊，對方說的話翻成自己的語言在左邊；對方每說完一句先播他的原聲、再唸翻譯。
 */
@Composable
fun TalkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by RemoteTalk.state.collectAsState()
    BackHandler(onBack = onBack)
    // 這頁開著就回報上線、看對象誰在線上、誰打來
    DisposableEffect(Unit) { RemoteTalk.startPresence(context); onDispose { } }
    var replaying by remember { mutableStateOf<TalkStore.Talk?>(null) }
    replaying?.let { t -> TalkReplay(t) { RemoteTalk.stopReplay(); replaying = null }; return }

    Column(Modifier.fillMaxSize()) {
        TopBar("遠距對話".tr, onBack)
        when {
            s.busy -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("連線中…".tr, color = Brand.TextMuted) }
            s.connected -> TalkRoom(Modifier.weight(1f))
            else -> TalkHome(Modifier.weight(1f)) { replaying = it }
        }
    }
}

// ---------------- 沒在通話：來電、對象、邀請、記錄 ----------------
@Composable
private fun TalkHome(modifier: Modifier, onReplay: (TalkStore.Talk) -> Unit) {
    val context = LocalContext.current
    val s by RemoteTalk.state.collectAsState()
    val invite by RemoteTalk.pendingInvite.collectAsState()
    val calls by RemoteTalk.calls.collectAsState()
    val contacts by TalkStore.contacts.collectAsState()
    val online by RemoteTalk.online.collectAsState()
    val talks by TalkStore.talks.collectAsState()
    val prefs = remember { context.getSharedPreferences("wristai_settings", 0) }
    var name by remember { mutableStateOf(prefs.getString("talk_name", null) ?: android.os.Build.MODEL.take(20)) }
    fun saveName() = prefs.edit().putString("talk_name", name.trim().ifBlank { android.os.Build.MODEL.take(20) }).apply()
    val myLang = TranslateLangs.shortName(TranslateLangs.mlkitOf(Settings.translateSource(context)) ?: "zh")
    var paste by remember { mutableStateOf("") }

    LazyColumn(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 來電
        val live = calls.filter { it.live }
        items(live, key = { "call" + it.room }) { call ->
            GlassCard(highlight = true) {
                Text("{0} 打來了".trf(call.name), style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text(langName(call.lang), style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("接聽".tr, { saveName(); RemoteTalk.answer(context, call) }, Modifier.weight(1f), tint = Brand.Success)
                    GhostButton("拒接".tr, { RemoteTalk.dismiss(context, call) }, Modifier.weight(1f), tint = Brand.Warning)
                }
            }
        }
        invite?.let { link ->
            item(key = "invite") {
                GlassCard(highlight = true) {
                    Text("收到遠距對話的邀請".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                    Spacer(Modifier.height(8.dp))
                    GhostButton("加入對話".tr, { saveName(); RemoteTalk.pendingInvite.value = null; RemoteTalk.join(context, link) },
                        Modifier.fillMaxWidth(), tint = Brand.Cyan)
                }
            }
        }
        s.error?.let { err ->
            item(key = "err") {
                GlassCard {
                    Text("沒有連上".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Warning)
                    Text(err, style = MaterialTheme.typography.bodyMedium, color = Brand.Text)
                }
            }
        }
        // 通話對象
        if (contacts.isNotEmpty()) {
            item(key = "contactsLabel") { SectionLabel("通話對象".tr) }
            items(contacts, key = { "c" + it.id + it.base }) { k ->
                val on = online["${k.id}@${k.base}"] == true
                ContactRow(k, on, onCall = { saveName(); RemoteTalk.call(context, k) },
                    onHistory = { talks.firstOrNull { t -> k.name in t.partners }?.let(onReplay) },
                    hasHistory = talks.any { t -> k.name in t.partners },
                    onRemove = { TalkStore.removeContact(context, k) })
            }
        }
        // 未接
        val missed = calls.filter { !it.live }
        if (missed.isNotEmpty()) {
            item(key = "missedLabel") { SectionLabel("未接來電".tr) }
            items(missed, key = { "m" + it.room }) { call ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.SurfaceHigh).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(call.name, style = MaterialTheme.typography.titleSmall, color = Brand.Warning)
                        Text(SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(call.at)), style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                    }
                    contacts.firstOrNull { it.id == call.from && it.base == call.base }?.let { k ->
                        GhostButton("回撥".tr, { saveName(); RemoteTalk.dismiss(context, call); RemoteTalk.call(context, k) }, tint = Brand.Cyan)
                        Spacer(Modifier.width(6.dp))
                    }
                    GhostButton("✕", { RemoteTalk.dismiss(context, call) })
                }
            }
        }
        // 開新對話 / 加入
        item(key = "new") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("新的對話".tr)
                Text("兩個人在不同地方、用各自的手機說各自的語言。對方每說完一句，你會先聽到他的原聲，再聽到翻譯。文字和原聲經過你的大D的主機轉送，主機不存檔；對話記錄存在你的手機。建議戴有麥克風的耳機。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                OutlinedTextField(name, { name = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("你的名字（對方會看到）".tr) }, shape = RoundedCornerShape(14.dp), colors = brandFieldColors())
                Text("你說的語言：{0}（在「翻譯」頁的「說的」可以改）".trf(myLang), style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                GhostButton("開新對話，邀請對方".tr, { saveName(); RemoteTalk.create(context) }, Modifier.fillMaxWidth(), tint = Brand.Cyan)
                OutlinedTextField(paste, { paste = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("或貼上對方傳來的邀請連結".tr) }, shape = RoundedCornerShape(14.dp), colors = brandFieldColors())
                if (paste.startsWith("dylan://talk")) GhostButton("用這個連結加入".tr, { saveName(); RemoteTalk.join(context, paste) }, Modifier.fillMaxWidth())
                if (s.ended) Text("對話已結束。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
            }
        }
        // 以前的對話
        if (talks.isNotEmpty()) {
            item(key = "talksLabel") { SectionLabel("以前的對話".tr) }
            items(talks, key = { "t" + it.id }) { t ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.SurfaceHigh).clickable { onReplay(t) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.partners.joinToString("、").ifBlank { "對話".tr }, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                        Text(SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(t.startedAt)) + " · " + "{0} 句".trf(t.lines.size),
                            style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                    }
                    Text("▶", fontSize = 18.sp, color = Brand.Cyan)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ContactRow(k: TalkStore.Contact, online: Boolean, onCall: () -> Unit, onHistory: () -> Unit, hasHistory: Boolean, onRemove: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.SurfaceHigh).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        // 頭像＋上線綠點
        Box {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Brand.SurfaceHigher), contentAlignment = Alignment.Center) {
                Text(k.name.take(1).ifBlank { "?" }, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
            }
            Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(Brand.Bg).padding(2.dp)) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(if (online) Brand.Success else Brand.TextFaint))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable { confirm = true }) {
            Text(k.name, style = MaterialTheme.typography.titleSmall, color = Brand.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text((if (online) "上線中".tr else "離線".tr) + " · " + langName(k.lang), style = MaterialTheme.typography.labelSmall,
                color = if (online) Brand.Success else Brand.TextFaint)
        }
        if (hasHistory) { GhostButton("記錄".tr, onHistory); Spacer(Modifier.width(6.dp)) }
        GhostButton("撥打".tr, onCall, tint = if (online) Brand.Success else Brand.Cyan)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(k.name) },
        text = { Text("要把 {0} 從通話對象移除嗎？以前的對話記錄會留著。".trf(k.name)) },
        confirmButton = { TextButton({ confirm = false; onRemove() }) { Text("移除".tr, color = Brand.Warning) } },
        dismissButton = { TextButton({ confirm = false }) { Text("取消".tr) } })
}

// ---------------- 通話中 ----------------
@Composable
private fun TalkRoom(modifier: Modifier) {
    val context = LocalContext.current
    val s by RemoteTalk.state.collectAsState()
    val live by LiveCaptions.state.collectAsState()
    Column(modifier) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(s.members.joinToString("  ·  ") { m -> (if (m.id == s.me) "你".tr else m.name) + "：" + langName(m.lang) },
                style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
            s.calling?.let { Text("正在打給 {0}，等對方接聽…".trf(it), style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted) }
            s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Warning) }
            val link = s.link
            if (link != null && s.members.size <= 1) {
                Spacer(Modifier.height(8.dp))
                GlassCard {
                    Text("把邀請連結傳給對方（LINE、簡訊…）。對方要先裝大D，點連結就會加入。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("傳送邀請".tr, {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "大D 遠距對話邀請：".tr + link), "傳送邀請".tr).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }, Modifier.weight(1f), tint = Brand.Cyan)
                        GhostButton("複製".tr, {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("talk", link))
                            Toast.makeText(context, "已複製".tr, Toast.LENGTH_SHORT).show()
                        }, Modifier.weight(1f))
                    }
                }
            }
        }
        val list = rememberLazyListState()
        LaunchedEffect(s.lines.size, s.lines.lastOrNull()?.translated, s.lines.lastOrNull()?.original) {
            if (s.lines.isNotEmpty()) list.animateScrollToItem(s.lines.size - 1)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Spacer(Modifier.height(8.dp)) }
            items(s.lines, key = { it.key }) { l -> TalkBubble(l.mine, l.name, l.original, l.translated, l.final, l.system) }
            item { Spacer(Modifier.height(8.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            var orig by remember { mutableStateOf(RemoteTalk.playOriginal(context)) }
            var speak by remember { mutableStateOf(RemoteTalk.speakTranslation(context)) }
            Toggle("對方原聲".tr, orig) { orig = it; RemoteTalk.setPlayOriginal(context, it) }
            Toggle("唸翻譯".tr, speak) { speak = it; RemoteTalk.setSpeakTranslation(context, it) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(if (live.running) Brand.Warning else Brand.Cyan)
                .clickable { RemoteTalk.toggleMic(context) }, contentAlignment = Alignment.Center) {
                Text(if (live.running) "■" else "🎤", fontSize = 24.sp, color = Brand.Bg)
            }
            Text(if (live.running) "正在聽你說話…".tr else "按麥克風開始說話".tr, style = MaterialTheme.typography.titleSmall,
                color = Brand.Text, modifier = Modifier.weight(1f))
            GhostButton("掛斷".tr, { RemoteTalk.leave() }, tint = Brand.Warning)
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Text((if (on) "● " else "○ ") + label, style = MaterialTheme.typography.bodyMedium, color = if (on) Brand.Cyan else Brand.TextMuted,
        modifier = Modifier.clickable { onChange(!on) }.padding(vertical = 6.dp))
}

@Composable
private fun TalkBubble(mine: Boolean, name: String, original: String, translated: String?, final: Boolean, system: String? = null, onPlay: (() -> Unit)? = null) {
    if (system != null) {
        Text(system, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        return
    }
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(Modifier.widthIn(max = 320.dp).clip(shape).background(if (mine) Brand.SurfaceHigher else Brand.SurfaceHigh)
            .then(if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier).padding(12.dp)) {
            if (!mine && name.isNotBlank()) Text(name, style = MaterialTheme.typography.labelSmall, color = Brand.Cyan)
            if (mine) {
                Text(original, style = MaterialTheme.typography.bodyLarge, color = Brand.Text, fontStyle = if (final) FontStyle.Normal else FontStyle.Italic)
                translated?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted) }
            } else {
                Text(translated ?: "…", style = MaterialTheme.typography.titleMedium, color = Brand.Text, fontStyle = if (final) FontStyle.Normal else FontStyle.Italic)
                Text(original, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            }
            if (onPlay != null) Text("▶ " + "播放這句".tr, style = MaterialTheme.typography.labelSmall, color = Brand.Cyan)
        }
    }
}

// ---------------- 回播 ----------------
@Composable
private fun TalkReplay(t: TalkStore.Talk, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar(t.partners.joinToString("、").ifBlank { "對話".tr }, onBack)
        Text(SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(t.startedAt)) + " · " + "{0} 句".trf(t.lines.size),
            style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint, modifier = Modifier.padding(horizontal = 20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("▶ " + "全部播放".tr, { RemoteTalk.replay(context, t) }, Modifier.weight(1f), tint = Brand.Cyan)
            GhostButton("■ " + "停止".tr, { RemoteTalk.stopReplay() }, Modifier.weight(1f))
            GhostButton("刪除".tr, { confirm = true }, tint = Brand.Warning)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(t.lines) { l ->
                TalkBubble(l.mine, l.name, l.text, l.translated, true, onPlay = if (l.audio != null || (!l.mine && l.translated != null)) ({ RemoteTalk.replayLine(context, t, l) }) else null)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("刪除這次對話？".tr) },
        text = { Text("文字和雙方的原聲都會從這支手機刪除。".tr) },
        confirmButton = { TextButton({ confirm = false; RemoteTalk.stopReplay(); TalkStore.delete(context, t.id); onBack() }) { Text("刪除".tr, color = Brand.Warning) } },
        dismissButton = { TextButton({ confirm = false }) { Text("取消".tr) } })
}
