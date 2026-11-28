import Foundation

/// 留存功能第一、三批的小幫手（照 Android `Vocab.kt`、`FoodExtras.kt`、`TranslateFixes.kt`、`WatchBattery.kt`）。

// MARK: - 個人詞庫

/// 使用者自己輸入的專有名詞（公司、產品、縮寫、人名）＋常替說話者取的名字。
/// 加進報告提示詞（`ReportLang.localize` → `applyTo`）；大D的主機收 X-Vocab（base64）。
enum Vocab {
    static let key = "custom_vocab"
    private static let maxTerms = 200

    static var raw: String {
        get { UserDefaults.standard.string(forKey: key) ?? "" }
        set { UserDefaults.standard.set(String(newValue.prefix(8000)), forKey: key) }
    }

    static var terms: [String] {
        var seen = Set<String>()
        return raw.components(separatedBy: CharacterSet(charactersIn: "\n,，、"))
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
            .prefix(maxTerms).map { $0 }
    }

    /// 常替說話者取的名字（次數多的在前）。
    @MainActor static func frequentNames(limit: Int = 12) -> [String] {
        var count: [String: Int] = [:]
        for m in MeetingStore.shared.meetings {
            for n in (m.speakerNames ?? [:]).values {
                let t = n.trimmingCharacters(in: .whitespaces)
                if !t.isEmpty, !ReportLang.isUnknownName(t), t != "本人" { count[t, default: 0] += 1 }
            }
        }
        return count.sorted { $0.value > $1.value || ($0.value == $1.value && $0.key < $1.key) }.prefix(limit).map(\.key)
    }

    /// 這次處理用的詞（處理開始時 prepare）。
    nonisolated(unsafe) static var active: [String] = []

    @MainActor static func prepare() {
        var seen = Set<String>()
        active = (terms + frequentNames(limit: 30)).filter { seen.insert($0).inserted }.prefix(maxTerms).map { $0 }
    }

    static func rule(_ words: [String] = active) -> String {
        words.isEmpty ? "" : "【使用者的專有名詞與常見人名】辨識和整理時，聽到同音或相近的詞，優先用這些寫法：" + words.joined(separator: "、") + "。沒出現的不要硬套。"
    }

    /// 報告提示詞（有「待辦事項」章節）才加；已經加過不再加。
    static func applyTo(_ prompt: String, words: [String] = active) -> String {
        let r = rule(words)
        if r.isEmpty || !prompt.contains("## 待辦事項") || prompt.contains("【使用者的專有名詞") { return prompt }
        return prompt.trimmingCharacters(in: .whitespacesAndNewlines) + "\n\n" + r
    }

    /// 送給大D的主機（標頭只能 ASCII）。
    static func header(_ words: [String] = active) -> String? {
        guard !words.isEmpty else { return nil }
        let b = Data(words.joined(separator: "\n").utf8).base64EncodedString()
        return b.count < 6000 ? b : nil
    }
}

// MARK: - 翻譯更正

/// 「翻錯的 → 正確的」（依翻成的語言）：之後的即時翻譯、遠距對話、情境片語都先套用。
enum TranslateFixes {
    struct Fix: Codable, Equatable, Hashable { var lang: String, wrong: String, right: String }
    static let key = "translate_fixes"
    private static let maxFixes = 300

    static var all: [Fix] {
        guard let d = UserDefaults.standard.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([Fix].self, from: d)) ?? []
    }

    private static func store(_ list: [Fix]) {
        let l = Array(list.suffix(maxFixes))
        UserDefaults.standard.set(try? JSONEncoder().encode(l), forKey: key)
    }

    static func add(lang: String, wrong: String, right: String) {
        let w = wrong.trimmingCharacters(in: .whitespacesAndNewlines), r = right.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !w.isEmpty, w != r else { return }
        store(all.filter { !($0.lang == lang && $0.wrong == w) } + [Fix(lang: lang, wrong: w, right: r)])
    }

    static func remove(_ f: Fix) { store(all.filter { $0 != f }) }

    /// 同語言比較：zh-Hant / zh-TW / zh 都算中文。
    private static func base(_ l: String) -> String { String(l.split(separator: "-").first ?? Substring(l)).lowercased() }

    /// 套用這個語言的更正（長的先換）。
    static func apply(_ text: String, lang: String, fixes: [Fix] = all) -> String {
        var t = text
        for f in fixes.filter({ base($0.lang) == base(lang) }).sorted(by: { $0.wrong.count > $1.wrong.count }) {
            t = t.replacingOccurrences(of: f.wrong, with: f.right, options: .caseInsensitive)
        }
        return t
    }
}

