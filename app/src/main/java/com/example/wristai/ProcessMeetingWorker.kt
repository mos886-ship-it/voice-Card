package com.example.wristai

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
import kotlinx.coroutines.Dispatchers
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

        /** 中断处理：停掉背景任务，标成「已中断」（按「重试」可以接着做）。 */
        fun cancel(context: Context, meetingId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("meeting-$meetingId")
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
                .enqueueUniqueWork("meeting-$meetingId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    private val ctx get() = applicationContext

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        var meeting = MeetingStore.get(ctx, id) ?: return Result.failure()
        if (meeting.status == MeetingStatus.DONE) return Result.success()
        val node = meeting.watchNodeId
        val startedAt = System.currentTimeMillis()

        val recordedAt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN).format(Date(meeting.createdAt)) +
            (if (meeting.source == AudioImporter.SOURCE_CALL) "（這是一通電話的通話錄音，通常是兩位說話者：本人與對方${meeting.title?.let { "，檔名：$it" } ?: ""}）" else "") +
            (MeetingStore.geo(ctx, id)?.place?.let { "，錄音地點：$it" } ?: "")

        // ---------- 手表还在录（长会议边录边传）：先把已经录满的 Gemini 段处理掉 ----------
        val mode = com.example.wristai.ai.AiConfig.mode(ctx)
        if (meeting.status == MeetingStatus.RECORDING) {
            val key = Settings.geminiKey(ctx)
            // 边录边转录只有 Gemini 模式有；组合式录完再一次辨识
            if (key.isBlank() || mode != com.example.wristai.ai.AiConfig.MODE_GEMINI) return Result.success()
            return try {
                withContext(Dispatchers.IO) {
                    LongMeeting.processChunks(ctx, id, final = false, deadline = startedAt + CHUNK_BUDGET_MS,
                        gemini = GeminiReportGenerator(key), recordedAt = recordedAt) { done, _ ->
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
                Log.w(TAG, "$id: early chunk transcription failed: ${e.message}")
                Result.success()
            }
        }

        // ---------- 饮食记录：估算热量与营养素（需要云端 AI） ----------
        if (FoodLog.isFood(id)) {
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
                val gemini = GeminiReportGenerator(geminiKey)
                val total = LongMeeting.chunkCount(meeting.durationSec, Settings.geminiChunkSec(ctx))
                MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.SUMMARIZING, error = null, chunkTotal = total) }
                val done = withContext(Dispatchers.IO) {
                    LongMeeting.processChunks(ctx, id, final = true, deadline = startedAt + CHUNK_BUDGET_MS,
                        gemini = gemini, recordedAt = recordedAt) { k, n ->
                        MeetingStore.update(ctx, id) { it.copy(chunkDone = k, chunkTotal = n) }
                        stepTo(id, "分段分析", "Gemini · ${minOf(k + 1, n)}/$n 段")
                        WatchNotifier.sendStatus(ctx, node, "Gemini 分析中 ${k + 1}/$n 段")
                    }
                }
                if (!done) {
                    Log.d(TAG, "$id: time budget used, continuing chunks in a new task")
                    enqueue(ctx, id)
                    return Result.success()
                }
                MeetingStore.update(ctx, id) { it.copy(chunkDone = total) }
                stepTo(id, "整合報告", "Gemini")
                WatchNotifier.sendStatus(ctx, node, "Gemini 整合完整報告…")
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
                WatchNotifier.sendStatus(ctx, node, "Gemini 整理報告中…")
                val result = withContext(Dispatchers.IO) {
                    val compact = File(ctx.cacheDir, "gemini_$id.m4a")
                    try {
                        VoiceRecorderExporter.encodeM4a(audio, compact, outRate = 16_000, bitRate = 32_000)
                        GeminiReportGenerator(geminiKey).generate(compact, meeting.durationSec, recordedAt,
                            onListening = { stepTo(id, "AI 聆聽整理", "Gemini") })
                    } finally {
                        compact.delete()
                    }
                }
                Log.d(TAG, "$id: report by ${result.model}")
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
                WatchNotifier.sendStatus(ctx, node, "已存入手機「錄音機」，用 Galaxy AI 轉錄 / 摘要")
                ReadyNotification.show(ctx, id, meeting.durationSec)
                return Result.success()
            }

            // ---------- 1. 语音识别（可分多次任务完成） ----------
            val total = SpeechTranscriber.chunkCount(audio)
            if (meeting.chunkDone < total) {
                meeting = MeetingStore.update(ctx, id) {
                    it.copy(status = MeetingStatus.TRANSCRIBING, chunkTotal = total, error = null)
                }!!
                val transcriber = SpeechTranscriber(ctx, Settings.language(ctx))
                val transcriptFile = MeetingStore.transcriptFile(ctx, id)
                if (meeting.chunkDone == 0) transcriptFile.writeText("")

                for (i in meeting.chunkDone until total) {
                    if (System.currentTimeMillis() - startedAt > TIME_BUDGET_MS) {
                        Log.d(TAG, "$id: time budget used, continuing at chunk $i")
                        enqueue(ctx, id)
                        return Result.success()
                    }
                    stepTo(id, "語音辨識", "手機內建辨識 · ${i + 1}/$total 段")
                    WatchNotifier.sendStatus(ctx, node, "語音辨識中 ${i + 1}/$total")
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
            WatchNotifier.sendStatus(ctx, node, "AI 整理報告中…")
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
            Log.e(TAG, "$id failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.FAILED, error = msg) }
            WatchNotifier.sendStatus(ctx, node, "處理失敗：$msg（可在手機上重試）")
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
            val msg = (com.example.wristai.ai.AiConfig.pipelineMissing(ctx) ?: "AI 引擎設定不完整") + "（設定 → AI 引擎）"
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.FAILED, error = msg) }
            WatchNotifier.sendStatus(ctx, node, "請在手機完成 AI 引擎設定")
            return Result.success()
        }
        if (food) plan(id, listOf("接收錄音", "語音辨識", "估算熱量"))
        return try {
            // ---------- 1. 语音辨识 ----------
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.TRANSCRIBING, error = null) }
            val finished = withContext(Dispatchers.IO) {
                com.example.wristai.ai.Pipeline.transcribe(ctx, id, meeting.durationSec, stt, startedAt + TIME_BUDGET_MS) { done, total ->
                    MeetingStore.update(ctx, id) { it.copy(chunkDone = done, chunkTotal = total) }
                    stepTo(id, "語音辨識", stt.name + if (total > 1) " · ${minOf(done + 1, total)}/$total 段" else "")
                    WatchNotifier.sendStatus(ctx, node, "語音辨識中（${stt.name}）" + if (total > 1) " ${minOf(done + 1, total)}/$total" else "…")
                }
            }
            if (!finished) {
                Log.d(TAG, "$id: speech recognition still running, continuing in a new task")
                enqueue(ctx, id)
                return Result.success()
            }
            val transcript = com.example.wristai.ai.Pipeline.transcriptText(ctx, id)
            if (transcript.isBlank()) throw com.example.wristai.ai.AiException("語音辨識沒有聽到可辨識的內容")
            MeetingStore.transcriptFile(ctx, id).writeText(transcript)

            // ---------- 2. 报告模型 ----------
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.SUMMARIZING, error = null) }
            stepTo(id, if (food) "估算熱量" else "報告整理", llm.name)
            WatchNotifier.sendStatus(ctx, node, if (food) "估算熱量中（${llm.name}）…" else "AI 整理報告中（${llm.name}）…")
            val raw = withContext(Dispatchers.IO) {
                com.example.wristai.ai.Pipeline.report(llm, transcript, meeting.durationSec, recordedAt,
                    if (food) FoodLog.TEXT_PROMPT else GeminiReportGenerator.TEXT_REPORT_PROMPT)
            }
            val report = ReportGenerator.parseReport(raw.substringBefore(GeminiReportGenerator.TRANSCRIPT_MARKER).trim())
            val model = "${stt.modelId}|${llm.modelId}"
            if (food) {
                val nutrition = FoodLog.parseNutrition(raw)
                MeetingStore.reportFile(ctx, id).writeText(FoodLog.stripNutrition(report.markdown))
                MeetingStore.update(ctx, id) {
                    it.copy(status = MeetingStatus.DONE, title = report.title, nutrition = nutrition, aiModel = model)
                }
                WatchNotifier.sendSummary(ctx, node, MeetingSummary(id, report.title, report.shortSummary))
                WatchNotifier.broadcastFoodToday(ctx)
                ReadyNotification.showReport(ctx, id, report.title, report.shortSummary)
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
            Log.d(TAG, "$id: report by $model")
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isStopped) return Result.failure()   // 使用者按了「中断处理」
            Log.e(TAG, "$id pipeline failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.FAILED, error = msg) }
            WatchNotifier.sendStatus(ctx, node, "處理失敗：$msg（可在手機上重試）")
            Result.failure()
        }
    }

    private suspend fun processFood(id: String, meeting: Meeting, recordedAt: String): Result {
        val node = meeting.watchNodeId
        val key = Settings.geminiKey(ctx)
        if (key.isBlank()) {
            val msg = "飲食記錄需要雲端 AI 估算熱量：請到設定啟用 Gemini（免費）"
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.FAILED, error = msg) }
            WatchNotifier.sendStatus(ctx, node, "請在手機啟用雲端 AI")
            return Result.success()
        }
        return try {
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.SUMMARIZING, error = null) }
            plan(id, listOf("接收錄音", "上傳音檔", "估算熱量"))
            stepTo(id, "上傳音檔", "Gemini")
            WatchNotifier.sendStatus(ctx, node, "估算熱量中…")
            val result = withContext(Dispatchers.IO) {
                val compact = File(ctx.cacheDir, "gemini_$id.m4a")
                try {
                    VoiceRecorderExporter.encodeM4a(MeetingStore.audioFile(ctx, id), compact, outRate = 16_000, bitRate = 32_000)
                    GeminiReportGenerator(key).generate(compact, meeting.durationSec, recordedAt, system = FoodLog.PROMPT,
                        onListening = { stepTo(id, "估算熱量", "Gemini") })
                } finally {
                    compact.delete()
                }
            }
            val nutrition = FoodLog.parseNutrition(result.raw)
            MeetingStore.transcriptFile(ctx, id).writeText(result.transcript)
            MeetingStore.reportFile(ctx, id).writeText(FoodLog.stripNutrition(result.report.markdown))
            MeetingStore.update(ctx, id) {
                it.copy(status = MeetingStatus.DONE, title = result.report.title, nutrition = nutrition, aiModel = result.model)
            }
            finishSteps(id)
            Log.d(TAG, "$id: food log ${nutrition?.kcal} kcal by ${result.model}")
            WatchNotifier.sendSummary(ctx, node, MeetingSummary(id, result.report.title, result.report.shortSummary))
            WatchNotifier.broadcastFoodToday(ctx)
            ReadyNotification.showReport(ctx, id, result.report.title, result.report.shortSummary)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isStopped) return Result.failure()   // 使用者按了「中断处理」
            Log.e(TAG, "$id food log failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.FAILED, error = msg) }
            WatchNotifier.sendStatus(ctx, node, "處理失敗：$msg（可在手機上重試）")
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
        if (!host.configured(ctx)) return null
        val node = meeting.watchNodeId
        val mode = cfg.mode(ctx)
        val pipelineOk = mode == cfg.MODE_PIPELINE && cfg.pipelineMissing(ctx) == null
        val geminiOk = Settings.geminiKey(ctx).isNotBlank()
        val fallback = when {
            pipelineOk -> "組合式 AI"
            geminiOk && (food || mode == cfg.MODE_GEMINI) -> "Gemini"
            // 录音：没有别的云端引擎时照旧交给三星录音机（Galaxy AI），之后可以按「重試」再给主机
            !food -> "手機的錄音機（Galaxy AI）"
            else -> null
        }
        val before = MeetingStore.get(ctx, id)?.steps.orEmpty()
        val lastStep = if (food) "估算熱量" else STEP_HOST_AI
        plan(id, before.filter { it == "接收錄音" || it == "存入錄音機" }.ifEmpty { listOf("接收錄音") } + listOf(STEP_HOST_UP, lastStep))
        // 标头只能放 ASCII：录音时间用数字格式
        val asciiAt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(meeting.createdAt))
        return try {
            MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.SUMMARIZING, error = null, engineNote = null) }
            stepTo(id, STEP_HOST_UP, "大D的主機")
            WatchNotifier.sendStatus(ctx, node, if (food) "大D的主機估算熱量中…" else "大D的主機整理報告中…")
            // 长录音在主机上可能超过 10 分钟：升成前台工作，避免被系统中途停掉
            runCatching { setForeground(getForegroundInfo()) }
            val res = withContext(Dispatchers.IO) {
                host.health(ctx)   // 5 秒：连不到就马上改用别的引擎，不要卡在上传
                val compact = File(ctx.cacheDir, "local_$id.m4a")
                try {
                    VoiceRecorderExporter.encodeM4a(MeetingStore.audioFile(ctx, id), compact, outRate = 16_000, bitRate = 32_000)
                    host.process(ctx, if (food) "food" else "meeting", asciiAt, meeting.durationSec, audio = compact,
                        onUploaded = { stepTo(id, lastStep, "大D的主機") })
                } finally {
                    compact.delete()
                }
            }
            val (report, transcript) = GeminiReportGenerator("").parse(res.text)
            MeetingStore.transcriptFile(ctx, id).writeText(transcript)
            if (food) {
                val nutrition = FoodLog.parseNutrition(res.text)
                MeetingStore.reportFile(ctx, id).writeText(FoodLog.stripNutrition(report.markdown))
                MeetingStore.update(ctx, id) {
                    it.copy(status = MeetingStatus.DONE, title = report.title, nutrition = nutrition, aiModel = res.model)
                }
                finishSteps(id)
                WatchNotifier.sendSummary(ctx, node, MeetingSummary(id, report.title, report.shortSummary))
                WatchNotifier.broadcastFoodToday(ctx)
                ReadyNotification.showReport(ctx, id, report.title, report.shortSummary)
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
            Log.d(TAG, "$id: report by ${res.model}")
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isStopped) return Result.failure()   // 使用者按了「中断处理」
            val why = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "$id: local host failed: $why")
            if (fallback != null) {
                MeetingStore.update(ctx, id) { it.copy(engineNote = why.trimEnd('。') + "。這次改用 $fallback。") }
                if (before.isNotEmpty() && !food) plan(id, before)
                null
            } else {
                MeetingStore.update(ctx, id) { it.copy(status = MeetingStatus.FAILED, error = why) }
                WatchNotifier.sendStatus(ctx, node, "處理失敗：$why（可在手機上重試）")
                Result.failure()
            }
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
                NotificationChannel(CHANNEL_ID, "錄音處理", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("大D 正在處理錄音")
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
