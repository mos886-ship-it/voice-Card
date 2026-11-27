package com.example.wristai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 报告「基本資料」的麦克风文字。 */
class MicRouteTest {
    @Test fun labels() {
        assertNull(MicRoute.label(null))
        assertEquals("手機", MicRoute.label("phone"))
        assertTrue(MicRoute.label("phone:fallback")!!.contains("沒有連著藍牙麥克風"))
        assertEquals("車上／藍牙：My Car（8 kHz 電話音質）", MicRoute.label("car:My Car:8000"))
        assertEquals("車上／藍牙：My Car（16 kHz）", MicRoute.label("car:My Car:16000"))
        assertEquals("車上／藍牙：藍牙", MicRoute.label("car::0"))
    }
}
