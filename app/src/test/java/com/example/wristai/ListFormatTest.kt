package com.example.wristai

import com.example.wristai.ui.dayLabel
import com.example.wristai.ui.humanDuration
import com.example.wristai.ui.summaryOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/** 录音清单：一行摘要、长度、日期分组（跟 iOS 版同规则）。 */
class ListFormatTest {
    @Test fun summaryFromReport() {
        val md = "# 續約會議\n\n## 摘要\n雙方同意**延長**合約一年。\n下週回覆報價。\n\n## 待辦\n- 寄合約"
        assertEquals("雙方同意延長合約一年。 下週回覆報價。", summaryOf(md))
    }

    @Test fun summaryMissingOrEmpty() {
        assertNull(summaryOf(null))
        assertNull(summaryOf("# 標題\n\n## 重點\n- a"))
        assertNull(summaryOf("## 摘要\n無\n## 待辦"))
    }

    @Test fun durations() {
        assertEquals("16 秒", humanDuration(16.4))
        assertEquals("2 分", humanDuration(150.0))
        assertEquals("1 小時", humanDuration(3600.0))
        assertEquals("1 小時 5 分", humanDuration(3900.0))
    }

    private fun at(y: Int, m: Int, d: Int, h: Int = 10) =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    @Test fun dayGroups() {
        val now = at(2026, 11, 23, 22)
        assertEquals("今天", dayLabel(at(2026, 11, 23, 0), now))
        assertEquals("昨天", dayLabel(at(2026, 11, 22, 23), now))
        assertEquals("11月20日 週五", dayLabel(at(2026, 11, 20), now))
        assertEquals("2025年11月20日 週四", dayLabel(at(2025, 11, 20), now))
    }
}
