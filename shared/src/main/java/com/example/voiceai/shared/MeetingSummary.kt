package com.example.voiceai.shared

import org.json.JSONObject

/** 手机处理完后推回手表显示的精简摘要（完整报告留在手机上）。 */
data class MeetingSummary(
    val recordingId: String,
    val title: String,
    val summary: String,
    /** 报告整理好的时间 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 录音的时间（录音开始；汇入档案是推回来的原始录音时间）。0 = 不知道 */
    val recordedAt: Long = 0,
    /** 录音长度（秒）。0 = 不知道 */
    val durationSec: Double = 0.0,
) {
    fun toBytes(): ByteArray = JSONObject()
        .put("recordingId", recordingId)
        .put("title", title)
        .put("summary", summary)
        .put("createdAt", createdAt)
        .put("recordedAt", recordedAt)
        .put("durationSec", durationSec)
        .toString()
        .toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(bytes: ByteArray): MeetingSummary {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            return MeetingSummary(
                recordingId = json.optString("recordingId"),
                title = json.optString("title"),
                summary = json.optString("summary"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                recordedAt = json.optLong("recordedAt", 0),
                durationSec = json.optDouble("durationSec", 0.0),
            )
        }
    }
}
