package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.wristai.ui.theme.Brand

/**
 * 第一次打开 App 的引导：介绍 → 权限 → 手表 → AI 处理方式。
 * [onFinish] 的参数 = 使用者选了「云端自动报告」，要直接带去设定填金钥。
 */
@Composable
fun OnboardingScreen(onFinish: (openCloudSetup: Boolean) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val total = 4
    if (step > 0) BackHandler { step-- }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        // 进度点 + 略过
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until total) {
                Box(
                    Modifier.padding(end = 6.dp).height(4.dp).width(if (i == step) 22.dp else 8.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (i <= step) Brand.Cyan else Brand.SurfaceHigher)
                )
            }
            Spacer(Modifier.weight(1f))
            if (step < total - 1) TextButton(onClick = { step = total - 1 }) { Text("略過".tr, color = Brand.TextMuted) }
        }
        AnimatedContent(step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "onboarding",
            modifier = Modifier.weight(1f)) { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                when (s) {
                    0 -> Welcome()
                    1 -> Permissions()
                    2 -> WatchStep()
                    else -> AiChoice(onFinish)
                }
            }
        }
        if (step < total - 1) {
            GradientButton(if (step == 0) "開始設定".tr else "下一步".tr, { step++ }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Title(text: String, sub: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = Brand.Text)
    Spacer(Modifier.height(8.dp))
    Text(sub, style = MaterialTheme.typography.bodyMedium, color = Brand.TextMuted)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun Feature(title: String, body: String) {
    Row(Modifier.padding(vertical = 8.dp)) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(Brand.Accent))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        }
    }
}

@Composable
private fun Welcome() {
    Column(horizontalAlignment = Alignment.Start) {
        BrandMark(64.dp)
        Spacer(Modifier.height(24.dp))
        Title("手錶一按，錄音自動變重點".tr, "會議、課堂、訪談、通話、靈感筆記…大D 把錄音、逐字稿、重點與待辦一次整理好。".tr)
        Feature("手錶或手機一鍵錄音".tr, "手錶錄、手機錄都可以，也能從手錶遙控手機錄音".tr)
        Feature("長達 6 小時的錄音".tr, "邊錄邊同步，錄完很快就有完整報告".tr)
        Feature("分出每位說話者".tr, "逐字稿依說話者分色，點一句就能聽原音".tr)
        Feature("摘要推送到手錶".tr, "錄音一結束，重點就在手腕上".tr)
    }
}

@Composable
private fun Permissions() {
    val context = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var mic by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    var notif by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        r[Manifest.permission.RECORD_AUDIO]?.let { mic = it }
        r[Manifest.permission.POST_NOTIFICATIONS]?.let { notif = it }
    }
    Column {
        Title("需要兩個權限".tr, "只用在你按下錄音的時候，不會在背景偷聽。".tr)
        PermissionRow("麥克風".tr, "用手機錄音、讓手錶遙控手機錄音".tr, mic)
        Spacer(Modifier.height(10.dp))
        PermissionRow("通知".tr, "報告完成、錄音中的狀態提醒".tr, notif)
        Spacer(Modifier.height(20.dp))
        if (!mic || !notif) {
            GhostButton("允許權限".tr, {
                launcher.launch(buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray())
            }, Modifier.fillMaxWidth(), tint = Brand.Cyan)
        }
    }
}

@Composable
private fun PermissionRow(name: String, why: String, ok: Boolean) {
    GlassCard(highlight = ok) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text(why, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            }
            if (ok) Icon(Icons.Filled.CheckCircle, "已允許".tr, tint = Brand.Success)
            else Text("未允許".tr, style = MaterialTheme.typography.labelMedium, color = Brand.Warning)
        }
    }
}

@Composable
private fun WatchStep() {
    val watch = rememberWatchName()
    Column {
        Title("連接你的手錶".tr, "Galaxy Watch 安裝 大D 後，就能在手腕上一鍵錄音。沒有手錶也能只用手機。".tr)
        GlassCard(highlight = watch != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (watch != null) Brand.Success else Brand.TextFaint))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(watch ?: "尚未偵測到手錶".tr, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                    Text(
                        if (watch != null) "已連線，可以開始錄音了".tr else "請在 Galaxy Wearable 配對手錶，並在手錶的 Play 商店安裝 大D".tr,
                        style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun AiChoice(onFinish: (Boolean) -> Unit) {
    var cloud by rememberSaveable { mutableStateOf(false) }
    Column {
        Title("錄音要怎麼整理？".tr, "之後隨時可以在設定更改。".tr)
        ChoiceCard(
            selected = !cloud, badge = "推薦".tr,
            title = "裝置端 · Galaxy AI".tr,
            body = "錄音只存在你的手機，自動放進三星「錄音機」，用 Galaxy AI 轉錄與摘要。隱私最好、免費。".tr,
        ) { cloud = false }
        Spacer(Modifier.height(12.dp))
        ChoiceCard(
            selected = cloud, badge = null,
            title = "雲端自動報告 · Gemini".tr,
            body = "錄完自動產生完整報告、分說話者逐字稿並推送到手錶。需要免費的 Google 金鑰；錄音會上傳到 Google 處理。".tr,
        ) { cloud = true }
        Spacer(Modifier.height(24.dp))
        GradientButton(if (cloud) "下一步：設定金鑰".tr else "完成，開始使用".tr, { onFinish(cloud) }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ChoiceCard(selected: Boolean, badge: String?, title: String, body: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) Brand.SurfaceHigh else Brand.Surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) Brand.Cyan else Brand.Outline, shape)
            .clickable(onClick = onClick).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Brand.Text, modifier = Modifier.weight(1f))
            badge?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Brand.Accent).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
    }
}
