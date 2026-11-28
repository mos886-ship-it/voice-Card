package com.example.wristai

import com.example.wristai.ui.ReportSeek
import com.example.wristai.ui.parseTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 摘要跳原音、翻譯更正、手錶電量。 */
class SeekAndFixesTest {
    private val transcript = """
[00:05] 說話者A：大家好，今天討論新產品上市的時程
[01:20] 說話者B：工廠那邊說模具要到三月才會完成
[02:10] 說話者A：那行銷活動就延到四月，預算維持兩百萬
""".trimIndent()

    @Test fun keyPointFindsItsMoment() {
        val lines = parseTranscript(transcript)
        assertEquals("01:20", ReportSeek.find("說話者B 表示模具三月才會完成", lines)?.first)
        assertEquals(130_000, ReportSeek.find("行銷活動延到四月，預算兩百萬", lines)?.second)
        assertNull(ReportSeek.find("跟錄音無關的一句話完全不像", lines))
    }

    @Test fun stampsOnlyForBullets() {
        val md = "## 摘要\n討論模具三月完成\n## 重點\n- 模具要到三月才會完成\n"
        val s = ReportSeek.stamps(md, transcript)
        assertEquals(setOf("模具要到三月才會完成"), s.keys)
        assertEquals(emptyMap<String, Pair<String, Int>>(), ReportSeek.stamps(md, "沒有時間的逐字稿"))
    }

    @Test fun fixesReplaceLongestFirst() {
        val fixes = listOf(TranslateFixes.Fix("zh", "大衛", "Dylan"), TranslateFixes.Fix("zh", "大衛科技", "大D科技"),
            TranslateFixes.Fix("en", "Big D", "Dylan"))
        assertEquals("大D科技的 Dylan", TranslateFixes.apply("大衛科技的 大衛", "zh", fixes))
        assertEquals("Dylan here", TranslateFixes.apply("big d here", "en", fixes))
    }

    @Test fun batteryPayload() {
        assertEquals(WatchBattery.Level(42, true, 1L), WatchBattery.parse("42,1", 1L))
        assertNull(WatchBattery.parse("abc"))
        assertNull(WatchBattery.parse("150,0"))
    }
}

class LinkAndNotionTest {
    @Test fun linkNormalize() {
        org.junit.Assert.assertEquals("https://youtu.be/abc", LinkImport.normalize("看這個 https://youtu.be/abc"))
        assertNull(LinkImport.normalize("沒有連結"))
        org.junit.Assert.assertTrue(LinkImport.isDirectMedia("https://x.com/ep1.MP3?x=1"))
        org.junit.Assert.assertFalse(LinkImport.isDirectMedia("https://www.youtube.com/watch?v=abc"))
    }

    @Test fun notionPageIdAndBlocks() {
        assertEquals("1a2b3c4d-1a2b-3c4d-1a2b-3c4d1a2b3c4d",
            NotionExport.pageId("https://www.notion.so/My-Page-1a2b3c4d1a2b3c4d1a2b3c4d1a2b3c4d"))
        assertNull(NotionExport.pageId("https://www.notion.so/nothing"))
        val b = NotionExport.blocks("## 重點\n- **甲** 乙\n| a | b |\n|---|---|\n| 1 | 2 |\n" + "長".repeat(4000))
        assertEquals(listOf("heading_2", "bulleted_list_item", "table", "paragraph", "paragraph", "paragraph"), b.map { it.getString("type") })
        assertEquals(true, b[1].getJSONObject("bulleted_list_item").getJSONArray("rich_text").getJSONObject(0)
            .getJSONObject("annotations").getBoolean("bold"))
        assertEquals(2, b[2].getJSONObject("table").getJSONArray("children").length())
    }
}
