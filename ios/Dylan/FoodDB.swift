import Foundation

/// 食藥署「食品營養成分資料集」（每 100 克；App 內建 Nutrition/tfnd.json，跟 Android assets/nutrition/tfnd.json 同一份，
/// 用 tools/gen_tfnd.py 產生）。政府資料開放授權條款－第1版：可商用，要標示來源（設定 →「內建資料」有寫）。
///
/// 用法（照 Android `FoodDb.kt`）：AI 把一餐拆成食材＋克數，並從 `promptList` 挑出完全相同的名稱；
/// App 用這裡的數字重算熱量與營養素（`FoodLog.finish`），對不到的才用 AI 估的。
enum FoodDB {
    struct Entry: Equatable {
        let name: String
        let state: String?
        let category: String
        let kcal: Double, protein: Double, fat: Double, carbs: Double
    }

    private struct Loaded { let map: [String: Entry]; let order: [Entry] }

    nonisolated(unsafe) private static var override: Loaded?
    private static let bundled: Loaded = {
        guard let url = Bundle.main.url(forResource: "tfnd", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return Loaded(map: [:], order: []) }
        return parse(data)
    }()
    private static var db: Loaded { override ?? bundled }

    /// 測試用：直接給 JSON 內容。
    static func loadForTest(_ data: Data) { override = parse(data) }

