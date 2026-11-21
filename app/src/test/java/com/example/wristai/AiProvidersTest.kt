package com.example.wristai

import com.example.wristai.ai.FunAsrStt
import com.example.wristai.ai.IflytekStt
import com.example.wristai.ai.OpenAiCompatLlm
import com.example.wristai.ai.OpenAiStt
import com.example.wristai.ui.aiModelLabel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 各家 AI 回传格式的解析（依官方文件的范例格式）。 */
class AiProvidersTest {
    @Test fun funAsr() {
        val json = """{"file_url":"x","transcripts":[{"channel_id":0,"text":"你好。大家好。","sentences":[
            {"begin_time":1200,"end_time":2000,"text":"大家好。","sentence_id":2,"speaker_id":1},
            {"begin_time":100,"end_time":900,"text":"你好。","sentence_id":1,"speaker_id":0}]}]}"""
        val s = FunAsrStt.parseFunAsr(json)
        assertEquals(2, s.size)
        assertEquals("你好。", s[0].text); assertEquals("0", s[0].speaker); assertEquals(100L, s[0].startMs)
        assertEquals("1", s[1].speaker)
    }

    /** 讯飞一句：json_1best 是 JSON 字串。 */
    private fun best(bg: String, rl: String, vararg words: String): JSONObject {
        val ws = org.json.JSONArray()
        for (w in words) ws.put(JSONObject().put("cw", org.json.JSONArray().put(JSONObject().put("w", w))))
        val st = JSONObject().put("bg", bg).put("rl", rl).put("rt", org.json.JSONArray().put(JSONObject().put("ws", ws)))
        return JSONObject().put("json_1best", JSONObject().put("st", st).toString())
    }

    @Test fun iflytek() {
        val order = JSONObject().put("lattice", org.json.JSONArray()
            .put(best("820", "1", "今天", "開會", "。"))
            .put(best("3000", "2", "好"))).toString()
        val s = IflytekStt.parseIflytek(order)
        assertEquals(2, s.size)
        assertEquals("今天開會。", s[0].text); assertEquals("1", s[0].speaker); assertEquals(820L, s[0].startMs)
        assertEquals("2", s[1].speaker)
    }

    @Test fun iflytekNoRole() {
        val s = IflytekStt.parseIflytek(JSONObject().put("lattice", org.json.JSONArray().put(best("0", "0", "嗨"))).toString())
        assertNull(s[0].speaker)
    }

    @Test fun openAiDiarized() {
        val s = OpenAiStt.parseOpenAi("""{"text":"x","segments":[{"speaker":"A","start":0.5,"end":2.0,"text":"Hello"},{"speaker":"B","start":2.5,"end":3.0,"text":"Hi"}]}""")
        assertEquals(2, s.size); assertEquals("A", s[0].speaker); assertEquals(500L, s[0].startMs); assertEquals("Hi", s[1].text)
    }

    @Test fun openAiPlain() {
        val s = OpenAiStt.parseOpenAi("""{"text":"只有文字"}""")
        assertEquals(1, s.size); assertNull(s[0].speaker)
    }

    @Test fun chatString() = assertEquals("報告", OpenAiCompatLlm.parseChat(JSONObject("""{"choices":[{"message":{"role":"assistant","content":" 報告 "}}]}""")))

    @Test fun chatParts() = assertEquals("ab", OpenAiCompatLlm.parseChat(JSONObject("""{"choices":[{"message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}]}""")))

    @Test fun pipelineLabel() {
        assertEquals("阿里 Fun-ASR 語音辨識 ＋ 阿里通義千問 qwen-plus", aiModelLabel("fun-asr|qwen-plus"))
        assertEquals("科大訊飛 錄音轉寫 ＋ 訊飛星火 4.0Ultra", aiModelLabel("iflytek-lfasr|4.0Ultra"))
        assertEquals("OpenAI 轉錄（gpt-4o-transcribe-diarize） ＋ Meta Llama-4-Maverick-17B-128E-Instruct-FP8",
            aiModelLabel("gpt-4o-transcribe-diarize|Llama-4-Maverick-17B-128E-Instruct-FP8"))
    }
}
