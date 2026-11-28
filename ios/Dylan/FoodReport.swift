import SwiftUI

/// 就醫週報（照 Android `FoodReport.kt`）：近 7／30 天的飲食整理（Markdown → PDF／Word），給醫師、營養師或家人看。
enum FoodReport {
    struct Goals { let kcal: Int, protein: Int, fiber: Int, carbCap: Int, modeLabel: String }

    static var currentGoals: Goals {
        Goals(kcal: FoodGoals.kcal, protein: FoodGoals.protein, fiber: FoodGoals.fiber, carbCap: FoodGoals.carbCap,
              modeLabel: FoodGoals.label(FoodGoals.mode))
    }

    static func dayStarts(_ days: Int, now: Date = Date()) -> [Date] {
        let today = Calendar.current.startOfDay(for: now)
        return (0..<days).reversed().compactMap { Calendar.current.date(byAdding: .day, value: -$0, to: today) }
    }

    /// 熱量長條：10 格 = 目標，超過的用 ▲ 標。
    static func bar(_ kcal: Int, _ goal: Int) -> String {
        guard goal > 0, kcal > 0 else { return "" }
        let n = min(15, max(0, Int((Double(kcal) * 10 / Double(goal)).rounded())))
        return String(repeating: "█", count: min(n, 10)) + (n > 10 ? String(repeating: "▲", count: n - 10) : "")
    }

    static func title(_ days: Int, now: Date = Date()) -> String {
        "飲食週報 {0}–{1}".trf(Fmt.stamp(dayStarts(days, now: now).first ?? now, "yyyy/MM/dd"), Fmt.stamp(now, "yyyy/MM/dd"))
    }

