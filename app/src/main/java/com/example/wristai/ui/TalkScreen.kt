package com.example.wristai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import com.example.wristai.LiveCaptions
import com.example.wristai.RemoteTalk
import com.example.wristai.Settings
import com.example.wristai.TalkFriends
import com.example.wristai.TalkAvatars
import androidx.compose.ui.graphics.asImageBitmap
import com.example.wristai.TalkStore
import com.example.wristai.TranslateLangs
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 打開遠距對話頁（獨立的一頁：主頁的電話圖示、翻譯頁、邀請連結、來電）。 */
object TalkPage {
    var open by mutableStateOf(false)
}

private fun langName(code: String) = TranslateLangs.shortName(TranslateLangs.mlkitOf(code) ?: code)

/** 頭像顏色：照名字固定一個顏色。 */
@Composable
private fun avatarColor(name: String): Color {
    val palette = listOf(Brand.Cyan, Brand.Violet, Brand.Success, Brand.Warning, Brand.Recording)
    return palette[(name.hashCode() and 0x7fffffff) % palette.size]
}

@Composable
private fun Avatar(name: String, size: Dp, online: Boolean? = null, photo: (() -> java.io.File?)? = null) {
    val c = avatarColor(name)
    // 頭貼照片（有的話）；換了頭貼 [TalkAvatars.version] 會變，重新讀
    val ver by TalkAvatars.version.collectAsState()
    val file = remember(ver, name) { photo?.invoke() }
    val bmp = remember(file?.path, file?.lastModified(), ver) {
        file?.takeIf { it.exists() }?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() }
    }
    Box {
        Box(Modifier.size(size).clip(CircleShape).background(c.copy(alpha = 0.18f)).border(1.dp, c.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center) {
            if (bmp != null) androidx.compose.foundation.Image(bmp, name, Modifier.size(size).clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Text(name.trim().take(1).uppercase().ifBlank { "?" }, color = c, fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp)
        }
        if (online != null) Box(Modifier.align(Alignment.BottomEnd).size(size * 0.3f).clip(CircleShape).background(Brand.Bg).padding(2.dp)) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(if (online) Brand.Success else Brand.TextFaint))
        }
    }
}

private const val CALL = "call"
private const val HANG_UP = "hangup"

/** 圓形按鈕（通話、接聽、掛斷、麥克風）。 */
@Composable
private fun RoundAction(glyph: String, label: String?, color: Color, size: Dp = 64.dp, filled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(CircleShape)
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = if (filled) 0f else 0.5f), CircleShape)
            .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            val tint = if (filled) Brand.Bg else color
            when (glyph) {
                // 電話圖示（彩色 emoji 在深色底上看不清楚）：撥打 / 接聽；掛斷 / 拒接 = 轉 135°
                CALL -> androidx.compose.material3.Icon(Icons.Filled.Call, null, tint = tint, modifier = Modifier.size(size * 0.45f))
                HANG_UP -> androidx.compose.material3.Icon(Icons.Filled.Call, null, tint = tint,
                    modifier = Modifier.size(size * 0.45f).rotate(135f))
                else -> Text(glyph, fontSize = (size.value * 0.4f).sp, color = tint)
            }
        }
        if (label != null) {
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Brand.TextMuted)
        }
    }
}

/**
 * 遠距對話（獨立一頁）：兩個人在不同地方、用各自的手機、說各自的語言。
 * - 上面：我的 ID（點了分享）。
 * - 分頁：好友（邀請、上線中、其他好友、加好友、邀請連結）｜最近（未接、以前的對話）｜我的（ID、名字、語言、播放設定）。
 * - 來電：整頁的來電畫面（接聽 / 拒接）。通話中：對方頭像、計時、對話泡泡、下方三顆圓鈕（原聲、麥克風、掛斷）。
 */
