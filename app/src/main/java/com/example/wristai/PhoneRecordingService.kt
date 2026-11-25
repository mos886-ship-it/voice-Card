package com.example.wristai

import com.example.voiceai.shared.AudioLevel
import android.Manifest
import android.annotation.SuppressLint
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
import com.example.voiceai.shared.WavFile
import com.example.voiceai.shared.WristAiProtocol
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 手机录音状态（Service 写、UI 读）。 */
object PhoneRecorder {
    /** 录音中 = 开始时间与会议 id；null = 没在录。 */
    data class Active(val meetingId: String, val startedAt: Long)

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()
    internal fun set(v: Active?) { _active.value = v }

    /** 最近一次启动失败的原因（给 UI 显示）。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    internal fun setError(v: String?) { _error.value = v }
}

/**
 * 手机麦克风录音（前台服务）。可以在手机 App 按，也可以从手表遥控。
 *
 * 跟手表长会议同一套格式：
 * - PCM 直接写进 meetings/<id>/audio.wav（后面的报告流程不用改）；
 * - 同时每 5 分钟写一段 AAC 到 segments/，录完直接接成 m4a 存进三星录音机（6 小时也只要几秒）；
 * - 录音中就让 Gemini 先分析录满 30 分钟的段，录完很快就有报告。
 */
class PhoneRecordingService : Service() {

    companion object {
        private const val TAG = "PhoneRecording"
        private const val ACTION_START = "com.example.wristai.PHONE_REC_START"
        private const val ACTION_STOP = "com.example.wristai.PHONE_REC_STOP"
        private const val EXTRA_NODE = "watch_node"
        private const val EXTRA_FOOD = "food"
        private const val CHANNEL_ID = "phone_recording"
        private const val NOTIFICATION_ID = 3
        private const val MB = 1024L * 1024
        private const val MIN_FREE_TO_START = 500 * MB
        private const val MIN_FREE_WHILE_RECORDING = 200 * MB
        private const val MAX_RECORDING_MS = 12 * 60 * 60 * 1000L

        /** [watchNode] = 从手表遥控时的手表节点（报告摘要会推回那只手表）。 */
        fun start(context: Context, watchNode: String? = null, food: Boolean = false) {
            ContextCompat.startForegroundService(
                context, Intent(context, PhoneRecordingService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_NODE, watchNode).putExtra(EXTRA_FOOD, food)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PhoneRecordingService::class.java).setAction(ACTION_STOP))
        }

        fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 1, Intent(context, PhoneRecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )

