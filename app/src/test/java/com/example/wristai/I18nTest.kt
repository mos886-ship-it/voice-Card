package com.example.wristai

import com.example.voiceai.shared.I18n
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 介面多语言：对照表完整（程式里每个 .tr 都有英文、韩文），参数对得上。 */
class I18nTest {
    private val json = File("../shared/src/main/assets/i18n/strings.json").readText()

    @After fun reset() = I18n.loadForTest(json, "zh")

    @Test fun everyKeyInCodeIsTranslated() {
        val table = JSONObject(json)
        val pat = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"\\.trf?\\b")
        val missing = mutableListOf<String>()
        for (root in listOf("src/main/java", "../wear/src/main/java")) {
            File(root).walkTopDown().filter { it.extension == "kt" }.forEach { f ->
                for (m in pat.findAll(f.readText())) {
                    val k = m.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"").replace("\\$", "$")
                    val e = table.optJSONObject(k)
                    if (e == null || e.optString("en").isEmpty() || e.optString("ko").isEmpty()) missing += "${f.name}: $k"
                }
            }
        }
        assertTrue("沒有翻譯：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test fun placeholdersMatch() {
        val table = JSONObject(json)
        val ph = Regex("\\{\\d}")
        for (k in table.keys()) {
            val want = ph.findAll(k).map { it.value }.toSet()
            for (l in listOf("en", "ko")) assertEquals("$l: $k", want, ph.findAll(table.getJSONObject(k).getString(l)).map { it.value }.toSet())
        }
    }

    @Test fun lookupAndFormat() {
        I18n.loadForTest(json, "en")
        assertEquals("Settings", "設定".tr)
        assertEquals("Found 3", "找到 {0} 筆".trf(3))
        assertEquals("沒有這句", "沒有這句".tr)        // 查不到 → 原文
        I18n.loadForTest(json, "ko")
        assertEquals("설정", "設定".tr)
        I18n.loadForTest(json, "zh")
        assertEquals("找到 3 筆", "找到 {0} 筆".trf(3))
    }
}
