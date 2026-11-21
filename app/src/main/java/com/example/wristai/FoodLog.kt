package com.example.wristai

import org.json.JSONObject

/** 一餐的营养估算（AI 依口述估的，不是精确值）。 */
data class Nutrition(
    val meal: String,
    val kcal: Int,
    val protein: Int,
    val carbs: Int,
    val fat: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("meal", meal).put("kcal", kcal).put("protein", protein).put("carbs", carbs).put("fat", fat)

    companion object {
        fun fromJson(j: JSONObject?): Nutrition? = j?.let {
            Nutrition(it.optString("meal"), it.optInt("kcal"), it.optInt("protein"), it.optInt("carbs"), it.optInt("fat"))
        }
    }
}

/**
 * 饮食记录：使用者用口语描述吃了什么 → AI 列出食物、估算热量与三大营养素。
 *
 * - 录音 id 以 [ID_PREFIX] 开头就是饮食记录（手表 / 手机按「飲食記錄」录的）。
 * - 估算热量要靠云端 AI（Gemini）；装置端的 Galaxy AI 做不到结构化的数字。
 * - 模型第 3 行输出 NUTRITION 机读行，App 解析后存进 Meeting.nutrition，首页加总「今日飲食」。
 */
object FoodLog {
    const val ID_PREFIX = "food_"
    const val NUTRITION_PREFIX = "NUTRITION:"

    fun isFood(id: String) = id.startsWith(ID_PREFIX)

    val PROMPT = """
        你是營養記錄助理。使用者會用口語描述剛吃（或喝）了什麼，請整理成飲食記錄，並估算熱量與三大營養素。
        - 依台灣常見的份量與做法估算（便當、小吃、麵店、手搖飲、便利商店等）。
        - 使用者明確說出的份量、杯型、甜度、冰塊、加料一定照用，不可以改成別的（例如說「大杯半糖」就是大杯、半糖），只有沒說的部分才用預設。
        - 沒說清楚的地方（份量、甜度、加料、烹調方式）用常見預設，並在「假設與待確認」寫出來。
        - 數字一律取整數；熱量單位大卡（kcal），營養素單位公克（g）。
        - 餐別依描述與錄音時間判斷：早餐、午餐、晚餐、點心、宵夜、飲料。
        - 若錄音內容不是在描述飲食，TITLE 寫「非飲食內容」，NUTRITION 全部填 0，並照實摘要內容。
        - 一律使用繁體中文（台灣用語）。這是估算，不要做醫療診斷。

        輸出格式（嚴格遵守，不要加其他前言）：
        第 1 行：TITLE: <餐別：主要食物，15 字內，例如「午餐：牛肉麵＋燙青菜」>
        第 2 行：SUMMARY: <一句話，含總熱量與蛋白質，例如「午餐約 780 大卡，蛋白質 35g」>
        第 3 行：$NUTRITION_PREFIX meal=<餐別>; kcal=<整數>; protein=<整數>; carbs=<整數>; fat=<整數>
        第 4 行起：Markdown，依序包含：
        ## 飲食內容
        （用表格：食物 | 份量 | 熱量(kcal) | 蛋白質(g) | 碳水(g) | 脂肪(g)；每樣食物一列）
        ## 合計
        （一行：約 X 大卡；蛋白質 Xg、碳水 Xg、脂肪 Xg）
        ## 營養小提醒
        （1～3 點，針對這一餐的簡短、實際建議）
        ## 假設與待確認

        之後單獨一行輸出 ${GeminiReportGenerator.TRANSCRIPT_MARKER}
        之後是逐字稿，每段一行：[mm:ss] 內容
    """.trimIndent()

    /** 组合式（语音辨识 → 大模型）用：输入是逐字稿，不要求再输出逐字稿。 */
    val TEXT_PROMPT: String by lazy {
        PROMPT.substringBefore("之後單獨一行輸出").trimEnd() + "\n\n（使用者給你的是語音辨識的逐字稿，可能有同音錯字，請依常理判斷食物名稱。）"
    }

    /** 从模型输出找出 NUTRITION 行。 */
    fun parseNutrition(text: String): Nutrition? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(NUTRITION_PREFIX, true) } ?: return null
        val kv = line.substringAfter(':').split(';', '；').mapNotNull { part ->
            val k = part.substringBefore('=').trim().lowercase()
            val v = part.substringAfter('=', "").trim()
            if (k.isEmpty() || v.isEmpty()) null else k to v
        }.toMap()
        fun num(k: String) = kv[k]?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 0
        return Nutrition(kv["meal"].orEmpty(), num("kcal"), num("protein"), num("carbs"), num("fat"))
    }

    /** 报告里不显示机读行。 */
    fun stripNutrition(markdown: String): String =
        markdown.lines().filterNot { it.trim().startsWith(NUTRITION_PREFIX, true) }.joinToString("\n")
}