@Composable
fun TalkScreen(onBack: () -> Unit, asTab: Boolean = false) {
    val context = LocalContext.current
    val s by RemoteTalk.state.collectAsState()
    val calls by RemoteTalk.calls.collectAsState()
    // 通話中按返回：留在「對話」（回到好友清單，通話繼續，上面有「回到通話」）；不是離開這一頁
    var minimized by remember { mutableStateOf(false) }
    LaunchedEffect(s.connected, s.room) { if (!s.connected) minimized = false }
    BackHandler { if (s.connected && !minimized) minimized = true else onBack() }
    DisposableEffect(Unit) { RemoteTalk.startPresence(context); onDispose { } }
    var replaying by remember { mutableStateOf<TalkStore.Talk?>(null) }
    replaying?.let { t -> TalkReplay(t) { RemoteTalk.stopReplay(); replaying = null }; return }

    val ringing = calls.firstOrNull { it.live }
    when {
        s.connected && !minimized -> CallScreen(onMinimize = { minimized = true })
        ringing != null && !s.busy && !s.connected -> IncomingCall(ringing)
        else -> TalkHome(onBack, showBack = !asTab, onResumeCall = if (s.connected) ({ minimized = false }) else null) { replaying = it }
    }
}

// ---------------- 首頁：我的 ID＋好友｜最近｜我的 ----------------
@Composable
private fun TalkHome(onBack: () -> Unit, showBack: Boolean, onResumeCall: (() -> Unit)? = null, onReplay: (TalkStore.Talk) -> Unit) {
    val context = LocalContext.current
    val s by RemoteTalk.state.collectAsState()
    val myUid by TalkFriends.myUid.collectAsState()
    val requests by TalkFriends.requests.collectAsState()
    val calls by RemoteTalk.calls.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(Unit) { TalkFriends.loadCached(context) }
    val missed = calls.count { !it.live }

    Column(Modifier.fillMaxSize()) {
        // ---------- 通話還在進行：綠色橫條，點了回到通話 ----------
        if (onResumeCall != null) {
            val who = s.members.filter { it.id != s.me }.joinToString("、") { it.name }.ifBlank { s.calling.orEmpty() }
            Row(Modifier.fillMaxWidth().background(Brand.Success.copy(alpha = 0.18f)).clickable(onClick = onResumeCall)
                .padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Brand.Success))
                Spacer(Modifier.width(10.dp))
                Text(("通話中".tr + (if (who.isNotBlank()) "：$who" else "")), style = MaterialTheme.typography.labelLarge, color = Brand.Text,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("回到通話".tr + " →", style = MaterialTheme.typography.labelLarge, color = Brand.Success)
            }
        }
        // ---------- 頁首 ----------
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // 底部分頁的「對話」：不用返回鍵
            if (showBack) { IconCircle(Icons.AutoMirrored.Filled.ArrowBack, "返回".tr, onBack); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text("遠距對話".tr, style = MaterialTheme.typography.headlineSmall, color = Brand.Text)
                Text("各說各的語言，即時翻譯".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            }
            // 我的 ID：點了分享
            myUid?.let { uid ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Brand.SurfaceHigh).border(1.dp, Brand.Outline, RoundedCornerShape(12.dp))
                    .clickable { shareId(context, uid) }.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.End) {
                    Text("我的 ID".tr, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                    Text(uid, style = MaterialTheme.typography.labelLarge, color = Brand.Cyan, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.padding(horizontal = 20.dp)) {
            Segmented(listOf(
                "好友".tr + (if (requests.isNotEmpty()) " · ${requests.size}" else ""),
                "最近".tr + (if (missed > 0) " · $missed" else ""),
                "我的".tr,
            ), tab) { tab = it }
        }
        s.error?.let { err ->
            Text(err, style = MaterialTheme.typography.bodySmall, color = Brand.Warning,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        when (tab) {
            0 -> FriendsTab(Modifier.weight(1f))
            1 -> RecentTab(Modifier.weight(1f), onReplay)
            else -> MeTab(Modifier.weight(1f))
        }
    }
}

private fun shareId(context: android.content.Context, uid: String) {
    val text = "我在大D的遠距對話 ID：{0}（在大D → 翻譯 → 遠距對話 →「加好友」輸入）".trf(uid)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享我的 ID".tr)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun myName(context: android.content.Context) =
    context.getSharedPreferences("wristai_settings", 0).getString("talk_name", null) ?: android.os.Build.MODEL.take(20)

// ---------------- 好友 ----------------
@Composable
private fun FriendsTab(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts by TalkStore.contacts.collectAsState()
    val online by RemoteTalk.online.collectAsState()
    val pending by TalkFriends.pending.collectAsState()
    val requests by TalkFriends.requests.collectAsState()
    val invite by RemoteTalk.pendingInvite.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var linkMenu by remember { mutableStateOf(false) }
    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_LONG).show()
    fun key(k: TalkStore.Contact) = "${k.id}@${k.base}"
    val (on, off) = contacts.partition { online[key(it)] == true }

    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Spacer(Modifier.height(4.dp)) }
            // 收到的邀請連結
            invite?.let { link ->
                item(key = "invite") {
                    GlassCard(highlight = true) {
                        Text("收到遠距對話的邀請".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                        Spacer(Modifier.height(8.dp))
                        GhostButton("加入對話".tr, { RemoteTalk.pendingInvite.value = null; RemoteTalk.join(context, link) },
                            Modifier.fillMaxWidth(), tint = Brand.Cyan)
                    }
                }
            }
            // 好友邀請
            items(requests, key = { "req" + it.id + it.base }) { r ->
                GlassCard(highlight = true, contentPadding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(r.name, 44.dp, photo = { TalkAvatars.file(context, r.base, r.id) })
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("{0} 想加你為好友".trf(r.name), style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                            Text(listOf(langName(r.lang), r.uid).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = Brand.TextMuted)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("接受".tr, { scope.launch { runCatching { TalkFriends.answer(context, r, true) }.onFailure { toast(it.message ?: "") } } },
                            Modifier.weight(1f), tint = Brand.Success)
                        GhostButton("拒絕".tr, { scope.launch { runCatching { TalkFriends.answer(context, r, false) }.onFailure { toast(it.message ?: "") } } },
                            Modifier.weight(1f), tint = Brand.Warning)
                    }
                }
            }
            if (contacts.isEmpty()) item(key = "empty") {
                GlassCard {
                    Text("還沒有好友".tr, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                    Spacer(Modifier.height(4.dp))
                    Text("把你的 ID 給對方，或輸入對方的 ID 加好友。對方接受後，就能像打電話一樣直接撥打，各說各的語言。".tr,
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                }
            }
            if (on.isNotEmpty()) {
                item(key = "onLabel") { SectionLabel("上線中 · {0}".trf(on.size)) }
                items(on, key = { "on" + key(it) }) { k ->
                    FriendRow(k, online = true, pending = false, onCall = { RemoteTalk.call(context, k) },
                        onRemove = { scope.launch { TalkFriends.remove(context, k) } })
                }
            }
            if (off.isNotEmpty()) {
                item(key = "offLabel") { SectionLabel(if (on.isEmpty()) "好友 · {0}".trf(off.size) else "其他好友 · {0}".trf(off.size)) }
                items(off, key = { "off" + key(it) }) { k ->
                    val waiting = key(k) in pending
                    FriendRow(k, online = false, pending = waiting,
                        onCall = { if (waiting) toast("{0} 還沒接受好友邀請".trf(k.name)) else RemoteTalk.call(context, k) },
                        onRemove = { scope.launch { TalkFriends.remove(context, k) } })
                }
            }
            item(key = "linkHint") {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("對方還沒有 ID？".tr + " ", style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
                    Text("用邀請連結開一場對話 →".tr, style = MaterialTheme.typography.bodySmall, color = Brand.Cyan,
                        modifier = Modifier.clickable { linkMenu = true }.padding(vertical = 4.dp))
                }
            }
            item { Spacer(Modifier.height(96.dp)) }   // 讓最後一列不被下面的按鈕擋住
        }
        // ---------- 下方固定：加好友 ----------
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brand.Bg.copy(alpha = 0.92f)).padding(horizontal = 20.dp, vertical = 14.dp)) {
            GradientButton("＋ " + "加好友".tr, { adding = true }, Modifier.fillMaxWidth())
        }
    }
    if (adding) AddFriendDialog { adding = false }
    if (linkMenu) InviteLinkDialog { linkMenu = false }
}

@Composable
private fun FriendRow(k: TalkStore.Contact, online: Boolean, pending: Boolean, onCall: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.Surface).border(1.dp, Brand.Outline, RoundedCornerShape(16.dp))
        .clickable { menu = true }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val ctx = LocalContext.current
        Avatar(k.name, 46.dp, online, photo = { TalkAvatars.file(ctx, k.base, k.id) })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(k.name, style = MaterialTheme.typography.titleMedium, color = Brand.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when {
                pending -> "等待對方接受".tr
                online -> "上線中".tr + " · " + langName(k.lang)
                else -> langName(k.lang) + (k.uid?.let { " · $it" } ?: "")
            }, style = MaterialTheme.typography.labelMedium, color = when { pending -> Brand.Warning; online -> Brand.Success; else -> Brand.TextFaint })
        }
        if (!pending) RoundAction(CALL, null, if (online) Brand.Success else Brand.Cyan, size = 44.dp, filled = online, onClick = onCall)
    }
    if (menu) AlertDialog(onDismissRequest = { menu = false },
        title = { Text(k.name) },
        text = {
            Column {
                Text(listOfNotNull(langName(k.lang), k.uid?.let { "ID $it" }).joinToString(" · "), color = Brand.TextMuted)
                Spacer(Modifier.height(8.dp))
                Text("移除後雙方都不能再互相撥打（要再加好友）；以前的對話記錄會留著。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
            }
        },
        confirmButton = { if (!pending) TextButton({ menu = false; onCall() }) { Text("撥打".tr) } },
        dismissButton = { TextButton({ menu = false; onRemove() }) { Text("移除好友".tr, color = Brand.Warning) } })
}

