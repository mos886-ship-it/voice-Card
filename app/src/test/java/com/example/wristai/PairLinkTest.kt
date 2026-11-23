package com.example.wristai

import com.example.wristai.ai.LocalHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 大D的主機配对连结（跟 local-ai/pairing.py 产生的一样）与装置型号。 */
class PairLinkTest {
    @Test fun parsesHostLink() {
        // pairing.py：urllib.parse.urlencode({"url", "lan", "code"})
        val l = LocalHost.parseLink("dylan://pair?url=http%3A%2F%2F203.0.113.5%3A48765&lan=http%3A%2F%2F192.168.0.23%3A8765&code=Ab-c_9xYz")!!
        assertEquals(listOf("http://203.0.113.5:48765", "http://192.168.0.23:8765"), l.urls)
        assertEquals("Ab-c_9xYz", l.code)
    }

    @Test fun sameUrlTwiceTriedOnce() {
        val l = LocalHost.parseLink("dylan://pair?url=http%3A%2F%2F192.168.0.23%3A8765&lan=http%3A%2F%2F192.168.0.23%3A8765&code=x")!!
        assertEquals(listOf("http://192.168.0.23:8765"), l.urls)
    }

    @Test fun rejectsOtherLinks() {
        assertNull(LocalHost.parseLink("https://example.com/pair?code=x"))
        assertNull(LocalHost.parseLink("dylan://pair?url=http%3A%2F%2Fa"))   // 没有配对码
        assertNull(LocalHost.parseLink("dylan://other?code=x"))
    }

    @Test fun modelNames() {
        assertEquals("Galaxy Z Fold5", LocalHost.modelName("SM-F946B"))
        assertEquals("Galaxy S25 Ultra", LocalHost.modelName("SM-S938N"))
        assertEquals("Pixel 9", LocalHost.modelName("Pixel 9"))
    }
}