// MARK: - 飲食：健康目標

/// 健康目標（均衡／減脂控卡／增肌高蛋白／控糖穩血糖）與每日目標（飲食 → 設定）。
enum FoodGoals {
    static let kcalKey = "food_goal_kcal", proteinKey = "food_goal_protein", fiberKey = "food_goal_fiber"
    static let modeKey = "food_goal_mode", carbCapKey = "carb_cap"
    static let balanced = "balanced", lose = "lose", muscle = "muscle", sugar = "sugar"
    static let allModes = [balanced, lose, muscle, sugar]

    static var kcal: Int { (UserDefaults.standard.object(forKey: kcalKey) as? Int) ?? 2000 }
    static var protein: Int { (UserDefaults.standard.object(forKey: proteinKey) as? Int) ?? 60 }
    static var fiber: Int { (UserDefaults.standard.object(forKey: fiberKey) as? Int) ?? 25 }
    static var mode: String { UserDefaults.standard.string(forKey: modeKey) ?? balanced }
    /// 控糖：每日碳水上限（克，0 = 不限）。
    static var carbCap: Int { UserDefaults.standard.integer(forKey: carbCapKey) }

    static func label(_ m: String) -> String {
        switch m { case lose: "減脂控卡".tr; case muscle: "增肌高蛋白".tr; case sugar: "控糖穩血糖".tr; default: "均衡".tr }
    }

    /// 每個目標的預設：熱量、蛋白質、纖維、碳水上限（0 = 不限）。
    static func preset(_ m: String) -> [Int] {
        switch m { case lose: [1600, 90, 25, 0]; case muscle: [2400, 130, 25, 0]; case sugar: [1800, 70, 30, 180]; default: [2000, 60, 25, 0] }
    }

    /// 選目標：存起來並套用預設的每日目標（之後還可以自己改數字）。
    static func setMode(_ m: String) {
        let v = preset(m), d = UserDefaults.standard
        d.set(m, forKey: modeKey); d.set(v[3], forKey: carbCapKey)
        d.set(v[0], forKey: kcalKey); d.set(v[1], forKey: proteinKey); d.set(v[2], forKey: fiberKey)
    }

    /// 提示詞：營養小提醒照目標講重點（跟主機 FOOD_ADVICE 一樣）。
    static func adviceRule(_ m: String = mode) -> String {
        switch m {
        case lose: "使用者的目標是減脂控卡：營養小提醒以熱量、份量、油脂與含糖飲料為重點，提出下一餐可以怎麼少一點熱量但吃得飽。"
        case muscle: "使用者的目標是增肌高蛋白：營養小提醒以這餐蛋白質夠不夠為重點，建議下一餐補什麼蛋白質來源（豆漿、蛋、雞胸、魚、豆腐等）。"
        case sugar: "使用者的目標是控糖穩血糖（適合銀髮族與三高）：營養小提醒特別標出精緻澱粉、含糖飲料與甜點的份量，建議穩定血糖的吃法（先吃菜和蛋白質、換成全穀、無糖）。"
        default: "營養小提醒給均衡飲食的簡短建議。"
        }
    }

}

// MARK: - 飲食：常吃的一鍵再記

enum FoodRepeat {
    /// 現在的餐別。
    static func mealNow(_ d: Date = Date()) -> String {
        let c = Calendar.current.dateComponents([.hour, .minute], from: d)
        let m = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        switch m {
        case ..<240: return "宵夜"
        case ..<630: return "早餐"
        case ..<840: return "午餐"
        case ..<1020: return "點心"
        case ..<1260: return "晚餐"
        default: return "宵夜"
        }
    }