@Composable
private fun AddFriendDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uid by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("加好友".tr) },
        text = {
            Column {
                Text("輸入對方的 ID（對方在遠距對話頁最上面看得到）。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Spacer(Modifier.height(8.dp))
                // 英文鍵盤、全大寫（注音、倉頡鍵盤打不出 ID）
                OutlinedTextField(uid, { uid = it.uppercase().take(12) }, Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii, autoCorrectEnabled = false),
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("K7MX-29QP", color = Brand.TextFaint, fontFamily = FontFamily.Monospace) },
                    shape = RoundedCornerShape(14.dp), colors = brandFieldColors())
            }
        },
        confirmButton = {
            TextButton({
                busy = true
                scope.launch {
                    runCatching { TalkFriends.add(context, uid, myName(context), Settings.translateSource(context)) }
                        .onSuccess { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); onDismiss() }
                        .onFailure { Toast.makeText(context, it.message ?: "失敗".tr, Toast.LENGTH_LONG).show() }
                    busy = false
                }
            }, enabled = !busy && uid.count { it.isLetterOrDigit() } >= 8) { Text(if (busy) "…" else "加好友".tr) }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消".tr) } })
}

/** 沒有 ID 的人：開一場對話傳邀請連結，或貼上別人傳來的連結。 */
@Composable
private fun InviteLinkDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var paste by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("用邀請連結".tr) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("開一場對話，把連結傳給對方（LINE、簡訊…）；對方裝了大D，點連結就會加入。對話過一次，之後就能用 ID 加好友。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                GhostButton("開新對話，傳邀請連結".tr, { onDismiss(); RemoteTalk.create(context) }, Modifier.fillMaxWidth(), tint = Brand.Cyan)
                OutlinedTextField(paste, { paste = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("或貼上對方傳來的邀請連結".tr) }, shape = RoundedCornerShape(14.dp), colors = brandFieldColors())
            }
        },
        confirmButton = { TextButton({ onDismiss(); RemoteTalk.join(context, paste) }, enabled = paste.trim().startsWith("dylan://talk")) { Text("加入".tr) } },
        dismissButton = { TextButton(onDismiss) { Text("取消".tr) } })
}

