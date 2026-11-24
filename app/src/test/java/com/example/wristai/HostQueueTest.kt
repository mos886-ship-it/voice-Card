package com.example.wristai

import com.example.wristai.ai.LocalHost
import org.junit.Assert.assertEquals
import org.junit.Test

/** 大D的主機排队文字（处理步骤、手表看到的）。 */
class HostQueueTest {
    @Test fun queueText() {
        assertEquals("排隊中：前面還有 2 筆，約 6 分鐘", LocalHost.queueText(2, 330))
        assertEquals("排隊中：前面還有 1 筆，不到 1 分鐘", LocalHost.queueText(1, 40))
        assertEquals("輪到你了，約 1 分鐘", LocalHost.queueText(0, 60))
        assertEquals("排隊中：前面還有 9 筆，約 1 小時 5 分", LocalHost.queueText(9, 3600 + 290))
    }
}