        /** 把手机录音状态告诉所有连着的手表。 */
        fun broadcastState(context: Context) {
            val payload = (PhoneRecorder.active.value?.startedAt ?: 0L).toString().toByteArray()
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val nodes = Wearable.getCapabilityClient(context)
                        .getCapability(WristAiProtocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await().nodes
                    for (n in nodes) Wearable.getMessageClient(context).sendMessage(n.id, WristAiProtocol.PATH_PHONE_REC_STATE, payload).await()
                }.onFailure { Log.w(TAG, "broadcast state failed: ${it.message}") }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getStringExtra(EXTRA_NODE), intent.getBooleanExtra(EXTRA_FOOD, false))
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(watchNode: String?, food: Boolean) {
        if (recordJob != null) { broadcastState(this); return }
        try {
            startInForeground()
        } catch (e: Exception) {
            // Android 14+：App 在后台时不能启动麦克风前台服务
            fail("手機在背景，無法開始錄音：${e.message}")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("沒有麥克風權限，請先打開 大D 允許麥克風")
            return
        }
        if (filesDir.usableSpace < MIN_FREE_TO_START) {
            fail("手機空間不足（剩 ${filesDir.usableSpace / MB} MB）")
            return
        }

        // 编号带这台手机的装置代码：多台装置共用云端备份也不会撞（RecordingIds）
        val id = com.example.voiceai.shared.RecordingIds.newId(this, if (food) FoodLog.ID_PREFIX else "")
        val record = try { createAudioRecord() } catch (e: Exception) {
            fail("無法啟動麥克風：${e.message}")
            return
        }
        audioRecord = record
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristAI:phoneRecording")
            .apply { acquire(MAX_RECORDING_MS) }

        val startedAt = System.currentTimeMillis()
        MeetingStore.save(this, Meeting(
            id = id, status = MeetingStatus.RECORDING, createdAt = startedAt, durationSec = 0.0,
            watchNodeId = watchNode, title = null, error = null, source = "phone",
            lang = SpeechLang.currentCode(this), dialect = SpeechLang.currentDialect(this),
        ))
        // 「拍照＋口述」：先拍好的照片接进这笔饮食记录
        if (food) FoodPhotos.pendingForVoice?.let { FoodPhotos.attach(this, id, it); FoodPhotos.pendingForVoice = null }
        // GPS 戳记：开始录音时手机的位置（背景定位，最多等 30 秒）
        scope.launch {
            com.example.voiceai.shared.GeoFix.current(this@PhoneRecordingService)?.let { MeetingStore.saveGeo(this@PhoneRecordingService, id, it.copy(source = "phone")) }
        }
        PhoneRecorder.setError(null)
        PhoneRecorder.set(PhoneRecorder.Active(id, startedAt))
        broadcastState(this)
        updateNotification(startedAt)

        val wav = MeetingStore.audioFile(this, id)
        val segDir = MeetingStore.segmentsDir(this, id).apply { mkdirs() }
        val segmentSamples = AudioSpec.SEGMENT_SECONDS.toLong() * AudioSpec.SAMPLE_RATE

        recordJob = scope.launch {
            val buffer = ByteArray(4096)
            var seq = 0
            var part = File(segDir, "$seq.part")
            var writer = AacWriter(part)
            var stopReason: String? = null
            FileOutputStream(wav).buffered(64 * 1024).use { out ->
                WavFile.writePlaceholderHeader(out)
                try {
                    AudioLevel.reset()
                    record.startRecording()
                    while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val n = record.read(buffer, 0, buffer.size)
                        if (n <= 0) continue
                        AudioLevel.feed(buffer, n, AudioSpec.SAMPLE_RATE)   // 录音卡的音量条
                        out.write(buffer, 0, n)
                        writer.write(buffer, n)
                        if (writer.samples >= segmentSamples) {
                            out.flush()
                            finishSegment(writer, part, seq, id, wav)
                            seq++
                            part = File(segDir, "$seq.part")
                            writer = AacWriter(part)
                            if (filesDir.usableSpace < MIN_FREE_WHILE_RECORDING) { stopReason = "手機空間不足，已自動停止"; break }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Recording error", e)
                    stopReason = "錄音中斷：${e.message}"
                }
            }
            if (writer.samples < AudioSpec.SAMPLE_RATE / 10) {
                val silence = ByteArray(AudioSpec.SAMPLE_RATE / 10 * 2)
                runCatching { writer.write(silence, silence.size) }
                runCatching { FileOutputStream(wav, true).use { it.write(silence) } }
            }
            runCatching { finishSegment(writer, part, seq, id, wav) }.onFailure { Log.e(TAG, "finish last segment", it) }
            WavFile.finalizeHeader(wav)

            val total = seq + 1
            MeetingStore.update(this@PhoneRecordingService, id) {
                it.copy(status = MeetingStatus.RECEIVED, segmentsAppended = total, segmentsTotal = total,
                    durationSec = WavFile.durationSeconds(wav), error = null)
            }
            Log.d(TAG, "$id: stopped, $total segments, ${"%.0f".format(WavFile.durationSeconds(wav))}s")
            ProcessMeetingWorker.enqueue(this@PhoneRecordingService, id)
            stopReason?.let { PhoneRecorder.setError(it) }
            WatchNotifier.sendStatus(this@PhoneRecordingService, watchNode, stopReason ?: "手機錄音已結束，開始處理…")
            if (stopReason != null) scope.launch(Dispatchers.Main) { stopRecording() }
        }
    }

    /** 写完一段：改成正式档名、更新会议长度，让 Gemini 先分析录满的部分。 */
    private fun finishSegment(writer: AacWriter, part: File, seq: Int, id: String, wav: File) {
        writer.close()
        val target = File(part.parentFile, "%05d_%d.m4a".format(seq, writer.samples))
        if (!part.renameTo(target)) error("無法儲存錄音分段")
        val seconds = (wav.length() - WavFile.HEADER_SIZE).coerceAtLeast(0) / AudioSpec.BYTES_PER_SECOND.toDouble()
        MeetingStore.update(this, id) { it.copy(segmentsAppended = seq + 1, durationSec = seconds) }
        if (Settings.geminiKey(this).isNotBlank()) ProcessMeetingWorker.enqueue(this, id)
    }

    @SuppressLint("MissingPermission") // 调用前已检查 RECORD_AUDIO
    private fun createAudioRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(AudioSpec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // 手机多半放在桌上收整个会议室的声音：用 MIC（有自动增益），离得远的人声音也收得到
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, AudioSpec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 8192),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失敗" }
        return record
    }

    private fun stopRecording() {
        val record = audioRecord
        val job = recordJob
        if (record == null || job == null) {
            if (PhoneRecorder.active.value == null) stopSelfCompletely()
            return
        }
        audioRecord = null
        scope.launch {
            runCatching { record.stop() }
            job.join()
            record.release()
            recordJob = null
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            PhoneRecorder.set(null)
            broadcastState(this@PhoneRecordingService)
            stopSelfCompletely()
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        PhoneRecorder.setError(message)
        PhoneRecorder.set(null)
        broadcastState(this)
        stopSelfCompletely()
    }

    private fun stopSelfCompletely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(startedAt: Long?) = run {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "手機錄音", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("大D 手機錄音中")
            .setContentText("錄完會自動產生重點報告")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .apply { if (startedAt != null) setWhen(startedAt).setUsesChronometer(true).setShowWhen(true) }
            .addAction(0, "停止錄音", stopIntent(this@PhoneRecordingService))
            .build()
    }

    private fun startInForeground() {
        // 有定位权限才加 location 类型（萤幕关了也能完成 GPS 定位）
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (com.example.voiceai.shared.GeoFix.hasPermission(this)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(null), type)
    }

    private fun updateNotification(startedAt: Long) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(startedAt))
    }

    override fun onDestroy() {
        audioRecord?.let { runCatching { it.stop() }; it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }
}
