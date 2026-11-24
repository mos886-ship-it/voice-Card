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

    /** 报告「基本資料」的「隱私」：主机上的录音、报告删了没。 */
    @Test fun cleanupText() {
        assertEquals("大D的主機上的錄音與報告都已刪除", ProcessMeetingWorker.cleanupText(true, true))
        assertEquals("大D的主機上的錄音已刪除；報告會在 24 小時內自動刪除", ProcessMeetingWorker.cleanupText(true, false))
        assertEquals("大D的主機會在 24 小時內自動刪除錄音與報告", ProcessMeetingWorker.cleanupText(false, false))
        // 存进 meta.json 再读回来不会掉；旧资料没有这栏 → null
        val m = Meeting(id = "x", status = MeetingStatus.DONE, createdAt = 1L, durationSec = 3.0,
            watchNodeId = null, title = null, error = null, hostCleanup = ProcessMeetingWorker.CLEANUP_ALL)
        assertEquals(ProcessMeetingWorker.CLEANUP_ALL, Meeting.fromJson(m.toJson()).hostCleanup)
        assertEquals(null, Meeting.fromJson(m.copy(hostCleanup = null).toJson()).hostCleanup)
    }
}
