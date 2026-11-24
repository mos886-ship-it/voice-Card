package com.example.wristai.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.voiceai.shared.AacWriter
import com.example.voiceai.shared.AudioSpec
import com.example.voiceai.shared.Segment
import com.example.voiceai.shared.WristAiProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 前台录音服务：AudioRecord → 每 5 分钟一段 AAC m4a → 录音中就陆续传给手机。
 *
 * 为 4～6 小时的长会议设计：
 * - 压缩成 48 kbps AAC，约 22 MB/小时（WAV 是 115 MB/小时）。
 * - 每段写完立刻传到手机，传成功就删掉，手表上通常只有正在录的那一段。
 * - 手机不在身边时分段会累积在手表上，之后自动 / 手动重送；6 小时约 130 MB。
 * - 开始前与录音中都会检查手表剩余空间，不够就安全停止（已录的部分都会保留）。
 * 用前台服务 + partial wake lock，熄屏 / 离开 App 时录音不会被系统中断。
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val ACTION_START = "com.example.wristai.wear.START"
        private const val ACTION_STOP = "com.example.wristai.wear.STOP"
        /** 测试用：adb 可指定较短的分段秒数。 */
        const val EXTRA_SEGMENT_SECONDS = "segment_seconds"
        /** 饮食记录（录音 id 以 food_ 开头，手机会估算热量） */
        const val EXTRA_FOOD = "food"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val MB = 1024L * 1024
        /** 开始录音至少要有的空间（够存约 4 小时的分段）。 */
        private const val MIN_FREE_TO_START = 100 * MB
        /** 录音中剩余空间低于这个就自动停止。 */
        private const val MIN_FREE_WHILE_RECORDING = 30 * MB
        /** 传送失败后多久再试。 */
        private const val RETRY_MS = 20_000L
        /** 最长录音（wake lock 上限）。 */
        private const val MAX_RECORDING_MS = 10 * 60 * 60 * 1000L

        fun start(context: Context, food: Boolean = false) {
            ContextCompat.startForegroundService(
                context, Intent(context, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_FOOD, food)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }

        /** 手表还能存几小时的录音（手机不在身边时；以剩余空间估算）。 */
        fun hoursOfStorageLeft(context: Context): Double =
            (WatchState.pendingDir(context).usableSpace - MIN_FREE_WHILE_RECORDING).coerceAtLeast(0) /
                (AudioSpec.SEGMENT_BIT_RATE / 8.0 * 3600)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null
    private var senderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val sendSignal = Channel<Unit>(Channel.CONFLATED)
    private val synced = AtomicInteger(0)
    @Volatile private var recording = false
    private var startedAt = 0L
    private var recordingId: String? = null
    /** 录音中途因为空间不足等原因自动停止时的说明。 */
    @Volatile private var autoStopReason: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(
                intent.getIntExtra(
                    EXTRA_SEGMENT_SECONDS,
                    // 测试用：shared_prefs/wristai_watch.xml 的 segment_seconds 可以改短
                    getSharedPreferences("wristai_watch", MODE_PRIVATE).getInt("segment_seconds", AudioSpec.SEGMENT_SECONDS),
                ).coerceIn(5, 3600),
                intent.getBooleanExtra(EXTRA_FOOD, false),
            )
            ACTION_STOP -> stopAndSend()
        }
        return START_NOT_STICKY
    }

    private var food = false

    private fun startRecording(segmentSeconds: Int, food: Boolean) {
        if (recordJob != null) return
        startInForeground()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            fail("沒有麥克風權限")
            return
        }

        val dir = WatchState.pendingDir(this)
        // 上次异常结束留下、没写完的分段无法修复，删掉
        dir.listFiles { f -> f.extension == "part" }?.forEach { it.delete() }
        val free = dir.usableSpace
        if (free < MIN_FREE_TO_START) {
            fail("手錶空間不足（剩 ${free / MB} MB）")
            return
        }

        this.food = food
        val id = (if (food) "food_" else "") + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        recordingId = id
        // GPS 戳记：以联动的手机为主要来源——先请手机定位；手机连不上或拿不到，才用手表自己的 GPS（备援）。
        // 手表的位置存成 <id>.geo，传到手机后删掉；传不出去就跟录音一起补传。
        scope.launch {
            val phoneOk = runCatching { PhoneLink.requestPhoneGeo(this@RecordingService, id) }.isSuccess &&
                WatchState.awaitGeoAck(id, 30_000) == true
            if (phoneOk) return@launch
            val fix = com.example.voiceai.shared.GeoFix.current(this@RecordingService) ?: return@launch
            val f = java.io.File(WatchState.pendingDir(this@RecordingService), "$id.geo")
            runCatching { f.writeText(fix.copy(source = "watch").toJson()) }
            runCatching { PhoneLink.sendGeo(this@RecordingService, f) }
        }
        val record = try {
            createAudioRecord()
        } catch (e: Exception) {
            fail("無法啟動麥克風：${e.message}")
            return
        }
        audioRecord = record
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristAI:recording")
            .apply { acquire(MAX_RECORDING_MS) }

        startedAt = System.currentTimeMillis()
        com.example.voiceai.shared.AudioLevel.reset()
        synced.set(0)
        autoStopReason = null
        recording = true
        WatchState.setPhoneStatus(null)
        publishProgress()

        val segmentSamples = segmentSeconds.toLong() * AudioSpec.SAMPLE_RATE
        recordJob = scope.launch {
            val buffer = ByteArray(4096)
            var seq = 0
            var part = File(dir, "$id.$seq.part")
            var writer = AacWriter(part)
            try {
                record.startRecording()
                while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        writer.write(buffer, n)
                        com.example.voiceai.shared.AudioLevel.feed(buffer, n, AudioSpec.SAMPLE_RATE)
                    }
                    if (writer.samples >= segmentSamples) {
                        finishSegment(writer, part, Segment(id, seq, writer.samples, last = false))
                        sendSignal.trySend(Unit)
                        seq++
                        part = File(dir, "$id.$seq.part")
                        writer = AacWriter(part)
                        if (dir.usableSpace < MIN_FREE_WHILE_RECORDING) {
                            autoStopReason = "手錶空間不足，已自動停止"
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                autoStopReason = "錄音中斷：${e.message}"
            }
            if (writer.samples < AudioSpec.SAMPLE_RATE / 10) {
                // 刚好在分段边界停止：补 0.1 秒静音，让最后一段是有效档案
                val silence = ByteArray(AudioSpec.SAMPLE_RATE / 10 * 2)
                runCatching { writer.write(silence, silence.size) }
            }
            runCatching { finishSegment(writer, part, Segment(id, seq, writer.samples, last = true)) }
                .onFailure { Log.e(TAG, "Finish last segment failed", it) }
            if (autoStopReason != null) {
                // 自动停止：直接走结束流程（传送剩余分段）
                stopAndSend()
            }
        }

        // 录音中：每写完一段就传；失败就隔一阵子再试
        senderJob = scope.launch {
            var failed = false
            while (isActive) {
                withTimeoutOrNull(if (failed) RETRY_MS else Long.MAX_VALUE) { sendSignal.receive() }
                failed = runCatching { synced.addAndGet(PhoneLink.sendPending(this@RecordingService)) }
                    .onFailure { Log.w(TAG, "Send while recording failed: ${it.message}") }
                    .isFailure
                publishProgress()
            }
        }
        scope.launch {
            // 定期刷新（手机断线 / 恢复时画面跟着更新）
            while (recording) { publishProgress(); delay(30_000) }
        }
    }

    private fun finishSegment(writer: AacWriter, part: File, seg: Segment) {
        writer.close()
        val target = File(part.parentFile, WristAiProtocol.segmentFileName(seg))
        if (!part.renameTo(target)) error("無法儲存錄音分段")
        Log.d(TAG, "Segment ${seg.seq} done: ${seg.samples} samples, ${target.length()} bytes, last=${seg.last}")
        WatchState.refreshPending(this)
        publishProgress()
    }

    private fun publishProgress() {
        if (!recording) return
        WatchState.refreshPending(this)
        WatchState.setRecorder(RecorderStatus.Recording(startedAt, synced.get(), WatchState.pendingCount.value, food))
    }

    @SuppressLint("MissingPermission") // 调用前已检查 RECORD_AUDIO
    private fun createAudioRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            AudioSpec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AudioSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, 8192),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失敗" }
        return record
    }

    @Synchronized
    private fun stopAndSend() {
        val record = audioRecord
        val job = recordJob
        if (record == null || job == null) {
            if (!recording) stopSelfCompletely()
            return
        }
        audioRecord = null
        val id = recordingId

        scope.launch {
            runCatching { record.stop() }
            job.join()
            recording = false
            recordJob = null
            record.release()
            senderJob?.cancelAndJoin()
            releaseWakeLock()

            val durationMs = System.currentTimeMillis() - startedAt
            if (durationMs < 1_000 && synced.get() == 0) {
                // 太短：删掉这次的分段
                WatchState.pendingDir(this@RecordingService)
                    .listFiles { f -> id != null && f.name.startsWith(id) }
                    ?.forEach { it.delete() }
                WatchState.refreshPending(this@RecordingService)
                fail("錄音太短")
                return@launch
            }

            WatchState.setRecorder(RecorderStatus.Sending)
            runCatching { PhoneLink.sendPending(this@RecordingService) }
                .onSuccess {
                    WatchState.setRecorder(RecorderStatus.Sent(id ?: ""))
                    WatchState.setPhoneStatus(autoStopReason?.let { "$it，已傳到手機" } ?: "已傳到手機，等待 AI 處理…")
                    stopSelfCompletely()
                }
                .onFailure { e ->
                    Log.e(TAG, "Final send failed", e)
                    val left = WatchState.pendingCount.value
                    fail("${e.message ?: "傳送失敗"}，$left 段已保留")
                }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun fail(message: String) {
        recording = false
        WatchState.setRecorder(RecorderStatus.Failed(message))
        stopSelfCompletely()
    }

    private fun stopSelfCompletely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "錄音中", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("大D 錄音中")
            .setContentText("每 5 分鐘自動同步到手機 · 點擊回到 App")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
        // 有定位权限才加上 location 类型（录音中萤幕关了也能完成 GPS 定位）；没权限就只有麦克风
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                (if (com.example.voiceai.shared.GeoFix.hasPermission(this)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    override fun onDestroy() {
        audioRecord?.let { runCatching { it.stop() }; it.release() }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
}
