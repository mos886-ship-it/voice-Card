import Foundation

/// 報告語言（照 Android `ReportLang.kt`）：TITLE、SUMMARY、報告用這個語言寫；逐字稿照錄音原本的語言。
///
/// 提示詞只寫一份中文版，換語言時由 `localize` 把章節標題、表頭、說話者代號、「未知」等固定字換掉，
/// 最後再加一條「報告語言」規則（蓋過前面所有「繁體中文」的要求）。
/// App 解析報告時（說話者表格、摘要、飲食表格）三種語言都認得。
///
/// 設定 `report_lang`：auto（跟 App 介面語言）/ zh / en / ko。
enum ReportLang: String, CaseIterable {
    case zh, en, ko

    static let key = "report_lang"
    static let auto = "auto"

    var label: String {
        switch self { case .zh: return "繁體中文"; case .en: return "English"; case .ko: return "한국어" }
    }
    var speaker: String {
        switch self { case .zh: return "說話者"; case .en: return "Speaker "; case .ko: return "화자 " }
    }
    var ruleName: String {
        switch self { case .zh: return "繁體中文（台灣用語）"; case .en: return "English"; case .ko: return "한국어" }
    }
    /// 說話者代號：說話者A / Speaker A / 화자 A
    func code(_ letter: Character) -> String { speaker + String(letter) }

    /// 設定值：auto / zh / en / ko
    static var setting: String {
        get { UserDefaults.standard.string(forKey: key) ?? auto }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }

    /// 現在要用的報告語言：auto → 跟 App 介面語言（韓文 → 한국어、中文 → 繁中、其他 → English）。
    static var current: ReportLang {
        let s = setting
        return s == auto ? fromLanguage(I18n.lang) : (ReportLang(rawValue: s) ?? .zh)
    }

    static func fromLanguage(_ l: String) -> ReportLang {
        switch l { case "zh": return .zh; case "ko": return .ko; default: return .en }
    }

    // MARK: - 提示詞：中文版 → 目標語言

    private static let headings: [(String, String, String)] = [
        ("摘要", "Summary", "요약"),
        ("說話者", "Speakers", "화자"),
        ("重點", "Key points", "핵심 내용"),
        ("結論 / 決定", "Conclusions / Decisions", "결론 / 결정"),
        ("待辦事項", "Action items", "할 일"),
        ("待確認 / 後續問題", "Open questions / Follow-ups", "확인 필요 / 후속 질문"),
        ("討論重點", "Discussion", "논의 내용"),
        ("決議事項", "Decisions", "결정 사항"),
        ("營養小提醒", "Nutrition tips", "영양 팁"),
        ("假設與待確認", "Assumptions to check", "가정 및 확인 필요"),
    ]
    private static let phrases: [(String, String, String)] = [
        ("說話者 | 名字 | 角色 | 主要觀點", "Speaker | Name | Role | Main points", "화자 | 이름 | 역할 | 주요 의견"),
        ("事項 | 負責人 | 期限", "Item | Owner | Due", "항목 | 담당자 | 기한"),
        ("「未知」", "「Unknown」", "「미상」"),
        ("「未指定」", "「Unassigned」", "「미지정」"),
        ("「無」", "「None」", "「없음」"),
        ("「本人」", "「Me」", "「본인」"),
        ("「（聽不清）」", "「(inaudible)」", "「(잘 안 들림)」"),
        ("「（待確認）」", "「(to confirm)」", "「(확인 필요)」"),
    ]

    private func pick(_ t: (String, String, String)) -> String {
        switch self { case .zh: return t.0; case .en: return t.1; case .ko: return t.2 }
    }