// ---------------- 最近：未接來電＋以前的對話 ----------------
@Composable
private fun RecentTab(modifier: Modifier, onReplay: (TalkStore.Talk) -> Unit) {
    val context = LocalContext.current
    val calls by RemoteTalk.calls.collectAsState()
    val contacts by TalkStore.contacts.collectAsState()
    val talks by TalkStore.talks.collectAsState()
    val missed = calls.filter { !it.live }
    val dayFmt = remember { SimpleDateFormat("M/d EEEE", com.example.voiceai.shared.I18n.locale) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.US) }
    LazyColumn(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        if (missed.isEmpty() && talks.isEmpty()) item {
            EmptyNote("還沒有通話記錄。每次通話的文字和雙方的原聲都存在這支手機，可以回播。".tr)
        }
        if (missed.isNotEmpty()) {
            item { SectionLabel("未接來電".tr) }
            items(missed, key = { "m" + it.room }) { call ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.Surface).border(1.dp, Brand.Outline, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(call.name, 42.dp, photo = { TalkAvatars.file(context, call.base, call.from) })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(call.name, style = MaterialTheme.typography.titleSmall, color = Brand.Warning)
                        Text("未接 · ".tr + timeFmt.format(Date(call.at)), style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                    }
                    contacts.firstOrNull { it.id == call.from && it.base == call.base }?.let { k ->
                        RoundAction(CALL, null, Brand.Cyan, size = 40.dp, filled = false) { RemoteTalk.dismiss(context, call); RemoteTalk.call(context, k) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("✕", color = Brand.TextFaint, modifier = Modifier.clickable { RemoteTalk.dismiss(context, call) }.padding(8.dp))
                }
            }
        }
        val byDay = talks.groupBy { dayFmt.format(Date(it.startedAt)) }
        for ((day, list) in byDay) {
            item(key = "d$day") { SectionLabel(day) }
            items(list, key = { "t" + it.id }) { t ->
                val who = t.partners.joinToString("、").ifBlank { "對話".tr }
                val mins = ((t.endedAt.takeIf { it > 0 } ?: t.lines.lastOrNull()?.at ?: t.startedAt) - t.startedAt).coerceAtLeast(0) / 60_000
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.Surface).border(1.dp, Brand.Outline, RoundedCornerShape(16.dp))
                    .clickable { onReplay(t) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 以前的對話：照名字找好友的頭貼
                    val k = contacts.firstOrNull { it.name == t.partners.firstOrNull() }
                    Avatar(who, 42.dp, photo = { k?.let { TalkAvatars.file(context, it.base, it.id) } })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(who, style = MaterialTheme.typography.titleSmall, color = Brand.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(timeFmt.format(Date(t.startedAt)) + " · " + "{0} 句".trf(t.lines.size) + (if (mins > 0) " · " + "{0} 分鐘".trf(mins) else ""),
                            style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                        t.lines.lastOrNull { !it.mine }?.let { l ->
                            Text(l.translated ?: l.text, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text("▶", fontSize = 18.sp, color = Brand.Cyan)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------- 我的：ID、名字、語言、播放 ----------------
@Composable
private fun MeTab(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val myUid by TalkFriends.myUid.collectAsState()
    val prefs = remember { context.getSharedPreferences("wristai_settings", 0) }
    var name by remember { mutableStateOf(myName(context)) }
    var busy by remember { mutableStateOf(false) }
    var orig by remember { mutableStateOf(RemoteTalk.playOriginal(context)) }
    var speak by remember { mutableStateOf(RemoteTalk.speakTranslation(context)) }
    var avatarMenu by remember { mutableStateOf(false) }
    fun saveAvatar(bytes: ByteArray?) {
        scope.launch {
            val err = TalkAvatars.setMine(context, bytes)
            Toast.makeText(context, err ?: (if (bytes == null) "已移除頭貼".tr else "頭貼已更新".tr), Toast.LENGTH_SHORT).show()
        }
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { TalkAvatars.square(context, uri) }
            if (bytes == null) Toast.makeText(context, "讀不到照片".tr, Toast.LENGTH_SHORT).show() else saveAvatar(bytes)
        }
    }
    if (avatarMenu) AlertDialog(onDismissRequest = { avatarMenu = false },
        title = { Text("頭貼".tr) },
        text = { Text("選一張照片，會裁成圓形；好友的清單和來電畫面都看得到。".tr, color = Brand.TextMuted) },
        confirmButton = {
            TextButton({
                avatarMenu = false
                picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("選照片".tr) }
        },
        dismissButton = { if (TalkAvatars.mine(context) != null) TextButton({ avatarMenu = false; saveAvatar(null) }) { Text("移除頭貼".tr, color = Brand.Warning) } })
    LazyColumn(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            GlassCard(highlight = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 頭貼：點了換照片 / 移除（朋友的好友清單、來電畫面都看得到）
                    Box(Modifier.clickable { avatarMenu = true }) {
                        Avatar(name, 64.dp, photo = { TalkAvatars.mine(context) })
                        Box(Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(Brand.Cyan), contentAlignment = Alignment.Center) {
                            Text("✎", fontSize = 12.sp, color = Brand.Bg)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleLarge, color = Brand.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("說 {0}".trf(langName(Settings.translateSource(context))), style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("我的 ID".tr, style = MaterialTheme.typography.labelMedium, color = Brand.TextMuted)
                Text(myUid ?: "—", style = MaterialTheme.typography.displaySmall, color = Brand.Text, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                val uid = myUid
                if (uid != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("分享".tr, { shareId(context, uid) }, Modifier.weight(1f), tint = Brand.Cyan)
                    GhostButton("複製".tr, {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("id", uid))
                        Toast.makeText(context, "已複製".tr, Toast.LENGTH_SHORT).show()
                    }, Modifier.weight(1f))
                } else GhostButton(if (busy) "…" else "取得我的 ID".tr, {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            runCatching { TalkFriends.register(context, name.trim(), Settings.translateSource(context)) }
                                .onFailure { Toast.makeText(context, it.message ?: "失敗".tr, Toast.LENGTH_LONG).show() }
                            busy = false
                        }
                    }
                }, Modifier.fillMaxWidth(), tint = Brand.Cyan)
                Text("朋友輸入這個 ID 就能加你；你接受後才能互相撥打。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            GlassCard {
                OutlinedTextField(name, { name = it.take(30); prefs.edit().putString("talk_name", it.trim().take(30).ifBlank { android.os.Build.MODEL.take(20) }).apply() },
                    Modifier.fillMaxWidth(), singleLine = true, label = { Text("你的名字（對方會看到）".tr) },
                    shape = RoundedCornerShape(14.dp), colors = brandFieldColors())
                Spacer(Modifier.height(8.dp))
                // 我說的語言：點了換（跟「翻譯 → 說的」是同一個設定）
                var lang by remember { mutableStateOf(Settings.translateSource(context)) }
                var picking by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.SurfaceHigh).clickable { picking = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("我說的語言".tr, style = MaterialTheme.typography.labelMedium, color = Brand.TextMuted)
                        Text(TranslateLangs.name(lang) + "（" + TranslateLangs.regionName(lang) + "）", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                    }
                    Text("更改 ▾".tr, style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
                }
                Text("對方說的話也會翻成這個語言；通話中點上方的語言也可以換。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint,
                    modifier = Modifier.padding(top = 6.dp))
                if (picking) TranslateLangSheet(source = true, current = lang, onDismiss = { picking = false }) { code ->
                    picking = false; lang = code; RemoteTalk.setMyLanguage(context, code)
                }
            }
        }
        item {
            GlassCard {
                Text("通話時".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                Spacer(Modifier.height(6.dp))
                ToggleRow("先播對方的原聲".tr, "聽得到語氣，再聽翻譯".tr, orig) { orig = it; RemoteTalk.setPlayOriginal(context, it) }
                ToggleRow("唸出翻譯".tr, "關掉就只看字幕".tr, speak) { speak = it; RemoteTalk.setSpeakTranslation(context, it) }
            }
        }
        item {
            Text("文字和原聲經過你的大D的主機轉送，主機不存對話內容；對話記錄只存在你的手機。建議戴有麥克風的耳機。".tr,
                style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Brand.Text)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Brand.Bg, checkedTrackColor = Brand.Cyan, uncheckedTrackColor = Brand.SurfaceHigh))
    }
}

// ---------------- 來電 ----------------
@Composable
private fun IncomingCall(call: RemoteTalk.Call) {
    val context = LocalContext.current
    // 來電鈴聲＋震動（照系統的靜音 / 震動模式）；畫面離開就停
    DisposableEffect(call.room) { com.example.wristai.TalkRing.startRinging(context); onDispose { com.example.wristai.TalkRing.stop() } }
    val t = rememberInfiniteTransition(label = "ring")
    val pulse by t.animateFloat(1f, 1.15f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.6f))
        Text("遠距對話來電".tr, style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
        Spacer(Modifier.height(28.dp))
        Box(Modifier.scale(pulse)) { Avatar(call.name, 120.dp, photo = { TalkAvatars.file(context, call.base, call.from) }) }
        Spacer(Modifier.height(24.dp))
        Text(call.name, style = MaterialTheme.typography.headlineMedium, color = Brand.Text)
        Spacer(Modifier.height(6.dp))
        Text(langName(call.lang) + "  ⇄  " + langName(Settings.translateSource(context)), style = MaterialTheme.typography.bodyLarge, color = Brand.TextMuted)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundAction(HANG_UP, "拒接".tr, Brand.Error, size = 72.dp) { RemoteTalk.dismiss(context, call) }
            RoundAction(CALL, "接聽".tr, Brand.Success, size = 72.dp) { RemoteTalk.answer(context, call) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ---------------- 通話中 ----------------
@Composable
private fun CallScreen(onMinimize: () -> Unit) {
    val context = LocalContext.current
    val s by RemoteTalk.state.collectAsState()
    val live by LiveCaptions.state.collectAsState()
    val others = s.members.filter { it.id != s.me }
    val partner = others.joinToString("、") { it.name }.ifBlank { s.calling.orEmpty() }
    // 計時：對方進來才開始
    var since by remember { mutableLongStateOf(0L) }
    LaunchedEffect(others.isNotEmpty()) { if (others.isNotEmpty() && since == 0L) since = System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val sec = if (since == 0L) 0 else ((now - since) / 1000).toInt()
    var orig by remember { mutableStateOf(RemoteTalk.playOriginal(context)) }
    var myLang by remember { mutableStateOf(Settings.translateSource(context)) }
    var picking by remember { mutableStateOf(false) }
    // 撥出去、對方還沒接：回鈴音
    val dialing = others.isEmpty() && s.calling != null
    DisposableEffect(dialing) {
        if (dialing) com.example.wristai.TalkRing.startRingback()
        onDispose { com.example.wristai.TalkRing.stop() }
    }
    if (picking) TranslateLangSheet(source = true, current = myLang, onDismiss = { picking = false }) { code ->
        picking = false; myLang = code; RemoteTalk.setMyLanguage(context, code)
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- 上方：對方 ----------
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Icons.AutoMirrored.Filled.ArrowBack, "返回".tr, onMinimize)
            Spacer(Modifier.width(12.dp))
            val contacts by TalkStore.contacts.collectAsState()
            val pk = others.firstOrNull()?.contact?.let { id -> contacts.firstOrNull { it.id == id } }
                ?: contacts.firstOrNull { it.name == partner }
            Avatar(partner.ifBlank { "?" }, 44.dp, online = others.isNotEmpty(), photo = { pk?.let { TalkAvatars.file(context, it.base, it.id) } })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(partner.ifBlank { "等對方加入…".tr }, style = MaterialTheme.typography.titleMedium, color = Brand.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    others.isEmpty() && s.calling != null -> "撥號中…".tr
                    others.isEmpty() -> "等對方加入…".tr
                    else -> "%02d:%02d".format(sec / 60, sec % 60) + " · " + (others.firstOrNull()?.let { langName(it.lang) } ?: "")
                }, style = MaterialTheme.typography.labelMedium, color = if (others.isEmpty()) Brand.Warning else Brand.Success)
            }
            // 我說的語言：點了換（通話中也可以）
            Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Brand.SurfaceHigh).border(1.dp, Brand.Outline, RoundedCornerShape(12.dp))
                .clickable { picking = true }.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.End) {
                Text("我說".tr, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                Text(langName(myLang) + " ▾", style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
            }
        }
        s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Warning, modifier = Modifier.padding(horizontal = 20.dp)) }
        // 邀請連結（自己開的房、還沒人進來）
        val link = s.link
        if (link != null && others.isEmpty()) {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
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
        // ---------- 中間：對話 ----------
        val list = rememberLazyListState()
        LaunchedEffect(s.lines.size, s.lines.lastOrNull()?.translated, s.lines.lastOrNull()?.original) {
            if (s.lines.isNotEmpty()) list.animateScrollToItem(s.lines.size - 1)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (s.lines.none { it.system == null }) Text(
                if (others.isEmpty()) "對方接聽後就可以開始說話。".tr else "按下面的麥克風開始說話；對方說的話會翻成你的語言。".tr,
                style = MaterialTheme.typography.bodyMedium, color = Brand.TextFaint, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp))
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(8.dp)) }
                items(s.lines, key = { it.key }) { l -> TalkBubble(l.mine, l.name, l.original, l.translated, l.final, l.system) }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
        // ---------- 下方：原聲｜麥克風｜掛斷 ----------
        Text(if (live.running) "正在聽你說話…".tr else "麥克風關著".tr, style = MaterialTheme.typography.labelMedium,
            color = if (live.running) Brand.Success else Brand.TextFaint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            // 原聲：外框樣式的開關（不要跟紅色的掛斷混在一起）
            RoundAction("♪", if (orig) "原聲 開".tr else "原聲 關".tr, if (orig) Brand.Cyan else Brand.TextFaint, size = 56.dp, filled = false) {
                orig = !orig; RemoteTalk.setPlayOriginal(context, orig)
            }
            RoundAction(if (live.running) "■" else "🎤", if (live.running) "停止".tr else "說話".tr,
                if (live.running) Brand.Warning else Brand.Cyan, size = 76.dp) { RemoteTalk.toggleMic(context) }
            RoundAction(HANG_UP, "掛斷".tr, Brand.Error, size = 56.dp) { RemoteTalk.leave() }
        }
    }
}

@Composable
private fun TalkBubble(mine: Boolean, name: String, original: String, translated: String?, final: Boolean, system: String? = null, onPlay: (() -> Unit)? = null) {
    if (system != null) {
        Text(system, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        return
    }
    val shape = if (mine) RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp) else RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(Modifier.widthIn(max = 320.dp).clip(shape).background(if (mine) Brand.Cyan.copy(alpha = 0.16f) else Brand.SurfaceHigh)
            .then(if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier).padding(12.dp)) {
            if (!mine && name.isNotBlank()) Text(name, style = MaterialTheme.typography.labelSmall, color = avatarColor(name))
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

/** 主頁右上的電話圖示：有來電、未接或好友邀請時加紅色數字。 */
@Composable
fun TalkEntryButton(onClick: () -> Unit) {
    val calls by RemoteTalk.calls.collectAsState()
    val requests by TalkFriends.requests.collectAsState()
    val badge = calls.size + requests.size
    Box {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Brand.Surface).border(1.dp, Brand.Outline, CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(Icons.Filled.Call, "遠距對話".tr, tint = Brand.Cyan, modifier = Modifier.size(20.dp))
        }
        if (badge > 0) Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Brand.Error), contentAlignment = Alignment.Center) {
            Text("$badge", color = Color.White, fontSize = 10.sp)
        }
    }
}
