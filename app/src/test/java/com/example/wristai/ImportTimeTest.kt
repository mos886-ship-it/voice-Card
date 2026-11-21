package com.example.wristai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/** 汇入档案：从档名推回录音时间。 */
class ImportTimeTest {
    private fun t(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis

    @Test fun samsungCall() = assertEquals(t(2024, 11, 18, 19, 56, 56), AudioImporter.timeFromName("通話錄音 王小明_241118_195656.m4a"))
    @Test fun fullDate() = assertEquals(t(2026, 3, 5, 9, 7, 30), AudioImporter.timeFromName("Dylan_20260305_090730.m4a"))
    @Test fun dashed() = assertEquals(t(2025, 1, 2, 14, 30), AudioImporter.timeFromName("Meeting 2025-01-02 14.30.m4a"))
    @Test fun noDate() = assertNull(AudioImporter.timeFromName("語音 001.m4a"))
    @Test fun invalidDate() = assertNull(AudioImporter.timeFromName("track_999999_999999.mp3"))
}
