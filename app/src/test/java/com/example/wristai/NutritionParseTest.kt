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
}
