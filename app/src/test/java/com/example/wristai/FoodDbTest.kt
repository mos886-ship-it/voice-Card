package com.example.wristai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 食藥署内建资料＋饮食 ITEM 行重算（用真正的 assets/nutrition/tfnd.json）。 */
class FoodDbTest {
    companion object {
        @BeforeClass @JvmStatic fun load() {
            val f = listOf("src/main/assets/nutrition/tfnd.json", "app/src/main/assets/nutrition/tfnd.json").map(::File).first { it.exists() }
            FoodDb.loadForTest(f.readText())
        }
    }

    private val sample = """
        TITLE: 午餐：排骨便當＋無糖豆漿
        SUMMARY: 午餐約 900 大卡，蛋白質 40g
        NUTRITION: meal=午餐; kcal=900; protein=40; carbs=110; fat=30
        ITEM: 食物=白飯; 份量=一碗; 克=200; 對照=白飯; kcal=360; protein=6; carbs=80; fat=1
        ITEM: 食物=炸排骨; 份量=一片; 克=120; 對照=無; kcal=380; protein=25; carbs=15; fat=24
        ITEM: 食物=無糖豆漿; 份量=一杯; 克=400; 對照=豆漿(無糖)·熟; kcal=140; protein=14; carbs=6; fat=7
        ITEM: 食物=亂對的東西; 份量=一點; 克=10; 對照=白飯; kcal=900; protein=0; carbs=0; fat=0
        ## 營養小提醒
        - 炸物熱量高
        ## 假設與待確認
        - 白飯一碗約 200g
    """.trimIndent()

    @Test fun listsFoodsForPrompt() {
        assertTrue(FoodDb.ready)
        assertTrue(FoodDb.promptList.contains("白飯"))
        assertTrue(FoodDb.promptList.length in 5_000..40_000)
    }

    @Test fun findIsExactOnly() {
        assertNotNull(FoodDb.find("白飯"))
        assertNotNull(FoodDb.find(" 白飯·熟 "))
        assertNull(FoodDb.find("無"))
        assertNull(FoodDb.find("白"))
    }

    @Test fun recomputesFromDatabase() {
        val items = FoodLog.parseItems(sample)
        assertEquals(4, items.size)
        val rice = items[0]
        assertNotNull(rice.db)
        assertEquals(Math.round(FoodDb.find("白飯")!!.kcal * 2).toInt(), rice.kcal)   // 200g = 2 × 每 100 克
        assertNull(items[1].db)                       // 對照=無 → AI 估的
        assertEquals(380, items[1].kcal)
        assertNull(items[3].db)                       // 资料库 18 大卡 vs AI 900：差太多，不用资料库
        assertEquals(900, items[3].kcal)
    }

    @Test fun finishBuildsTableAndSummary() {
        val done = FoodLog.finish(sample, sample.lines().drop(2).joinToString("\n"), "AI 的摘要")
        val n = done.nutrition!!
        assertEquals(FoodLog.parseItems(sample).sumOf { it.kcal }, n.kcal)
        assertTrue(done.markdown.startsWith("## 飲食內容"))
        assertTrue(done.markdown.contains("食藥署：白飯"))
        assertTrue(done.markdown.contains("AI 估算"))
        assertTrue(done.markdown.contains("## 合計"))
        assertTrue(done.markdown.contains("## 營養小提醒"))
        assertFalse(done.markdown.contains("ITEM:"))
        assertFalse(done.markdown.contains("NUTRITION:"))
        assertEquals("午餐約 ${n.kcal} 大卡，蛋白質 ${n.protein}g", done.summary)
    }

    @Test fun oldFormatStaysAsIs() {
        val old = "TITLE: 早餐\nSUMMARY: 早餐約 400 大卡\nNUTRITION: meal=早餐; kcal=400; protein=15; carbs=50; fat=12\n## 飲食內容\n| a | b |"
        val done = FoodLog.finish(old, old.lines().drop(2).joinToString("\n"), "早餐約 400 大卡")
        assertEquals(400, done.nutrition!!.kcal)
        assertEquals("早餐約 400 大卡", done.summary)
        assertTrue(done.markdown.startsWith("## 飲食內容"))
    }

    @Test fun promptCarriesDatabaseList() {
        assertTrue(FoodLog.PROMPT.contains("【食藥署食品營養成分資料】"))
        assertTrue(FoodLog.PROMPT.contains("ITEM:"))
        assertTrue(FoodLog.TEXT_PROMPT.contains("白飯"))
        assertFalse(FoodLog.TEXT_PROMPT.contains("TRANSCRIPT"))
        assertFalse(FoodLog.PROMPT.lines().any { it.startsWith("        ") })   // trimIndent 有作用
    }
}
