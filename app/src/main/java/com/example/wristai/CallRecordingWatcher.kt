package com.example.wristai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 自动整理通话录音。
 *
 * Android 10 起，第三方 App 都录不到通话声音（LINE / WhatsApp 等网路电话也一样，通话中别的 App 的麦克风会被静音）。
 * 能录到双方声音的只有手机内建的电话 App：三星「电话」→ 设定 →「录制通话」，档案存在 Recordings/Call。
 * 这里监看媒体库：有新的通话录音就汇入 WristAI，自动产生通话报告（不再另存一份到录音机）。
 *
 * 用 WorkManager 的 content URI 触发：媒体库有变动系统才唤醒，平常不耗电。
 * 这种任务不能跨重开机保存，所以开机时（[BootReceiver]）和打开 App 时都会重新排程。
 */
class CallRecordingWatcher(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CallRecordingWatcher"
        private const val WORK = "call-recording-watcher"
        private const val PREFS = "wristai_settings"
        private const val KEY_ENABLED = "call_import"
        private const val KEY_LAST_SEEN = "call_import_last_seen"

        /** 三星（One UI 4 起）与多数手机的通话录音资料夹。 */
        private val CALL_DIRS = listOf("Recordings/Call/", "Call/", "Recordings/Call recordings/", "MIUI/sound_recorder/call_rec/")

        private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun isEnabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)

        fun audioPermission() =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

        fun hasPermission(c: Context) =
            ContextCompat.checkSelfPermission(c, audioPermission()) == PackageManager.PERMISSION_GRANTED

        fun setEnabled(c: Context, on: Boolean) {
            val e = prefs(c).edit().putBoolean(KEY_ENABLED, on)
            // 打开时从「现在」开始算，不会把以前所有通话都汇入
            if (on) e.putLong(KEY_LAST_SEEN, System.currentTimeMillis() / 1000)
            e.apply()
            if (on) schedule(c) else WorkManager.getInstance(c).cancelUniqueWork(WORK)
        }

        /** 排程（或重新排程）下一次「媒体库有变动」的唤醒。 */
        fun schedule(c: Context) {
            if (!isEnabled(c) || !hasPermission(c)) return
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)  // 录音档写完再处理
                .setTriggerContentMaxDelay(1, TimeUnit.MINUTES)
                .build()
            val req = OneTimeWorkRequestBuilder<CallRecordingWatcher>().setConstraints(constraints).build()
            WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
        }

        /** 找出比 [sinceSec] 新的通话录音：(uri, 加入时间秒, 档名)。 */
        fun findNew(c: Context, sinceSec: Long): List<Triple<android.net.Uri, Long, String>> {
            val out = mutableListOf<Triple<android.net.Uri, Long, String>>()
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
            val where = "${MediaStore.Audio.Media.DATE_ADDED} > ? AND (" +
                CALL_DIRS.joinToString(" OR ") { "$pathCol LIKE ?" } + ")"
            val args = arrayOf(sinceSec.toString()) + CALL_DIRS.map { if (Build.VERSION.SDK_INT >= 29) "$it%" else "%/$it%" }
            c.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DISPLAY_NAME),
                where, args, "${MediaStore.Audio.Media.DATE_ADDED} ASC",
            )?.use { cur ->
                while (cur.moveToNext()) {
                    out += Triple(ContentUris.withAppendedId(collection, cur.getLong(0)), cur.getLong(1), cur.getString(2).orEmpty())
                }
            }
            return out
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        try {
            if (!isEnabled(ctx) || !hasPermission(ctx)) return Result.success()
            var last = prefs(ctx).getLong(KEY_LAST_SEEN, System.currentTimeMillis() / 1000)
            for ((uri, added, name) in findNew(ctx, last)) {
                // 通话还在录时档案可能还没写完：等 IS_PENDING 结束才会出现在查询里（MediaStore 预设不列 pending 档）
                runCatching {
                    AudioImporter.import(ctx, uri, source = AudioImporter.SOURCE_CALL, fallbackEnd = added * 1000,
                        title = name.substringBeforeLast('.'))
                }.onSuccess { Log.d(TAG, "Imported call recording $name as $it") }
                    .onFailure { Log.w(TAG, "Import $name failed: ${it.message}") }
                last = maxOf(last, added)
                prefs(ctx).edit().putLong(KEY_LAST_SEEN, last).apply()
            }
        } finally {
            // content URI 任务只触发一次，处理完再排下一次
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CallRecordingWatcher>().setConstraints(
                    Constraints.Builder()
                        .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
                        .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                        .setTriggerContentMaxDelay(1, TimeUnit.MINUTES)
                        .build()
                ).build()
            )
        }
        return Result.success()
    }
}

/** 开机后重新排程通话录音监看（content URI 任务不会跨重开机保存）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) CallRecordingWatcher.schedule(context)
    }
}
