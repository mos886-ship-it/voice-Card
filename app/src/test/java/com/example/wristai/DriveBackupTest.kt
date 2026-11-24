package com.example.wristai

import com.example.wristai.backup.DriveBackup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Google 云端硬碟备份：打包 / 解开、增量指纹、档名、Drive 清单解析（不用连网）。 */
class DriveBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun meetingDir(): File = tmp.newFolder("m").apply {
        File(this, "meta.json").writeText("""{"id":"20261124_1"}""")
        File(this, "report.md").writeText("## 摘要\n測試")
        File(this, "transcript.txt").writeText("[00:00] 說話者A：你好")
        File(this, "audio.wav").writeBytes(ByteArray(4096))   // 不直接放进 zip（另外压成 m4a）
        File(this, "host_job.txt").writeText("x")             // 处理中的暂存，不备份
        File(this, "segments").mkdirs()
    }

    @Test fun packAndUnpack() {
        val dir = meetingDir()
        val m4a = tmp.newFile("a.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val zip = tmp.newFile("b.zip")
        DriveBackup.pack(dir, m4a, zip)
        val out = tmp.newFolder("restored")
        val names = zip.inputStream().use { DriveBackup.unpack(it, out) }
        assertEquals(setOf("meta.json", "report.md", "transcript.txt", "audio.m4a"), names.toSet())
        assertEquals("## 摘要\n測試", File(out, "report.md").readText())
        assertFalse(File(out, "host_job.txt").exists())
    }

    /** 从网路下载的 zip 不能写到资料夹外面（zip slip）。 */
    @Test fun unpackRejectsPaths() {
        val bytes = ByteArrayOutputStream().also { b ->
            ZipOutputStream(b).use { z ->
                for (n in listOf("../evil.txt", "sub/x.txt", "..", "meta.json")) {
                    z.putNextEntry(ZipEntry(n)); z.write("x".toByteArray()); z.closeEntry()
                }
            }
        }.toByteArray()
        val out = tmp.newFolder("safe")
        val names = DriveBackup.unpack(bytes.inputStream(), out)
        assertEquals(listOf("meta.json"), names)
        assertFalse(File(out.parentFile, "evil.txt").exists())
    }

    @Test fun fingerprintChangesOnlyWhenContentChanges() {
        val dir = meetingDir()
        val a = DriveBackup.fingerprint(dir, audio = true)
        assertEquals(a, DriveBackup.fingerprint(dir, audio = true))
        assertNotEquals(a, DriveBackup.fingerprint(dir, audio = false))      // 改成不带录音 → 要重传
        File(dir, "report.md").writeText("## 摘要\n改過了，比較長")
        assertNotEquals(a, DriveBackup.fingerprint(dir, audio = true))
        File(dir, "host_job.txt").writeText("changed")                      // 暂存档不影响
        val b = DriveBackup.fingerprint(dir, audio = true)
        File(dir, "host_job.txt").writeText("changed again!!")
        assertEquals(b, DriveBackup.fingerprint(dir, audio = true))
    }

    @Test fun zipName() {
        fun m(title: String?) = Meeting(id = "20261124_225933", status = MeetingStatus.DONE, createdAt = 1L, durationSec = 1.0,
            watchNodeId = null, title = title, error = null)
        assertEquals("20261124_225933 Q3 預算週會.zip", DriveBackup.zipName(m("Q3 預算週會")))
        assertEquals("20261124_225933 A B.zip", DriveBackup.zipName(m("A/B")))
        assertEquals("20261124_225933.zip", DriveBackup.zipName(m(null)))
        assertTrue(DriveBackup.eligible(m("x")))
        assertFalse(DriveBackup.eligible(m("x").copy(status = MeetingStatus.RECORDING)))
    }

    /** 授权失败的原因：没登记 OAuth 用户端（10）要讲清楚，不能只说「沒有完成」。 */
    @Test fun consentError() {
        assertTrue(DriveBackup.consentError(0, 10).contains("還沒在 Google Cloud 登記"))
        assertTrue(DriveBackup.consentError(0, null).contains("還沒在 Google Cloud 登記"))   // 选完帐号就被取消
        assertEquals("已取消 Google 授權", DriveBackup.consentError(0, 16))
        assertEquals("Google 授權失敗（代碼 8）", DriveBackup.consentError(-1, 8))
    }

    /** Drive files.list 的回应（官方格式）：只认 App 放的（有 dylanId）。 */
    @Test fun parseList() {
        val j = JSONObject("""{"files":[
            {"id":"f1","name":"20261124_1 會議.zip","size":"2048","appProperties":{"dylanId":"20261124_1","dylanVer":"abc"}},
            {"id":"f2","name":"使用者自己放的.pdf","size":"10"}
        ]}""")
        val r = DriveBackup.parseList(j)
        assertEquals(1, r.size)
        assertEquals(DriveBackup.Remote("f1", "20261124_1", "abc", "20261124_1 會議.zip", 2048), r[0])
    }
}
