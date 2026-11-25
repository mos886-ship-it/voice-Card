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
                    return Item(name: name, portion: portion, grams: grams, db: nil, kcal: aiKcal,
                                protein: num("protein"), carbs: num("carbs"), fat: num("fat"), label: true)
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
                return Item(name: name, portion: portion, grams: grams, db: nil, kcal: aiKcal,
                            protein: num("protein"), carbs: num("carbs"), fat: num("fat"))
            }
    }

    /// 報告內文（不含「# 標題」）→ 有 ITEM 行時：App 產生「飲食內容」表格（多「來源」欄）與「合計」，
    /// 營養與摘要用重算後的數字。沒有 ITEM 行（舊格式、大D的主機）就照 AI 給的。
    static func finish(raw: String, body: String, aiSummary: String) -> (nutrition: AppleAI.Nutrition?, body: String, summary: String) {
        let ai = GeminiClient.parseNutrition(raw)
        let items = parseItems(raw)
        let cleaned = GeminiClient.stripNutrition(body)
        guard let ai, !items.isEmpty else { return (ai, cleaned, aiSummary) }
        let n = AppleAI.Nutrition(meal: ai.meal, kcal: items.reduce(0) { $0 + $1.kcal },
                                  protein: items.reduce(0) { $0 + $1.protein },
                                  carbs: items.reduce(0) { $0 + $1.carbs }, fat: items.reduce(0) { $0 + $1.fat })
        let fromDb = items.filter { $0.db != nil }.count
        var t = "## 飲食內容\n| 食物 | 份量 | 熱量(kcal) | 蛋白質(g) | 碳水(g) | 脂肪(g) | 來源 |\n|---|---|---|---|---|---|---|\n"
        for i in items {
            let portion = [i.portion, i.grams > 0 ? "約 \(i.grams)g" : ""].filter { !$0.isEmpty }.joined(separator: " · ")
            let src = i.db.map { "食藥署：\($0.name)" } ?? (i.label ? "營養標示" : "AI 估算")
            t += "| \(i.name) | \(portion) | \(i.kcal) | \(i.protein) | \(i.carbs) | \(i.fat) | \(src) |\n"
        }
        let fromLabel = items.filter(\.label).count
        let sources = [fromDb > 0 ? "\(fromDb) 樣依食藥署「食品營養成分資料集」計算" : nil,
                       fromLabel > 0 ? "\(fromLabel) 樣依包裝營養標示" : nil].compactMap { $0 }
        t += "\n## 合計\n約 \(n.kcal) 大卡；蛋白質 \(n.protein)g、碳水 \(n.carbs)g、脂肪 \(n.fat)g"
            + (sources.isEmpty ? "（皆為 AI 估算）" : "（\(items.count) 樣中 \(sources.joined(separator: "、"))）") + "\n\n"
        let meal = n.meal.isEmpty ? "這餐" : n.meal
        return (n, t + cleaned.trimmingCharacters(in: .whitespacesAndNewlines), "\(meal)約 \(n.kcal) 大卡，蛋白質 \(n.protein)g")
    }
}
