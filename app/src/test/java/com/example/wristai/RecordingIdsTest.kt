package com.example.wristai

import com.example.voiceai.shared.RecordingIds
import com.example.voiceai.shared.WristAiProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/** 录音编号带装置代码：同一秒、不同装置不会撞；旧格式照样认得。 */
class RecordingIdsTest {
    private val t = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse("20261124_225933")!!

    @Test fun formatAndParse() {
        val a = RecordingIds.format("", t, "k7q2")
        assertEquals("20261124_225933_k7q2", a)
        assertNotEquals(a, RecordingIds.format("", t, "x9m3"))            // 同一秒、不同装置
        assertEquals(t.time, RecordingIds.timeOf(a))
        assertEquals("k7q2", RecordingIds.deviceOf(a))
        val food = RecordingIds.format(FoodLog.ID_PREFIX, t, "k7q2")
        assertEquals("food_20261124_225933_k7q2", food)
        assertTrue(FoodLog.isFood(food))
        assertEquals(t.time, RecordingIds.timeOf(food))
        assertEquals(t.time, RecordingIds.timeOf("imp_20261124_225933_k7q2"))
    }

    /** 旧编号（没有装置代码）、汇入撞名时加的「_」都还认得时间。 */
    @Test fun oldFormats() {
        assertEquals(t.time, RecordingIds.timeOf("20261124_225933"))
        assertNull(RecordingIds.deviceOf("20261124_225933"))
        assertNull(RecordingIds.deviceOf("food_20261124_225933"))
        assertEquals(t.time, RecordingIds.timeOf("imp_20261124_225933_k7q2_"))
        assertNull(RecordingIds.timeOf("bogus"))
    }

    /** 手表分段档名用「__」分隔，编号里的单一底线不会被切错。 */
    @Test fun segmentFileNameStillParses() {
        val id = RecordingIds.format("food_", t, "w4tc")
        val name = WristAiProtocol.segmentFileName(com.example.voiceai.shared.Segment(id, 3, 4800, true))
        val back = WristAiProtocol.parseSegmentFileName(name)!!
        assertEquals(id, back.recordingId)
        assertEquals(3, back.seq)
        assertTrue(back.last)
    }
}
