package com.example.wristai

import org.json.JSONObject

/** 一餐的营养估算（AI 依口述估的，不是精确值）。 */
data class Nutrition(
    val meal: String,
    val kcal: Int,
    val protein: Int,
    val carbs: Int,
    val fat: Int,
    /** 膳食纖維（克，算在碳水裡面）；旧记录没有 = 0 */
    val fiber: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("meal", meal).put("kcal", kcal).put("protein", protein).put("carbs", carbs).put("fat", fat).put("fiber", fiber)

    companion object {
        fun fromJson(j: JSONObject?): Nutrition? = j?.let {
            Nutrition(it.optString("meal"), it.optInt("kcal"), it.optInt("protein"), it.optInt("carbs"), it.optInt("fat"), it.optInt("fiber"))
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
        - 每樣食物的蛋白質＋碳水＋脂肪（克）加起來不能超過它的重量；熱量約等於 蛋白質×4＋碳水×4＋脂肪×9。
        - fiber 是膳食纖維（克），算在碳水裡面，不能超過碳水。
        - 餐別依描述與錄音時間判斷：早餐、午餐、晚餐、點心、宵夜、飲料。
        - 若錄音內容不是在描述飲食，TITLE 寫「非飲食內容」，NUTRITION 全部填 0，不要寫 ITEM，並照實摘要內容。
        - 一律使用繁體中文（台灣用語）。這是估算，不要做醫療診斷。

        輸出格式（嚴格遵守，不要加其他前言）：
        第 1 行：TITLE: <餐別：主要食物，15 字內，例如「午餐：牛肉麵＋燙青菜」>
        第 2 行：SUMMARY: <一句話，含總熱量與蛋白質，例如「午餐約 780 大卡，蛋白質 35g」>
        第 3 行：$NUTRITION_PREFIX meal=<餐別>; kcal=<整數>; protein=<整數>; carbs=<整數>; fat=<整數>; fiber=<整數>
        接著每樣食物一行：$ITEM_PREFIX 食物=<名稱>; 份量=<口語份量，例如一碗、半盒>; 克=<整數>; 對照=<清單名稱或 無>; kcal=<整數>; protein=<整數>; carbs=<整數>; fat=<整數>; fiber=<整數>
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
        /** 膳食纖維（克，算在碳水裡面） */
        val fiber: Int = 0,
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
        // 没有 ITEM 行（大D的主機、旧格式）：用 AI 的 NUTRITION 行；那一行缺的数字用 AI 自己的表格加总补上
        if (items.isEmpty() || ai == null) {
            val clean = stripNutrition(markdown)
            val (md, fixed) = fixTable(clean, ai?.meal.orEmpty())
            if (fixed != null) return Finished(fixed, md, FoodWords.of(ReportLang.active).summary(fixed))
            return Finished(ai?.let { fillFromTable(it, markdown) }, clean, aiSummary)
        }
        val n = Nutrition(ai.meal, items.sumOf { it.kcal }, items.sumOf { it.protein }, items.sumOf { it.carbs }, items.sumOf { it.fat },
            items.sumOf { it.fiber })
        val fromDb = items.count { it.db != null }
        val w = FoodWords.of(ReportLang.active)
        val table = buildString {
            appendLine("## ${w.content}")
            appendLine("| ${w.header.joinToString(" | ")} |")
            appendLine("|---|---|---|---|---|---|---|---|")
            for (it in items) {
                val portion = listOf(it.portion, if (it.grams > 0) w.grams(it.grams) else "").filter { s -> s.isNotBlank() }.joinToString(" · ")
                val src = it.db?.let { d -> w.tfda + d.name } ?: if (it.label) w.label else w.ai
                appendLine("| ${it.name} | $portion | ${it.kcal} | ${it.protein} | ${it.carbs} | ${it.fat} | ${it.fiber} | $src |")
            }
            appendLine()
            appendLine("## ${w.total}")
            val fromLabel = items.count { it.label }
            appendLine(w.totalLine(n, items.size, fromDb, fromLabel))
            appendLine()
        }
        return Finished(n, table + stripNutrition(markdown).trim(), w.summary(n))
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
                return@mapNotNull fitted(Item(name, kv["份量"].orEmpty(), grams, null, num("kcal"), num("protein"), num("carbs"), num("fat"), label = true, fiber = num("fiber")))
            val db = FoodDb.find(ref)?.takeIf { grams > 0 }
            val dbKcal = db?.let { Math.round(it.kcal * grams / 100).toInt() }
            val plausible = db != null && (aiKcal == 0 || dbKcal!!.toDouble() / aiKcal in 0.4..2.5)
            if (plausible) Item(name, kv["份量"].orEmpty(), grams, db, dbKcal!!,
                Math.round(db!!.protein * grams / 100).toInt(), Math.round(db.carbs * grams / 100).toInt(), Math.round(db.fat * grams / 100).toInt(),
                fiber = Math.round(db.fiber * grams / 100).toInt())
            else fitted(Item(name, kv["份量"].orEmpty(), grams, null, aiKcal, num("protein"), num("carbs"), num("fat"), fiber = num("fiber")))
        }.toList()

    private fun fitted(it: Item): Item = fit(it.grams, it.kcal, it.protein, it.carbs, it.fat).let { f ->
        // 纖維算在碳水里：碳水缩小时纖維跟着同比例缩、而且不超过碳水
        val fiber = (if (it.carbs > 0 && f[2] < it.carbs) it.fiber * f[2] / it.carbs else it.fiber).coerceAtMost(f[2])
        it.copy(kcal = f[0], protein = f[1], carbs = f[2], fat = f[3], fiber = fiber)
    }

    /**
     * 蛋白質＋碳水＋脂肪的克數不能超過食物重量（AI 常估太高，例如 31 克算出 40 克的營養素）：
     * 超過就依比例縮到不超過重量；熱量照 4／4／9 重算（AI 估的熱量明顯比較高時）。回传 [kcal, protein, carbs, fat]。
     */
    fun fit(grams: Int, kcal: Int, p: Int, c: Int, f: Int): IntArray {
        val sum = p + c + f
        if (grams <= 0 || sum <= grams) return intArrayOf(kcal, p, c, f)
        val k = grams.toDouble() / sum
        val p2 = (p * k).toInt(); val c2 = (c * k).toInt(); val f2 = (f * k).toInt()
        val est = 4 * p2 + 4 * c2 + 9 * f2
        return intArrayOf(if (kcal == 0 || kcal > est * 1.15) est else kcal, p2, c2, f2)
    }

    /** 表格「份量」栏里的克数（「31g」「約 200 克」）；没有 → 0。 */
    private fun gramsIn(s: String): Int =
        Regex("(\\d+)\\s*(公克|克|g)(?![a-zA-Z])", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /**
     * 修正表格（AI 自己写的、或 App 产生的都可以）：每一列份量有克数、营养素加起来又超过克数 → 依比例缩小。
     * 回传（修正后的 Markdown, 修正后的合计；没有改 → null）。
     */
    internal fun fixTable(markdown: String, meal: String): Pair<String, Nutrition?> {
        val lines = markdown.lines().toMutableList()
        val hi = lines.indexOfFirst { l -> l.trim().startsWith("|") && (l.contains("熱量") || l.contains("kcal", true)) }
        if (hi < 0) return markdown to null
        fun cells(r: String) = r.trim().trim('|').split("|").map { it.trim() }
        val h = cells(lines[hi])
        fun col(vararg keys: String) = h.indexOfFirst { c -> keys.any { c.contains(it, true) } }
        val iPortion = col("份量", "Portion", "분량"); val iK = col("熱量", "kcal", "Calories", "열량")
        val iP = col("蛋白", "Protein", "단백질"); val iC = col("碳水", "Carbs", "탄수"); val iF = col("脂肪", "Fat", "지방")
        val iFib = col("纖維", "Fiber", "식이섬유")
        if (iP < 0 || iC < 0 || iF < 0) return markdown to null
        var changed = false
        var kcal = 0; var p = 0; var c = 0; var f = 0; var fib = 0
        var i = hi + 1
        while (i < lines.size && lines[i].trim().startsWith("|")) {
            val r = cells(lines[i]).toMutableList()
            val sep = r.all { x -> x.isEmpty() || x.all { it == '-' || it == ':' } }
            val total = r.firstOrNull()?.let { it.contains("合計") || it.contains("總計") || it.equals("Total", true) || it.contains("합계") } == true
            if (!sep && !total) {
                fun n(j: Int) = if (j < 0) 0 else Regex("\\d+").find(r.getOrNull(j).orEmpty())?.value?.toIntOrNull() ?: 0
                val g = gramsIn(r.getOrNull(iPortion).orEmpty()).takeIf { it > 0 } ?: gramsIn(r.getOrNull(0).orEmpty())
                val fx = fit(g, n(iK), n(iP), n(iC), n(iF))
                val fiber = n(iFib).let { v -> (if (n(iC) > 0 && fx[2] < n(iC)) v * fx[2] / n(iC) else v).coerceAtMost(fx[2]) }
                if (fx[1] != n(iP) || fx[2] != n(iC) || fx[3] != n(iF) || fx[0] != n(iK)) {
                    if (iK >= 0 && iK < r.size) r[iK] = "${fx[0]}"
                    r[iP] = "${fx[1]}"; r[iC] = "${fx[2]}"; r[iF] = "${fx[3]}"
                    if (iFib >= 0 && iFib < r.size) r[iFib] = "$fiber"
                    lines[i] = "| " + r.joinToString(" | ") + " |"
                    changed = true
                }
                kcal += fx[0]; p += fx[1]; c += fx[2]; f += fx[3]; fib += fiber
            }
            if (total) lines[i] = ""   // AI 写的合计列：数字改过就不对了，拿掉
            i++
        }
        if (!changed) return markdown to null
        val n = Nutrition(meal, kcal, p, c, f, fib)
        // 「合計」一节的那一行换成新的数字
        val ti = lines.indexOfFirst { l -> l.trim().startsWith("#") && l.trim().trimStart('#').trim().let { it == "合計" || it.equals("Total", true) || it == "합계" } }
        if (ti >= 0) {
            val li = (ti + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }
            if (li != null && !lines[li].trim().startsWith("#")) lines[li] = FoodWords.of(ReportLang.active).totalLine(n, 0, 0, 0)
        }
        return lines.filterIndexed { idx, l -> !(l.isEmpty() && idx > hi && idx < i) }.joinToString("\n") to n
    }

    private val LEGACY_PROMPT = """
        你是營養記錄助理。使用者會用口語描述剛吃（或喝）了什麼，請整理成飲食記錄，並估算熱量與三大營養素。
        - 依台灣常見的份量與做法估算（便當、小吃、麵店、手搖飲、便利商店等）。
        - 使用者明確說出的份量、杯型、甜度、冰塊、加料一定照用，不可以改成別的（例如說「大杯半糖」就是大杯、半糖），只有沒說的部分才用預設。
        - 沒說清楚的地方（份量、甜度、加料、烹調方式）用常見預設，並在「假設與待確認」寫出來。
        - 數字一律取整數；熱量單位大卡（kcal），營養素單位公克（g）。
        - 每樣食物的蛋白質＋碳水＋脂肪（克）加起來不能超過它的重量；熱量約等於 蛋白質×4＋碳水×4＋脂肪×9。
        - 餐別依描述與錄音時間判斷：早餐、午餐、晚餐、點心、宵夜、飲料。
        - 若錄音內容不是在描述飲食，TITLE 寫「非飲食內容」，NUTRITION 全部填 0，並照實摘要內容。
        - 一律使用繁體中文（台灣用語）。這是估算，不要做醫療診斷。

        輸出格式（嚴格遵守，不要加其他前言）：
        第 1 行：TITLE: <餐別：主要食物，15 字內，例如「午餐：牛肉麵＋燙青菜」>
        第 2 行：SUMMARY: <一句話，含總熱量與蛋白質，例如「午餐約 780 大卡，蛋白質 35g」>
        第 3 行：$NUTRITION_PREFIX meal=<餐別>; kcal=<整數>; protein=<整數>; carbs=<整數>; fat=<整數>
        第 4 行起：Markdown，依序包含：
        ## 飲食內容
        （用表格：食物 | 份量 | 熱量(kcal) | 蛋白質(g) | 碳水(g) | 脂肪(g) | 纖維(g)；每樣食物一列）
        ## 合計
        （一行：約 X 大卡；蛋白質 Xg、碳水 Xg（纖維 Xg）、脂肪 Xg）
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

    /** 从模型输出找出 NUTRITION 行（容许全形冒号 / 等号、逗号分隔、中文栏位名；小模型常写得不太标准）。 */
    fun parseNutrition(text: String): Nutrition? {
        val line = text.lineSequence().map { it.trim().trimStart('*', '#', ' ') }
            .firstOrNull { it.startsWith("NUTRITION", true) } ?: return null
        val body = line.substring("NUTRITION".length).trimStart('*', ' ', ':', '：')
        val kv = body.split(';', '；', ',', '，', '、').mapNotNull { part ->
            val i = part.indexOfFirst { it == '=' || it == '＝' || it == ':' || it == '：' }
            if (i <= 0) return@mapNotNull null
            val k = part.substring(0, i).trim().lowercase()
            val v = part.substring(i + 1).trim()
            if (k.isEmpty() || v.isEmpty()) null else k to v
        }.toMap()
        fun num(vararg ks: String) = ks.firstNotNullOfOrNull { kv[it] }?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 0
        return Nutrition(kv["meal"] ?: kv["餐別"] ?: "", num("kcal", "熱量", "热量", "calories"), num("protein", "蛋白質", "蛋白质"),
            num("carbs", "碳水", "碳水化合物", "carbohydrate"), num("fat", "脂肪"), num("fiber", "纖維", "膳食纖維", "纤维", "膳食纤维"))
    }

    /**
     * 修正旧记录：大D的主機写的 NUTRITION 行缺数字（例如脂肪 0）、但表格有 → 用表格补上（只做一次；健康同步会跟着更新）。
     */
    fun repairStored(context: android.content.Context) {
        val prefs = context.getSharedPreferences("wristai_settings", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("nutrition_repair_v2", false)) return
        MeetingStore.reload(context)
        for (m in MeetingStore.meetings.value) {
            val n = m.nutrition ?: continue
            if (!isFood(m.id) || m.status != MeetingStatus.DONE) continue
            val md = MeetingStore.reportFile(context, m.id).takeIf { it.exists() }?.readText() ?: continue
            // 营养素超过重量的列依比例缩小（表格与合计都改）；再补缺的数字
            val (md2, byTable) = fixTable(md, n.meal)
            if (byTable != null) MeetingStore.reportFile(context, m.id).writeText(md2)
            val fixed = byTable ?: fillFromTable(n, md)
            if (fixed != n) MeetingStore.update(context, m.id) { it.copy(nutrition = fixed) }
        }
        prefs.edit().putBoolean("nutrition_repair_v2", true).apply()
    }

    /** AI 自己写的「飲食內容」表格：各栏加总（热量、蛋白质、碳水、脂肪），用来补 NUTRITION 行缺的数字。 */
    internal fun fillFromTable(n: Nutrition, markdown: String): Nutrition {
        if (n.kcal > 0 && n.protein > 0 && n.carbs > 0 && n.fat > 0 && n.fiber > 0) return n
        val rows = markdown.lines().map { it.trim() }.filter { it.startsWith("|") }
        val header = rows.firstOrNull { r -> r.contains("熱量") || r.contains("kcal", true) } ?: return n
        fun cells(r: String) = r.trim('|').split("|").map { it.trim() }
        val h = cells(header)
        fun col(vararg keys: String) = h.indexOfFirst { c -> keys.any { c.contains(it, true) } }
        val iK = col("熱量", "kcal"); val iP = col("蛋白"); val iC = col("碳水"); val iF = col("脂肪"); val iFib = col("纖維", "Fiber")
        val body = rows.dropWhile { it != header }.drop(1).map { cells(it) }
            .filter { r -> r.none { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } } }
            .filter { r -> r.firstOrNull()?.let { it.contains("合計") || it.contains("總計") } != true }
        if (body.isEmpty()) return n
        fun sum(i: Int) = if (i < 0) 0 else body.sumOf { r -> r.getOrNull(i)?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 0 }
        return n.copy(
            kcal = n.kcal.takeIf { it > 0 } ?: sum(iK),
            protein = n.protein.takeIf { it > 0 } ?: sum(iP),
            carbs = n.carbs.takeIf { it > 0 } ?: sum(iC),
            fat = n.fat.takeIf { it > 0 } ?: sum(iF),
            fiber = n.fiber.takeIf { it > 0 } ?: sum(iFib),
        )
    }

    /** 报告里不显示机读行。 */
    fun stripNutrition(markdown: String): String =
        markdown.lines().filterNot { it.trim().startsWith(NUTRITION_PREFIX, true) || it.trim().startsWith(ITEM_PREFIX, true) }
            .joinToString("\n")
}

/** 饮食报告里 App 自己产生的文字（跟着报告语言）。NUTRITION 的餐别一律中文（健康同步、问候语靠它判断）。 */
internal class FoodWords(
    val content: String, val header: List<String>, val total: String,
    val tfda: String, val label: String, val ai: String,
    val grams: (Int) -> String,
    val totalLine: (Nutrition, Int, Int, Int) -> String,
    val summary: (Nutrition) -> String,
) {
    companion object {
        private val MEALS = mapOf(
            "早餐" to ("Breakfast" to "아침"), "午餐" to ("Lunch" to "점심"), "晚餐" to ("Dinner" to "저녁"),
            "點心" to ("Snack" to "간식"), "宵夜" to ("Late-night snack" to "야식"), "飲料" to ("Drink" to "음료"),
        )

        fun meal(m: String, l: ReportLang): String = when (l) {
            ReportLang.ZH -> m.ifBlank { "這餐" }
            ReportLang.EN -> MEALS[m]?.first ?: "This meal"
            ReportLang.KO -> MEALS[m]?.second ?: "이번 식사"
        }

        fun of(l: ReportLang): FoodWords = when (l) {
            ReportLang.ZH -> FoodWords(
                "飲食內容", listOf("食物", "份量", "熱量(kcal)", "蛋白質(g)", "碳水(g)", "脂肪(g)", "纖維(g)", "來源"), "合計",
                "食藥署：", "營養標示", "AI 估算", { "約 ${it}g" },
                { n, size, db, lab ->
                    val sources = listOfNotNull(
                        if (db > 0) "$db 樣依食藥署「食品營養成分資料集」計算" else null,
                        if (lab > 0) "$lab 樣依包裝營養標示" else null,
                    )
                    "約 ${n.kcal} 大卡；蛋白質 ${n.protein}g、碳水 ${n.carbs}g（纖維 ${n.fiber}g）、脂肪 ${n.fat}g" +
                        if (sources.isNotEmpty()) "（$size 樣中 ${sources.joinToString("、")}）" else "（皆為 AI 估算）"
                },
                { n -> "${meal(n.meal, l)}約 ${n.kcal} 大卡，蛋白質 ${n.protein}g" },
            )
            ReportLang.EN -> FoodWords(
                "What you ate", listOf("Food", "Portion", "Calories (kcal)", "Protein (g)", "Carbs (g)", "Fat (g)", "Fiber (g)", "Source"), "Total",
                "Taiwan FDA: ", "Nutrition label", "AI estimate", { "about ${it}g" },
                { n, size, db, lab ->
                    val sources = listOfNotNull(
                        if (db > 0) "$db from the Taiwan FDA food composition database" else null,
                        if (lab > 0) "$lab from the package nutrition label" else null,
                    )
                    "About ${n.kcal} kcal; protein ${n.protein}g, carbs ${n.carbs}g (fiber ${n.fiber}g), fat ${n.fat}g" +
                        if (sources.isNotEmpty()) " (of $size items: ${sources.joinToString(", ")})" else " (all AI estimates)"
                },
                { n -> "${meal(n.meal, l)}: about ${n.kcal} kcal, protein ${n.protein}g" },
            )
            ReportLang.KO -> FoodWords(
                "먹은 음식", listOf("음식", "분량", "열량(kcal)", "단백질(g)", "탄수화물(g)", "지방(g)", "식이섬유(g)", "출처"), "합계",
                "대만 식약청: ", "영양성분표", "AI 추정", { "약 ${it}g" },
                { n, size, db, lab ->
                    val sources = listOfNotNull(
                        if (db > 0) "${db}개는 대만 식약청 식품영양성분 데이터 기준" else null,
                        if (lab > 0) "${lab}개는 포장 영양성분표 기준" else null,
                    )
                    "약 ${n.kcal}kcal; 단백질 ${n.protein}g, 탄수화물 ${n.carbs}g(식이섬유 ${n.fiber}g), 지방 ${n.fat}g" +
                        if (sources.isNotEmpty()) " (${size}개 중 ${sources.joinToString(", ")})" else " (모두 AI 추정)"
                },
                { n -> "${meal(n.meal, l)} 약 ${n.kcal}kcal, 단백질 ${n.protein}g" },
            )
        }
    }
}
