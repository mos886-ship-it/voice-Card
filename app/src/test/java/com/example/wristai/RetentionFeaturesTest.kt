package com.example.wristai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 常吃的、健康目標、個人詞庫。 */
class RetentionFeaturesTest {
    private fun at(h: Int, m: Int = 0) = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }

    @Test fun mealSlots() {
        assertEquals("早餐", FoodRepeat.mealNow(at(8)))
        assertEquals("午餐", FoodRepeat.mealNow(at(12, 30)))
        assertEquals("點心", FoodRepeat.mealNow(at(15)))
        assertEquals("晚餐", FoodRepeat.mealNow(at(19)))
        assertEquals("宵夜", FoodRepeat.mealNow(at(23)))
        assertEquals("宵夜", FoodRepeat.mealNow(at(2)))
    }

    @Test fun favoritesNeedTwoTimes() {
        val now = 1_800_000_000_000L
        fun meal(id: String, title: String, daysAgo: Int) = Meeting(id, MeetingStatus.DONE, now - daysAgo * 86_400_000L, 0.0, null, title, null,
            nutrition = Nutrition("午餐", 700, 30, 80, 20))
        val food = listOf(meal("food_1", "午餐：牛肉麵", 1), meal("food_2", "晚餐：牛肉麵", 3), meal("food_3", "午餐：便當", 2),
            meal("food_4", "早餐：蛋餅＋豆漿", 1), meal("food_5", "早餐：蛋餅＋豆漿", 2), meal("food_6", "早餐：蛋餅＋豆漿", 40))
        val favs = FoodRepeat.favorites(food, now)
        assertEquals(listOf("牛肉麵", "蛋餅＋豆漿"), favs.map { FoodRepeat.dish(it.first.title) })
        assertEquals("food_1", favs[0].first.id)          // 最近那一筆
        assertEquals(2, favs[1].second)                    // 40 天前的不算
    }

    @Test fun goalPresets() {
        assertEquals(180, FoodGoals.preset(FoodGoals.SUGAR)[3])
        assertTrue(FoodGoals.preset(FoodGoals.MUSCLE)[1] > FoodGoals.preset(FoodGoals.BALANCED)[1])
        assertTrue(FoodGoals.adviceRule(FoodGoals.SUGAR).contains("精緻澱粉"))
    }

    @Test fun vocabOnlyOnReportPrompts() {
        val words = listOf("BOM", "王經理")
        val p = Vocab.applyTo("…\n## 待辦事項\n", words)
        assertTrue(p.contains("BOM、王經理"))
        assertEquals(p, Vocab.applyTo(p, words))                                    // 不重複加
        assertEquals("NUTRITION only", Vocab.applyTo("NUTRITION only", words))      // 飲食不加
        assertEquals("x\n## 待辦事項", Vocab.applyTo("x\n## 待辦事項", emptyList()))
    }
}
