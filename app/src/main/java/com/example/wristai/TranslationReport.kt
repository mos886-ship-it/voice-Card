package com.example.wristai

import android.content.Context
import com.example.voiceai.shared.RecordingIds
import java.util.Date

/**
 * 把一笔即时翻译记录做成报告：原文＋译文当成文字记录，交给跟录音一样的 AI 引擎（大D的主机 / 组合式 / Gemini / Claude）整理，
 * 也套用报告语言与领域专家模式。做出来是一笔「錄音報告」（没有音档，来源 [SOURCE]），翻译记录会连到它。
 */
object TranslationReport {
    const val SOURCE = "translation"
    private const val PREFIX = "tr_"

    fun isTranslation(m: Meeting) = m.source == SOURCE

    /** 给 AI 的文字：说明是即时翻译记录、两种语言，每句原文＋译文。 */
    fun text(s: TranslationStore.Session): String = buildString {
        append("（以下是一段即時翻譯的記錄：說的語言 ")
        append(TranslateLangs.name(s.source)).append("（").append(s.source).append("），翻成 ")
        append(TranslateLangs.name(s.target, target = true)).append("。每段第一行是原話（手機語音辨識，可能有錯字），「→」那行是手機自動翻譯，僅供參考。")
        append("對話可能是兩方輪流說不同語言；請依原話整理，翻譯有出入時以原話為準。）\n\n")
        append(TranslationStore.asText(s))
    }

    /** 建一笔报告并开始处理；回传新的报告编号（翻译记录同时连到它）。 */
    fun create(c: Context, s: TranslationStore.Session): String {
        val id = RecordingIds.newId(c, PREFIX, Date(s.startedAt))
        MeetingStore.save(c, Meeting(id, MeetingStatus.RECEIVED, s.startedAt, s.durationSec.toDouble(), null, null, null,
            source = SOURCE))
        MeetingStore.transcriptFile(c, id).writeText(text(s))
        TranslationStore.save(c, s.copy(meetingId = id))
        ProcessMeetingWorker.enqueue(c, id)
        return id
    }
}
