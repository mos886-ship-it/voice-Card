package com.example.wristai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.wristai.PhoneRecorder
import com.example.wristai.PhoneRecordingService
import com.example.wristai.RemoteRecordActivity
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.delay

/**
 * 手机录音卡：待机 = 开始按钮；录音中 = 计时 + 停止。
 * [foodMode] = 饮食分页用（按钮是「記錄這一餐」）；录音分页用一般录音。
 * 录音中只在对应的分页显示（饮食记录中不会出现在录音分页，反之亦然）。
 */
/**
 * 开始手机录音的按钮共用：要麦克风权限（必须）＋定位权限（GPS 戳记，可拒绝；只问一次，不会每次都跳）。
 * 回传 start(food)。
 */
@Composable
fun rememberRecordStarter(): (Boolean) -> Unit {
    val context = LocalContext.current
    val pendingFood = remember { androidx.compose.runtime.mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            PhoneRecordingService.start(context, food = pendingFood.value)
    }
    return { food ->
        pendingFood.value = food
        val prefs = context.getSharedPreferences("wristai_ui", android.content.Context.MODE_PRIVATE)
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val askLocation = !com.example.voiceai.shared.GeoFix.hasPermission(context) && !prefs.getBoolean("asked_location", false)
        if (mic && !askLocation) PhoneRecordingService.start(context, food = food)
        else {
            prefs.edit().putBoolean("asked_location", true).apply()
            launcher.launch(buildList {
                if (!mic) add(Manifest.permission.RECORD_AUDIO)
                if (askLocation) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
            }.toTypedArray())
        }
    }
}

@Composable
fun PhoneRecordCard(onOpenMeeting: (String) -> Unit, foodMode: Boolean = false) {
    val context = LocalContext.current
    val active by PhoneRecorder.active.collectAsState()
    val error by PhoneRecorder.error.collectAsState()
    val starter = rememberRecordStarter()
    fun start(food: Boolean = false) = starter(food)

    // 另一种录音进行中：这一页显示提示即可，不重复显示计时
    val a = active?.takeIf { com.example.wristai.FoodLog.isFood(it.meetingId) == foodMode }
    if (active != null && a == null) {
        GlassCard {
            Text(if (foodMode) "手機正在錄音中，結束後才能記錄飲食" else "飲食記錄中，請到「飲食」分頁查看",
                style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        }
        return
    }
    if (a == null && foodMode) {
        GradientButton("記錄這一餐", { start(food = true) }, Modifier.fillMaxWidth().height(56.dp), recordDot = true)
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Warning)
        }
        return
    }
    if (a == null) {
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphTile({ Waveform(Modifier.size(20.dp), Brand.Text) })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("手機錄音", style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                    Text(
                        if (RemoteRecordActivity.canStartFromBackground(context)) "放在桌子中間收音最好 · 也能從手錶遙控開始"
                        else "放在桌子中間收音最好 · 開啟「手錶遠端啟動」可從手錶遙控",
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                    )
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Warning)
            }
            Spacer(Modifier.height(14.dp))
            // 辨识语言：记住上次的选择，新录音用这个
            LangPicker(com.example.wristai.SpeechLang.current) { com.example.wristai.SpeechLang.set(context, it) }
            Spacer(Modifier.height(10.dp))
            GradientButton("開始手機錄音", { start() }, Modifier.fillMaxWidth(), recordDot = true)
        }
    } else {
        val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(a.startedAt) { while (true) { now.longValue = System.currentTimeMillis(); delay(500) } }
        val sec = ((now.longValue - a.startedAt) / 1000).coerceAtLeast(0)
        val food = com.example.wristai.FoodLog.isFood(a.meetingId)
        GlassCard(highlight = true, onClick = { onOpenMeeting(a.meetingId) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(Brand.Recording, 8.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (food) "飲食記錄中" else "手機錄音中 · " + recLang(context, a.meetingId).short,
                    style = MaterialTheme.typography.labelLarge, color = Brand.Recording)
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60),
                fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Brand.Text,
            )
            Text(if (food) "說出吃了什麼、大概份量（例如：牛肉麵一碗、半糖紅茶），說完按停止"
                else "每 30 分鐘先在背景分析，錄完很快就有報告 · 螢幕關掉也會繼續錄",
                style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            // 即时音量条＋收音状态
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.Bg.copy(alpha = 0.45f)).padding(12.dp)) {
                LevelMeter()
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("停止錄音", { PhoneRecordingService.stop(context) }, Modifier.weight(1f), tint = Brand.Recording)
            }
        }
    }
}

/** 这笔录音的辨识语言（录音中的卡片显示用）。 */
private fun recLang(context: android.content.Context, id: String): com.example.wristai.SpeechLang =
    com.example.wristai.SpeechLang.of(com.example.wristai.MeetingStore.get(context, id)?.lang)
