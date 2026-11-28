package com.example.wristai

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.voiceai.shared.MeetingSummary
import com.example.wristai.ai.AiException
import com.example.wristai.ai.LocalHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 处理一场会议：逐段语音识别 → Claude 整理报告 → 推摘要回手表。
 *
 * WorkManager 单次任务最多跑 10 分钟，所以识别每跑约 7 分钟就保存进度、
 * 排一个接续任务，长会议也能做完，App 被杀也能从断点继续。
 */
class ProcessMeetingWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ProcessMeetingWorker"
        private const val KEY_ID = "meeting_id"
        private const val TIME_BUDGET_MS = 7 * 60 * 1000L
        /** 长会议：开始新的一段前，这次任务已经跑超过 5 分钟就交给接续任务（WorkManager 单次上限 10 分钟）。 */
        private const val CHUNK_BUDGET_MS = 5 * 60 * 1000L
        private const val CHANNEL_ID = "processing"

        /** 使用者按「中断处理」时记在 error 的文字（画面用它分辨「中断」与「失败」）。 */
        const val CANCELED = "已手動中斷處理"
        const val STEP_HOST_UP = "上傳到大D的主機"
        const val STEP_HOST_AI = "大D的主機整理"
        /** 主机做完就删掉录音（含转档），只留报告文字。 */
        const val STEP_HOST_PURGE = "主機刪除錄音"
        /** 报告传回手机，主机上的报告也删掉。 */
        const val STEP_HOST_BACK = "傳回報告"

        /** 送进大D的主机伫列的工作编号（Worker 被系统停掉、App 重开后接着查，不用重传）。 */
        fun hostJobFile(context: Context, id: String) = File(MeetingStore.dir(context, id), "host_job.txt")

        const val CLEANUP_ALL = "大D的主機上的錄音與報告都已刪除"

        /** 「基本資料」的「隱私」：主机回报录音删了没（[purged]）、报告拿走后删了没（[reportGone]）。 */
        // 存中文原文（显示时才翻译，换介面语言也对）
        fun cleanupText(purged: Boolean, reportGone: Boolean): String = when {
            purged && reportGone -> CLEANUP_ALL
            purged -> "大D的主機上的錄音已刪除；報告會在 24 小時內自動刪除"
            reportGone -> "大D的主機上的報告已刪除；錄音會在 24 小時內自動刪除"
            else -> "大D的主機會在 24 小時內自動刪除錄音與報告"
        }

        /** 主机连不到多久就放弃、改用别的引擎（短暂断线 / 主机重开机都会等）。 */
        private const val HOST_GONE_MS = 15 * 60_000L

        /** 中断处理：停掉背景任务，标成「已中断」（按「重试」可以接着做）。 */
        fun cancel(context: Context, meetingId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("meeting-${meetingId}")
            // 在大D的主机排队中 / 处理中：通知主机丢掉（不占别人的位置）；按「重試」会重新送
            hostJobFile(context, meetingId).takeIf { it.exists() }?.let { f ->
                val jid = f.readText().trim()
                f.delete()
                if (jid.isNotEmpty()) Thread { com.example.wristai.ai.LocalHost.deleteJob(context, jid) }.start()
            }
            MeetingStore.update(context, meetingId) {
                it.copy(status = MeetingStatus.FAILED, error = CANCELED, stepDetail = null, processEndedAt = System.currentTimeMillis())
            }
        }

        fun enqueue(context: Context, meetingId: String) {
            val request = OneTimeWorkRequestBuilder<ProcessMeetingWorker>()
                .setInputData(workDataOf(KEY_ID to meetingId))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("meeting-${meetingId}", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    private val ctx get() = applicationContext

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        var meeting = MeetingStore.get(ctx, id) ?: return Result.failure()
        if (meeting.status == MeetingStatus.DONE) return Result.success()
        ReportLang.active = ReportLang.current(ctx)   // 报告用的语言（提示词、说话者代号、饮食表格）
        ReportDomain.prepare(ctx)                     // 领域专家模式（法律、医疗…；自动判断）
        Vocab.prepare(ctx)                            // 个人词库＋常用人名
        val node = meeting.watchNodeId
        val startedAt = System.currentTimeMillis()

        val recordedAt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN).format(Date(meeting.createdAt)) +
            (if (meeting.source == AudioImporter.SOURCE_CALL) "（這是一通電話的通話錄音，通常是兩位說話者：本人與對方${meeting.title?.let { "，檔名：$it" } ?: ""}）" else "") +
            (MeetingStore.geo(ctx, id)?.place?.let { (if (meeting.source == FoodPhotos.SOURCE) "，拍攝地點：" else "，錄音地點：") + it } ?: "")

        // ---------- 即时翻译记录做成的报告：只有文字，没有录音 ----------
        if (TranslationReport.isTranslation(meeting)) return processText(id, meeting, recordedAt)

        // ---------- 手表还在录（长会议边录边传）：先把已经录满的 Gemini 段处理掉 ----------
        val mode = com.example.wristai.ai.AiConfig.mode(ctx)
        if (meeting.status == MeetingStatus.RECORDING) {
            val key = Settings.geminiKey(ctx)
            // 边录边转录只有 Gemini 模式有；组合式录完再一次辨识
            if (key.isBlank() || mode != com.example.wristai.ai.AiConfig.MODE_GEMINI) return Result.success()
            return try {
                withContext(Dispatchers.IO) {
                    LongMeeting.processChunks(ctx, id, final = false, deadline = startedAt + CHUNK_BUDGET_MS,
                        gemini = GeminiReportGenerator(key, SpeechLang.geminiNote(meeting.lang, meeting.dialect)), recordedAt = recordedAt) { done, _ ->
                        MeetingStore.update(ctx, id) { it.copy(chunkDone = done) }
                    }
                }
                MeetingStore.update(ctx, id) { m ->
                    var k = 0
                    while (LongMeeting.chunkFile(ctx, id, k).exists()) k++
                    m.copy(chunkDone = k)
                }
                Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 录音中先转录只是加速；失败了录完后会再做，不把会议标成失败
                Log.w(TAG, "${id}: early chunk transcription failed: ${e.message}")
                Result.success()
            }
        }

        // ---------- 一按就停（不到 1 秒，手表 / 手机都算）：误触，不送辨识，整笔丢掉 ----------
        run {
            val wav = MeetingStore.audioFile(ctx, id)
            // 分段全部到齐才判断（手表边录边传，还没到齐的不能当成太短）
            val complete = meeting.segmentsTotal >= 1 && meeting.segmentsAppended >= meeting.segmentsTotal
            val tooShort = complete && wav.exists() && com.example.voiceai.shared.WavFile.durationSeconds(wav) < PhoneRecordingService.MIN_RECORDING_SEC
            if (tooShort && meeting.importedAt == null && meeting.source != AudioImporter.SOURCE_CALL &&
                FoodPhotos.files(ctx, id).isEmpty()) {
                Log.d(TAG, "${id}: shorter than ${PhoneRecordingService.MIN_RECORDING_SEC}s, discarded")
                MeetingStore.delete(ctx, id)
                WatchNotifier.sendStatus(ctx, node, "錄音不到 1 秒，已取消".tr)
                return Result.success()
            }
        }

        // ---------- 饮食记录：估算热量与营养素（需要云端 AI） ----------
        if (FoodLog.isFood(id)) {
            // 这一餐在哪个国家 → 用当地的营养资料库（台湾食藥署、美国 USDA、日本成分表、韩国食药处）与当地份量
            FoodRegion.active = FoodRegion.of(ctx, id)
            FoodGoals.active = FoodGoals.mode(ctx)   // 健康目標：營養小提醒照目標講重點
            FoodDb.use(ctx, FoodRegion.active)
            // 有照片：只有 Gemini 看得懂图片（免费额度就够），主机 / 组合式先不走
            val photos = FoodPhotos.files(ctx, id)
            if (photos.isNotEmpty()) return processFood(id, meeting, recordedAt, photos)
            tryLocalHost(id, meeting, food = true)?.let { return it }
            return if (mode == com.example.wristai.ai.AiConfig.MODE_PIPELINE) processPipeline(id, meeting, recordedAt, startedAt, food = true)
            else processFood(id, meeting, recordedAt)
        }

        // 这次要走的步骤（手机画面显示进度用）
        run {
            val exportPlanned = meeting.exportedUri == null && Settings.exportToVoiceRecorder(ctx) && meeting.source != AudioImporter.SOURCE_CALL
            val geminiOn = mode == com.example.wristai.ai.AiConfig.MODE_GEMINI && Settings.geminiKey(ctx).isNotBlank()
            plan(id, buildList {
                add("接收錄音")
                if (exportPlanned) add("存入錄音機")
                when {
                    mode == com.example.wristai.ai.AiConfig.MODE_PIPELINE -> { add("語音辨識"); add("報告整理") }
                    geminiOn && LongMeeting.isLong(ctx, meeting) -> { add("分段分析"); add("整合報告") }
                    geminiOn -> { add("上傳音檔"); add("AI 聆聽整理") }
                    Settings.apiKey(ctx).isNotBlank() && !com.example.wristai.ai.AiConfig.explicit(ctx) -> { add("語音辨識"); add("報告整理") }
                }
            })
        }

        try {
            val audio = MeetingStore.audioFile(ctx, id)

            // ---------- 0. 存进三星录音机（给 Galaxy AI 转录 / 摘要） ----------
            if (meeting.exportedUri == null && Settings.exportToVoiceRecorder(ctx) && meeting.source != AudioImporter.SOURCE_CALL) {
                stepTo(id, "存入錄音機")
                val segments = SegmentAssembler.segmentFiles(ctx, id)
                    .takeIf { meeting.segmentsTotal > 0 && it.size == meeting.segmentsTotal }.orEmpty()
                val uri = withContext(Dispatchers.IO) {
                    VoiceRecorderExporter.export(ctx, audio, meeting.createdAt, segments)
                }
                meeting = MeetingStore.update(ctx, id) { it.copy(exportedUri = uri.toString()) }!!
            }
            // 分段已经接进 audio.wav、也存进录音机了，删掉省空间
            MeetingStore.segmentsDir(ctx, id).takeIf { it.exists() && (meeting.exportedUri != null || !Settings.exportToVoiceRecorder(ctx)) }
                ?.deleteRecursively()

            // ---------- 有 Gemini Key（免费）→ Gemini 直接听录音，出报告 + 逐字稿 ----------
            // ---------- 组合式：语音辨识 ＋ 报告模型（百炼、讯飞、OpenAI、Meta、Claude） ----------
            // ---------- 大D的主機（使用者自己的电脑）：有设定就优先，连不到再走下面的引擎 ----------
            tryLocalHost(id, meeting, food = false)?.let { return it }

            if (mode == com.example.wristai.ai.AiConfig.MODE_PIPELINE) return processPipeline(id, meeting, recordedAt, startedAt, food = false)

            val geminiKey = Settings.geminiKey(ctx).takeIf { mode == com.example.wristai.ai.AiConfig.MODE_GEMINI }.orEmpty()
            if (geminiKey.isNotBlank() && LongMeeting.isLong(ctx, meeting)) {
                // ---------- 长会议：每 30 分钟一段转录，最后合成报告 ----------
                val gemini = GeminiReportGenerator(geminiKey, SpeechLang.geminiNote(meeting.lang, meeting.dialect))
                val total = LongMeeting.chunkCount(meeting.durationSec, Settings.geminiChunkSec(ctx))
                MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.SUMMARIZING, error = null, chunkTotal = total) }
                val done = withContext(Dispatchers.IO) {
                    LongMeeting.processChunks(ctx, id, final = true, deadline = startedAt + CHUNK_BUDGET_MS,
                        gemini = gemini, recordedAt = recordedAt) { k, n ->
                        MeetingStore.update(ctx, id) { it.copy(chunkDone = k, chunkTotal = n) }
                        stepTo(id, "分段分析", "Gemini · {0}/{1} 段".trf(minOf(k + 1, n), n))
                        WatchNotifier.sendStatus(ctx, node, "Gemini 分析中 {0}/{1} 段".trf(k + 1, n))
                    }
                }
                if (!done) {
                    Log.d(TAG, "${id}: time budget used, continuing chunks in a new task")
                    enqueue(ctx, id)
                    return Result.success()
                }
                MeetingStore.update(ctx, id) { it.copy(chunkDone = total) }
                stepTo(id, "整合報告", "Gemini")
                WatchNotifier.sendStatus(ctx, node, "Gemini 整合完整報告…".tr)
                val (report, transcript) = withContext(Dispatchers.IO) {
                    LongMeeting.finish(ctx, id, gemini, meeting.durationSec, recordedAt)
                }
                MeetingStore.transcriptFile(ctx, id).writeText(transcript)
                MeetingStore.reportFile(ctx, id).writeText(report.markdown)
                val names = Speakers.namesFromReport(report.markdown)
                MeetingStore.update(ctx, id) {
                    it.copy(status = MeetingStatus.DONE, title = report.title, speakerNames = names, aiModel = gemini.lastModel)
                }
                finishSteps(id)
                val summary = Speakers.apply(report.shortSummary, names)
                WatchNotifier.sendSummary(ctx, node, MeetingSummary(id, report.title, summary))
                ReadyNotification.showReport(ctx, id, report.title, summary)
                return Result.success()
            }

            if (geminiKey.isNotBlank()) {
                MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.SUMMARIZING, error = null) }
                stepTo(id, "上傳音檔", "Gemini")
                WatchNotifier.sendStatus(ctx, node, "Gemini 整理報告中…".tr)
                val result = withContext(Dispatchers.IO) {
                    val compact = File(ctx.cacheDir, "gemini_${id}.m4a")
                    try {
                        VoiceRecorderExporter.encodeM4a(audio, compact, outRate = 16_000, bitRate = 32_000)
                        GeminiReportGenerator(geminiKey, SpeechLang.geminiNote(meeting.lang, meeting.dialect)).generate(compact, meeting.durationSec, recordedAt,
                            onListening = { stepTo(id, "AI 聆聽整理", "Gemini") })
                    } finally {
                        compact.delete()
                    }
                }
                Log.d(TAG, "${id}: report by ${result.model}")
                MeetingStore.transcriptFile(ctx, id).writeText(result.transcript)
                MeetingStore.reportFile(ctx, id).writeText(result.report.markdown)
                val names = Speakers.namesFromReport(result.report.markdown)
                MeetingStore.update(ctx, id) {
                    it.copy(status = MeetingStatus.DONE, title = result.report.title, speakerNames = names, aiModel = result.model)
                }
                finishSteps(id)
                val summary = Speakers.apply(result.report.shortSummary, names)
                WatchNotifier.sendSummary(ctx, node, MeetingSummary(id, result.report.title, summary))
                ReadyNotification.showReport(ctx, id, result.report.title, summary)
                return Result.success()
            }

            // 没有任何 AI Key（或选了只用 Galaxy AI）→ 只交给 Galaxy AI，到此结束
            if (Settings.apiKey(ctx).isBlank() || com.example.wristai.ai.AiConfig.explicit(ctx)) {
                MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.EXPORTED, error = null) }
                finishSteps(id)
                WatchNotifier.sendStatus(ctx, node, "已存入手機「錄音機」，用 Galaxy AI 轉錄 / 摘要".tr)
                ReadyNotification.show(ctx, id, meeting.durationSec)
                return Result.success()
            }

            // ---------- 1. 语音识别（可分多次任务完成） ----------
            val total = SpeechTranscriber.chunkCount(audio)
            if (meeting.chunkDone < total) {
                meeting = MeetingStore.update(ctx, id) {
                    it.copy(status = MeetingStatus.TRANSCRIBING, chunkTotal = total, error = null)
                }!!
                val transcriber = SpeechTranscriber(ctx, SpeechLang.of(meeting.lang).let { if (it.other) it.locale else Settings.language(ctx) })
                val transcriptFile = MeetingStore.transcriptFile(ctx, id)
                if (meeting.chunkDone == 0) transcriptFile.writeText("")

                for (i in meeting.chunkDone until total) {
                    if (System.currentTimeMillis() - startedAt > TIME_BUDGET_MS) {
                        Log.d(TAG, "${id}: time budget used, continuing at chunk ${i}")
                        enqueue(ctx, id)
                        return Result.success()
                    }
                    stepTo(id, "語音辨識", "手機內建辨識 · {0}/{1} 段".trf(i + 1, total))
                    WatchNotifier.sendStatus(ctx, node, "語音辨識中 {0}/{1}".trf(i + 1, total))
                    val text = transcriber.transcribeChunk(audio, i)
                    if (text.isNotBlank()) {
                        val sec = i * SpeechTranscriber.CHUNK_SECONDS
                        transcriptFile.appendText("[%02d:%02d] %s\n".format(sec / 60, sec % 60, text))
                    }
                    meeting = MeetingStore.update(ctx, id) { it.copy(chunkDone = i + 1) }!!
                }
            }

            // ---------- 2. Claude 整理报告 ----------
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.SUMMARIZING, error = null) }
            stepTo(id, "報告整理", "Claude")
            WatchNotifier.sendStatus(ctx, node, "AI 整理報告中…".tr)
            val transcript = MeetingStore.transcriptFile(ctx, id).takeIf { it.exists() }?.readText().orEmpty()
            val report = withContext(Dispatchers.IO) {
                ReportGenerator(Settings.apiKey(ctx)).generate(transcript, meeting.durationSec, recordedAt)
            }
            MeetingStore.reportFile(ctx, id).writeText(report.markdown)
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.DONE, title = report.title, aiModel = ReportGenerator.MODEL) }
            finishSteps(id)

            // ---------- 3. 推摘要回手表 ----------
            WatchNotifier.sendSummary(ctx, node, MeetingSummary(id, report.title, report.shortSummary))
            return Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isStopped) return Result.failure()   // 使用者按了「中断处理」
            Log.e(TAG, "${id} failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.FAILED, error = msg) }
            WatchNotifier.sendStatus(ctx, node, "處理失敗：{0}（可在手機上重試）".trf(msg))
            return Result.failure()
        }
    }

    /**
     * 组合式 AI：语音辨识（可分多次任务）→ 逐字稿 → 报告模型。[food] = 饮食记录（估算热量）。
     */
    private suspend fun processPipeline(id: String, meeting: Meeting, recordedAt: String, startedAt: Long, food: Boolean): Result {
        val node = meeting.watchNodeId
        val stt = com.example.wristai.ai.AiConfig.stt(ctx)
        val llm = com.example.wristai.ai.AiConfig.llm(ctx)
        if (stt == null || llm == null) {
            val msg = (com.example.wristai.ai.AiConfig.pipelineMissing(ctx) ?: "AI 引擎設定不完整".tr) + "（設定 → AI 引擎）".tr
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.FAILED, error = msg) }
            WatchNotifier.sendStatus(ctx, node, "請在手機完成 AI 引擎設定".tr)
            return Result.success()
        }
        if (food) plan(id, listOf("接收錄音", "語音辨識", "估算熱量"))
        return try {
            // ---------- 1. 语音辨识 ----------
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.TRANSCRIBING, error = null) }
            val finished = withContext(Dispatchers.IO) {
                com.example.wristai.ai.Pipeline.transcribe(ctx, id, meeting.durationSec, stt, startedAt + TIME_BUDGET_MS) { done, total ->
                    MeetingStore.update(ctx, id) { it.copy(chunkDone = done, chunkTotal = total) }
                    stepTo(id, "語音辨識", stt.name + if (total > 1) " · {0}/{1} 段".trf(minOf(done + 1, total), total) else "")
                    WatchNotifier.sendStatus(ctx, node, "語音辨識中（{0}）".trf(stt.name) + if (total > 1) " ${minOf(done + 1, total)}/${total}" else "…")
                }
            }
            if (!finished) {
                Log.d(TAG, "${id}: speech recognition still running, continuing in a new task")
                enqueue(ctx, id)
                return Result.success()
            }
            val transcript = com.example.wristai.ai.Pipeline.transcriptText(ctx, id)
            if (transcript.isBlank()) throw com.example.wristai.ai.AiException("語音辨識沒有聽到可辨識的內容".tr)
            MeetingStore.transcriptFile(ctx, id).writeText(transcript)

            // ---------- 2. 报告模型 ----------
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.SUMMARIZING, error = null) }
            stepTo(id, if (food) "估算熱量" else "報告整理", llm.name)
            WatchNotifier.sendStatus(ctx, node, if (food) "估算熱量中（{0}）…".trf(llm.name) else "AI 整理報告中（{0}）…".trf(llm.name))
            val raw = withContext(Dispatchers.IO) {
                com.example.wristai.ai.Pipeline.report(llm, transcript, meeting.durationSec, recordedAt,
                    if (food) FoodLog.TEXT_PROMPT else GeminiReportGenerator.TEXT_REPORT_PROMPT)
            }
            val report = ReportGenerator.parseReport(raw.substringBefore(GeminiReportGenerator.TRANSCRIPT_MARKER).trim())
            val model = "${stt.modelId}|${llm.modelId}"
            if (food) {
                val done = FoodLog.finish(raw, report.markdown, report.shortSummary)
                MeetingStore.reportFile(ctx, id).writeText(done.markdown)
                MeetingStore.update(ctx, id) {
                    it.copy(status = MeetingStatus.DONE, title = report.title, nutrition = done.nutrition, aiModel = model)
                }
                WatchNotifier.sendSummary(ctx, node, MeetingSummary(id, report.title, done.summary))
                WatchNotifier.broadcastFoodToday(ctx)
                ReadyNotification.showReport(ctx, id, report.title, done.summary)
            } else {
                MeetingStore.reportFile(ctx, id).writeText(report.markdown)
                val names = Speakers.namesFromReport(report.markdown)
                MeetingStore.update(ctx, id) {
                    it.copy(status = MeetingStatus.DONE, title = report.title, speakerNames = names, aiModel = model)
                }
                val summary = Speakers.apply(report.shortSummary, names)
                WatchNotifier.sendSummary(ctx, node, MeetingSummary(id, report.title, summary))
                ReadyNotification.showReport(ctx, id, report.title, summary)
            }
            finishSteps(id)
            Log.d(TAG, "${id}: report by ${model}")
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isStopped) return Result.failure()   // 使用者按了「中断处理」
            Log.e(TAG, "${id} pipeline failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.FAILED, error = msg) }
            WatchNotifier.sendStatus(ctx, node, "處理失敗：{0}（可在手機上重試）".trf(msg))
            Result.failure()
        }
    }

    /**
     * 只有文字的报告（即时翻译记录）：逐字稿已经在 transcript.txt。
     * 引擎顺序跟录音一样：大D的主机（有设定）→ 组合式的报告模型 → Gemini → Claude；都没有就标失败。
     */
    private suspend fun processText(id: String, meeting: Meeting, recordedAt: String): Result {
        val node = meeting.watchNodeId
        val cfg = com.example.wristai.ai.AiConfig
        val host = com.example.wristai.ai.LocalHost
        val text = MeetingStore.transcriptFile(ctx, id).takeIf { it.exists() }?.readText().orEmpty()
        if (text.isBlank()) {
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.FAILED, error = "翻譯記錄是空的".tr) }
            return Result.success()
        }
        val mode = cfg.mode(ctx)
        val llm = if (mode == cfg.MODE_PIPELINE) cfg.llm(ctx) else null
        val gemini = Settings.geminiKey(ctx).takeIf { it.isNotBlank() }
        val claude = Settings.apiKey(ctx).takeIf { it.isNotBlank() }
        val useHost = host.active(ctx)
        plan(id, listOf("接收記錄") + (if (useHost) listOf(STEP_HOST_UP, STEP_HOST_AI, STEP_HOST_PURGE, STEP_HOST_BACK) else listOf("報告整理")))
        MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.SUMMARIZING, error = null, engineNote = null) }

        fun finish(raw: String, model: String) {
            val report = ReportGenerator.parseReport(raw.substringBefore(GeminiReportGenerator.TRANSCRIPT_MARKER).trim())
            MeetingStore.reportFile(ctx, id).writeText(report.markdown)
            val names = Speakers.namesFromReport(report.markdown)
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.DONE, title = report.title, speakerNames = names, aiModel = model) }
            finishSteps(id)
            ReadyNotification.showReport(ctx, id, report.title, Speakers.apply(report.shortSummary, names))
        }

        return try {
            // 1. 大D的主机：文字直接送（X-Input: text），失败再换别的
            if (useHost) {
                val ok = try {
                    stepTo(id, STEP_HOST_UP, "大D的主機".tr)
                    val asciiAt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(meeting.createdAt))
                    val res = withContext(Dispatchers.IO) {
                        hostJob(id, node, false, STEP_HOST_AI, hostJobFile(ctx, id)) {
                            host.submit(ctx, "meeting", asciiAt, meeting.durationSec, text = text)
                        }
                    }
                    finish(res.text, res.model); true
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    if (isStopped) return Result.failure()
                    val why = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "${id}: host failed for text: $why")
                    hostJobFile(ctx, id).delete()
                    if (llm == null && gemini == null && claude == null) throw e
                    MeetingStore.update(ctx, id) { it.copy(engineNote = why.trimEnd('。') + "。這次改用其他 AI。".tr) }
                    plan(id, listOf("接收記錄", "報告整理"))
                    false
                }
                if (ok) return Result.success()
            }
            // 2. 其他引擎
            val (raw, model) = withContext(Dispatchers.IO) {
                when {
                    llm != null -> {
                        stepTo(id, "報告整理", llm.name)
                        com.example.wristai.ai.Pipeline.report(llm, text, meeting.durationSec, recordedAt, GeminiReportGenerator.TEXT_REPORT_PROMPT) to "text|${llm.modelId}"
                    }
                    gemini != null -> {
                        stepTo(id, "報告整理", "Gemini")
                        val g = GeminiReportGenerator(gemini)
                        g.completeText(GeminiReportGenerator.TEXT_REPORT_PROMPT, "記錄時間：$recordedAt。請依格式整理以下文字記錄：\n<transcript>\n$text\n</transcript>") to
                            "text|${g.lastModel ?: "gemini"}"
                    }
                    claude != null -> {
                        stepTo(id, "報告整理", "Claude")
                        val r = ReportGenerator(claude).generate(text, meeting.durationSec, recordedAt)
                        ("TITLE: ${r.title}\nSUMMARY: ${r.shortSummary}\n${r.markdown}") to "text|${ReportGenerator.MODEL}"
                    }
                    else -> throw com.example.wristai.ai.AiException("翻譯記錄做報告需要 AI 引擎：大D的主機、Gemini、組合式或 Claude（設定 → AI 引擎）".tr)
                }
            }
            finish(raw, model)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isStopped) return Result.failure()
            Log.e(TAG, "${id} text report failed", e)
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.FAILED, error = e.message ?: e.javaClass.simpleName) }
            Result.failure()
        }
    }

    private suspend fun processFood(id: String, meeting: Meeting, recordedAt: String, photos: List<File> = emptyList()): Result {
        val node = meeting.watchNodeId
        val key = Settings.geminiKey(ctx)
        // 只有照片、没有口述
        val hasAudio = meeting.durationSec > 0.3 && MeetingStore.audioFile(ctx, id).exists()
        if (key.isBlank()) {
            val msg = if (photos.isNotEmpty()) "照片辨識飲食需要 Gemini（免費）：請到設定填 Gemini 金鑰".tr
                else "飲食記錄需要雲端 AI 估算熱量：請到設定啟用 Gemini（免費）".tr
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.FAILED, error = msg) }
            WatchNotifier.sendStatus(ctx, node, "請在手機啟用雲端 AI".tr)
            return Result.success()
        }
        return try {
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.SUMMARIZING, error = null) }
            plan(id, buildList {
                add(if (hasAudio) "接收錄音" else "接收照片")
                if (hasAudio) add("上傳音檔")
                add("估算熱量")
            })
            stepTo(id, if (hasAudio) "上傳音檔" else "估算熱量", "Gemini")
            WatchNotifier.sendStatus(ctx, node, "估算熱量中…".tr)
            val result = withContext(Dispatchers.IO) {
                val compact = File(ctx.cacheDir, "gemini_${id}.m4a")
                try {
                    if (hasAudio) VoiceRecorderExporter.encodeM4a(MeetingStore.audioFile(ctx, id), compact, outRate = 16_000, bitRate = 32_000)
                    // 台语 / 客语：提示词加语言说明（跟录音报告一样）
                    GeminiReportGenerator(key, if (hasAudio) SpeechLang.geminiNote(meeting.lang, meeting.dialect) else "")
                        .generate(if (hasAudio) compact else null, meeting.durationSec, recordedAt, system = FoodLog.PROMPT,
                        onListening = { stepTo(id, "估算熱量", "Gemini") }, images = photos)
                } finally {
                    compact.delete()
                }
            }
            val done = FoodLog.finish(result.raw, result.report.markdown, result.report.shortSummary)
            val nutrition = done.nutrition
            MeetingStore.transcriptFile(ctx, id).writeText(result.transcript)
            MeetingStore.reportFile(ctx, id).writeText(done.markdown)
            MeetingStore.update(ctx, id) {
                it.copy(status = MeetingStatus.DONE, title = result.report.title, nutrition = nutrition, aiModel = result.model)
            }
            finishSteps(id)
            Log.d(TAG, "${id}: food log ${nutrition?.kcal} kcal by ${result.model}")
            WatchNotifier.sendSummary(ctx, node, MeetingSummary(id, result.report.title, done.summary))
            WatchNotifier.broadcastFoodToday(ctx)
            ReadyNotification.showReport(ctx, id, result.report.title, done.summary)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isStopped) return Result.failure()   // 使用者按了「中断处理」
            Log.e(TAG, "${id} food log failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.FAILED, error = msg) }
            WatchNotifier.sendStatus(ctx, node, "處理失敗：{0}（可在手機上重試）".trf(msg))
            Result.failure()
        }
    }

    /**
     * 大D的主機：有设定就优先（录音不出使用者的设备，隐私上等同装置端）。
     * 回传 null = 这次没由主机处理（连不到 / 失败，而且还有别的引擎可用；原因写进 engineNote），交给后面的路由。
     */
    private suspend fun tryLocalHost(id: String, meeting: Meeting, food: Boolean): Result? {
        val host = com.example.wristai.ai.LocalHost
        val cfg = com.example.wristai.ai.AiConfig
        if (!host.active(ctx)) return null
        val node = meeting.watchNodeId
        val mode = cfg.mode(ctx)
        val pipelineOk = mode == cfg.MODE_PIPELINE && cfg.pipelineMissing(ctx) == null
        val geminiOk = Settings.geminiKey(ctx).isNotBlank()
        val fallback = when {
            pipelineOk -> "組合式 AI".tr
            geminiOk && (food || mode == cfg.MODE_GEMINI) -> "Gemini"
            // 录音：没有别的云端引擎时照旧交给三星录音机（Galaxy AI），之后可以按「重試」再给主机
            !food -> "手機的錄音機（Galaxy AI）".tr
            else -> null
        }
        val before = MeetingStore.get(ctx, id)?.steps.orEmpty()
        val lastStep = if (food) "估算熱量" else STEP_HOST_AI
        plan(id, before.filter { it == "接收錄音" || it == "存入錄音機" }.ifEmpty { listOf("接收錄音") } + listOf(STEP_HOST_UP, lastStep, STEP_HOST_PURGE, STEP_HOST_BACK))
        // 标头只能放 ASCII：录音时间用数字格式
        val asciiAt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(meeting.createdAt))
        return try {
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.SUMMARIZING, error = null, engineNote = null) }
            stepTo(id, STEP_HOST_UP, "大D的主機".tr)
            WatchNotifier.sendStatus(ctx, node, if (food) "大D的主機估算熱量中…".tr else "大D的主機整理報告中…".tr)
            // 长录音在主机上可能超过 10 分钟：升成前台工作，避免被系统中途停掉
            runCatching { setForeground(getForegroundInfo()) }
            val res = withContext(Dispatchers.IO) {
                val h = host.health(ctx)   // 5 秒：连不到就马上改用别的引擎，不要卡在上传
                val lang = SpeechLang.of(meeting.lang).code
                // 语言交给主机判断（主机认不得会回错误，再改用别的引擎）
                val dialect = if (SpeechLang.of(meeting.lang) == SpeechLang.HAK) HakkaDialect.of(meeting.dialect).code else null
                val jobFile = hostJobFile(ctx, id)
                if (!h.jobs && !jobFile.exists()) {
                    // 旧版主机：连着等结果
                    val compact = File(ctx.cacheDir, "local_${id}.m4a")
                    try {
                        VoiceRecorderExporter.encodeM4a(MeetingStore.audioFile(ctx, id), compact, outRate = 16_000, bitRate = 32_000)
                        host.process(ctx, if (food) "food" else "meeting", asciiAt, meeting.durationSec, audio = compact,
                            lang = lang, dialect = dialect, onUploaded = { stepTo(id, lastStep, "大D的主機".tr) })
                            .also {
                                // 旧主机处理完就把暂存的录音删掉；报告直接随回应传回、主机不留
                                stepTo(id, STEP_HOST_PURGE, "主機上的錄音已刪除".tr)
                                stepTo(id, STEP_HOST_BACK, "已收到報告".tr)
                                MeetingStore.update(ctx, id) { m -> m.copy(hostCleanup = CLEANUP_ALL) }
                            }
                    } finally {
                        compact.delete()
                    }
                } else hostJob(id, node, food, lastStep, jobFile) {
                    val compact = File(ctx.cacheDir, "local_${id}.m4a")
                    try {
                        stepTo(id, STEP_HOST_UP, if (h.ahead > 0) host.queueText(h.ahead, h.waitSec) else "大D的主機".tr)
                        VoiceRecorderExporter.encodeM4a(MeetingStore.audioFile(ctx, id), compact, outRate = 16_000, bitRate = 32_000)
                        host.submit(ctx, if (food) "food" else "meeting", asciiAt, meeting.durationSec, audio = compact,
                            lang = lang, dialect = dialect)
                    } finally {
                        compact.delete()
                    }
                }
            }
            val (report, transcript) = GeminiReportGenerator("").parse(res.text)
            MeetingStore.transcriptFile(ctx, id).writeText(transcript)
            if (food) {
                val done = FoodLog.finish(res.text, report.markdown, report.shortSummary)
                MeetingStore.reportFile(ctx, id).writeText(done.markdown)
                MeetingStore.update(ctx, id) {
                    it.copy(status = MeetingStatus.DONE, title = report.title, nutrition = done.nutrition, aiModel = res.model)
                }
                finishSteps(id)
                WatchNotifier.sendSummary(ctx, node, MeetingSummary(id, report.title, done.summary))
                WatchNotifier.broadcastFoodToday(ctx)
                ReadyNotification.showReport(ctx, id, report.title, done.summary)
            } else {
                MeetingStore.reportFile(ctx, id).writeText(report.markdown)
                val names = Speakers.namesFromReport(report.markdown)
                MeetingStore.update(ctx, id) {
                    it.copy(status = MeetingStatus.DONE, title = report.title, speakerNames = names, aiModel = res.model)
                }
                finishSteps(id)
                val summary = Speakers.apply(report.shortSummary, names)
                WatchNotifier.sendSummary(ctx, node, MeetingSummary(id, report.title, summary))
                ReadyNotification.showReport(ctx, id, report.title, summary)
            }
            Log.d(TAG, "${id}: report by ${res.model}")
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isStopped) return Result.failure()   // 使用者按了「中断处理」
            val why = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "${id}: local host failed: ${why}")
            // 改用别的引擎 / 标失败：主机上排着的那笔就不要了
            hostJobFile(ctx, id).takeIf { it.exists() }?.let { f ->
                f.readText().trim().takeIf { it.isNotEmpty() }?.let { jid -> withContext(Dispatchers.IO) { host.deleteJob(ctx, jid) } }
                f.delete()
            }
            if (fallback != null) {
                MeetingStore.update(ctx, id) { it.copy(engineNote = why.trimEnd('。') + "。這次改用 {0}。".trf(fallback)) }
                if (before.isNotEmpty() && !food) plan(id, before)
                null
            } else {
                MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.FAILED, error = why) }
                WatchNotifier.sendStatus(ctx, node, "處理失敗：{0}（可在手機上重試）".trf(why))
                Result.failure()
            }
        }
    }

    /**
     * 新版主机：送进伫列（[submit]）→ 每 5 秒查一次排队 / 处理进度（处理步骤显示「前面還有 N 筆」，有变才通知手表）
     * → 做完拿结果、删掉主机上的那笔。工作编号存在 [jobFile]：Worker 被停掉再跑时接着查，不重传。
     * 主机找不到这笔（过期 / 主机换了）就重送一次；连不到超过 15 分钟才放弃。
     */
    private suspend fun hostJob(id: String, node: String?, food: Boolean, lastStep: String, jobFile: File,
                                submit: () -> LocalHost.Job): LocalHost.Result {
        val host = com.example.wristai.ai.LocalHost
        var jid = jobFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
        var resent = false
        var lastSaid = ""
        var unreachableSince = 0L
        while (true) {
            if (jid == null) {
                val j = submit()
                jid = j.id
                jobFile.writeText(j.id)
            }
            val j = try {
                host.job(ctx, jid).also { unreachableSince = 0L }
            } catch (e: LocalHost.JobGone) {
                jobFile.delete()
                if (resent) throw AiException("大D的主機找不到這筆工作".tr)
                resent = true
                jid = null
                continue
            } catch (e: LocalHost.Unreachable) {
                val now = System.currentTimeMillis()
                if (unreachableSince == 0L) unreachableSince = now
                if (now - unreachableSince > HOST_GONE_MS) throw AiException(e.message ?: "連不到大D的主機".tr)
                stepTo(id, lastStep, "暫時連不到大D的主機，等它回來…".tr)
                delay(15_000)
                continue
            }
            when (j.state) {
                "done" -> {
                    // 显示给使用者看：主机上的录音已删、报告传回后主机上也删掉
                    stepTo(id, STEP_HOST_PURGE, if (j.purged) "主機上的錄音已刪除，只留報告文字".tr else "主機會在 24 小時內刪除錄音".tr)
                    delay(600)
                    stepTo(id, STEP_HOST_BACK, "下載報告中…".tr)
                    val gone = host.deleteJob(ctx, jid)
                    stepTo(id, STEP_HOST_BACK, if (gone) "已收到報告，主機上的報告也已刪除".tr else "已收到報告（主機會在 24 小時內刪除）".tr)
                    MeetingStore.update(ctx, id) { m -> m.copy(hostCleanup = cleanupText(j.purged, gone)) }
                    delay(600)
                    jobFile.delete()
                    return LocalHost.Result(j.text ?: throw AiException("大D的主機沒有回傳報告".tr), j.model ?: host.DEFAULT_TAG)
                }
                "error" -> {
                    host.deleteJob(ctx, jid)
                    jobFile.delete()
                    throw AiException(j.error ?: "大D的主機處理失敗".tr)
                }
                "queued" -> {
                    val say = host.queueText(j.ahead, j.waitSec)
                    stepTo(id, lastStep, say)
                    // 手表只在「前面还有几笔」变了才通知（不要每 5 秒震一次）
                    val brief = if (j.ahead > 0) "大D的主機排隊中，前面還有 {0} 筆".trf(j.ahead) else ""
                    if (brief.isNotEmpty() && brief != lastSaid) WatchNotifier.sendStatus(ctx, node, brief)
                    lastSaid = brief
                }
                else -> {   // running
                    stepTo(id, lastStep, "大D的主機".tr + if (j.waitSec > 60) "（約 {0} 分鐘）".trf((j.waitSec + 59) / 60) else "")
                    if (lastSaid != "running") WatchNotifier.sendStatus(ctx, node, if (food) "大D的主機估算熱量中…".tr else "大D的主機整理報告中…".tr)
                    lastSaid = "running"
                }
            }
            delay(5_000)
        }
    }

    // ---------- 处理步骤（手机画面进度） ----------

    /** 设定这次的步骤；同样的步骤已经在跑（接续任务）就不重设。第 0 步「接收錄音」一开始就算完成。 */
    private fun plan(id: String, steps: List<String>) {
        val now = System.currentTimeMillis()
        MeetingStore.update(ctx, id) { m ->
            if (m.steps == steps && m.step in 1 until steps.size) m
            else m.copy(steps = steps, step = minOf(1, steps.size), stepDetail = null, stepStartedAt = now,
                processStartedAt = now, processEndedAt = 0)
        }
    }

    /** 进到某一步（名字要在步骤清单里，不在就略过）。 */
    private fun stepTo(id: String, name: String, detail: String? = null) {
        MeetingStore.update(ctx, id) { m ->
            val i = m.steps.indexOf(name)
            if (i < 0) m
            else m.copy(step = i, stepDetail = detail?.ifBlank { null },
                stepStartedAt = if (m.step == i) m.stepStartedAt else System.currentTimeMillis())
        }
    }

    private fun finishSteps(id: String) {
        MeetingStore.update(ctx, id) { it.copy(step = it.steps.size, stepDetail = null, processEndedAt = System.currentTimeMillis()) }
    }

    /** Android 11 及以下的加急任务需要一个前台通知。 */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "錄音處理".tr, NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("大D 正在處理錄音".tr)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(2, notification)
        }
    }
}
