package com.example.wristai

import android.content.Context

/**
 * 报告语言（繁体中文 / English / 한국어）：TITLE、SUMMARY、报告用这个语言写；逐字稿照录音原本的语言。
 *
 * 提示词只写一份中文版，换语言时由 [localize] 把章节标题、表头、说话者代号、「未知」等固定字换掉，
 * 最后再加一条「报告语言」规则（盖过前面所有「繁体中文」的要求）。
 * App 解析报告时（说话者表格、摘要、长录音名单）三种语言都认得：[speakerHeadings]、[isSpeakerCode] 等。
 *
 * 设定 `report_lang`：auto（跟 App 介面语言）/ zh / en / ko。
 */
enum class ReportLang(val code: String, val label: String, val speaker: String, val ruleName: String) {
    ZH("zh", "繁體中文", "說話者", "繁體中文（台灣用語）"),
    EN("en", "English", "Speaker ", "English"),
    KO("ko", "한국어", "화자 ", "한국어");

    /** 说话者代号：說話者A / Speaker A / 화자 A */
    fun code(letter: Char): String = speaker + letter

    companion object {
        private const val PREFS = "wristai_settings"
        private const val KEY = "report_lang"
        const val AUTO = "auto"

        fun of(code: String?): ReportLang = entries.firstOrNull { it.code == code } ?: ZH

        /** 设定值：auto / zh / en / ko */
        fun setting(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO
        fun set(c: Context, v: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v).apply()

        /** 现在要用的报告语言：设定 auto → 跟 App 介面语言（韩文 → 한국어、中文 → 繁中、其他 → English）。 */
        fun current(c: Context): ReportLang = when (val s = setting(c)) {
            AUTO -> fromLocale(uiLanguage(c))
            else -> of(s)
        }

        fun fromLocale(language: String): ReportLang = when (language) {
            "zh" -> ZH
            "ko" -> KO
            else -> EN
        }

        /** App 介面的语言（个别 App 语言优先，没有就是系统语言）。 */
        fun uiLanguage(c: Context): String = c.resources.configuration.locales[0].language

        /**
         * 这次处理用的报告语言（[ProcessMeetingWorker] 开始时设好；GeminiReportGenerator / 组合式读这个，
         * 不用到处传 Context）。
         */
        @Volatile var active: ReportLang = ZH

        // ---------- 提示词：中文版 → 目标语言 ----------

        private val HEADINGS = listOf(
            // 中文, English, 한국어（章节标题）
            Triple("摘要", "Summary", "요약"),
            Triple("說話者", "Speakers", "화자"),
            Triple("重點", "Key points", "핵심 내용"),
            Triple("結論 / 決定", "Conclusions / Decisions", "결론 / 결정"),
            Triple("待辦事項", "Action items", "할 일"),
            Triple("待確認 / 後續問題", "Open questions / Follow-ups", "확인 필요 / 후속 질문"),
            Triple("討論重點", "Discussion", "논의 내용"),
            Triple("決議事項", "Decisions", "결정 사항"),
            Triple("營養小提醒", "Nutrition tips", "영양 팁"),
            Triple("假設與待確認", "Assumptions to check", "가정 및 확인 필요"),
            Triple("專業重點", "Expert notes", "전문가 관점"),
            Triple("心智圖大綱", "Mind map outline", "마인드맵 개요"),
            Triple("名詞解釋", "Glossary", "용어 설명"),
            Triple("複習題", "Review questions", "복습 문제"),
        )
        private val PHRASES = listOf(
            Triple("說話者 | 名字 | 角色 | 主要觀點", "Speaker | Name | Role | Main points", "화자 | 이름 | 역할 | 주요 의견"),
            Triple("事項 | 負責人 | 期限", "Item | Owner | Due", "항목 | 담당자 | 기한"),
            Triple("「未知」", "「Unknown」", "「미상」"),
            Triple("「未指定」", "「Unassigned」", "「미지정」"),
            Triple("「無」", "「None」", "「없음」"),
            Triple("「本人」", "「Me」", "「본인」"),
            Triple("「（聽不清）」", "「(inaudible)」", "「(잘 안 들림)」"),
            Triple("「（待確認）」", "「(to confirm)」", "「(확인 필요)」"),
            // 领域专家模式（[ReportDomain]）
            Triple("「## 專業重點」", "「## Expert notes」", "「## 전문가 관점」"),
            Triple("「領域：○○」", "「Domain: …」", "「분야: …」"),
            Triple("「（建議諮詢專業人員）」", "「(consult a professional)」", "「(전문가 상담 권장)」"),
            // 課堂講座
            Triple("「## 心智圖大綱」", "「## Mind map outline」", "「## 마인드맵 개요」"),
            Triple("「## 名詞解釋」", "「## Glossary」", "「## 용어 설명」"),
            Triple("「## 複習題」", "「## Review questions」", "「## 복습 문제」"),
            Triple("「名詞：一句白話解釋」", "「Term: one-line plain explanation」", "「용어: 쉬운 한 줄 설명」"),
            Triple("「答：」", "「A:」", "「답:」"),
        )

        private fun pick(t: Triple<String, String, String>, l: ReportLang) = when (l) { ZH -> t.first; EN -> t.second; KO -> t.third }

        /** 把中文提示词换成 [l] 的报告格式（[ZH] 原样回传）。 */
        fun localize(prompt0: String, l: ReportLang = active): String {
            // 领域专家模式的规则先加进去（报告类提示词才加），再一起换成报告语言
            val prompt = Vocab.applyTo(ReportDomain.applyTo(prompt0))
            if (l == ZH || prompt.contains("【報告語言")) return prompt   // 已经换过（例如组合式先换、Gemini 又换一次）
            var p = prompt
            for (h in HEADINGS) p = p.replace(Regex("(?m)^(\\s*)## " + Regex.escape(h.first) + "\\s*$"), "$1## " + pick(h, l))
            for (ph in PHRASES) p = p.replace(ph.first, pick(ph, l))
            // 说话者代号：說話者A / 說話者X / 說話者? → Speaker A …
            p = p.replace(Regex("說話者([A-Z?])"), l.speaker.replace("$", "\\$") + "$1")
            p = p.replace("一律使用繁體中文（台灣用語）。", "")
            return p.trimEnd() + "\n\n" + rule(l)
        }

        /** 盖过前面所有中文要求的报告语言规则。 */
        fun rule(l: ReportLang): String =
            "【報告語言（最優先，覆蓋前面所有「繁體中文」的要求）】TITLE、SUMMARY、Markdown 報告（章節標題照上面給的寫法）一律用 ${l.ruleName} 撰寫；" +
                "說話者代號寫成「${l.code('A')}、${l.code('B')}…」。逐字稿照錄音原本的語言寫，不要翻譯。" +
                "飲食的 NUTRITION 與 ITEM 行維持原本的欄位格式（欄位名稱與餐別用中文），只有標題、摘要與報告文字用 ${l.ruleName}。"

        // ---------- 解析：三种语言都认得 ----------

        /** 「說話者」章节（旧版「與會者」）的标题。 */
        fun isSpeakerHeading(text: String): Boolean {
            val t = text.trimStart('#').trim()
            return t.contains("與會者") || t == "說話者" || t.equals("Speakers", true) || t.equals("Speaker", true) || t == "화자" || t == "참석자"
        }

        /** 「摘要」章节。 */
        fun isSummaryHeading(text: String): Boolean = text.trim().let { it == "摘要" || it.equals("Summary", true) || it == "요약" }

        /** 「无内容」：無 / None / 없음 */
        fun isNone(text: String): Boolean = text.trim().trimEnd('.', '。').let { it in setOf("無", "无", "None", "N/A", "없음", "해당 없음") || it.equals("none", true) }

        /** 表头第一栏是说话者栏。 */
        fun isSpeakerColumn(header: String): Boolean = header.contains("說話者") || header.contains("Speaker", true) || header.contains("화자")

        /** 表头的名字栏。 */
        fun isNameColumn(header: String): Boolean =
            header.contains("名字") || header.contains("姓名") || header.contains("稱呼") || header.equals("Name", true) || header.contains("이름")

        /** 说话者代号（說話者A / Speaker A / 화자 A）开头。 */
        fun isSpeakerCode(text: String): Boolean =
            Regex("^(說話者|Speaker\\s?|화자\\s?)[A-Z?]").containsMatchIn(text.trim())

        /** 名字未知的写法。 */
        val UNKNOWN = setOf("", "未知", "無", "不明", "未指定", "未提及", "-", "—", "N/A", "Unknown", "unknown", "None", "미상", "알 수 없음", "없음")
    }
}
