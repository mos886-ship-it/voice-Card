package com.example.wristai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** 報告匯出（Markdown → Word）、就醫週報、翻譯情境。 */
class ExportFeaturesTest {
    private val md = """
## 摘要
這是 **重點** 摘要。
## 待辦事項
| 事項 | 負責人 | 期限 |
|---|---|---|
| 寄報價 | 王經理 | 12/1 |
- 第一點
  - 子項目
1. 編號一
""".trimIndent()

    @Test fun markdownBlocks() {
        val b = ReportExport.blocks(md)
        assertEquals(ReportExport.Block.Heading(2, "摘要"), b[0])
        val table = b.filterIsInstance<ReportExport.Block.Table>().single()
        assertEquals(2, table.rows.size)                       // 分隔線不算一列
        assertEquals(listOf("寄報價", "王經理", "12/1"), table.rows[1])
        val bullets = b.filterIsInstance<ReportExport.Block.Bullet>()
        assertEquals(listOf(0, 1, 0), bullets.map { it.depth })
        assertEquals("1.", bullets[2].number)
        assertEquals(listOf("這是 " to false, "重點" to true, " 摘要。" to false), ReportExport.runs("這是 **重點** 摘要。"))
    }

    @Test fun docxIsZipWithDocument() {
        val bytes = ReportExport.docx("會議 <A&B>", md)
        val names = mutableListOf<String>()
        var doc = ""
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                names += e.name
                if (e.name == "word/document.xml") doc = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(names.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml")))
        assertTrue(doc.contains("會議 &lt;A&amp;B&gt;"))          // 標題有跳脫
        assertTrue(doc.contains("<w:tbl>"))
        assertTrue(doc.contains("王經理"))
        assertFalse(doc.contains("**"))
    }

    @Test fun safeFileName() {
        assertEquals("飲食週報 2026 11 22", ReportExport.safeName("飲食週報 2026/11/22"))
    }

    @Test fun foodReportCountsDays() {
        val now = FoodReport.dayStarts(1).first() + 20 * 3_600_000L      // 今天 20:00
        fun meal(id: String, daysAgo: Int, kcal: Int, carbs: Int) =
            Meeting(id, MeetingStatus.DONE, now - daysAgo * 86_400_000L - 3_600_000L, 0.0, null, "午餐：牛肉麵", null,
                nutrition = Nutrition("午餐", kcal, 30, carbs, 20, 5))
        val food = listOf(meal("a", 0, 900, 200), meal("b", 1, 700, 100), meal("c", 10, 800, 90))
        val g = FoodReport.Goals(2000, 60, 25, 180, "控糖穩血糖")
        val r = FoodReport.markdown(food, 7, g, now)
        assertTrue(r.contains("有記錄 2 天、共 2 餐"))                  // 10 天前的不算
        assertTrue(r.contains("碳水超過上限：1 / 2 天"))
        assertTrue(r.contains("牛肉麵：2 次，平均 800 大卡"))
        assertEquals("█████", FoodReport.bar(1000, 2000))
        assertEquals("██████████▲▲", FoodReport.bar(2400, 2000))
    }

    @Test fun phrasesGoToTheOtherLanguage() {
        assertEquals("ja", TranslateScene.otherLang("zh-TW", "ja", me = "zh"))   // 我說中文 → 片語翻成日文
        assertEquals("en", TranslateScene.otherLang("en-US", "zh", me = "zh"))   // 對方說英文
        assertTrue(TranslateScene.preset(TranslateScene.CARE)!!.rate < 1f)
        assertEquals(null, TranslateScene.preset(TranslateScene.GENERAL))
    }
}
