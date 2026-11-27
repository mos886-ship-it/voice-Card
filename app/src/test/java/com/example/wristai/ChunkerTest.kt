package com.example.wristai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 即时翻译边说边切句。 */
class ChunkerTest {
    @Test fun splitsAtSentenceEndWhenMoreFollows() {
        assertEquals("今天天氣很好。" to "我們去公園", Chunker.split("今天天氣很好。我們去公園"))
        assertEquals("How are you doing today?" to "I am fine", Chunker.split("How are you doing today? I am fine"))
        // 句号在最后：还不知道是不是说完，不切
        assertNull(Chunker.split("今天天氣很好。"))
        // 前段太短（嗯。）不切
        assertNull(Chunker.split("嗯。好的我知道"))
    }

    @Test fun splitsLongTextWithoutPunctuation() {
        val zh = "我們今天要討論的是下個月的產品發表會，還有行銷預算要怎麼分配給各個部門比較好然後我們再來看一下時程表"
        val (head, tail) = Chunker.split(zh)!!
        assertEquals("我們今天要討論的是下個月的產品發表會，", head)
        assertEquals(zh, head + tail)
        assertNull(Chunker.split("短短的一句話"))
        val en = "so what we really need to figure out is how many people are coming to the event next week and where they will park"
        val (h, t) = Chunker.split(en)!!
        assertEquals(3, t.split(" ").size)
        assertEquals(en, "$h $t")
    }

    @Test fun remainderDropsAlreadyCutPart() {
        assertEquals("我們去公園", Chunker.remainder("今天天氣很好。我們去公園", "今天天氣很好。"))
        // 辨识器改了前面的字：照字数略过
        assertEquals("我們去公園", Chunker.remainder("今天天氣真好，我們去公園", "今天天氣很好。"))
        assertEquals("abc", Chunker.remainder(" abc ", ""))
    }

    @Test fun newCutIsPrefixOfResult() {
        val t = "今天天氣很好。我們去公園"
        assertEquals("今天天氣很好。", Chunker.newCut(t, "", "今天天氣很好。", "我們去公園"))
    }
}
