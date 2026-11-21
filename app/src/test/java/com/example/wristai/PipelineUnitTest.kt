package com.example.wristai

import com.example.voiceai.shared.AudioSpec
import com.example.voiceai.shared.WavFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PipelineUnitTest {

    private fun tempWav(pcmSeconds: Double): File {
        val f = File.createTempFile("wristai", ".wav")
        f.outputStream().use { o ->
            WavFile.writePlaceholderHeader(o)
            o.write(ByteArray((pcmSeconds * AudioSpec.BYTES_PER_SECOND).toInt()))
        }
        WavFile.finalizeHeader(f)
        return f
    }

    @Test fun wavHeaderHasCorrectSizes() {
        val f = tempWav(1.0)
        val h = ByteBuffer.wrap(f.readBytes(), 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(f.readBytes().copyOfRange(0, 4)))
        assertEquals(36 + AudioSpec.BYTES_PER_SECOND, h.getInt(4))
        assertEquals(AudioSpec.SAMPLE_RATE, h.getInt(24))
        assertEquals(AudioSpec.BYTES_PER_SECOND, h.getInt(40))
        assertEquals(1.0, WavFile.durationSeconds(f), 0.001)
        f.delete()
    }

    @Test fun copyPcmStripsHeader() {
        val f = tempWav(0.5)
        val pcm = File.createTempFile("wristai", ".pcm")
        WavFile.copyPcm(f, pcm)
        assertEquals(f.length() - WavFile.HEADER_SIZE, pcm.length())
        f.delete(); pcm.delete()
    }

    @Test fun chunkCountRoundsUp() {
        val c = SpeechTranscriber.CHUNK_SECONDS.toDouble()
        listOf(0.0 to 0, 1.0 to 1, c to 1, c + 1 to 2, c * 3 to 3).forEach { (sec, expected) ->
            val f = tempWav(sec)
            assertEquals("seconds=$sec", expected, SpeechTranscriber.chunkCount(f))
            f.delete()
        }
    }

    @Test fun parseReportWithTitleAndSummary() {
        val r = ReportGenerator("k").parse(
            "TITLE: 週會\nSUMMARY: 決定下週上線。\n## 會議摘要\n內容"
        )
        assertEquals("週會", r.title)
        assertEquals("決定下週上線。", r.shortSummary)
        assertTrue(r.markdown.startsWith("# 週會\n\n## 會議摘要"))
    }

    @Test fun parseReportWithoutHeaderFallsBack() {
        val r = ReportGenerator("k").parse("## 會議摘要\n只有內容")
        assertEquals("錄音紀錄", r.title)
        assertTrue(r.shortSummary.isNotBlank())
    }

    @Test(expected = ReportGenerator.ReportException::class)
    fun blankKeyIsRejected() {
        ReportGenerator(" ").generate("text", 10.0, "now")
    }

    @Test fun geminiOutputSplitsReportAndTranscript() {
        val out = """
            TITLE: 手錶 App 上線會議
            SUMMARY: 決定下週五上線，小王測試、小李寫文件。
            ## 會議摘要
            討論上線時間。
            ${GeminiReportGenerator.TRANSCRIPT_MARKER}
            [00:00] 說話者A：今天的週會討論新版手錶 App 的上線時間。
        """.trimIndent()
        val (report, transcript) = GeminiReportGenerator("k").parse(out)
        assertEquals("手錶 App 上線會議", report.title)
        assertEquals("決定下週五上線，小王測試、小李寫文件。", report.shortSummary)
        assertTrue(report.markdown.contains("## 會議摘要"))
        assertTrue(!report.markdown.contains(GeminiReportGenerator.TRANSCRIPT_MARKER))
        assertTrue(transcript.startsWith("[00:00] 說話者A"))
    }

    @Test fun geminiOutputWithoutTranscriptStillParses() {
        val (report, transcript) = GeminiReportGenerator("k").parse("TITLE: 測試\nSUMMARY: 摘要\n## 會議摘要\n內容")
        assertEquals("測試", report.title)
        assertEquals("", transcript)
    }

    // ---- 三星录音机标记 ----

    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { a, b -> a + b }
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
    }

    private fun mp4(moovChildren: ByteArray): File {
        val f = File.createTempFile("wristai", ".m4a")
        f.writeBytes(box("ftyp", "M4A ".toByteArray()) + box("mdat", ByteArray(100) { it.toByte() }) +
            box("moov", box("mvhd", ByteArray(20)), moovChildren))
        return f
    }

    @Test fun samsungTagAddedToExistingSmta() {
        // 三星手机的 MediaMuxer 会写 udta/smta（只有 mdln，没有 saut）
        val f = mp4(box("udta", box("SDLN", "SEQ_PLAY".toByteArray()),
            box("smta", ByteArray(4), box("mdln", "SM-F946B".toByteArray()))))
        val moovAt = 12 + 108  // ftyp(12) + mdat(108)
        val mdatBefore = f.readBytes().copyOfRange(12, moovAt)
        assertTrue(!SamsungRecordingTag.hasTag(f))
        assertTrue(SamsungRecordingTag.apply(f))
        assertTrue(SamsungRecordingTag.hasTag(f))
        val bytes = f.readBytes()
        assertTrue(mdatBefore.contentEquals(bytes.copyOfRange(12, moovAt)))  // 音频数据没动
        val moovSize = ByteBuffer.wrap(bytes, moovAt, 4).int
        assertEquals(bytes.size - moovAt, moovSize)
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("mdlnSM-F946B"))  // 原有内容保留
        f.delete()
    }

    @Test fun samsungTagCreatesUdtaWhenMissing() {
        // 非三星手机：完全没有 udta
        val f = mp4(ByteArray(0))
        assertTrue(SamsungRecordingTag.apply(f))
        assertTrue(SamsungRecordingTag.hasTag(f))
        val size = f.length()
        assertTrue(SamsungRecordingTag.apply(f))  // 重复执行不会再加
        assertEquals(size, f.length())
        f.delete()
    }

    @Test
    fun speakerNamesFromReport() {
        val md = """
            ## 會議摘要
            討論預算。
            ## 與會者
            | 說話者 | 名字 | 角色 | 主要觀點 |
            |---|---|---|---|
            | 說話者A | 王經理 | 主持人 | 要求控制預算 |
            | **說話者B** | 未知 | 報告人 | 說明進度 |
            | 說話者C | 小林（待確認） | 提問者 | 詢問時程 |
            ## 討論重點
            | 不是 | 這張表 |
            |---|---|
            | x | y |
        """.trimIndent()
        val names = Speakers.namesFromReport(md)
        assertEquals(mapOf("說話者A" to "王經理", "說話者C" to "小林（待確認）"), names)
        assertEquals("王經理 請 說話者B 跟進", Speakers.apply("說話者A 請 說話者B 跟進", names))
        assertEquals(emptyMap<String, String>(), Speakers.namesFromReport("## 會議摘要\n無"))
        // 新版章节名「說話者」
        assertEquals(mapOf("說話者A" to "林老師"), Speakers.namesFromReport(
            "## 摘要\n無\n## 說話者\n| 說話者 | 名字 | 角色 | 主要觀點 |\n|---|---|---|---|\n| 說話者A | 林老師 | 老師 | 講解 |\n## 重點\n無"))
    }

    @Test
    fun segmentPathRoundTrip() {
        val seg = com.example.voiceai.shared.Segment("20261118_140234", 7, 4_800_000, last = true)
        val path = com.example.voiceai.shared.WristAiProtocol.segmentPath(seg)
        assertEquals("/wristai/seg/20261118_140234/7/4800000/end", path)
        assertEquals(seg, com.example.voiceai.shared.WristAiProtocol.parseSegmentPath(path))
        val name = com.example.voiceai.shared.WristAiProtocol.segmentFileName(seg)
        assertEquals(seg, com.example.voiceai.shared.WristAiProtocol.parseSegmentFileName(name))
        assertEquals(null, com.example.voiceai.shared.WristAiProtocol.parseSegmentPath("/wristai/audio/x"))
        assertEquals(null, com.example.voiceai.shared.WristAiProtocol.parseSegmentFileName("20261118_140234.wav"))
    }

    @Test
    fun longMeetingChunkParsing() {
        val raw = """
            SPEAKERS:
            說話者A | 王經理 | 主持人 | 男聲、低沉
            說話者B | 未知 | 報告人 | 女聲
            NOTES:
            - 說話者B 報告進度
            ===TRANSCRIPT===
            [00:05] 說話者A：開始吧
            [01:10] 說話者B：好
        """.trimIndent()
        val c = LongMeeting.parseChunk(raw)
        assertEquals(2, c.speakers.size)
        assertEquals("- 說話者B 報告進度", c.notes)
        // 第 2 段（从 30:00 开始），整场超过 1 小时 → h:mm:ss
        assertEquals("[0:30:05] 說話者A：開始吧\n[0:31:10] 說話者B：好", LongMeeting.shiftTranscript(c.transcript, 1800, true))
        assertEquals("[30:05] 說話者A：開始吧\n[31:10] 說話者B：好", LongMeeting.shiftTranscript(c.transcript, 1800, false))
        // 静音段的说明文字不放进逐字稿
        assertEquals("[01:01] 說話者A：hi", LongMeeting.shiftTranscript("（本段錄音無發言內容）\n[00:01] 說話者A：hi", 60, false))

        // 后段把 A 的名字写成未知，不能盖掉前段认出的名字；新的说话者 C 要加进来
        val later = LongMeeting.parseChunk(
            "SPEAKERS:\n說話者A | 未知 | 主持人 | 男聲\n說話者C | 小林 | 提問者 | 男聲\nNOTES:\n-\n===TRANSCRIPT===\n")
        val roster = LongMeeting.mergeRoster(listOf(c, later))
        assertTrue(roster.contains("說話者A | 王經理"))
        assertTrue(roster.contains("說話者C | 小林"))
        assertEquals(3, roster.lines().size)
    }

    @Test
    fun longMeetingChunkCount() {
        assertEquals(1, LongMeeting.chunkCount(1800.0, 1800))
        assertEquals(2, LongMeeting.chunkCount(1801.5, 1800))
        assertEquals(12, LongMeeting.chunkCount(6 * 3600.0, 1800))
        assertEquals(1, LongMeeting.chunkCount(0.0, 1800))
    }

    @Test
    fun foodLogNutritionParsing() {
        val raw = "TITLE: 午餐：牛肉麵＋燙青菜\nSUMMARY: 午餐約 780 大卡\n" +
            "NUTRITION: meal=午餐; kcal=780; protein=35g; carbs=95; fat=22\n## 飲食內容\n| 食物 | 份量 |"
        assertEquals(Nutrition("午餐", 780, 35, 95, 22), FoodLog.parseNutrition(raw))
        assertTrue(!FoodLog.stripNutrition(raw).contains("NUTRITION"))
        assertEquals(null, FoodLog.parseNutrition("TITLE: x"))
        assertTrue(FoodLog.isFood("food_20261118_120000"))
        assertTrue(!FoodLog.isFood("20261118_120000"))
    }
}
