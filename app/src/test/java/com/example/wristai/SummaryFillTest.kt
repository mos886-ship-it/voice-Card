package com.example.wristai

import org.junit.Assert.assertEquals
import org.junit.Test

/** 报告「## 摘要」是「無」时用 SUMMARY 行补上。 */
class SummaryFillTest {
    @Test fun fillsNone() = assertEquals(
        "## 摘要\n主播報導候選人拆除違建的政見。\n## 說話者\n無",
        fillEmptySummary("## 摘要\n無\n## 說話者\n無", "主播報導候選人拆除違建的政見。"),
    )

    @Test fun keepsRealSummary() = assertEquals(
        "## 摘要\n真的摘要\n## 重點",
        fillEmptySummary("## 摘要\n真的摘要\n## 重點", "SUMMARY 行"),
    )

    @Test fun fillsEmptySection() = assertEquals(
        "## 摘要\nS\n## 重點",
        fillEmptySummary("## 摘要\n\n## 重點", "S"),
    )
}
