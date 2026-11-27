package com.example.wristai

import android.content.Context
import org.json.JSONObject

/**
 * 各国官方的食品营养成分资料（每 100 克），App 内建在 assets/nutrition/：
 * - TW：tfnd.json — 衛生福利部食品藥物管理署「食品營養成分資料集」（政府資料開放授權條款－第1版），tools/gen_tfnd.py
 * - US：us.json — USDA FoodData Central（公共領域 CC0），tools/gen_usda.py
 * - JP：jp.json — 文部科學省「日本食品標準成分表」，tools/gen_mext.py
 * - KR：kr.json — 식품의약품안전처「식품영양성분 데이터베이스」，tools/gen_mfds.py
 * 每个档案的 "source"、"license" 栏写出处（关于与授权页也有）。
 *
 * 一餐用哪一份：这一餐所在的国家（[FoodRegion.of]）。处理前呼叫 [use]，之后 [ready] / [find] / [promptList] 都是那一份。
 * AI 把一餐拆成食材＋克数，并从 [promptList] 里挑出完全相同的名称；App 用资料重算（[FoodLog.finish]），对不到的才用 AI 估的。
 */
object FoodDb {
    data class Entry(
        val name: String, val state: String?, val category: String,
        val kcal: Double, val protein: Double, val fat: Double, val carbs: Double,
        /** 膳食纖維（每 100 克） */
        val fiber: Double = 0.0,
    )

    /** 一份资料库：[label] 是报告与提示词用的简称（例「食藥署」「USDA」）。 */
    class Db(val country: String, val label: String, val source: String, val entries: Map<String, Entry>) {
        /** 给 AI 的清单：依类别分行，生的 / 熟的加「·生」「·熟」提醒克数要对应。 */
        val promptList: String by lazy {
            entries.values.groupBy { it.category }.entries.joinToString("\n") { (cat, list) ->
                "【$cat】" + list.joinToString("、") { e -> e.name + (e.state?.let { "·$it" } ?: "") }
            }
        }
    }

    /** 国家 → 档案、简称。 */
    private val FILES = mapOf(
        "TW" to ("nutrition/tfnd.json" to "食藥署"),
        "US" to ("nutrition/us.json" to "USDA"),
        "JP" to ("nutrition/jp.json" to "日本食品成分表"),
        "KR" to ("nutrition/kr.json" to "韓國食藥處"),
    )

    private val loaded = mutableMapOf<String, Db?>()

    /** 现在这一餐用的资料库（没有 = 当地没有内建资料，饮食照旧用 AI 估）。 */
    @Volatile var active: Db? = null
        private set

    /** 有没有这个国家的资料库（档案在不在）。 */
    fun has(c: Context, country: String): Boolean = db(c, country) != null

    @Synchronized
    private fun db(c: Context, country: String): Db? = loaded.getOrPut(country) {
        val (file, label) = FILES[country] ?: return@getOrPut null
        runCatching {
            val text = c.assets.open(file).bufferedReader().use { it.readText() }
            val j = JSONObject(text)
            Db(country, label, j.optString("source"), parse(j)).takeIf { it.entries.isNotEmpty() }
        }.getOrNull()
    }

    /** 这一餐用 [country] 的资料库（没有就 null → AI 估）。 */
    fun use(c: Context, country: String) { active = db(c, country) }

    /** 旧的进入点：用现在所在国家的资料库。 */
    fun init(context: Context) { if (active == null) use(context, FoodRegion.of(context)) }

    /** 单元测试用：直接给 JSON 内容（当成台湾）。 */
    internal fun loadForTest(json: String, country: String = "TW", label: String = "食藥署") {
        val j = JSONObject(json)
        active = Db(country, label, j.optString("source"), parse(j))
    }

    private fun parse(j: JSONObject): Map<String, Entry> {
        val arr = j.getJSONArray("foods")
        return (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i)
            val e = Entry(
                o.getString("n"), o.optString("s").ifBlank { null }, o.optString("cat"),
                o.optDouble("k", 0.0), o.optDouble("p", 0.0), o.optDouble("f", 0.0), o.optDouble("c", 0.0),
                o.optDouble("fib", 0.0),
            )
            e.name to e
        }
    }

    /** 有没有载入（没 [use] 或当地没有资料 → false，饮食照旧用 AI 估）。 */
    val ready get() = !active?.entries.isNullOrEmpty()

    /** 简称（提示词、报告用）；没有资料库时是「食藥署」（旧报告的写法）。 */
    val label get() = active?.label ?: "食藥署"

    /** AI 回的名称 → 资料（去掉「·生」「·熟」后缀、前后空白；一定要完全相同才算）。 */
    fun find(name: String?): Entry? {
        val n = name?.trim()?.removeSuffix("·生")?.removeSuffix("·熟")?.trim() ?: return null
        if (n.isEmpty() || n == "無" || n == "无" || n.equals("none", true)) return null
        return active?.entries?.get(n)
    }

    val promptList: String get() = active?.promptList.orEmpty()
}
