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

    const val ITEM_PREFIX = "ITEM:"

    /**
     * 提示词：有内建的食藥署资料就用「拆食材＋对照资料库」版（App 重算数字、自己产生表格与合计），
     * 没有就用旧版（AI 自己写表格与合计）。用之前要先 [FoodDb.init]。
     */
    val PROMPT: String get() = if (FoodDb.ready) dbPrompt(withTranscript = true) else LEGACY_PROMPT

    private fun dbPrompt(withTranscript: Boolean): String = """
        你是營養記錄助理。使用者會用口語描述剛吃（或喝）了什麼，請整理成飲食記錄，並估算熱量與三大營養素。
        - 依台灣常見的份量與做法估算（便當、小吃、麵店、手搖飲、便利商店等）。
        - 使用者明確說出的份量、杯型、甜度、冰塊、加料一定照用，不可以改成別的（例如說「大杯半糖」就是大杯、半糖），只有沒說的部分才用預設。
        - 沒說清楚的地方（份量、甜度、加料、烹調方式）用常見預設，並在「假設與待確認」寫出來。
        - 把這一餐拆成食材或單品，每樣一行 ITEM，估算重量（克，可食部分）。煎炒炸用的油、勾芡、糖、醬料要另外列一行（例如「調合植物油」）。
        - 「對照」：從下面的【食藥署食品營養成分資料】清單挑一個「完全相同」的名稱（不要寫「·生」「·熟」）；清單裡沒有相近的就填「無」，不要硬湊。
          清單標「·生」的是生重：克數要填烹調前的生重。整道小吃（例如牛肉麵）清單沒有就拆成麵、肉、湯、青菜各自對照，拆不了就填「無」。
        - 若附有照片：依照片辨識食物與份量（參考碗盤、餐具、杯子、包裝的大小估克數）；照片與口述不一致時以口述為準；看不清楚或被擋住的寫進「假設與待確認」。
        - 照片裡看得到包裝的「營養標示」：照標示的熱量、營養素與份量（每份 / 每 100 克）換算吃下的量，「對照」填「營養標示」。
        - 每行也寫你自己估的熱量與營養素（App 對不到資料時用）。數字一律取整數；熱量單位大卡（kcal），營養素單位公克（g）。
        - 餐別依描述與錄音時間判斷：早餐、午餐、晚餐、點心、宵夜、飲料。
        - 若錄音內容不是在描述飲食，TITLE 寫「非飲食內容」，NUTRITION 全部填 0，不要寫 ITEM，並照實摘要內容。
        - 一律使用繁體中文（台灣用語）。這是估算，不要做醫療診斷。

        輸出格式（嚴格遵守，不要加其他前言）：
        第 1 行：TITLE: <餐別：主要食物，15 字內，例如「午餐：牛肉麵＋燙青菜」>
        第 2 行：SUMMARY: <一句話，含總熱量與蛋白質，例如「午餐約 780 大卡，蛋白質 35g」>
        第 3 行：$NUTRITION_PREFIX meal=<餐別>; kcal=<整數>; protein=<整數>; carbs=<整數>; fat=<整數>
        接著每樣食物一行：$ITEM_PREFIX 食物=<名稱>; 份量=<口語份量，例如一碗、半盒>; 克=<整數>; 對照=<清單名稱或 無>; kcal=<整數>; protein=<整數>; carbs=<整數>; fat=<整數>
        之後是 Markdown（不要寫食物表格與合計，App 會自己產生），依序包含：
        ## 營養小提醒
        （1～3 點，針對這一餐的簡短、實際建議）
        ## 假設與待確認
    """.trimIndent() +
        (if (withTranscript) "\n\n之後單獨一行輸出 ${GeminiReportGenerator.TRANSCRIPT_MARKER}\n之後是逐字稿，每段一行：[mm:ss] 內容" else "") +
        "\n\n【食藥署食品營養成分資料】（每 100 克；只能從這裡挑「對照」名稱）\n" + FoodDb.promptList

    /** 飲食内容表格的一列。[db] = 对到的食藥署资料（null = 用 AI 估的）。 */
    data class Item(
        val name: String, val portion: String, val grams: Int, val db: FoodDb.Entry?,
        val kcal: Int, val protein: Int, val carbs: Int, val fat: Int,
        /** 照片里的包装营养标示算的（数字是 AI 照标示换算） */
        val label: Boolean = false,
    )

    /** 做完的饮食记录：营养合计、报告（含 App 产生的表格）、一句话摘要。 */
    data class Finished(val nutrition: Nutrition?, val markdown: String, val summary: String)

    /**
     * 模型输出 → 存档用的结果。有 ITEM 行：对到食藥署的用资料库重算，App 自己产生「飲食內容」表格与「合計」，
     * 摘要也用重算后的数字；没有 ITEM 行（旧格式、大D的主機）就照 AI 给的。
     */
    fun finish(raw: String, markdown: String, aiSummary: String): Finished {
        val ai = parseNutrition(raw)
        val items = parseItems(raw)
        if (items.isEmpty() || ai == null) return Finished(ai, stripNutrition(markdown), aiSummary)
        val n = Nutrition(ai.meal, items.sumOf { it.kcal }, items.sumOf { it.protein }, items.sumOf { it.carbs }, items.sumOf { it.fat })
        val fromDb = items.count { it.db != null }
        val table = buildString {
            appendLine("## 飲食內容")
            appendLine("| 食物 | 份量 | 熱量(kcal) | 蛋白質(g) | 碳水(g) | 脂肪(g) | 來源 |")
            appendLine("|---|---|---|---|---|---|---|")
            for (it in items) {
                val portion = listOf(it.portion, if (it.grams > 0) "約 ${it.grams}g" else "").filter { s -> s.isNotBlank() }.joinToString(" · ")
                val src = it.db?.let { d -> "食藥署：${d.name}" } ?: if (it.label) "營養標示" else "AI 估算"
                appendLine("| ${it.name} | $portion | ${it.kcal} | ${it.protein} | ${it.carbs} | ${it.fat} | $src |")
            }
            appendLine()
            appendLine("## 合計")
            val fromLabel = items.count { it.label }
            val sources = listOfNotNull(
                if (fromDb > 0) "$fromDb 樣依食藥署「食品營養成分資料集」計算" else null,
                if (fromLabel > 0) "$fromLabel 樣依包裝營養標示" else null,
            )
            appendLine("約 ${n.kcal} 大卡；蛋白質 ${n.protein}g、碳水 ${n.carbs}g、脂肪 ${n.fat}g" +
                if (sources.isNotEmpty()) "（${items.size} 樣中 ${sources.joinToString("、")}）" else "（皆為 AI 估算）")
            appendLine()
        }
        val meal = n.meal.ifBlank { "這餐" }
        return Finished(n, table + stripNutrition(markdown).trim(), "${meal}約 ${n.kcal} 大卡，蛋白質 ${n.protein}g")
    }

    /** 解析 ITEM 行；对照的名称在资料库里，而且跟 AI 自己估的差不多（0.4～2.5 倍），才用资料库的数字。 */
    fun parseItems(text: String): List<Item> = text.lineSequence().map { it.trim() }
        .filter { it.startsWith(ITEM_PREFIX, true) }
        .mapNotNull { line ->
            val kv = line.substringAfter(':').split(';', '；').mapNotNull { part ->
                val k = part.substringBefore('=').trim()
                val v = part.substringAfter('=', "").trim()
                if (k.isEmpty()) null else k.lowercase() to v
            }.toMap()
            fun num(k: String) = kv[k]?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 0
            val name = kv["食物"].orEmpty().ifBlank { return@mapNotNull null }
            val grams = num("克")
            val aiKcal = num("kcal")
            val ref = kv["對照"] ?: kv["对照"]
            if (ref?.trim() == "營養標示" || ref?.trim() == "营养标示")
                return@mapNotNull Item(name, kv["份量"].orEmpty(), grams, null, num("kcal"), num("protein"), num("carbs"), num("fat"), label = true)
            val db = FoodDb.find(ref)?.takeIf { grams > 0 }
            val dbKcal = db?.let { Math.round(it.kcal * grams / 100).toInt() }
            val plausible = db != null && (aiKcal == 0 || dbKcal!!.toDouble() / aiKcal in 0.4..2.5)
            if (plausible) Item(name, kv["份量"].orEmpty(), grams, db, dbKcal!!,
                Math.round(db!!.protein * grams / 100).toInt(), Math.round(db.carbs * grams / 100).toInt(), Math.round(db.fat * grams / 100).toInt())
            else Item(name, kv["份量"].orEmpty(), grams, null, aiKcal, num("protein"), num("carbs"), num("fat"))
        }.toList()

    private val LEGACY_PROMPT = """
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
    val TEXT_PROMPT: String get() =
        (if (FoodDb.ready) dbPrompt(withTranscript = false) else LEGACY_PROMPT.substringBefore("之後單獨一行輸出").trimEnd()) +
            "\n\n（使用者給你的是語音辨識的逐字稿，可能有同音錯字，請依常理判斷食物名稱。）"

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
        markdown.lines().filterNot { it.trim().startsWith(NUTRITION_PREFIX, true) || it.trim().startsWith(ITEM_PREFIX, true) }
            .joinToString("\n")
}
