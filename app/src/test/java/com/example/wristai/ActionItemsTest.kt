package com.example.wristai

import org.junit.Assert.assertEquals
import org.junit.Test

/** 待办总览：从报告的「待辦事項」章节读出事项（表格或条列，三种语言）。 */
class ActionItemsTest {
    @Test fun tableWithOwnerAndDue() {
        val md = """
            # 週會
            ## 重點
            - 進度正常
            ## 待辦事項
            | 事項 | 負責人 | 期限 |
            |---|---|---|
            | 寄報價單給客戶 | 說話者B | 週五 |
            | 確認場地 | 未指定 | 未指定 |
            ## 待確認 / 後續問題
            - 預算還沒定
        """.trimIndent()
        val items = ActionItems.parse("m1", md)
        assertEquals(2, items.size)
        assertEquals(ActionItems.Item("m1", "寄報價單給客戶", "說話者B", "週五"), items[0])
        assertEquals(ActionItems.Item("m1", "確認場地", null, null), items[1])
    }

    @Test fun bulletsAndOtherLanguages() {
        val md = "## Action items\n- **Send the deck** to Amy\n1. Book a room\n## Open questions / Follow-ups\n- x"
        assertEquals(listOf("Send the deck to Amy", "Book a room"), ActionItems.parse("m", md).map { it.task })
        assertEquals(1, ActionItems.parse("m", "## 할 일\n| 항목 | 담당 | 기한 |\n|--|--|--|\n| 회의록 공유 | 화자 A | 내일 |").size)
    }

    @Test fun noneMeansEmpty() {
        assertEquals(0, ActionItems.parse("m", "## 待辦事項\n無\n## 其他\n- a").size)
        assertEquals(0, ActionItems.parse("m", "## 待辦事項\n| 事項 | 負責人 | 期限 |\n|---|---|---|\n| 無 | | |").size)
    }
}
