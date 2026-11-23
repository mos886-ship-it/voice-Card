import SwiftUI

/// 「飲食」分頁（照 Android `ui/FoodScreen.kt`）：「記錄這一餐」、今日熱量圓環＋三大營養素、依日期分組的歷史（每天合計）。
/// 飲食記錄 = 錄音 id 以 food_ 開頭（手錶「飲食」鍵錄的）；營養由 Apple（優先）或 Gemini 估算。
struct FoodView: View {
    @Binding var toast: String?
    @EnvironmentObject private var store: MeetingStore
    @State private var openId: String?

    static let goal = 2000

    static func todayKcal(_ all: [Meeting]) -> Int {
        all.filter { $0.isFood && Calendar.current.isDateInToday($0.createdAt) }.reduce(0) { $0 + ($1.nutrition?.kcal ?? 0) }
    }

    var body: some View {
        let meals = store.meetings.filter(\.isFood)
        let today = meals.filter { Calendar.current.isDateInToday($0.createdAt) }
        let sum = { (k: KeyPath<AppleAI.Nutrition, Int>) in today.reduce(0) { $0 + ($1.nutrition?[keyPath: k] ?? 0) } }
        let kcal = sum(\.kcal)
        let days = Dictionary(grouping: meals) { Calendar.current.startOfDay(for: $0.createdAt) }.sorted { $0.key > $1.key }

        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("飲食").font(.headlineSmall).foregroundStyle(Brand.text)
                    Text("說出吃了什麼，AI 估算熱量與營養素").font(.bodySmall).foregroundStyle(Brand.textMuted)
                }

                GlassCard {
                    GradientButton(text: "記錄這一餐", recordDot: true) {
                        toast = "請用手錶的「飲食」鍵記錄（手機錄音下一版加上）"
                    }
                    HStack(spacing: 20) {
                        ZStack {
                            Circle().stroke(Brand.surfaceHigher, lineWidth: 10)
                            Circle().trim(from: 0, to: min(1, Double(kcal) / Double(Self.goal)))
                                .stroke(kcal > Self.goal ? AnyShapeStyle(Brand.warning) : AnyShapeStyle(Brand.accent),
                                        style: StrokeStyle(lineWidth: 10, lineCap: .round))
                                .rotationEffect(.degrees(-90))
                            VStack(spacing: 0) {
                                Text(String(kcal)).font(.system(size: 26, weight: .bold)).foregroundStyle(Brand.text)
                                Text("/ \(String(Self.goal)) 大卡").font(.labelMedium).foregroundStyle(Brand.textMuted)
                            }
                        }
                        .frame(width: 120, height: 120)
                        VStack(alignment: .leading, spacing: 8) {
                            nutrient("蛋白質", sum(\.protein), Brand.cyan)
                            nutrient("碳水", sum(\.carbs), Brand.violet)
                            nutrient("脂肪", sum(\.fat), Brand.pink)
                        }
                    }
                    .padding(.top, 16)
                }

                if meals.isEmpty {
                    GlassCard {
                        Text("還沒有飲食記錄").font(.titleMedium).foregroundStyle(Brand.text)
                        Text("在手錶按「飲食」，說出吃了什麼（例如「中午吃了一碗牛肉麵和燙青菜」），停止後會自動估算熱量。")
                            .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)
                    }
                }

                ForEach(days, id: \.key) { day, list in
                    HStack {
                        Text(dayLabel(day)).font(.titleSmall).foregroundStyle(Brand.text)
                        Spacer()
                        Text("\(String(list.reduce(0) { $0 + ($1.nutrition?.kcal ?? 0) })) 大卡").font(.labelMedium).foregroundStyle(Brand.textMuted)
                    }
                    .padding(.top, 6)
                    ForEach(list) { m in
                        NavigationLink(value: m.id) { MealRow(m: m) }.buttonStyle(.plain)
                    }
                }
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
    }

    private func nutrient(_ name: String, _ g: Int, _ color: Color) -> some View {
        HStack(spacing: 8) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(name).font(.bodySmall).foregroundStyle(Brand.textMuted)
            Text("\(g) g").font(.titleSmall).foregroundStyle(Brand.text)
        }
    }

    private func dayLabel(_ d: Date) -> String {
        if Calendar.current.isDateInToday(d) { return "今天" }
        if Calendar.current.isDateInYesterday(d) { return "昨天" }
        return Fmt.stamp(d, "M/d EEEE")
    }
}

private struct MealRow: View {
    let m: Meeting
    var body: some View {
        GlassCard(padding: 14) {
            HStack(spacing: 12) {
                GlyphTile { BowlGlyph(color: Brand.success).frame(width: 22, height: 22) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(m.title).font(.titleMedium).foregroundStyle(Brand.text).lineLimit(1)
                    if let n = m.nutrition {
                        // 營養素不要在數字中間斷行（照 Android 2026-11-21 修正）
                        Text("\(Fmt.stamp(m.createdAt, "HH:mm")) · 蛋白質 \(n.protein)g · 碳水 \(n.carbs)g · 脂肪 \(n.fat)g")
                            .font(.bodySmall).foregroundStyle(Brand.textMuted).lineLimit(1).minimumScaleFactor(0.8)
                    } else {
                        StatusChip(style: m.statusStyle).padding(.top, 4)
                    }
                }
                Spacer(minLength: 0)
                if let n = m.nutrition {
                    VStack(alignment: .trailing, spacing: 0) {
                        Text(String(n.kcal)).font(.titleLarge).foregroundStyle(Brand.text)
                        Text("大卡").font(.labelMedium).foregroundStyle(Brand.textMuted)
                    }
                }
            }
        }
    }
}
