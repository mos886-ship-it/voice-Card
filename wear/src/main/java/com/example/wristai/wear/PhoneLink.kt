package com.example.wristai.wear

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import android.content.Context
import android.util.Log
import com.example.voiceai.shared.WristAiProtocol
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 手表 → 手机：用 ChannelClient 传录音。
 *
 * - 新格式：录音中每 5 分钟一段 AAC m4a（[WristAiProtocol.segmentPath]），边录边传。
 * - 旧格式：整段 WAV（[WristAiProtocol.audioPath]），只为了重送旧版本留下的录音。
 * 传送成功就删掉手表上的档案；失败就留在 pending 目录，之后自动或手动重送。
 */
object PhoneLink {
    private const val TAG = "PhoneLink"
    /** 手机解码、存档一段最多等多久。 */
    private const val ACK_TIMEOUT_MS = 90_000L

    /** 同一时间只传一个档案（录音中的自动传送与「重送」按钮不会互相干扰）。 */
    private val sendLock = Mutex()

    private suspend fun findPhone(context: Context) =
        Wearable.getCapabilityClient(context)
            .getCapability(WristAiProtocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
            .let { nodes -> nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() }
            ?: error("找不到手機".tr)

    private suspend fun sendFile(context: Context, file: File, path: String) = withContext(Dispatchers.IO) {
        val phone = findPhone(context)
        val channelClient = Wearable.getChannelClient(context)
        val channel = channelClient.openChannel(phone.id, path).await()
        try {
            val input = channelClient.getInputStream(channel).await()
            channelClient.getOutputStream(channel).await().use { out ->
                file.inputStream().use { it.copyTo(out, bufferSize = 64 * 1024) }
            }
            // 输出流关闭 = 传送结束；等手机存好后回传的确认字节，收到才删档
            val ack = coroutineScope {
                val timeout = launch { delay(ACK_TIMEOUT_MS); runCatching { channelClient.close(channel).await() } }
                val r = runCatching { input.read() }.getOrDefault(-1)
                timeout.cancel()
                r
            }
            if (ack != 1) error("手機沒有確認收到".tr)
        } catch (e: Exception) {
            runCatching { channelClient.close(channel).await() }
            throw e
        }
        Log.d(TAG, "Sent ${file.name} (${file.length()} bytes) to ${phone.displayName}")
        file.delete()
    }

    /** 遥控手机录音（[WristAiProtocol.CMD_START] / STOP / QUERY）。 */
    suspend fun sendPhoneCommand(context: Context, cmd: String) = withContext(Dispatchers.IO) {
        val phone = findPhone(context)
        Wearable.getMessageClient(context)
            .sendMessage(phone.id, WristAiProtocol.PATH_PHONE_REC_CMD, cmd.toByteArray()).await()
    }

    /** 手錶電量告訴手機（"百分比,充電中"）。 */
    suspend fun sendBattery(context: Context) = withContext(Dispatchers.IO) {
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = if (bm.isCharging) 1 else 0
        val phone = findPhone(context)
        Wearable.getMessageClient(context).sendMessage(phone.id, WristAiProtocol.PATH_BATTERY, "$pct,$charging".toByteArray()).await()
    }

    /** 把手表选的辨识语言传给手机（成功才清掉「还没传」的记号）。 */
    suspend fun sendLang(context: Context) = withContext(Dispatchers.IO) {
        val phone = findPhone(context)
        Wearable.getMessageClient(context)
            .sendMessage(phone.id, WristAiProtocol.PATH_LANG_SET, WatchState.lang.value.payload.toByteArray()).await()
        WatchState.markLangSynced(context)
    }

    /** 跟手机要录音清单（摘要页）。手机回 PATH_LIST。 */
    suspend fun requestList(context: Context, request: String = "") = withContext(Dispatchers.IO) {
        val phone = findPhone(context)
        Wearable.getMessageClient(context)
            .sendMessage(phone.id, WristAiProtocol.PATH_LIST_REQ, request.toByteArray(Charsets.UTF_8)).await()
    }

    /** 跟手机要一笔录音的完整报告（手机回 PATH_DETAIL，由 PhoneMessageListenerService 收）。 */
    suspend fun requestDetail(context: Context, id: String) = withContext(Dispatchers.IO) {
        val phone = findPhone(context)
        Wearable.getMessageClient(context)
            .sendMessage(phone.id, WristAiProtocol.PATH_DETAIL_REQ, id.toByteArray()).await()
    }

    /** 待传档案，依录音、序号排好（旧的 WAV 也算）。 */
    fun pendingFiles(context: Context): List<File> =
        WatchState.pendingDir(context).listFiles { f -> f.extension == "wav" || f.extension == "m4a" }
            ?.sortedWith(compareBy({ it.name.substringBefore("__") }, { WristAiProtocol.parseSegmentFileName(it.name)?.seq ?: 0 }))
            ?: emptyList()

    private fun pathFor(file: File): String? =
        if (file.extension == "wav") WristAiProtocol.audioPath(file.nameWithoutExtension)
        else WristAiProtocol.parseSegmentFileName(file.name)?.let { WristAiProtocol.segmentPath(it) }

    /**
     * 依序传送所有待传档案；遇到第一个失败就停（保持顺序），抛出异常。
     * 返回成功传送的数量。
     */
    suspend fun sendPending(context: Context): Int = sendLock.withLock {
        var ok = 0
        // 手机不在时改的语言：先补传，手机收到第一段录音时就用新的语言
        if (WatchState.langDirty(context)) runCatching { sendLang(context) }
        try {
            for (f in pendingFiles(context)) {
                val path = pathFor(f) ?: continue
                sendFile(context, f, path)
                ok++
                WatchState.refreshPending(context)
            }
            // 补传还没送到的 GPS 戳记
            WatchState.pendingDir(context).listFiles { f -> f.extension == "geo" }?.forEach { runCatching { sendGeo(context, it) } }
        } finally {
            WatchState.refreshPending(context)
        }
        ok
    }

    /** 请手机定位（GPS 以联动的手机为主要来源）。 */
    suspend fun requestPhoneGeo(context: Context, id: String) = withContext(Dispatchers.IO) {
        val phone = findPhone(context)
        Wearable.getMessageClient(context)
            .sendMessage(phone.id, WristAiProtocol.PATH_GEO_REQ, id.toByteArray()).await()
    }

    /** 把一笔录音的 GPS 戳记（档名 <录音id>.geo）传给手机，成功就删档。 */
    suspend fun sendGeo(context: Context, file: java.io.File) = withContext(Dispatchers.IO) {
        val fix = com.example.voiceai.shared.GeoFix.fromJson(file.readText()) ?: run { file.delete(); return@withContext }
        val json = org.json.JSONObject(fix.toJson()).put("id", file.nameWithoutExtension).toString()
        val phone = findPhone(context)
        Wearable.getMessageClient(context)
            .sendMessage(phone.id, WristAiProtocol.PATH_GEO, json.toByteArray(Charsets.UTF_8)).await()
        file.delete()
    }
}
