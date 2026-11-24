package com.example.wristai

import kotlinx.coroutines.launch
import android.util.Log
import com.example.voiceai.shared.Segment
import com.example.voiceai.shared.WavFile
import com.example.voiceai.shared.WristAiProtocol
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * 手表打开 Channel 传录音时，系统会唤起这个 Service。
 * 回调跑在后台线程上，所以这里可以直接同步读完整个文件。
 */
class AudioReceiverService : WearableListenerService() {

    companion object {
        private const val TAG = "AudioReceiver"
    }

    /** 手表遥控手机录音。 */
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WristAiProtocol.PATH_GEO_REQ) {
            // 手表开始录音：用手机的位置当 GPS 戳记（主要来源），回报手表拿到了没有
            val id = event.data.toString(Charsets.UTF_8).trim()
            val node = event.sourceNodeId
            if (!id.matches(Regex("[A-Za-z0-9_]+"))) return
            val ctx = applicationContext
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val fix = com.example.voiceai.shared.GeoFix.current(ctx, 20_000)
                if (fix != null) MeetingStore.saveGeo(ctx, id, fix.copy(source = "phone"))
                runCatching {
                    com.google.android.gms.tasks.Tasks.await(com.google.android.gms.wearable.Wearable.getMessageClient(ctx)
                        .sendMessage(node, WristAiProtocol.PATH_GEO_ACK, "$id|${if (fix != null) 1 else 0}".toByteArray()))
                }
            }
            return
        }
        if (event.path == WristAiProtocol.PATH_GEO) {
            val json = event.data.toString(Charsets.UTF_8)
            val id = runCatching { org.json.JSONObject(json).getString("id") }.getOrNull()
            val fix = com.example.voiceai.shared.GeoFix.fromJson(json)
            // id 只能是录音 id（不能带路径）
            if (id != null && fix != null && id.matches(Regex("[A-Za-z0-9_]+"))) MeetingStore.saveGeo(this, id, fix)
            return
        }
        if (event.path == WristAiProtocol.PATH_LIST_REQ) {
            WatchNotifier.sendList(this, event.sourceNodeId, event.data.toString(Charsets.UTF_8))
            return
        }
        if (event.path == WristAiProtocol.PATH_DETAIL_REQ) {
            WatchNotifier.sendDetail(this, event.sourceNodeId, event.data.toString(Charsets.UTF_8).trim())
            return
        }
        if (event.path != WristAiProtocol.PATH_PHONE_REC_CMD) return
        when (event.data.toString(Charsets.UTF_8)) {
            WristAiProtocol.CMD_START -> RemoteRecordActivity.requestFromWatch(this, event.sourceNodeId)
            WristAiProtocol.CMD_STOP ->
                if (PhoneRecorder.active.value != null) PhoneRecordingService.stop(this)
                else PhoneRecordingService.broadcastState(this)
            WristAiProtocol.CMD_QUERY -> { PhoneRecordingService.broadcastState(this); WatchNotifier.broadcastTheme(this); WatchNotifier.broadcastFoodToday(this) }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        WristAiProtocol.parseSegmentPath(channel.path)?.let { receiveSegment(channel, it); return }
        val recordingId = WristAiProtocol.recordingIdFromPath(channel.path) ?: return
        val channelClient = Wearable.getChannelClient(this)
        val target = MeetingStore.audioFile(this, recordingId)
        val tmp = java.io.File(target.parentFile, "audio.part")

        try {
            Tasks.await(channelClient.getInputStream(channel)).use { input ->
                tmp.outputStream().use { input.copyTo(it, bufferSize = 64 * 1024) }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Log.d(TAG, "Received $recordingId: ${target.length()} bytes")

            MeetingStore.save(
                this,
                Meeting(
                    id = recordingId,
                    status = MeetingStatus.RECEIVED,
                    createdAt = System.currentTimeMillis(),
                    durationSec = WavFile.durationSeconds(target),
                    watchNodeId = channel.nodeId,
                    title = null,
                    error = null,
                    lang = SpeechLang.currentCode(this),
                )
            )
            ack(channelClient, channel)
            WatchNotifier.sendStatus(this, channel.nodeId, "手機已收到錄音，開始處理…")
            ProcessMeetingWorker.enqueue(this, recordingId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to receive $recordingId", e)
            tmp.delete()
            WatchNotifier.sendStatus(this, channel.nodeId, "手機接收失敗：${e.message}")
        } finally {
            runCatching { Tasks.await(channelClient.close(channel)) }
        }
    }

    /** 长会议边录边传的一段（AAC）。 */
    private fun receiveSegment(channel: ChannelClient.Channel, seg: Segment) {
        val channelClient = Wearable.getChannelClient(this)
        val dir = MeetingStore.segmentsDir(this, seg.recordingId).apply { mkdirs() }
        val tmp = java.io.File(dir, "${seg.seq}.part")
        try {
            Tasks.await(channelClient.getInputStream(channel)).use { input ->
                tmp.outputStream().use { input.copyTo(it, bufferSize = 64 * 1024) }
            }
            Log.d(TAG, "Received segment ${seg.recordingId}#${seg.seq}: ${tmp.length()} bytes, last=${seg.last}")
            val complete = SegmentAssembler.onSegment(this, seg, tmp, channel.nodeId)
            ack(channelClient, channel)
            val meeting = MeetingStore.get(this, seg.recordingId)
            if (complete) {
                val min = ((meeting?.durationSec ?: 0.0) / 60).toInt()
                WatchNotifier.sendStatus(this, channel.nodeId, "手機已收到完整錄音（$min 分鐘），開始處理…")
                ProcessMeetingWorker.enqueue(this, seg.recordingId)
            } else if (meeting?.status == MeetingStatus.RECORDING && Settings.geminiKey(this).isNotBlank()) {
                // 还在录：让 Gemini 先处理已经满 30 分钟的部分，录完很快就有报告
                ProcessMeetingWorker.enqueue(this, seg.recordingId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to receive segment ${seg.recordingId}#${seg.seq}", e)
            tmp.delete()
            // 手表会保留这段并稍后重送
        } finally {
            runCatching { Tasks.await(channelClient.close(channel)) }
        }
    }

    /** 存好之后回传 1 个字节，手表收到才删掉自己的档案（避免手机存档失败时录音遗失）。 */
    private fun ack(channelClient: ChannelClient, channel: ChannelClient.Channel) {
        runCatching { Tasks.await(channelClient.getOutputStream(channel)).use { it.write(1); it.flush() } }
            .onFailure { Log.w(TAG, "ack failed", it) }
    }
}
