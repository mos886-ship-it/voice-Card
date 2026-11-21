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
}