    /// 標題去掉餐別：「午餐：牛肉麵＋燙青菜」→「牛肉麵＋燙青菜」。
    static func dish(_ title: String?) -> String {
        var t = title ?? ""
        if let r = t.range(of: "：") { t = String(t[r.upperBound...]) }
        if let r = t.range(of: ":") { t = String(t[r.upperBound...]) }
        return t.trimmingCharacters(in: .whitespaces)
    }

    /// 常吃的：30 天內同一道記過 2 次以上，依次數排，每道取最近一筆。
    static func favorites(_ food: [Meeting], now: Date = Date()) -> [(Meeting, Int)] {
        let recent = food.filter { m in
            guard m.isFood, m.status == .done, m.nutrition != nil else { return false }
            let d = dish(m.title)
            return now.timeIntervalSince(m.createdAt) < 30 * 86_400 && !d.isEmpty && !m.title.contains("非飲食")
        }
        var out: [(Meeting, Int)] = []
        for (_, list) in Dictionary(grouping: recent, by: { dish($0.title) }) where list.count >= 2 {
            if let latest = list.max(by: { $0.createdAt < $1.createdAt }) { out.append((latest, list.count)) }
        }
        out.sort { a, b in a.1 != b.1 ? a.1 > b.1 : a.0.createdAt > b.0.createdAt }
        return Array(out.prefix(6))
    }

    /// 再記一次：新的一筆（現在、現在的餐別），報告照抄；回傳新編號。
    @MainActor @discardableResult
    static func repeatMeal(_ src: Meeting) -> String {
        let store = MeetingStore.shared
        let meal = mealNow()
        let id = RecordingID.make(prefix: Meeting.foodPrefix)
        var m = Meeting.new(id: id, source: "repeat")
        m.title = meal + "：" + dish(src.title)
        m.summary = src.summary.map { s in src.nutrition.map { s.replacingOccurrences(of: $0.meal, with: meal) } ?? s }
        m.nutrition = src.nutrition.map { var n = $0; n.meal = meal; return n }
        m.aiModel = src.aiModel
        m.lang = src.lang
        m.status = .done
        try? FileManager.default.createDirectory(at: MeetingStore.dir(id), withIntermediateDirectories: true)
        if let md = store.report(src.id) {
            let fixed = src.nutrition.map { md.replacingOccurrences(of: $0.meal, with: meal) } ?? md
            try? fixed.write(to: MeetingStore.reportURL(id), atomically: true, encoding: .utf8)
        }
        store.save(m)
        return id
    }
}

// MARK: - 手錶電量

/// 手錶 App 打開時送來（"百分比,1=充電中"）；「今天」的手錶標籤顯示（6 小時內的才顯示）。
enum WatchBattery {
    struct Level: Equatable { let percent: Int, charging: Bool, at: Date }
    private static let maxAge: TimeInterval = 6 * 3600

    static func parse(_ raw: String, at: Date = Date()) -> Level? {
        let parts = raw.trimmingCharacters(in: .whitespaces).split(separator: ",")
        guard let p = parts.first.flatMap({ Int($0.trimmingCharacters(in: .whitespaces)) }), (0...100).contains(p) else { return nil }
        return Level(percent: p, charging: parts.count > 1 && parts[1].trimmingCharacters(in: .whitespaces) == "1", at: at)
    }

    static func save(_ raw: String) {
        guard let l = parse(raw) else { return }
        let d = UserDefaults.standard
        d.set(l.percent, forKey: "watch_batt"); d.set(l.charging, forKey: "watch_batt_chg"); d.set(l.at.timeIntervalSince1970, forKey: "watch_batt_at")
    }

    static func recent(now: Date = Date()) -> Level? {
        let d = UserDefaults.standard
        let at = d.double(forKey: "watch_batt_at")
        guard at > 0, now.timeIntervalSince1970 - at <= maxAge else { return nil }
        return Level(percent: d.integer(forKey: "watch_batt"), charging: d.bool(forKey: "watch_batt_chg"), at: Date(timeIntervalSince1970: at))
    }
}