    private static func parse(_ data: Data) -> Loaded {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let foods = root["foods"] as? [[String: Any]] else { return Loaded(map: [:], order: []) }
        var map: [String: Entry] = [:]
        var order: [Entry] = []
        for o in foods {
            guard let n = o["n"] as? String else { continue }
            let s = (o["s"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            func d(_ k: String) -> Double { (o[k] as? NSNumber)?.doubleValue ?? 0 }
            let e = Entry(name: n, state: s, category: o["cat"] as? String ?? "",
                          kcal: d("k"), protein: d("p"), fat: d("f"), carbs: d("c"))
            if map[n] == nil { order.append(e) } else if let i = order.firstIndex(where: { $0.name == n }) { order[i] = e }
            map[n] = e
        }
        return Loaded(map: map, order: order)
    }

    /// 有沒有載入（檔案壞了 → 空的，飲食照舊用 AI 估）。
    static var ready: Bool { !db.map.isEmpty }

    /// AI 回的名稱 → 資料（去掉「·生」「·熟」後綴、前後空白；一定要完全相同才算）。
    static func find(_ name: String?) -> Entry? {
        guard var n = name?.trimmingCharacters(in: .whitespaces) else { return nil }
        for suf in ["·生", "·熟"] where n.hasSuffix(suf) { n = String(n.dropLast(suf.count)) }
        n = n.trimmingCharacters(in: .whitespaces)
        if n.isEmpty || n == "無" || n == "无" { return nil }
        return db.map[n]
    }

    /// 給 AI 的清單：依類別分行（照資料出現的順序），生的 / 熟的加「·生」「·熟」提醒克數要對應。
    static var promptList: String {
        var cats: [String] = []
        var byCat: [String: [String]] = [:]
        for e in db.order {
            if byCat[e.category] == nil { cats.append(e.category) }
            byCat[e.category, default: []].append(e.name + (e.state.map { "·" + $0 } ?? ""))
        }
        return cats.map { "【\($0)】" + byCat[$0]!.joined(separator: "、") }.joined(separator: "\n")
    }
}

/// 飲食記錄的 ITEM 行重算（照 Android `FoodLog.parseItems` / `FoodLog.finish`）。
enum FoodLog {
    static let itemPrefix = "ITEM:"

    struct Item {
        let name: String, portion: String, grams: Int
        let db: FoodDB.Entry?
        let kcal: Int, protein: Int, carbs: Int, fat: Int
        /// 照片裡的包裝營養標示算的（數字是 AI 照標示換算）
        var label = false
    }

    /// 解析 ITEM 行；對照的名稱在資料庫裡，而且跟 AI 自己估的差不多（0.4～2.5 倍），才用資料庫的數字。
    static func parseItems(_ text: String) -> [Item] {
        text.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { $0.uppercased().hasPrefix(itemPrefix) }
            .compactMap { line -> Item? in
                var kv: [String: String] = [:]
                let body = line[line.index(after: line.firstIndex(of: ":")!)...]
                for part in body.split(whereSeparator: { $0 == ";" || $0 == "；" }) {
                    let p = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                    let k = p[0].trimmingCharacters(in: .whitespaces)
                    if k.isEmpty { continue }
                    kv[k.lowercased()] = p.count > 1 ? p[1].trimmingCharacters(in: .whitespaces) : ""
                }
                func num(_ k: String) -> Int {
                    guard let v = kv[k], let r = v.range(of: #"\d+"#, options: .regularExpression) else { return 0 }
                    return Int(v[r]) ?? 0
                }
                guard let name = kv["食物"], !name.isEmpty else { return nil }
                let portion = kv["份量"] ?? ""
                let grams = num("克")
                let aiKcal = num("kcal")
                let g = Double(grams) / 100
                let ref = (kv["對照"] ?? kv["对照"])?.trimmingCharacters(in: .whitespaces)
                if ref == "營養標示" || ref == "营养标示" {
                    return fitted(Item(name: name, portion: portion, grams: grams, db: nil, kcal: aiKcal,
                                       protein: num("protein"), carbs: num("carbs"), fat: num("fat"), label: true))
                }
                if grams > 0, let db = FoodDB.find(ref) {
                    let dbKcal = Int((db.kcal * g).rounded())
                    let ratio = aiKcal == 0 ? 1 : Double(dbKcal) / Double(aiKcal)
                    if aiKcal == 0 || (0.4...2.5).contains(ratio) {
                        return Item(name: name, portion: portion, grams: grams, db: db, kcal: dbKcal,
                                    protein: Int((db.protein * g).rounded()), carbs: Int((db.carbs * g).rounded()),
                                    fat: Int((db.fat * g).rounded()))
                    }
                }
                return fitted(Item(name: name, portion: portion, grams: grams, db: nil, kcal: aiKcal,
                                   protein: num("protein"), carbs: num("carbs"), fat: num("fat")))
            }
    }

    private static func fitted(_ i: Item) -> Item {
        let f = fit(grams: i.grams, kcal: i.kcal, p: i.protein, c: i.carbs, f: i.fat)
        var o = Item(name: i.name, portion: i.portion, grams: i.grams, db: i.db, kcal: f.kcal, protein: f.p, carbs: f.c, fat: f.f)
        o.label = i.label
        return o
    }

    /// 蛋白質＋碳水＋脂肪的克數不能超過食物重量（照 Android `FoodLog.fit`；AI 常估太高，例如 31 克算出 40 克的營養素）：
    /// 超過就依比例縮到不超過重量（無條件捨去）；熱量照 4／4／9 重算（AI 的熱量是 0 或比 4／4／9 高 15% 以上時）。
    static func fit(grams: Int, kcal: Int, p: Int, c: Int, f: Int) -> (kcal: Int, p: Int, c: Int, f: Int) {
        let sum = p + c + f
        guard grams > 0, sum > grams else { return (kcal, p, c, f) }
        let k = Double(grams) / Double(sum)
        let p2 = Int(Double(p) * k), c2 = Int(Double(c) * k), f2 = Int(Double(f) * k)
        let est = 4 * p2 + 4 * c2 + 9 * f2
        return (kcal == 0 || Double(kcal) > Double(est) * 1.15 ? est : kcal, p2, c2, f2)
    }

    /// 表格「份量」欄裡的克數（「31g」「約 200 克」）；沒有 → 0。
    static func gramsIn(_ s: String) -> Int {
        guard let m = s.range(of: #"(\d+)\s*(公克|克|g)(?![a-zA-Z])"#, options: [.regularExpression, .caseInsensitive]),
              let d = s[m].range(of: #"\d+"#, options: .regularExpression) else { return 0 }
        return Int(s[m][d]) ?? 0
    }

    private static func cells(_ r: String) -> [String] {
        r.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "|"))
            .components(separatedBy: "|").map { $0.trimmingCharacters(in: .whitespaces) }
    }
    private static func firstNum(_ s: String?) -> Int {
        guard let s, let r = s.range(of: #"\d+"#, options: .regularExpression) else { return 0 }
        return Int(s[r]) ?? 0
    }
    private static func isTotalRow(_ first: String?) -> Bool {
        guard let t = first else { return false }
        return t.contains("合計") || t.contains("總計") || t.lowercased() == "total" || t.contains("합계")
    }

    /// 修正表格（照 Android `fixTable`）：每一列份量有克數、營養素加起來又超過克數 → 依比例縮小；拿掉 AI 的合計列、換掉「合計」那一行。
    /// 回傳（修正後的 Markdown, 修正後的合計；沒有改 → nil）。
    static func fixTable(_ markdown: String, meal: String) -> (String, AppleAI.Nutrition?) {
        var lines = markdown.components(separatedBy: "\n")
        guard let hi = lines.firstIndex(where: { l in
            l.trimmingCharacters(in: .whitespaces).hasPrefix("|") && (l.contains("熱量") || l.lowercased().contains("kcal"))
        }) else { return (markdown, nil) }
        let h = cells(lines[hi])
        func col(_ keys: String...) -> Int { h.firstIndex { c in keys.contains { c.lowercased().contains($0.lowercased()) } } ?? -1 }
        let iPortion = col("份量", "Portion", "분량"), iK = col("熱量", "kcal", "Calories", "열량")
        let iP = col("蛋白", "Protein", "단백질"), iC = col("碳水", "Carbs", "탄수"), iF = col("脂肪", "Fat", "지방")
        guard iP >= 0, iC >= 0, iF >= 0 else { return (markdown, nil) }
        var changed = false
        var kcal = 0, p = 0, c = 0, f = 0
        var i = hi + 1
        while i < lines.count, lines[i].trimmingCharacters(in: .whitespaces).hasPrefix("|") {
            var r = cells(lines[i])
            let sep = r.allSatisfy { x in x.isEmpty || x.allSatisfy { $0 == "-" || $0 == ":" } }
            let total = isTotalRow(r.first)
            if !sep && !total {
                func n(_ j: Int) -> Int { j < 0 || j >= r.count ? 0 : firstNum(r[j]) }
                let g0 = iPortion >= 0 && iPortion < r.count ? gramsIn(r[iPortion]) : 0
                let g = g0 > 0 ? g0 : gramsIn(r.first ?? "")
                let fx = fit(grams: g, kcal: n(iK), p: n(iP), c: n(iC), f: n(iF))
                if fx.p != n(iP) || fx.c != n(iC) || fx.f != n(iF) || fx.kcal != n(iK) {
                    if iK >= 0 && iK < r.count { r[iK] = "\(fx.kcal)" }
                    if iP < r.count { r[iP] = "\(fx.p)" }
                    if iC < r.count { r[iC] = "\(fx.c)" }
                    if iF < r.count { r[iF] = "\(fx.f)" }
                    lines[i] = "| " + r.joined(separator: " | ") + " |"
                    changed = true
                }
                kcal += fx.kcal; p += fx.p; c += fx.c; f += fx.f
            }
            if total { lines[i] = "" }   // AI 寫的合計列：數字改過就不對了，拿掉
            i += 1
        }
        guard changed else { return (markdown, nil) }
        let n = AppleAI.Nutrition(meal: meal, kcal: kcal, protein: p, carbs: c, fat: f)
        // 「合計」一節的那一行換成新的數字
        if let ti = lines.firstIndex(where: { l in
            let t = l.trimmingCharacters(in: .whitespaces)
            guard t.hasPrefix("#") else { return false }
            let x = t.trimmingCharacters(in: CharacterSet(charactersIn: "# "))
            return x == "合計" || x.lowercased() == "total" || x == "합계"
        }), let li = lines.indices.dropFirst(ti + 1).first(where: { !lines[$0].trimmingCharacters(in: .whitespaces).isEmpty }),
           !lines[li].trimmingCharacters(in: .whitespaces).hasPrefix("#") {
            lines[li] = FoodWords.of(ReportLang.current).totalLine(n, 0, 0, 0)
        }
        let end = i
        let out = lines.enumerated().filter { idx, l in !(l.isEmpty && idx > hi && idx < end) }.map(\.element)
        return (out.joined(separator: "\n"), n)
    }

    /// AI 自己寫的飲食表格：各欄加總，用來補 NUTRITION 行缺的數字（照 Android `fillFromTable`）。
    static func fillFromTable(_ n: AppleAI.Nutrition, _ markdown: String) -> AppleAI.Nutrition {
        if n.kcal > 0 && n.protein > 0 && n.carbs > 0 && n.fat > 0 { return n }
        let rows = markdown.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { $0.hasPrefix("|") }
        guard let header = rows.first(where: { $0.contains("熱量") || $0.lowercased().contains("kcal") }) else { return n }
        let h = cells(header)
        func col(_ keys: String...) -> Int { h.firstIndex { c in keys.contains { c.lowercased().contains($0.lowercased()) } } ?? -1 }
        let iK = col("熱量", "kcal"), iP = col("蛋白"), iC = col("碳水"), iF = col("脂肪")
        let body = rows.drop(while: { $0 != header }).dropFirst().map(cells)
            .filter { r in !r.contains { c in !c.isEmpty && c.allSatisfy { $0 == "-" || $0 == ":" } } }
            .filter { r in !(r.first.map { $0.contains("合計") || $0.contains("總計") } ?? false) }
        guard !body.isEmpty else { return n }
        func sum(_ i: Int) -> Int { i < 0 ? 0 : body.reduce(0) { $0 + firstNum(i < $1.count ? $1[i] : nil) } }
        return AppleAI.Nutrition(meal: n.meal, kcal: n.kcal > 0 ? n.kcal : sum(iK), protein: n.protein > 0 ? n.protein : sum(iP),
                                 carbs: n.carbs > 0 ? n.carbs : sum(iC), fat: n.fat > 0 ? n.fat : sum(iF))
    }

    /// 報告內文（不含「# 標題」）→ 有 ITEM 行時：App 產生「飲食內容」表格（多「來源」欄）與「合計」，
    /// 營養與摘要用重算後的數字。沒有 ITEM 行（舊格式、大D的主機）就照 AI 給的。
    static func finish(raw: String, body: String, aiSummary: String) -> (nutrition: AppleAI.Nutrition?, body: String, summary: String) {
        let ai = GeminiClient.parseNutrition(raw)
        let items = parseItems(raw)
        let cleaned = GeminiClient.stripNutrition(body)
        // 沒有 ITEM 行（大D的主機、舊格式）：用 AI 的 NUTRITION 行；營養素超過重量的列依比例縮小，缺的數字用 AI 自己的表格補上
        guard let ai, !items.isEmpty else {
            let (md, fixed) = fixTable(cleaned, meal: ai?.meal ?? "")
            if let fixed { return (fixed, md, FoodWords.of(ReportLang.current).summary(fixed)) }
            return (ai.map { fillFromTable($0, body) }, cleaned, aiSummary)
        }
        let n = AppleAI.Nutrition(meal: ai.meal, kcal: items.reduce(0) { $0 + $1.kcal },
                                  protein: items.reduce(0) { $0 + $1.protein },
                                  carbs: items.reduce(0) { $0 + $1.carbs }, fat: items.reduce(0) { $0 + $1.fat })
        let fromDb = items.filter { $0.db != nil }.count
        let w = FoodWords.of(ReportLang.current)   // App 自己產生的文字跟著報告語言
        var t = "## \(w.content)\n| \(w.header.joined(separator: " | ")) |\n|---|---|---|---|---|---|---|\n"
        for i in items {
            let portion = [i.portion, i.grams > 0 ? w.grams(i.grams) : ""].filter { !$0.isEmpty }.joined(separator: " · ")
            let src = i.db.map { w.tfda + $0.name } ?? (i.label ? w.label : w.ai)
            t += "| \(i.name) | \(portion) | \(i.kcal) | \(i.protein) | \(i.carbs) | \(i.fat) | \(src) |\n"
        }
        let fromLabel = items.filter(\.label).count
        t += "\n## \(w.total)\n" + w.totalLine(n, items.count, fromDb, fromLabel) + "\n\n"
        return (n, t + cleaned.trimmingCharacters(in: .whitespacesAndNewlines), w.summary(n))
    }
}
