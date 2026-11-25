package com.example.wristai

import com.example.wristai.ui.greetingFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 首页问候：依时段换大标，小字依星期与今天的记录。 */
class GreetingTest {
    private fun at(dow: Int, hour: Int) = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 23, hour, 0, 0)   // 2026/11/23 是星期一
        while (get(Calendar.DAY_OF_WEEK) != dow) add(Calendar.DAY_OF_MONTH, 1)
    }

    @Test fun titlesByHour() {
        val d = Calendar.WEDNESDAY
        val titles = listOf(7, 12, 15, 18, 21, 1).map { greetingFor(at(d, it), 0, false, false, 0, 2000, false).title }
        assertEquals(listOf("早安", "午安", "下午好", "傍晚好", "晚上好", "夜深了"), titles)
    }

    @Test fun weekendMorning() {
        val g = greetingFor(at(Calendar.SATURDAY, 8), 0, false, false, 0, 2000, false)
        assertEquals("週末早安", g.title)
    }

    @Test fun asksAboutLunchOnlyWhenFoodIsUsedAndNotLogged() {
        assertTrue(greetingFor(at(Calendar.TUESDAY, 12), 0, false, false, 0, 2000, usesFood = true).line.contains("午餐"))
        assertTrue(!greetingFor(at(Calendar.TUESDAY, 12), 0, true, false, 0, 2000, usesFood = true).line.contains("午餐吃了嗎"))
        assertTrue(!greetingFor(at(Calendar.TUESDAY, 12), 0, false, false, 0, 2000, usesFood = false).line.contains("午餐吃了嗎"))
    }

    @Test fun countsTodaysRecordsInTheEvening() {
        val g = greetingFor(at(Calendar.TUESDAY, 22), 3, true, true, 1500, 2000, usesFood = true)
        assertEquals("今天記了 3 筆，辛苦了，好好休息。", g.line)
    }

    @Test fun sameLineAllDay() {
        val a = greetingFor(at(Calendar.MONDAY, 8), 0, false, false, 0, 2000, false)
        val b = greetingFor(at(Calendar.MONDAY, 9), 0, false, false, 0, 2000, false)
        assertEquals(a, b)
    }
}
