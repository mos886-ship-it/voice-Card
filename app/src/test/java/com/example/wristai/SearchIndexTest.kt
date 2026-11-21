package com.example.wristai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 报告搜寻：多关键字、地点、日期、前后文。 */
class SearchIndexTest {
    private val doc = SearchIndex.Doc(
        "s",
        listOf(
            "標題" to "烏山頭水庫續約爭議",
            "日期" to "2026/11/19 11/19 11月19日",
            "地點" to "Phnom Penh, Khan Sen Sok",
            "說話者" to "王經理",
            "報告" to "## 摘要\n主播報導候選人當選後要把違建的鐵皮屋通通拆掉，引發台南鄉親討論。",
            "逐字稿" to "[00:01] 王經理：大家好",
        ),
    )

    @Test fun allTermsAnyOrder() {
        assertTrue(SearchIndex.matches(doc, SearchIndex.terms("台南  鐵皮屋")))
        assertTrue(SearchIndex.matches(doc, SearchIndex.terms("鐵皮屋 台南")))
        assertFalse(SearchIndex.matches(doc, SearchIndex.terms("台南 高雄")))
    }

    @Test fun placeDateSpeakerCaseInsensitive() {
        assertTrue(SearchIndex.matches(doc, SearchIndex.terms("phnom")))
        assertTrue(SearchIndex.matches(doc, SearchIndex.terms("11/19")))
        assertTrue(SearchIndex.matches(doc, SearchIndex.terms("王經理")))
    }

    @Test fun snippetFromReport() {
        val s = SearchIndex.snippet(doc, SearchIndex.terms("鐵皮屋"))!!
        assertEquals("報告", s.where)
        assertTrue(s.text.contains("鐵皮屋"))
        assertFalse(s.text.contains("#"))
    }

    @Test fun snippetTitleOnly() {
        val s = SearchIndex.snippet(doc, SearchIndex.terms("水庫"))!!
        assertEquals("標題", s.where)
    }
}
