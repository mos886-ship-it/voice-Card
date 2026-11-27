package com.example.wristai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 领域专家模式：规则内容、只加在报告提示词、换报告语言。 */
class ReportDomainTest {
    private val table = ReportDomain.parse(
        listOf("../shared/src/main/assets/report_domains.json", "shared/src/main/assets/report_domains.json")
            .map { File(it) }.first { it.exists() }.readText())

    @Test fun rulesPerSetting() {
        assertEquals("", ReportDomain.rules(table, ReportDomain.OFF))
        assertEquals("", ReportDomain.rules(table, "nope"))
        val med = ReportDomain.rules(table, "medical")
        assertTrue(med.contains("【領域專家模式：醫療】"))
        assertTrue(med.contains("劑量"))
        assertTrue(med.contains("不構成醫療建議"))
        val auto = ReportDomain.rules(table, ReportDomain.AUTO)
        assertTrue(auto.contains("法律、醫療、財務金融"))
        assertTrue(auto.contains("不要加「## 專業重點」"))
        assertFalse(auto.contains("{focus}"))
    }

    @Test fun onlyReportPrompts() {
        val rules = ReportDomain.rules(table, "legal")
        assertTrue(ReportDomain.applyTo("…\n## 待辦事項\n…", rules).endsWith(rules))
        assertEquals("NUTRITION: … ## 待辦事項", ReportDomain.applyTo("NUTRITION: … ## 待辦事項", rules))
        assertEquals("SPEAKERS: NOTES:", ReportDomain.applyTo("SPEAKERS: NOTES:", rules))
    }

    @Test fun localizedToEnglish() {
        ReportDomain.activeRules = ReportDomain.rules(table, "legal")
        try {
            val p = ReportLang.localize("## 待確認 / 後續問題\n## 待辦事項\n", ReportLang.EN)
            assertTrue(p.contains("「## Expert notes」"))
            assertTrue(p.contains("「Domain: …」"))
            assertTrue(p.contains("「(consult a professional)」"))
        } finally { ReportDomain.activeRules = "" }
    }
}
