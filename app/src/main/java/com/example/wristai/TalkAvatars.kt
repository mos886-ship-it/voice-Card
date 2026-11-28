package com.example.wristai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import com.example.voiceai.shared.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 遠距對話的頭貼：
 * - 自己的：選一張照片 → 裁正中間的正方形、縮成 256×256 JPEG → 存在手機（files/talk_av/me.jpg）並傳到大D的主機。
 * - 好友的：上線回報、好友邀請、來電帶頭貼版本（av）；版本變了才去主機抓（只有好友抓得到），存在 files/talk_av/。
 * 畫面用 [version] 這個狀態知道要重畫。
 */
object TalkAvatars {
    private const val SIZE = 256
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private val fetching = mutableSetOf<String>()

    private fun dir(c: Context) = File(c.filesDir, "talk_av").apply { mkdirs() }
    fun mine(c: Context): File? = File(dir(c), "me.jpg").takeIf { it.exists() && it.length() > 0 }
    private fun key(base: String, id: String) = (base.hashCode().toUInt().toString(16)) + "_" + id.filter { it.isLetterOrDigit() }
    fun file(c: Context, base: String, id: String): File? = File(dir(c), key(base, id) + ".jpg").takeIf { it.exists() && it.length() > 0 }
    private fun known(c: Context, base: String, id: String) = c.getSharedPreferences("talk_store", 0).getLong("av:" + key(base, id), 0)

    /** 照片 → 正方形 256×256 JPEG（照 EXIF 轉正）。 */
    fun square(c: Context, uri: Uri): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val src = c.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rot = c.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
            }
        } ?: 0f
        val side = minOf(src.width, src.height)
        val m = Matrix().apply { val s = SIZE.toFloat() / side; postScale(s, s); postRotate(rot) }
        val out = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side, m, true)
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        if (out !== src) out.recycle(); src.recycle()
        bos.toByteArray()
    }.getOrNull()

    /** 換自己的頭貼（[jpeg] = null 刪掉）：存手機、傳到每台有身分的主機。回錯誤訊息或 null。 */
    suspend fun setMine(c: Context, jpeg: ByteArray?): String? = withContext(Dispatchers.IO) {
        val f = File(dir(c), "me.jpg")
        if (jpeg == null) f.delete() else f.writeBytes(jpeg)
        _version.value++
        var err: String? = null
        var ok = false
        for (b in TalkStore.bases(c)) {
            val id = TalkStore.identity(c, b) ?: continue
            val (code, j) = runCatching {
                RemoteTalk.http("POST", "$b/v1/talk/avatar", null, contactToken = id.token, raw = jpeg ?: ByteArray(0), contentType = "image/jpeg")
            }.getOrElse { err = "連不到大D的主機，頭貼之後再同步".tr; continue }
            if (code == 404 && j.optString("error").isBlank()) err = "大D的主機還沒有頭貼功能：請在 Mac 上重新部署大D的主機".tr
            else if (code != 200) err = j.optString("error").ifBlank { "頭貼上傳失敗".tr }
            else ok = true
        }
        // 有一台主機成功就好（連不到的那台，下次上線回報時會補傳）
        if (ok) null else err
    }

    /** 主機說某人的頭貼版本是 [av]：跟手機上的不一樣就去抓（0 = 對方沒有頭貼，刪掉）。 */
    fun sync(c: Context, base: String, id: String, av: Long) {
        if (id.isBlank() || av == known(c, base, id)) return
        val k = key(base, id)
        synchronized(fetching) { if (!fetching.add(k)) return }
        scope.launch {
            try {
                val f = File(dir(c), "$k.jpg")
                if (av == 0L) f.delete()
                else {
                    val token = TalkStore.identity(c, base)?.token ?: return@launch
                    val conn = (URL("$base/v1/talk/avatar/$id").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6_000; readTimeout = 15_000; setRequestProperty("X-Contact-Token", token)
                    }
                    try {
                        if (conn.responseCode != 200) return@launch
                        val bytes = conn.inputStream.use { it.readBytes() }
                        if (bytes.size > 100_000) return@launch
                        f.writeBytes(bytes)
                    } finally { conn.disconnect() }
                }
                c.getSharedPreferences("talk_store", 0).edit().putLong("av:$k", av).apply()
                _version.value++
            } finally { synchronized(fetching) { fetching.remove(k) } }
        }
    }

    /** 換了主機或剛取得身分：把自己的頭貼補傳上去（主機上沒有的話）。 */
    fun pushIfMissing(c: Context, base: String, serverAv: Long) {
        val f = mine(c) ?: return
        if (serverAv != 0L) return
        val id = TalkStore.identity(c, base) ?: return
        scope.launch { runCatching { RemoteTalk.http("POST", "$base/v1/talk/avatar", null, contactToken = id.token, raw = f.readBytes(), contentType = "image/jpeg") } }
    }
}
