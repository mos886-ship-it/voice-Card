package com.example.wristai

import android.content.Context
import org.json.JSONObject

/**
 * 食藥署「食品營養成分資料集」（每 100 克；App 内建 assets/nutrition/tfnd.json，用 tools/gen_tfnd.py 产生）。
 * 政府資料開放授權條款－第1版：可商用，要标示来源（关于与授权页有写）。
 *
 * 用法：AI 把一餐拆成食材＋克数，并从 [promptList] 里挑出完全相同的名称；
 * App 用这里的数字重算热量与营养素（[FoodLog.finish]），对不到的才用 AI 估的。
 */
object FoodDb {
    data class Entry(
        val name: String, val state: String?, val category: String,
        val kcal: Double, val protein: Double, val fat: Double, val carbs: Double,
    )

    @Volatile private var cache: Map<String, Entry>? = null

    fun init(context: Context) {
        if (cache != null) return
        synchronized(this) {
            if (cache != null) return
            cache = runCatching {
                parse(context.assets.open("nutrition/tfnd.json").bufferedReader().use { it.readText() })
            }.getOrDefault(emptyMap())
        }
    }

    /** 单元测试用：直接给 JSON 内容。 */
    internal fun loadForTest(json: String) { cache = parse(json) }

    private fun parse(json: String): Map<String, Entry> {
        val arr = JSONObject(json).getJSONArray("foods")
        return (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i)
            val e = Entry(
                o.getString("n"), o.optString("s").ifBlank { null }, o.optString("cat"),
                o.optDouble("k", 0.0), o.optDouble("p", 0.0), o.optDouble("f", 0.0), o.optDouble("c", 0.0),
            )
            e.name to e
        }
    }

    /** 有没有载入（没 init 或档案坏了 → 空的，饮食照旧用 AI 估）。 */
    val ready get() = !cache.isNullOrEmpty()

    /** AI 回的名称 → 资料（去掉「·生」「·熟」后缀、前后空白；一定要完全相同才算）。 */
    fun find(name: String?): Entry? {
        val n = name?.trim()?.removeSuffix("·生")?.removeSuffix("·熟")?.trim() ?: return null
        if (n.isEmpty() || n == "無" || n == "无") return null
        return cache?.get(n)
    }

    /** 给 AI 的清单：依类别分行，生的 / 熟的加「·生」「·熟」提醒克数要对应。 */
    val promptList: String by lazy {
        val all = cache ?: return@lazy ""
        all.values.groupBy { it.category }.entries.joinToString("\n") { (cat, list) ->
            "【$cat】" + list.joinToString("、") { e -> e.name + (e.state?.let { "·$it" } ?: "") }
        }
    }
}
