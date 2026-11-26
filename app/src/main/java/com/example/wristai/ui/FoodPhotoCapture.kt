package com.example.wristai.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.wristai.FoodPhotos
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 拍照记录饮食：一餐最多 3 张（相机或相簿），可以「只拍照」或「拍照＋口述」。
 * [withVoice] = 一开始选「拍照＋口述」；[autoCamera] = 打开就先开相机。
 * 只拍照：送出后建立饮食记录（[FoodPhotos.createPhotoOnly]）；拍照＋口述：照片交给接下来的手机录音。
 */
@Composable
fun FoodPhotoCapture(
    withVoice: Boolean, autoCamera: Boolean = true, onDismiss: () -> Unit,
    /** 「拍照＋口述」：照片已交给 [FoodPhotos.pendingForVoice]，由呼叫端开始饮食录音（麦克风权限的结果才收得到） */
    onStartVoice: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val staged = remember { mutableStateListOf<File>() }
    var voice by remember { mutableStateOf(withVoice) }
    var busy by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<File?>(null) }

    fun addFrom(uris: List<Uri>, cleanup: File? = null) {
        if (uris.isEmpty()) { cleanup?.delete(); return }
        busy = true
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { FoodPhotos.stage(context, it) } }
            cleanup?.delete()   // 相机原图读完（缩小存好）才删
            staged.addAll(files.take(FoodPhotos.MAX - staged.size))
            busy = false
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = capture
        capture = null
        if (ok && f != null && f.length() > 0) addFrom(listOf(Uri.fromFile(f)), cleanup = f) else f?.delete()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(FoodPhotos.MAX)) { uris ->
        addFrom(uris)
    }

    fun openCamera() {
        val (f, uri) = FoodPhotos.newCaptureUri(context)
        capture = f
        runCatching { camera.launch(uri) }
    }

    // 定位权限：跟录音一样只问一次（之前问过、拒绝了就不再问，拍照照常只是没有地点）
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        FoodPhotos.startGeo(context)
        if (autoCamera) openCamera()
    }

    LaunchedEffect(Unit) {
        FoodPhotos.clearStaging(context)
        val prefs = context.getSharedPreferences("wristai_ui", android.content.Context.MODE_PRIVATE)
        if (!com.example.voiceai.shared.GeoFix.hasPermission(context) && !prefs.getBoolean("asked_location", false)) {
            prefs.edit().putBoolean("asked_location", true).apply()
            locPerm.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            FoodPhotos.startGeo(context)
            if (autoCamera) openCamera()
        }
    }

    Dialog(onDismissRequest = { FoodPhotos.clearStaging(context); FoodPhotos.cancelGeo(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(Brand.CardRadius)).background(Brand.Surface).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("拍照記錄飲食", style = MaterialTheme.typography.titleLarge, color = Brand.Text)
            Segmented(listOf("只拍照", "拍照＋口述"), if (voice) 1 else 0) { voice = it == 1 }

            // ---------- 已拍的照片（最多 3 张） ----------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in staged.toList()) {
                    val bmp = remember(f) { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }
                    Box(Modifier.size(92.dp).clip(RoundedCornerShape(12.dp)).background(Brand.SurfaceHigh)) {
                        bmp?.let { Image(it, null, Modifier.size(92.dp), contentScale = ContentScale.Crop) }
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f)).clickable { staged.remove(f); f.delete() },
                            contentAlignment = Alignment.Center,
                        ) { Text("✕", color = Color.White, fontSize = 12.sp) }
                    }
                }
                if (staged.isEmpty()) Text(if (busy) "處理照片中…" else "還沒有照片：便當、飲料可以分開拍，最多 3 張",
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted, modifier = Modifier.padding(vertical = 8.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val room = staged.size < FoodPhotos.MAX && !busy
                GhostButton(if (staged.isEmpty()) "拍照" else "再拍一張", { if (room) openCamera() }, Modifier.weight(1f),
                    tint = if (room) Brand.Text else Brand.TextFaint)
                GhostButton("從相簿選", {
                    if (room) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, Modifier.weight(1f), tint = if (room) Brand.Text else Brand.TextFaint)
            }

            Text(
                if (voice) "按下一步後直接說，例如「大杯半糖」「飯吃一半」；照片和口述會合成一筆記錄。"
                else "照片看不出份量和甜度，選「拍照＋口述」補一句話，熱量會準很多。包裝食品可以拍營養標示。",
                style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted,
            )
            Text("照片會縮小、去掉位置資訊後交給 Gemini 辨識；照片和拍攝地點留在這支手機的記錄裡。",
                style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("取消", { FoodPhotos.clearStaging(context); FoodPhotos.cancelGeo(); onDismiss() }, Modifier.weight(1f))
                GradientButton(if (voice) "下一步：口述" else "送出", {
                    val list = staged.toList()
                    if (voice) {
                        FoodPhotos.pendingForVoice = list
                        FoodPhotos.cancelGeo()   // 口述的由手机录音自己定位
                        onStartVoice()
                    } else {
                        FoodPhotos.createPhotoOnly(context, list)
                    }
                    onFinished()
                }, Modifier.weight(1f), enabled = staged.isNotEmpty() && !busy)
            }
        }
    }
}