    static func markdown(_ food: [Meeting], days: Int, g: Goals, now: Date = Date()) -> String {
        let starts = dayStarts(days, now: now)
        guard let first = starts.first else { return "" }
        let done = food.filter { $0.isFood && $0.status == .done && $0.nutrition != nil && $0.createdAt >= first && $0.createdAt <= now }
        let perDay = starts.map { s in done.filter { $0.createdAt >= s && $0.createdAt < s.addingTimeInterval(86_400) } }
        let logged = perDay.filter { !$0.isEmpty }
        func sum(_ l: [Meeting], _ k: KeyPath<AppleAI.Nutrition, Int>) -> Int { l.reduce(0) { $0 + ($1.nutrition?[keyPath: k] ?? 0) } }
        func avg(_ k: KeyPath<AppleAI.Nutrition, Int>) -> Int { logged.isEmpty ? 0 : logged.reduce(0) { $0 + sum($1, k) } / logged.count }
        var o = "## " + "摘要".tr + "\n"
        o += "- " + "期間：近 {0} 天，有記錄 {1} 天、共 {2} 餐".trf(days, logged.count, done.count) + "\n"
        o += "- " + "健康目標：{0}；每日目標 {1} 大卡、蛋白質 {2} g、纖維 {3} g".trf(g.modeLabel, g.kcal, g.protein, g.fiber)
            + (g.carbCap > 0 ? "、" + "碳水上限 {0} g".trf(g.carbCap) : "") + "\n"
        if logged.isEmpty { return o + "\n" + "這段期間沒有飲食記錄。".tr + "\n" }
        let within = logged.filter { sum($0, \.kcal) <= g.kcal }.count
        o += "- " + "平均每天：{0} 大卡（目標的 {1}%）".trf(avg(\.kcal), avg(\.kcal) * 100 / max(1, g.kcal)) + "\n"
        o += "- " + "熱量未超標：{0} / {1} 天".trf(within, logged.count) + "\n"
        o += "- " + "蛋白質達標：{0} / {1} 天".trf(logged.filter { sum($0, \.protein) >= g.protein }.count, logged.count) + "\n"
        if g.carbCap > 0 { o += "- " + "碳水超過上限：{0} / {1} 天".trf(logged.filter { sum($0, \.carbs) > g.carbCap }.count, logged.count) + "\n" }
        o += "\n## " + "每日平均 vs 目標".tr + "\n"
        o += "| " + "項目".tr + " | " + "平均".tr + " | " + "目標".tr + " |\n|---|---|---|\n"
        o += "| " + "熱量（大卡）".tr + " | \(avg(\.kcal)) | \(g.kcal) |\n"
        o += "| " + "蛋白質（g）".tr + " | \(avg(\.protein)) | \(g.protein) |\n"
        o += "| " + "碳水（g）".tr + " | \(avg(\.carbs)) | \(g.carbCap > 0 ? "≤ \(g.carbCap)" : "—") |\n"
        o += "| " + "脂肪（g）".tr + " | \(avg(\.fat)) | — |\n"
        o += "| " + "纖維（g）".tr + " | \(avg(\.fiber)) | \(g.fiber) |\n\n"
        let p = logged.reduce(0) { $0 + sum($1, \.protein) }, c = logged.reduce(0) { $0 + sum($1, \.carbs) }, f = logged.reduce(0) { $0 + sum($1, \.fat) }
        let e = max(1, p * 4 + c * 4 + f * 9)
        o += "## " + "熱量來源".tr + "\n"
        o += "- " + "蛋白質 {0}%、碳水 {1}%、脂肪 {2}%（一般建議：蛋白質 10–35%、碳水 45–65%、脂肪 20–35%）".trf(p * 400 / e, c * 400 / e, f * 900 / e) + "\n\n"
        o += "## " + "每天".tr + "\n"
        o += "| " + ["日期", "餐數", "大卡", "對目標", "蛋白質", "碳水", "脂肪", "纖維"].map(\.tr).joined(separator: " | ") + " |\n"
        o += "|---|---|---|---|---|---|---|---|\n"
        for (i, l) in perDay.enumerated() {
            let k = sum(l, \.kcal)
            o += "| \(Fmt.stamp(starts[i], "M/d (EEE)")) | \(l.count) | \(l.isEmpty ? "—" : String(k)) | \(bar(k, g.kcal)) | "
                + "\(sum(l, \.protein)) | \(sum(l, \.carbs)) | \(sum(l, \.fat)) | \(sum(l, \.fiber)) |\n"
        }
        o += "\n"
        let favs = Dictionary(grouping: done, by: { FoodRepeat.dish($0.title) }).filter { !$0.key.isEmpty }
            .sorted { $0.value.count > $1.value.count }.prefix(8)
        if !favs.isEmpty {
            o += "## " + "常吃的".tr + "\n"
            for (dish, l) in favs { o += "- " + "{0}：{1} 次，平均 {2} 大卡".trf(dish, l.count, sum(l, \.kcal) / l.count) + "\n" }
            o += "\n"
        }
        return o + "熱量與營養為 AI 依口述或照片估算，僅供參考，不能取代營養師或醫療建議。".tr + "\n"
    }
}

/// 飲食 → 分析 →「就醫週報」：近 7／30 天 → PDF／Word。
struct FoodReportCard: View {
    @EnvironmentObject private var store: MeetingStore
    @State private var days = 7

    var body: some View {
        GlassCard {
            Text("就醫週報".tr).font(.titleSmall).foregroundStyle(Brand.text)
            Text("把近幾天的飲食整理成一份報告（平均對目標、每天一列、熱量來源、常吃的），給醫師、營養師或家人看。".tr)
                .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.vertical, 6)
            Picker("", selection: $days) {
                Text("近 7 天".tr).tag(7)
                Text("近 30 天".tr).tag(30)
            }
            .pickerStyle(.segmented)
            HStack(spacing: 8) {
                GhostButton(text: "PDF", systemImage: "doc.richtext", tint: Brand.cyan) { export(.pdf) }
                GhostButton(text: "Word", systemImage: "doc", tint: Brand.cyan) { export(.word) }
            }
            .padding(.top, 8)
        }
    }

    private func export(_ f: ReportExport.Format) {
        let title = FoodReport.title(days)
        let md = FoodReport.markdown(store.meetings, days: days, g: FoodReport.currentGoals)
        if let u = ReportExport.write(title: title, md: md, format: f, footer: "由大D產生 · {0}".trf(Fmt.stamp(Date(), "yyyy/M/d HH:mm"))) {
            ReportExport.share([u])
        }
    }
}
