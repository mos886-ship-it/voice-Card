package com.example.wristai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 即时翻译的语言：辨识服务代码 → ML Kit 代码、分组、预设地区、预设翻成。 */
class TranslateLangsTest {
    @Test fun mapsRecognizerTagsToMlKit() {
        assertEquals("zh", TranslateLangs.mlkitOf("zh-TW"))
        assertEquals("zh", TranslateLangs.mlkitOf("cmn-Hant-TW"))
        assertEquals("zh", TranslateLangs.mlkitOf("yue-Hant-HK"))   // 粤语当中文翻
        assertEquals("he", TranslateLangs.mlkitOf("iw-IL"))
        assertEquals("id", TranslateLangs.mlkitOf("in-ID"))
        assertEquals("tl", TranslateLangs.mlkitOf("fil-PH"))
        assertEquals("no", TranslateLangs.mlkitOf("nb-NO"))
        assertEquals("en", TranslateLangs.mlkitOf("en_GB"))
        assertNull(TranslateLangs.mlkitOf("xx-YY"))
    }

    @Test fun groupsAccentsAndPicksStatus() {
        val s = TranslateLangs.Support(installed = setOf("en-US"), downloadable = setOf("ja-JP"), online = setOf("en-GB", "zh-TW", "yue-Hant-HK", "xx-YY"), queried = true)
        val src = TranslateLangs.sources(s)
        assertEquals(setOf("en", "ja", "zh"), src.keys)
        assertEquals(2, src["en"]!!.size)
        assertEquals(2, src["zh"]!!.size)
        assertEquals(TranslateLangs.Status.OFFLINE, s.status("en_us"))
        assertEquals(TranslateLangs.Status.DOWNLOADABLE, s.status("ja-JP"))
        assertEquals(TranslateLangs.Status.ONLINE, s.status("en-GB"))
        assertTrue(TranslateLangs.defaultTag("zh", s).contains("TW"))
    }

    @Test fun defaultAccentPrefersRecentThenCommon() {
        val s = TranslateLangs.Support(online = setOf("en-AU", "en-CA", "en-US", "ja-JP"), queried = true)
        java.util.Locale.setDefault(java.util.Locale.TAIWAN)
        assertEquals("en-US", TranslateLangs.defaultTag("en", s))
        assertEquals("en-AU", TranslateLangs.defaultTag("en", s, preferred = listOf("ja-JP", "en-AU")))
    }

    @Test fun defaultTargetAvoidsSameLanguage() {
        assertEquals("en", LiveCaptions.defaultTarget("zh-TW", "zh"))
        assertEquals("zh", LiveCaptions.defaultTarget("en-US", "en"))
        assertEquals("ko", LiveCaptions.defaultTarget("ja-JP", "ko"))
    }
}
