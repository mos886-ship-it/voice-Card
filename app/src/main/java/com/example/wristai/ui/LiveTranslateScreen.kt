package com.example.wristai.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.example.voiceai.shared.I18n
import com.example.voiceai.shared.tr
import com.example.wristai.LiveCaptions
import com.example.wristai.PhoneRecorder
import com.example.wristai.PhoneRecordingService
import com.example.wristai.SpeechLang
import com.example.wristai.ui.theme.Brand

/** 别的画面要打开即时翻译页（主页的「翻譯」、录音中的「即時翻譯」）。 */
object TranslatePage {
    var requested by mutableStateOf(false)
    fun open() { requested = true }
}

/**
 * 即时翻译页：用手机录音，边录边显示原文与译文（手机内建辨识＋ML Kit 装置端翻译，不上传）。
 * 录音照常存成一笔记录、录完照常产生完整报告；离开这页字幕继续跑，回来还看得到。
 */
@Composable
fun LiveTranslateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by LiveCaptions.state.collectAsState()
    val active by PhoneRecorder.active.collectAsState()
    val recording = active != null && !com.example.wristai.FoodLog.isFood(active!!.meetingId)
    val source = SpeechLang.current
    var target by remember { mutableStateOf(if (s.running) s.target else LiveCaptions.defaultTarget(source, I18n.lang)) }
    val starter = rememberRecordStarter()
    val supported = LiveCaptions.supported(source)

    fun startCaptions() = LiveCaptions.start(context, source, target)

    Column(Modifier.fillMaxSize()) {
        TopBar("即時翻譯".tr, onBack)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 说的语言（跟录音的辨识语言同一个设定）
            Text("說的語言".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            LangPicker(source, enabled = !s.running) { SpeechLang.set(context, it) }
            Text("翻譯成".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
            val targets = LiveCaptions.Target.entries
            Segmented(targets.map { it.label }, targets.indexOf(target)) { i ->
                target = targets[i]
                if (s.running) LiveCaptions.start(context, source, target)   // 换语言：重新开始
            }
            when {
                !supported -> Text("台語、客語、粵語手機辨識不了，沒有即時字幕；錄完的報告不受影響。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.Warning)
                s.running -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("停止翻譯".tr, { LiveCaptions.stop() }, Modifier.weight(1f))
                    if (recording) GradientButton("停止錄音".tr, { PhoneRecordingService.stop(context) }, Modifier.weight(1f))
                }
                recording -> GradientButton("開始翻譯（目前的錄音）".tr, { startCaptions() }, Modifier.fillMaxWidth())
                else -> GradientButton("開始錄音並翻譯".tr, { starter(false); startCaptions() }, Modifier.fillMaxWidth(), recordDot = true)
            }
            (s.error ?: s.status)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (s.error != null) Brand.Warning else Brand.TextMuted)
            }
        }
        Spacer(Modifier.height(10.dp))

        // 字幕：新的在下面，自动捲到最新
        val list = rememberLazyListState()
        LaunchedEffect(s.lines.size, s.lines.lastOrNull()?.translated, s.lines.lastOrNull()?.original) {
            if (s.lines.isNotEmpty()) list.animateScrollToItem(s.lines.size - 1)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(Brand.CardRadius)).background(Brand.Surface),
        ) {
            if (s.lines.isEmpty()) {
                Text(
                    if (s.running) "開始說話，字幕會出現在這裡…".tr
                    else "邊錄邊翻譯：上面選好語言，按開始。辨識與翻譯都在手機上做（手機沒有那個語言的離線語音時，會改用 Google 線上辨識）；錄完照常產生完整報告。".tr,
                    style = MaterialTheme.typography.bodyMedium, color = Brand.TextFaint, modifier = Modifier.padding(18.dp),
                )
            } else LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                items(s.lines, key = { it.id }) { l ->
                    Column {
                        Text(l.original, style = MaterialTheme.typography.bodySmall,
                            color = Brand.TextMuted, fontStyle = if (l.partial) FontStyle.Italic else FontStyle.Normal)
                        if (!l.partial) {
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.Top) {
                                Box(Modifier.padding(top = 8.dp).size(5.dp).clip(RoundedCornerShape(3.dp)).background(Brand.Cyan))
                                Spacer(Modifier.width(8.dp))
                                Text(l.translated ?: "…", style = MaterialTheme.typography.titleMedium,
                                    color = if (l.translated == null) Brand.TextFaint else Brand.Text)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
        Text(
            "手機內建辨識＋裝置端翻譯，僅供參考；完整、準確的內容以錄完的報告為準。".tr,
            style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}
