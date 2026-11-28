import SwiftUI

/// 「飲食」分頁（照 Android `ui/FoodScreen.kt`）：「記錄這一餐」、今日熱量圓環＋三大營養素、依日期分組的歷史（每天合計）。
/// 飲食記錄 = 錄音 id 以 food_ 開頭（手錶「飲食」鍵錄的）；營養由 Apple（優先）或 Gemini 估算。
struct FoodView: View {
    @ObservedObject private var rec = PhoneRecorder.shared
    @ObservedObject private var langs = LangSettings.shared
    @Binding var toast: String?
    @EnvironmentObject private var store: MeetingStore
    @State private var openId: String?

    static var goal: Int { FoodGoals.kcal }

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
                    Text("飲食".tr).font(.headlineSmall).foregroundStyle(Brand.text)
                    Text("說出吃了什麼，AI 估算熱量與營養素".tr).font(.bodySmall).foregroundStyle(Brand.textMuted)
                }

                GlassCard {
                    // 辨識語言（跟主頁同一個設定）：台語、客語說的餐點也能記
                    VStack(alignment: .leading, spacing: 6) {
                        LangPicker(value: langs.lang, disabled: rec.isRecording) { langs.lang = $0 }
                        Text(LangPicker.hint(langs.lang)).font(.labelMedium).foregroundStyle(Brand.textMuted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.trailing, langs.lang == .hak ? 110 : 0)
                            .overlay(alignment: .trailing) {
                                // 一直留在畫面上只是藏起來（iOS 16 反覆插入／拿掉 Menu 會點不動）
                                DialectMenu(value: langs.dialect) { langs.dialect = $0 }
                                    .opacity(langs.lang == .hak ? 1 : 0)
                                    .allowsHitTesting(langs.lang == .hak)
                                    .accessibilityHidden(langs.lang != .hak)
                            }
                            .padding(.horizontal, 6)
                    }
                    .padding(.bottom, 12)
                    GradientButton(text: rec.isRecording ? "停止錄音".tr : "記錄這一餐".tr, recordDot: !rec.isRecording) {
                        if rec.isRecording { rec.stop() } else { rec.start(food: true) }
                    }
                    if !rec.isRecording {
                        Button { FoodPhotoCapture.shared.open(withVoice: false) } label: {
                            Label("拍照記錄（最多 {0} 張）".trf(FoodPhotos.max), systemImage: "camera")
                                .font(.labelLarge).frame(maxWidth: .infinity).frame(height: 44)
                        }
                        .buttonStyle(.bordered).tint(Brand.cyan).padding(.top, 8)
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
                                Text("/ {0} 大卡".trf(String(Self.goal))).font(.labelMedium).foregroundStyle(Brand.textMuted)
                            }
                        }
                        .frame(width: 120, height: 120)
                        VStack(alignment: .leading, spacing: 8) {
                            nutrient("蛋白質".tr, sum(\.protein), Brand.cyan)
                            nutrient("碳水".tr, sum(\.carbs), Brand.violet)
                            nutrient("脂肪".tr, sum(\.fat), Brand.pink)
                            nutrient("纖維".tr, sum(\.fiber), Brand.success)
                        }
                    }
                    .padding(.top, 16)
                }

                // 常吃的：30 天內同一道記過 2 次以上，一鍵再記一次（照抄，不問 AI）
                let favs = FoodRepeat.favorites(meals)
                if !favs.isEmpty && !rec.isRecording {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("常吃的".tr).font(.titleSmall).foregroundStyle(Brand.text)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(favs, id: \.0.id) { m, n in
                                    Button {
                                        FoodRepeat.repeatMeal(m)
                                        toast = "已記錄：{0}（{1} 大卡）".trf(FoodRepeat.mealNow().tr + "：" + FoodRepeat.dish(m.title), m.nutrition?.kcal ?? 0)
                                    } label: {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text("＋ " + FoodRepeat.dish(m.title)).font(.titleSmall).foregroundStyle(Brand.text).lineLimit(1)
                                            Text("{0} 大卡 · {1} 次".trf(m.nutrition?.kcal ?? 0, n)).font(.labelSmall).foregroundStyle(Brand.textMuted)
                                        }
                                        .padding(.horizontal, 12).padding(.vertical, 8)
                                        .background(RoundedRectangle(cornerRadius: 12).fill(Brand.surface))
                                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Brand.outline, lineWidth: 1))
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                }

                if meals.isEmpty {
                    GlassCard {
                        Text("還沒有飲食記錄".tr).font(.titleMedium).foregroundStyle(Brand.text)
                        Text("按「記錄這一餐」或手錶的「飲食」，說出吃了什麼（例如「中午吃了一碗牛肉麵和燙青菜」），停止後會自動估算熱量。".tr)
                            .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)
                    }
                }

                ForEach(days, id: \.key) { day, list in
                    HStack {
                        Text(dayLabel(day)).font(.titleSmall).foregroundStyle(Brand.text)
                        Spacer()
                        Text("{0} 大卡".trf(String(list.reduce(0) { $0 + ($1.nutrition?.kcal ?? 0) }))).font(.labelMedium).foregroundStyle(Brand.textMuted)
                    }
                    .padding(.top, 6)
                    CardGrid(minimum: 320) {
                        ForEach(list) { m in
                            NavigationLink(value: m.id) { MealRow(m: m) }.buttonStyle(.plain)
                        }
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
        if Calendar.current.isDateInToday(d) { return "今天".tr }
        if Calendar.current.isDateInYesterday(d) { return "昨天".tr }
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
                        Text("{0} · 蛋白質 {1}g · 碳水 {2}g · 脂肪 {3}g".trf(Fmt.stamp(m.createdAt, "HH:mm"), n.protein, n.carbs, n.fat)
                             + (n.fiber > 0 ? " · " + "纖維 {0}g".trf(n.fiber) : ""))
                            .font(.bodySmall).foregroundStyle(Brand.textMuted).lineLimit(1).minimumScaleFactor(0.8)
                    } else {
                        StatusChip(style: m.statusStyle).padding(.top, 4)
                    }
                }
                Spacer(minLength: 0)
                if let n = m.nutrition {
                    VStack(alignment: .trailing, spacing: 0) {
                        Text(String(n.kcal)).font(.titleLarge).foregroundStyle(Brand.text)
                        Text("大卡".tr).font(.labelMedium).foregroundStyle(Brand.textMuted)
                    }
                }
            }
        }
    }
}
