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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import com.example.wristai.LiveCaptions
import com.example.wristai.RemoteTalk
import com.example.wristai.Settings
import com.example.wristai.TranslateLangs
import com.example.wristai.ui.theme.Brand

/** 要不要打开遠距對話页（收到邀请连结、翻译页按「遠距對話」）。 */
object TalkPage {
    var open by mutableStateOf(false)
}

/**
 * 遠距對話：两个人在不同地方、用各自的手机、说各自的语言。
 * 开房（要配对过大D的主机）→ 把邀请连结传给对方 → 对方在大D里打开连结加入。
 * 自己说的话照常辨识（右边泡泡），对方说的话翻成自己的语言（左边泡泡，大字），可以自动朗读（建议戴耳机）。
 */
@Composable
fun TalkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by RemoteTalk.state.collectAsState()
    val live by LiveCaptions.state.collectAsState()
    val invite by RemoteTalk.pendingInvite.collectAsState()
    BackHandler(onBack = onBack)
    val prefs = remember { context.getSharedPreferences("wristai_settings", 0) }
    var name by remember { mutableStateOf(prefs.getString("talk_name", null) ?: android.os.Build.MODEL.take(20)) }
    fun saveName() = prefs.edit().putString("talk_name", name.trim()).apply()
    val myLang = TranslateLangs.shortName(TranslateLangs.mlkitOf(Settings.translateSource(context)) ?: "zh")

    Column(Modifier.fillMaxSize()) {
        TopBar("遠距對話".tr, onBack)
        if (!s.connected && !s.busy) {
            // ---------- 还没进房：开房或加入 ----------
            Column(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("兩個人在不同地方、用各自的手機說各自的語言：你看到的是翻成你的語言，對方看到的是翻成他的語言。文字經過你的大D的主機轉送，不存檔。建議戴有麥克風的耳機。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                OutlinedTextField(name, { name = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("你的名字（對方會看到）".tr) }, shape = RoundedCornerShape(14.dp), colors = brandFieldColors())
                Text("你說的語言：{0}（在「翻譯」頁的「說的」可以改）".trf(myLang), style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                invite?.let { link ->
                    GlassCard(highlight = true) {
                        Text("收到遠距對話的邀請".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                        Spacer(Modifier.height(8.dp))
                        GhostButton("加入對話".tr, { saveName(); RemoteTalk.pendingInvite.value = null; RemoteTalk.join(context, link, name) },
                            Modifier.fillMaxWidth(), tint = Brand.Cyan)
                    }
                }
                GhostButton("開新對話，邀請對方".tr, { saveName(); RemoteTalk.create(context, name) }, Modifier.fillMaxWidth(), tint = Brand.Cyan)
                var paste by remember { mutableStateOf("") }
                OutlinedTextField(paste, { paste = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("或貼上對方傳來的邀請連結".tr) }, shape = RoundedCornerShape(14.dp), colors = brandFieldColors())
                if (paste.startsWith("dylan://talk")) GhostButton("用這個連結加入".tr, { saveName(); RemoteTalk.join(context, paste, name) }, Modifier.fillMaxWidth())
                s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Warning) }
                if (s.ended) Text("對話已結束。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
            }
            return@Column
        }
        if (s.busy) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("連線中…".tr, color = Brand.TextMuted) }
            return@Column
        }
        // ---------- 房间里 ----------
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(s.members.joinToString("  ·  ") { m ->
                (if (m.id == s.me) "你".tr else m.name) + "：" + TranslateLangs.shortName(TranslateLangs.mlkitOf(m.lang) ?: m.lang)
            }, style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
            s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Warning) }
            val link = s.link
            if (link != null && s.members.size <= 1) {
                Spacer(Modifier.height(8.dp))
                GlassCard {
                    Text("把邀請連結傳給對方（LINE、簡訊…）。對方要先裝大D，點連結就會加入。".tr,
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
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
            items(s.lines, key = { it.key }) { l -> TalkBubble(l) }
            item { Spacer(Modifier.height(8.dp)) }
        }
        // ---------- 下排：麦克风、朗读、离开 ----------
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(if (live.running) Brand.Warning else Brand.Cyan)
                .clickable { RemoteTalk.toggleMic(context) }, contentAlignment = Alignment.Center) {
                Text(if (live.running) "■" else "🎤", fontSize = 24.sp, color = Brand.Bg)
            }
            Column(Modifier.weight(1f)) {
                Text(if (live.running) "正在聽你說話…".tr else "按麥克風開始說話".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                var speak by remember { mutableStateOf(Settings.autoSpeak(context)) }
                Text((if (speak) "● " else "○ ") + "朗讀對方的話".tr, style = MaterialTheme.typography.bodySmall,
                    color = if (speak) Brand.Cyan else Brand.TextMuted,
                    modifier = Modifier.clickable { speak = !speak; Settings.setAutoSpeak(context, speak) }.padding(vertical = 4.dp))
            }
            GhostButton("離開".tr, { RemoteTalk.leave() })
        }
    }
}

@Composable
private fun TalkBubble(l: RemoteTalk.Line) {
    if (l.system != null) {
        Text(l.system, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        return
    }
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (l.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(Modifier.widthIn(max = 320.dp).clip(shape).background(if (l.mine) Brand.SurfaceHigher else Brand.SurfaceHigh).padding(12.dp)) {
            if (!l.mine) Text(l.name, style = MaterialTheme.typography.labelSmall, color = Brand.Cyan)
            if (l.mine) {
                Text(l.original, style = MaterialTheme.typography.bodyLarge, color = Brand.Text, fontStyle = if (l.final) FontStyle.Normal else FontStyle.Italic)
                l.translated?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted) }
            } else {
                Text(l.translated ?: "…", style = MaterialTheme.typography.titleMedium, color = Brand.Text,
                    fontStyle = if (l.final) FontStyle.Normal else FontStyle.Italic)
                Text(l.original, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            }
        }
    }
}
