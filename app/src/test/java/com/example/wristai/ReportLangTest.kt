package com.example.wristai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 报告语言：提示词换成英文 / 韩文格式，App 解析三种语言的报告。 */
class ReportLangTest {
    @After fun reset() { ReportLang.active = ReportLang.ZH }

    private val prompt = """
        第 3 行起：Markdown 報告，依序包含以下章節（沒有內容的章節寫「無」）：
        ## 摘要
        ## 說話者
        （用表格：說話者 | 名字 | 角色 | 主要觀點；名字未知就寫「未知」）
        ## 待辦事項
        （用表格：事項 | 負責人 | 期限）
        - 依第一次開口的順序編代號：說話者A、說話者B、說話者C…；分不出是誰時用「說話者?」。
        之後是完整逐字稿，每段一行，格式：[mm:ss] 說話者X：內容
        一律使用繁體中文（台灣用語）。
    """.trimIndent()

    @Test fun chineseUnchanged() = assertEquals(prompt, ReportLang.localize(prompt, ReportLang.ZH))

    @Test fun englishPrompt() {
        val p = ReportLang.localize(prompt, ReportLang.EN)
        assertTrue(p.contains("## Summary\n## Speakers"))
        assertTrue(p.contains("Speaker | Name | Role | Main points"))
        assertTrue(p.contains("Item | Owner | Due"))
        assertTrue(p.contains("Speaker A、Speaker B、Speaker C") && p.contains("「Speaker ?」") && p.contains("[mm:ss] Speaker X："))
        assertTrue(p.contains("「None」") && p.contains("「Unknown」"))
        assertFalse(p.contains("一律使用繁體中文（台灣用語）。"))
        assertTrue(p.trimEnd().endsWith(ReportLang.rule(ReportLang.EN)))
    }

    @Test fun koreanPrompt() {
        val p = ReportLang.localize(prompt, ReportLang.KO)
        assertTrue(p.contains("## 요약\n## 화자"))
        assertTrue(p.contains("화자 | 이름 | 역할 | 주요 의견"))
        assertTrue(p.contains("화자 A、화자 B"))
        assertTrue(p.contains("한국어"))
    }

    @Test fun speakerNamesInEveryLanguage() {
        val en = "## Summary\nx\n## Speakers\n| Speaker | Name | Role | Main points |\n|---|---|---|---|\n| Speaker A | Manager Wang | host | x |\n| Speaker B | Unknown | guest | y |\n## Key points\n"
        assertEquals(mapOf("Speaker A" to "Manager Wang"), Speakers.namesFromReport(en))
        val ko = "## 화자\n| 화자 | 이름 | 역할 | 주요 의견 |\n|---|---|---|---|\n| 화자 A | 김 팀장 | 진행 | x |\n| 화자 B | 미상 | 참석 | y |\n"
        assertEquals(mapOf("화자 A" to "김 팀장"), Speakers.namesFromReport(ko))
        val zh = "## 說話者\n| 說話者 | 名字 | 角色 | 主要觀點 |\n|---|---|---|---|\n| 說話者A | 王經理 | 主持 | x |\n"
        assertEquals(mapOf("說話者A" to "王經理"), Speakers.namesFromReport(zh))
    }

    @Test fun parsingHelpers() {
        assertTrue(ReportLang.isSpeakerCode("Speaker A | Kim | host"))
        assertTrue(ReportLang.isSpeakerCode("화자 B | 미상"))
        assertTrue(ReportLang.isSpeakerCode("說話者C | 未知"))
        assertFalse(ReportLang.isSpeakerCode("Speakers"))
        assertTrue(ReportLang.isSummaryHeading("Summary") && ReportLang.isSummaryHeading("요약") && ReportLang.isSummaryHeading("摘要"))
        assertTrue(ReportLang.isNone("None.") && ReportLang.isNone("없음") && ReportLang.isNone("無"))
        assertEquals(ReportLang.KO, ReportLang.fromLocale("ko"))
        assertEquals(ReportLang.ZH, ReportLang.fromLocale("zh"))
        assertEquals(ReportLang.EN, ReportLang.fromLocale("ja"))
    }

    @Test fun foodReportInEnglish() {
        ReportLang.active = ReportLang.EN
        val raw = "NUTRITION: meal=午餐; kcal=600; protein=20; carbs=80; fat=15\n" +
            "ITEM: 食物=Beef noodles; 份量=1 bowl; 克=500; 對照=無; kcal=600; protein=20; carbs=80; fat=15"
        val f = FoodLog.finish(raw, "## Nutrition tips\nDrink water.", "ai")
        assertTrue(f.markdown, f.markdown.startsWith("## What you ate\n| Food | Portion | Calories (kcal)"))
        assertTrue(f.markdown.contains("AI estimate") && f.markdown.contains("## Total"))
        assertEquals("Lunch: about 600 kcal, protein 20g", f.summary)
        assertEquals("午餐", f.nutrition?.meal)   // 健康同步靠中文餐别
    }
}
