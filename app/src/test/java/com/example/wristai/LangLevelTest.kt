package com.example.wristai

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
