package com.example.wristai

import com.example.voiceai.shared.AudioLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** 辨识语言与音量条（跟 iOS SpeechLang / AudioLevel 同规则）。 */
class LangLevelTest {
    @Test fun langCodes() {
        assertEquals(SpeechLang.ZH, SpeechLang.of(null))      // 旧资料 = 国语
        assertEquals(SpeechLang.ZH, SpeechLang.of("zh"))
        assertEquals(SpeechLang.NAN, SpeechLang.of("nan"))
        assertEquals("", SpeechLang.geminiNote("zh"))
        assertTrue(SpeechLang.geminiNote("nan").contains("不要用台羅拼音"))
    }

    @Test fun otherLangs() {
        assertEquals(SpeechLang.EN, SpeechLang.of("en"))
        assertTrue(SpeechLang.EN.other && !SpeechLang.HAK.other)
        assertEquals("ja-JP", SpeechLang.JA.locale)
        val en = SpeechLang.geminiNote("en")
        assertTrue(en.contains("英語原文") && en.contains("報告仍用繁體中文"))
        assertTrue(SpeechLang.geminiNote("yue").contains("粵語字"))
        // 手表同一套代号
        val s = com.example.voiceai.shared.SpeechLangs
        for (l in SpeechLang.entries) assertEquals(l.code, s.lang(l.code))
        assertEquals("en", s.encode("en", "hailu"))
        assertEquals("韓語", s.display("ko", null))
    }

    @Test fun hakka() {
        assertEquals(SpeechLang.HAK, SpeechLang.of("hak"))
        assertEquals(HakkaDialect.SIXIAN, HakkaDialect.of(null))          // 预设四县
        assertEquals(HakkaDialect.HAILU, HakkaDialect.of("hailu"))
        assertEquals("客語・海陸", SpeechLang.display("hak", "hailu"))
        assertEquals("客語・四縣", SpeechLang.display("hak", null))
        assertEquals("台語", SpeechLang.display("nan", "hailu"))           // 不是客语就不显示腔调
        val note = SpeechLang.geminiNote("hak", "dapu")
        assertTrue(note.contains("客語（大埔腔") && note.contains("不要用客語拼音"))
    }

    /** 手机 ↔ 手表同步语言用的代号（shared SpeechLangs）跟手机的 SpeechLang 一致。 */
    @Test fun watchLangPayload() {
        val s = com.example.voiceai.shared.SpeechLangs
        assertEquals("zh", s.encode(null, null))
        assertEquals("nan", s.encode("nan", "hailu"))                       // 不是客语就不带腔调
        assertEquals("hak|sixian", s.encode("hak", null))
        assertEquals("hak" to "dapu", s.decode("hak|dapu"))
        assertEquals("zh" to null, s.decode("bogus"))
        assertEquals("客語・海陸", s.display("hak", "hailu"))
        assertEquals("國台語", s.display("nan", null))                     // 手錶上台語叫「國台語」
        for (l in SpeechLang.entries) assertEquals(l.code, s.lang(l.code))   // 三种语言代号都认得
        for (d in HakkaDialect.entries) assertEquals(d.label, s.DIALECTS.first { it.first == d.code }.second)
        assertEquals(SpeechLang.display("hak", "zhaoan"), s.display("hak", "zhaoan"))
    }

    /** 逐字稿语言标签（跟 iOS 一样）。 */
    @Test fun transcriptTag() {
        assertEquals(listOf("國", "台", "客", "英", "日", "韓", "粵", "越", "泰", "印"), SpeechLang.entries.map { it.tag })
        assertEquals("國語", SpeechLang.of(null).tagLabel(null))           // 旧资料 = 国
        assertEquals("台語", SpeechLang.NAN.tagLabel("hailu"))
        assertEquals("客語四縣腔", SpeechLang.HAK.tagLabel(null))
        assertEquals("客語詔安腔", SpeechLang.HAK.tagLabel("zhaoan"))
    }

    @Test fun levelScale() {
        assertEquals(-60f, AudioLevel.dB(0.0), 0.01f)               // 下限
        assertEquals(0f, AudioLevel.norm(1e-6), 0.0001f)            // -60 dB：安静 = 0
        assertEquals(1f, AudioLevel.norm(1.0), 0.0001f)             // 0 dB：满格
        // -30 dB → (−30+52)/44 = 0.5 → 平方 0.25
        assertEquals(0.25f, AudioLevel.norm(1e-3), 0.001f)
    }

    private fun pcm(amp: Double, samples: Int, rate: Int = 16_000): ByteArray {
        val b = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (amp * 32767 * sin(2 * PI * 440 * i / rate)).toInt().toShort().toInt()
            b[2 * i] = (v and 0xff).toByte(); b[2 * i + 1] = (v shr 8).toByte()
        }
        return b
    }

    @Test fun barsPerWindowAndStatus() {
        AudioLevel.reset()
        // 16 kHz：约 85 ms = 1360 个样本一根；1 秒 → 11 根
        val sec = pcm(0.3, 16_000)
        AudioLevel.feed(sec, sec.size, 16_000)
        val s = AudioLevel.state.value
        assertEquals(AudioLevel.COUNT, s.history.size)
        assertEquals(11, s.history.count { it > 0f })
        assertTrue(s.db > -20f && s.db < -10f)                      // 正弦波 0.3 振幅 ≈ -13.5 dB
        assertFalse(s.loud)
        val loud = pcm(1.0, 2_000)
        AudioLevel.feed(loud, loud.size, 16_000)
        assertTrue(AudioLevel.state.value.loud)                     // 峰值 > 0.97
        AudioLevel.reset()
    }
}
