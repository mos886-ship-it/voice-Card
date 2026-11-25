package com.example.wristai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 饮食拍照：一餐最多 [MAX] 张，存成 photo_1.jpg… 放在记录资料夹里（Drive 备份会一起备份）。
 *
 * 照片一律缩成长边 [LONG_EDGE] 像素、JPEG 品质 [QUALITY]（约 60–120 KB）：
 * Gemini 768 以内都算一样的价钱（一块），手机上看也够清楚；重新编码顺便去掉 EXIF（GPS 位置）。
 */
object FoodPhotos {
    const val MAX = 3
    const val LONG_EDGE = 768
    const val QUALITY = 80

    /** 「拍照＋口述」：照片先放这里，手机录音开始时（[PhoneRecordingService]）接进那笔饮食记录。 */
    @Volatile var pendingForVoice: List<File>? = null

    fun files(context: Context, id: String): List<File> =
        MeetingStore.dir(context, id).listFiles { f -> f.name.matches(Regex("photo_\\d+\\.jpg")) }
            ?.sortedBy { it.name }.orEmpty()

    /** 系统相机要写入的位置（FileProvider，不需要相机权限）。 */
    fun newCaptureUri(context: Context): Pair<File, Uri> {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        return f to FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }

    private fun stagingDir(context: Context) = File(context.cacheDir, "food_pending").apply { mkdirs() }

    /** 清掉还没送出的照片（打开拍照画面 / 取消时）。 */
    fun clearStaging(context: Context) {
        stagingDir(context).listFiles()?.forEach { it.delete() }
        File(context.cacheDir, "camera").listFiles()?.forEach { it.delete() }
        pendingForVoice = null
    }

    /** 读进一张照片（相机或相簿），转正、缩小、去 EXIF，存到暂存区；失败回 null。 */
    fun stage(context: Context, src: Uri): File? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight).takeIf { it > 0 } ?: return null
        var sample = 1
        while (longest / (sample * 2) >= LONG_EDGE) sample *= 2
        val decoded = context.contentResolver.openInputStream(src)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = context.contentResolver.openInputStream(src)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = minOf(1f, LONG_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        val f = File(stagingDir(context), "p_${System.nanoTime()}.jpg")
        f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        if (out !== decoded) out.recycle()
        decoded.recycle()
        f
    }.getOrNull()

    /** 把暂存的照片搬进记录资料夹（photo_1.jpg…）。 */
    fun attach(context: Context, id: String, staged: List<File>) {
        val start = files(context, id).size
        staged.take(MAX - start).forEachIndexed { i, f ->
            val dst = File(MeetingStore.dir(context, id), "photo_${start + i + 1}.jpg")
            if (!f.renameTo(dst)) { f.copyTo(dst, overwrite = true); f.delete() }
        }
    }

    /** 只有照片（没有口述）：建一笔饮食记录并开始处理，回传记录 id。 */
    fun createPhotoOnly(context: Context, staged: List<File>): String {
        val id = com.example.voiceai.shared.RecordingIds.newId(context, FoodLog.ID_PREFIX)
        MeetingStore.save(context, Meeting(
            id = id, status = MeetingStatus.RECEIVED, createdAt = System.currentTimeMillis(), durationSec = 0.0,
            watchNodeId = null, title = null, error = null, source = SOURCE,
            lang = SpeechLang.currentCode(context), dialect = SpeechLang.currentDialect(context),
        ))
        attach(context, id, staged)
        ProcessMeetingWorker.enqueue(context, id)
        return id
    }

    /** Meeting.source：只有照片的饮食记录。 */
    const val SOURCE = "photo"
}