    /// 把中文提示詞換成這個語言的報告格式（繁中原樣回傳）。
    func localize(_ prompt: String) -> String {
        guard self != .zh else { return prompt }
        var p = prompt
        for h in Self.headings {
            let re = try! NSRegularExpression(pattern: "(?m)^(\\s*)## " + NSRegularExpression.escapedPattern(for: h.0) + "\\s*$")
            p = re.stringByReplacingMatches(in: p, range: NSRange(p.startIndex..., in: p),
                                            withTemplate: "$1## " + NSRegularExpression.escapedTemplate(for: pick(h)))
        }
        for ph in Self.phrases { p = p.replacingOccurrences(of: ph.0, with: pick(ph)) }
        let re = try! NSRegularExpression(pattern: "說話者([A-Z?])")
        p = re.stringByReplacingMatches(in: p, range: NSRange(p.startIndex..., in: p),
                                        withTemplate: NSRegularExpression.escapedTemplate(for: speaker) + "$1")
        p = p.replacingOccurrences(of: "一律使用繁體中文（台灣用語）。", with: "")
        return p.trimmingCharacters(in: .whitespacesAndNewlines) + "\n\n" + rule
    }

    /// 蓋過前面所有中文要求的報告語言規則（跟 Android 一字不差）。
    var rule: String {
        "【報告語言（最優先，覆蓋前面所有「繁體中文」的要求）】TITLE、SUMMARY、Markdown 報告（章節標題照上面給的寫法）一律用 \(ruleName) 撰寫；"
            + "說話者代號寫成「\(code("A"))、\(code("B"))…」。逐字稿照錄音原本的語言寫，不要翻譯。"
            + "飲食的 NUTRITION 與 ITEM 行維持原本的欄位格式（欄位名稱與餐別用中文），只有標題、摘要與報告文字用 \(ruleName)。"
    }

    // MARK: - 解析：三種語言都認得

    /// 「說話者」章節（舊版「與會者」）的標題。
    static func isSpeakerHeading(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: CharacterSet(charactersIn: "# ")).trimmingCharacters(in: .whitespaces)
        let l = t.lowercased()
        return t.contains("與會者") || t == "說話者" || l == "speakers" || l == "speaker" || t == "화자" || t == "참석자"
    }

    /// 「摘要」章節。
    static func isSummaryHeading(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: CharacterSet(charactersIn: "# ")).trimmingCharacters(in: .whitespaces)
        return t == "摘要" || t.lowercased() == "summary" || t == "요약"
    }

    /// 「無內容」：無 / None / 없음
    static func isNone(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: ".。"))
        return ["無", "无", "（無）", "-", "—", "None", "N/A", "없음", "해당 없음"].contains(t) || t.lowercased() == "none"
    }

    /// 表頭第一欄是說話者欄。
    static func isSpeakerColumn(_ h: String) -> Bool {
        h.contains("說話者") || h.lowercased().contains("speaker") || h.contains("화자")
    }

    /// 表頭的名字欄。
    static func isNameColumn(_ h: String) -> Bool {
        h.contains("名字") || h.contains("姓名") || h.contains("稱呼") || h.lowercased() == "name" || h.contains("이름")
    }

    /// 說話者代號（說話者A / Speaker A / 화자 A）開頭。
    static func isSpeakerCode(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespaces).range(of: #"^(說話者|Speaker\s?|화자\s?)[A-Z?]"#, options: .regularExpression) != nil
    }

    /// 名字未知的寫法。
    static let unknown: Set<String> = ["", "未知", "無", "不明", "未指定", "未提及", "-", "—", "N/A",
                                       "Unknown", "unknown", "None", "미상", "알 수 없음", "없음"]

    static func isUnknownName(_ s: String) -> Bool {
        unknown.contains(s) || s.hasPrefix("未知") || s.lowercased().hasPrefix("unknown") || s.hasPrefix("미상") || s.hasPrefix("알 수 없음")
    }
}

/// 飲食報告裡 App 自己產生的文字（照 Android `FoodWords`，跟著報告語言）。NUTRITION 的餐別一律中文（健康同步、問候語靠它）。
struct FoodWords {
    let content: String, header: [String], total: String
    let tfda: String, label: String, ai: String
    let grams: (Int) -> String
    let totalLine: (AppleAI.Nutrition, Int, Int, Int) -> String
    let summary: (AppleAI.Nutrition) -> String

