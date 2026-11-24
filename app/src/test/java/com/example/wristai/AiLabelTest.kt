package com.example.wristai

import com.example.wristai.ui.aiModelLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/** 报告「AI」栏位的模型名称。 */
class AiLabelTest {
    @Test fun gemini() = assertEquals("Google Gemini 3.8 Flash", aiModelLabel("gemini-3.8-flash"))
    @Test fun geminiLite() = assertEquals("Google Gemini 3.5 Flash-Lite", aiModelLabel("gemini-3.5-flash-lite"))
    @Test fun claude() = assertEquals("Anthropic Claude Opus 5.5", aiModelLabel("claude-opus-5-5"))
    @Test fun unknown() = assertEquals("未記錄（舊報告）", aiModelLabel(null))
    @Test fun localHost() = assertEquals("大D的主機：FunASR ＋ Qwen3-8B", aiModelLabel("local-funasr|qwen3-8b"))
    @Test fun localHostText() = assertEquals("大D的主機：文字 ＋ SARC 台語 12B", aiModelLabel("local-text|sarc-taigi-12b"))
    @Test fun localHostUnknownModel() = assertEquals("大D的主機：Breeze-ASR-26 ＋ my-llm",aiModelLabel("local-breeze|my-llm"))
    @Test fun normalizeUrl() {
        assertEquals("http://192.168.1.20:8765", com.example.wristai.ai.LocalHost.normalize(" 192.168.1.20:8765/ "))
        assertEquals("https://x.example:8765", com.example.wristai.ai.LocalHost.normalize("https://x.example:8765"))
        assertEquals("", com.example.wristai.ai.LocalHost.normalize("  "))
    }
    @Test fun localHostHakka() = assertEquals("大D的主機：Taiwan Tongues ＋ Qwen3-8B", aiModelLabel("local-taiwan-tongues|qwen3-8b"))
}
