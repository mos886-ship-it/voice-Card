package com.example.wristai

import org.junit.Assert.assertEquals
import org.junit.Test

/** 大D的主機（小模型）写的 NUTRITION 行不标准、缺脂肪：要读得到，缺的用表格补。 */
class NutritionParseTest {
    private val table = """
        ## 飲食內容
        | 食物 | 份量 | 熱量(kcal) | 蛋白質(g) | 碳水(g) | 脂肪(g) |
        |---|---|---|---|---|---|
        | 牛肉麵 | 一碗 | 600 | 30 | 70 | 20 |
        | 燙青菜 | 一盤 | 50 | 2 | 5 | 3 |
        | 合計 | | 650 | 32 | 75 | 23 |
    """.trimIndent()

    @Test fun standardLine() =
        assertEquals(Nutrition("午餐", 650, 32, 75, 23), FoodLog.parseNutrition("NUTRITION: meal=午餐; kcal=650; protein=32; carbs=75; fat=23"))

    @Test fun looseLine() {
        assertEquals(Nutrition("午餐", 650, 32, 75, 23), FoodLog.parseNutrition("**NUTRITION：** meal＝午餐，kcal=650，protein=32，carbs=75，fat=23g"))
        assertEquals(Nutrition("午餐", 650, 32, 75, 23), FoodLog.parseNutrition("NUTRITION: 餐別=午餐; 熱量=650; 蛋白質=32; 碳水=75; 脂肪=23"))
    }

    @Test fun missingFatFilledFromTable() {
        val raw = "TITLE: 午餐\nSUMMARY: x\nNUTRITION: meal=午餐; kcal=650; protein=32; carbs=75\n$table"
        val f = FoodLog.finish(raw, table, "x")
        assertEquals(Nutrition("午餐", 650, 32, 75, 23), f.nutrition)
    }

    @Test fun completeLineUntouched() {
        val n = Nutrition("午餐", 700, 30, 80, 25)
        assertEquals(n, FoodLog.fillFromTable(n, table))
    }

    @Test fun macrosCannotExceedWeight() {
        // 31 克卻算出 10＋13＋17＝40 克 → 依比例縮到不超過 31 克，熱量照 4/4/9 重算
        val f = FoodLog.fit(31, 245, 10, 13, 17)
        assert(f[1] + f[2] + f[3] <= 31) { f.toList().toString() }
        assertEquals(listOf(185, 7, 10, 13), f.toList())
        assertEquals(listOf(600, 30, 70, 20), FoodLog.fit(500, 600, 30, 70, 20).toList())   // 正常的不動
    }

    @Test fun itemLineIsFitted() {
        val raw = "NUTRITION: meal=點心; kcal=245; protein=10; carbs=13; fat=17\n" +
            "ITEM: 食物=堅果巧克力; 份量=一小包; 克=31; 對照=無; kcal=245; protein=10; carbs=13; fat=17"
        val n = FoodLog.finish(raw, "", "x").nutrition!!
        assert(n.protein + n.carbs + n.fat <= 31)
    }

    @Test fun hostTableIsFitted() {
        val md = """
            ## 飲食內容
            | 食物 | 份量 | 熱量(kcal) | 蛋白質(g) | 碳水(g) | 脂肪(g) |
            |---|---|---|---|---|---|
            | 堅果巧克力 | 31g | 245 | 10 | 13 | 17 |
            ## 合計
            約 245 大卡；蛋白質 10g、碳水 13g、脂肪 17g
        """.trimIndent()
        val f = FoodLog.finish("NUTRITION: meal=點心; kcal=245; protein=10; carbs=13; fat=17\n$md", md, "x")
        assertEquals(Nutrition("點心", 185, 7, 10, 13), f.nutrition)
        assert(f.markdown.contains("| 185 | 7 | 10 | 13 |")) { f.markdown }
        assert(f.markdown.contains("約 185 大卡")) { f.markdown }
    }
}