    private static let meals: [String: (String, String)] = [
        "早餐": ("Breakfast", "아침"), "午餐": ("Lunch", "점심"), "晚餐": ("Dinner", "저녁"),
        "點心": ("Snack", "간식"), "宵夜": ("Late-night snack", "야식"), "飲料": ("Drink", "음료"),
    ]

    static func meal(_ m: String, _ l: ReportLang) -> String {
        switch l {
        case .zh: return m.isEmpty ? "這餐" : m
        case .en: return meals[m]?.0 ?? "This meal"
        case .ko: return meals[m]?.1 ?? "이번 식사"
        }
    }

    static func of(_ l: ReportLang) -> FoodWords {
        switch l {
        case .zh:
            return FoodWords(content: "飲食內容", header: ["食物", "份量", "熱量(kcal)", "蛋白質(g)", "碳水(g)", "脂肪(g)", "來源"], total: "合計",
                             tfda: "食藥署：", label: "營養標示", ai: "AI 估算", grams: { "約 \($0)g" },
                             totalLine: { n, size, db, lab in
                                 let s = [db > 0 ? "\(db) 樣依食藥署「食品營養成分資料集」計算" : nil,
                                          lab > 0 ? "\(lab) 樣依包裝營養標示" : nil].compactMap { $0 }
                                 return "約 \(n.kcal) 大卡；蛋白質 \(n.protein)g、碳水 \(n.carbs)g、脂肪 \(n.fat)g"
                                     + (s.isEmpty ? "（皆為 AI 估算）" : "（\(size) 樣中 \(s.joined(separator: "、"))）")
                             },
                             summary: { n in "\(meal(n.meal, l))約 \(n.kcal) 大卡，蛋白質 \(n.protein)g" })
        case .en:
            return FoodWords(content: "What you ate", header: ["Food", "Portion", "Calories (kcal)", "Protein (g)", "Carbs (g)", "Fat (g)", "Source"],
                             total: "Total", tfda: "Taiwan FDA: ", label: "Nutrition label", ai: "AI estimate", grams: { "about \($0)g" },
                             totalLine: { n, size, db, lab in
                                 let s = [db > 0 ? "\(db) from the Taiwan FDA food composition database" : nil,
                                          lab > 0 ? "\(lab) from the package nutrition label" : nil].compactMap { $0 }
                                 return "About \(n.kcal) kcal; protein \(n.protein)g, carbs \(n.carbs)g, fat \(n.fat)g"
                                     + (s.isEmpty ? " (all AI estimates)" : " (of \(size) items: \(s.joined(separator: ", ")))")
                             },
                             summary: { n in "\(meal(n.meal, l)): about \(n.kcal) kcal, protein \(n.protein)g" })
        case .ko:
            return FoodWords(content: "먹은 음식", header: ["음식", "분량", "열량(kcal)", "단백질(g)", "탄수화물(g)", "지방(g)", "출처"],
                             total: "합계", tfda: "대만 식약청: ", label: "영양성분표", ai: "AI 추정", grams: { "약 \($0)g" },
                             totalLine: { n, size, db, lab in
                                 let s = [db > 0 ? "\(db)개는 대만 식약청 식품영양성분 데이터 기준" : nil,
                                          lab > 0 ? "\(lab)개는 포장 영양성분표 기준" : nil].compactMap { $0 }
                                 return "약 \(n.kcal)kcal; 단백질 \(n.protein)g, 탄수화물 \(n.carbs)g, 지방 \(n.fat)g"
                                     + (s.isEmpty ? " (모두 AI 추정)" : " (\(size)개 중 \(s.joined(separator: ", ")))")
                             },
                             summary: { n in "\(meal(n.meal, l)) 약 \(n.kcal)kcal, 단백질 \(n.protein)g" })
        }
    }
}
