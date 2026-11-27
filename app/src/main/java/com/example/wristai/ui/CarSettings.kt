package com.example.wristai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.voiceai.shared.tr
import com.example.wristai.CarMode
import com.example.wristai.ui.theme.Brand

/**
 * 设定 → 車用：车上录音的麦克风、报告做好时朗读、朗读速度、马上试念，以及捷径 / 「模式與日常」怎么设。
 * 开车时不看萤幕：用捷径、例行程序或手表开始录音，报告好了念出来。
 */
@Composable
fun CarSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        TopBar("車用".tr, onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("開車時不看螢幕：用捷徑、例行程序或手錶開始錄音，報告好了直接唸給你聽。".tr,
                style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)

            SectionLabel("麥克風".tr)
            MicSourceCard()

            SectionLabel("朗讀".tr)
            GlassCard {
                Text("報告做好時唸出來".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                Spacer(Modifier.height(8.dp))
                var mode by remember { mutableStateOf(CarMode.readAfter(context)) }
                val opts = listOf(CarMode.READ_OFF to "關".tr, CarMode.READ_BT to "連著藍牙時".tr, CarMode.READ_ALWAYS to "一律".tr)
                Segmented(opts.map { it.second }, opts.indexOfFirst { it.first == mode }.coerceAtLeast(0)) { i ->
                    mode = opts[i].first; CarMode.setReadAfter(context, mode)
                }
                Spacer(Modifier.height(6.dp))
                Text("「連著藍牙時」：連著車子或耳機的藍牙麥克風才唸，平常不會突然出聲。唸的是標題和摘要，用報告語言。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Spacer(Modifier.height(14.dp))
                Text("朗讀速度".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                Spacer(Modifier.height(8.dp))
                var rate by remember { mutableStateOf(CarMode.rate(context)) }
                val rates = listOf(0.8f to "慢".tr, 1.0f to "正常".tr, 1.25f to "快".tr)
                Segmented(rates.map { it.second }, rates.indexOfFirst { kotlin.math.abs(it.first - rate) < 0.01f }.coerceAtLeast(1)) { i ->
                    rate = rates[i].first; CarMode.setRate(context, rate)
                }
            }
            var todos by remember { mutableStateOf(CarMode.readTodosToo(context)) }
            SwitchCard("連待辦一起唸".tr, "報告好了時，也唸這份報告的待辦事項。".tr, todos, { todos = it; CarMode.setReadTodosToo(context, it) })
            GlassCard {
                Text("現在試試".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("唸最新報告".tr, { CarMode.readLatest(context) }, Modifier.weight(1f), tint = Brand.Cyan)
                    GhostButton("唸今天待辦".tr, { CarMode.readTodos(context) }, Modifier.weight(1f), tint = Brand.Cyan)
                }
                Spacer(Modifier.height(6.dp))
                Text("聲音從媒體音量出來；連著車子時會從車上喇叭播放。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
            }

            SectionLabel("捷徑與例行程序".tr)
            GlassCard {
                Text("不用打開 App 就能做的事".tr, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                Spacer(Modifier.height(8.dp))
                for (s in listOf("開始錄音", "停止錄音", "記錄飲食", "唸最新報告", "唸今天待辦"))
                    Text("・" + s.tr, style = MaterialTheme.typography.bodyMedium, color = Brand.Text)
                Spacer(Modifier.height(10.dp))
                Text("用法：".tr, style = MaterialTheme.typography.labelLarge, color = Brand.TextMuted)
                Text("1. 長按大D的 App 圖示就有這些捷徑，也可以拖到桌面。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Text("2. 三星「模式與日常」→ 例行程序：條件選「連上藍牙裝置（你的車）」，動作選「開啟 App 捷徑 → 大D 開始錄音」，上車就自動開始錄；下車（中斷藍牙）再加一條「停止錄音」。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Text("3. 手錶上的「用手機錄音」也可以在車上開始、停止錄音。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Spacer(Modifier.height(6.dp))
                Text("語音助理：可以說「Hey Google，開啟大D」。直接說「用大D開始錄音」要 App 從 Play 商店上架後才能用，現在先用捷徑或例行程序。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
            }
            Spacer(Modifier.fillMaxWidth().height(24.dp))
        }
    }
}
