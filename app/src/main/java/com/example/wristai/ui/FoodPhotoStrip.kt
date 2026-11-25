package com.example.wristai.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.wristai.FoodPhotos
import com.example.wristai.MeetingStatus

/** 饮食记录的照片（最多 3 张并排）；点一下全屏看。 */
@Composable
fun FoodPhotoStrip(id: String, status: MeetingStatus) {
    val context = LocalContext.current
    val files = remember(id, status) { FoodPhotos.files(context, id) }
    if (files.isEmpty()) return
    val images = remember(files) { files.mapNotNull { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    var big by remember { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEachIndexed { i, img ->
            Image(img, "飲食照片 ${i + 1}", Modifier.weight(1f).height(if (images.size == 1) 200.dp else 120.dp)
                .clip(RoundedCornerShape(12.dp)).clickable { big = i }, contentScale = ContentScale.Crop)
        }
    }
    big?.let { i ->
        Dialog(onDismissRequest = { big = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { big = null }, contentAlignment = Alignment.Center) {
                Image(images[i], "飲食照片 ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            }
        }
    }
}
