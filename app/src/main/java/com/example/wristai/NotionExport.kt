package com.example.wristai

import android.content.Context
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 存到 Notion：使用者自己建一個 Notion「內部整合」（integration），把金鑰貼到設定，
 * 再把要放報告的頁面「連結」給那個整合（頁面右上 ⋯ → 連結 → 選整合）。之後每份報告存成那頁底下的一個子頁面。
 * 金鑰只存在這支手機，只用來呼叫 api.notion.com。
 */
object NotionExport {
    private const val API = "https://api.notion.com/v1"
    private const val VERSION = "2022-06-28"
    private const val MAX_TEXT = 1900          // Notion 一段文字最多 2000 字
    private const val BATCH = 90               // 一次最多 100 個區塊
    private fun p(c: Context) = c.getSharedPreferences("wristai_settings", Context.MODE_PRIVATE)

    fun token(c: Context) = p(c).getString("notion_token", "").orEmpty()
    fun parent(c: Context) = p(c).getString("notion_parent", "").orEmpty()
    fun setToken(c: Context, v: String) = p(c).edit().putString("notion_token", v.trim()).apply()
    fun setParent(c: Context, v: String) = p(c).edit().putString("notion_parent", v.trim()).apply()
    fun configured(c: Context) = token(c).isNotEmpty() && pageId(parent(c)) != null

    /** 頁面連結或 id → 32 碼 id（加上連字號）。 */
    fun pageId(raw: String): String? {
        val dashed = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![0-9a-fA-F])")
            .findAll(raw).lastOrNull()?.value?.replace("-", "")
        val hex = (dashed ?: Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{32}(?![0-9a-fA-F])").findAll(raw).lastOrNull()?.value)?.lowercase() ?: return null
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private fun rich(text: String): JSONArray {
        val a = JSONArray()
        for ((t, bold) in ReportExport.runs(text)) for (chunk in t.chunked(MAX_TEXT)) {
            a.put(JSONObject().put("type", "text").put("text", JSONObject().put("content", chunk))
                .apply { if (bold) put("annotations", JSONObject().put("bold", true)) })
        }
        return a
    }

    private fun block(type: String, text: String) = JSONObject().put("object", "block").put("type", type)
        .put(type, JSONObject().put("rich_text", rich(text)))

    /** Markdown → Notion 區塊（標題、條列、編號、表格、段落）。 */
    fun blocks(md: String): List<JSONObject> = ReportExport.blocks(md).flatMap { b ->
        when (b) {
            is ReportExport.Block.Heading -> listOf(block(if (b.level <= 1) "heading_1" else if (b.level == 2) "heading_2" else "heading_3", b.text))
            is ReportExport.Block.Bullet -> listOf(block(if (b.number != null) "numbered_list_item" else "bulleted_list_item",
                "  ".repeat(b.depth) + b.text))
            is ReportExport.Block.Para -> b.text.chunked(MAX_TEXT).map { block("paragraph", it) }
            is ReportExport.Block.Table -> {
                val width = b.rows.maxOf { it.size }
                listOf(JSONObject().put("object", "block").put("type", "table").put("table", JSONObject()
                    .put("table_width", width).put("has_column_header", true).put("has_row_header", false)
                    .put("children", JSONArray().apply {
                        for (row in b.rows) put(JSONObject().put("object", "block").put("type", "table_row").put("table_row",
                            JSONObject().put("cells", JSONArray().apply { for (i in 0 until width) put(rich(row.getOrElse(i) { "" })) })))
                    })))
            }
        }
    }

    private fun call(token: String, method: String, path: String, body: JSONObject): JSONObject {
        val conn = (URL(API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 10_000; readTimeout = 30_000; doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Notion-Version", VERSION)
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                throw IllegalStateException(when (code) {
                    401 -> "Notion 金鑰不對".tr
                    404 -> "找不到頁面：請在 Notion 頁面右上 ⋯ → 連結，把它連結給你的整合".tr
                    else -> "Notion 回應錯誤（{0}）：{1}".trf(code, msg)
                })
            }
            return JSONObject(text)
        } finally { conn.disconnect() }
    }

    /** 存成子頁面，回傳網址。阻塞執行（背景執行緒）。 */
    fun save(c: Context, title: String, md: String): String {
        val token = token(c)
        val parent = pageId(parent(c)) ?: throw IllegalStateException("請先在設定填 Notion 頁面連結".tr)
        val all = blocks(md)
        val page = call(token, "POST", "/pages", JSONObject()
            .put("parent", JSONObject().put("page_id", parent))
            .put("properties", JSONObject().put("title", JSONObject().put("title", rich(title.take(200)))))
            .put("children", JSONArray(all.take(BATCH))))
        val id = page.getString("id")
        for (chunk in all.drop(BATCH).chunked(BATCH)) call(token, "PATCH", "/blocks/$id/children", JSONObject().put("children", JSONArray(chunk)))
        return page.optString("url")
    }
}
