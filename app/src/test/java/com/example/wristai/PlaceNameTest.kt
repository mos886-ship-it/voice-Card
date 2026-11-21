package com.example.wristai

import org.junit.Assert.assertEquals
import org.junit.Test

/** 录音地点的地名组法。 */
class PlaceNameTest {
    @Test fun latinDedupAndCommas() =
        assertEquals("Phnom Penh, Khan Sen Sok", MeetingStore.placeName(listOf("Phnom Penh", "Khan Sen Sok", "Khan Sensok")))

    @Test fun chineseJoinedDirectly() =
        assertEquals("臺南市官田區烏山頭里", MeetingStore.placeName(listOf("臺南市", "臺南市", "官田區", "烏山頭里")))

    @Test fun skipsBlanks() =
        assertEquals("Taipei, Xinyi District", MeetingStore.placeName(listOf("Taipei", " ", "Taipei City", "Xinyi District")))
}
