import SwiftUI
import Charts

// 三大主軸：錄音報告、飲食記錄、即時翻譯，各一個分頁（照 Android `ui/Hub.kt`、交接/HANDOFF.md「三大主軸各自的頁面」）。
// 每個主軸上面一排小分頁，選到哪個記在 UserDefaults（hub_<主軸>）。

enum Pillar: String {
    case records, food, translate

    var tabs: [String] {
        switch self {
        case .records: return ["錄音".tr, "分析".tr, "管理".tr, "設定".tr]
        case .food: return ["今天".tr, "記錄".tr, "分析".tr, "設定".tr]
        case .translate: return ["翻譯".tr, "記錄".tr, "分析".tr, "設定".tr]
        }
    }
}

/// 主軸頁：上面一排小分頁，下面是選到的內容。
struct HubScaffold<Content: View>: View {
    let pillar: Pillar
    @ViewBuilder let content: (Int) -> Content
    @AppStorage private var selected: Int

    init(pillar: Pillar, @ViewBuilder content: @escaping (Int) -> Content) {
        self.pillar = pillar
        self.content = content
        _selected = AppStorage(wrappedValue: 0, "hub_" + pillar.rawValue)
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $selected) {
                ForEach(Array(pillar.tabs.enumerated()), id: \.offset) { i, t in Text(t).tag(i) }
            }
            .pickerStyle(.segmented)
            .pageColumn()
            .padding(.top, 6)
            .padding(.bottom, 2)
            content(min(selected, pillar.tabs.count - 1))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

// MARK: - 三個主軸

struct RecordsHub: View {
    let open: (String) -> Void
    @Binding var toast: String?
    var body: some View {
        HubScaffold(pillar: .records) { i in
            switch i {
            case 0: RecordsView(open: open, toast: $toast)
            case 1: RecordAnalysisView(open: open)
            case 2: FilesView(open: open, toast: $toast)
            default: RecordSettingsView()
            }
        }
    }
}

struct FoodHub: View {
    let open: (String) -> Void
    @Binding var toast: String?
    var body: some View {
        HubScaffold(pillar: .food) { i in
            switch i {
            case 0: FoodView(toast: $toast)
            case 1: FoodRecordsView(open: open)
            case 2: FoodAnalysisView()
            default: FoodSettingsView()
            }
        }
    }
}

struct TranslateHub: View {
    let open: (String) -> Void
    @Binding var toast: String?
    var body: some View {
        HubScaffold(pillar: .translate) { i in
            switch i {
            case 0: LiveTranslateView(embedded: true)
            case 1: TranslationRecordsView(open: open, toast: $toast)
            case 2: TranslateAnalysisView()
            default: TranslateSettingsView()
            }
        }
    }
}

// MARK: - 共用

/// 飲食目標（飲食 → 設定）：熱量、蛋白質、纖維。
enum FoodGoals {
    static let kcalKey = "food_goal_kcal", proteinKey = "food_goal_protein", fiberKey = "food_goal_fiber"
    static var kcal: Int { (UserDefaults.standard.object(forKey: kcalKey) as? Int) ?? 2000 }
    static var protein: Int { (UserDefaults.standard.object(forKey: proteinKey) as? Int) ?? 60 }
    static var fiber: Int { (UserDefaults.standard.object(forKey: fiberKey) as? Int) ?? 25 }
}

/// 近 7／30 天。
private struct RangePicker: View {
    @Binding var days: Int
    var body: some View {
        Picker("", selection: $days) {
            Text("近 7 天".tr).tag(7)
            Text("近 30 天".tr).tag(30)
        }
        .pickerStyle(.segmented)
    }
}

private func dayList(_ days: Int) -> [Date] {
    let today = Calendar.current.startOfDay(for: Date())
    return (0..<days).reversed().compactMap { Calendar.current.date(byAdding: .day, value: -$0, to: today) }
}

private func inRange(_ d: Date, _ days: Int) -> Bool {
    guard let from = dayList(days).first else { return false }
    return d >= from
}

private struct PageTitle: View {
    let title: String
    let sub: String
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title).font(.headlineSmall).foregroundStyle(Brand.text)
            Text(sub).font(.bodySmall).foregroundStyle(Brand.textMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct Bars: View {
    let points: [(Date, Double)]
    var goal: Double?
    var unit: String
    var body: some View {
        Chart {
            ForEach(points, id: \.0) { p in
                BarMark(x: .value("日", p.0, unit: .day), y: .value(unit, p.1))
                    .foregroundStyle(goal != nil && p.1 > goal! ? Brand.warning : Brand.cyan)
            }
            if let goal {
                RuleMark(y: .value("目標", goal)).lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    .foregroundStyle(Brand.textMuted)
            }
        }
        .chartXAxis {
            AxisMarks(values: .stride(by: .day, count: points.count > 10 ? 7 : 1)) { _ in
                AxisValueLabel(format: .dateTime.month(.defaultDigits).day(), centered: true)
            }
        }
        .frame(height: 160)
    }
}

// MARK: - 錄音：分析

struct RecordAnalysisView: View {
    let open: (String) -> Void
    @EnvironmentObject private var store: MeetingStore
    @AppStorage(UIMode.key) private var mode: UIMode = .business
    @State private var days = 7
    @State private var done = ActionItems.done
    @State private var showDone = false

    var body: some View {
        let all = store.meetings.filter { !$0.isFood }
        let ms = all.filter { inRange($0.createdAt, days) }
        let total = ms.reduce(0) { $0 + $1.durationSec }
        let perDay = dayList(days).map { d in
            (d, ms.filter { Calendar.current.isDate($0.createdAt, inSameDayAs: d) }.reduce(0) { $0 + $1.durationSec } / 60)
        }
        let todos = ActionItems.all(store)
        let open_ = todos.flatMap { m, items in items.filter { !done.contains($0.key) }.map { (m, $0) } }
        let closed = todos.flatMap { m, items in items.filter { done.contains($0.key) }.map { (m, $0) } }
        let failed = all.filter { $0.status == .failed }
        let sources = Dictionary(grouping: ms, by: \.source).map { ($0.key, $0.value.count) }.sorted { $0.1 > $1.1 }
        let people = Dictionary(grouping: ms.flatMap { ($0.speakerNames ?? [:]).values.filter { !ReportLang.isUnknownName($0) } }, by: { $0 })
            .map { ($0.key, $0.value.count) }.sorted { $0.1 > $1.1 }.prefix(6)
        let models = Dictionary(grouping: ms.compactMap(\.aiModel), by: { $0 }).map { ($0.key, $0.value.count) }.sorted { $0.1 > $1.1 }

        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                PageTitle(title: "錄音分析".tr, sub: "段數、時間與待辦".tr)
                RangePicker(days: $days)
                HStack(spacing: 8) {
                    StatTile(value: "\(ms.count)", label: "段".tr)
                    StatTile(value: Fmt.duration(total), label: "總長".tr)
                    StatTile(value: ms.isEmpty ? "—" : Fmt.duration(total / Double(ms.count)), label: "平均".tr)
                }
                .fixedSize(horizontal: false, vertical: true)
                GlassCard {
                    Text("每天錄音（分鐘）".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 8)
                    Bars(points: perDay, unit: "分鐘".tr)
                }

                SectionLabel(text: "待辦總覽".tr + "（\(open_.count)）")
                GlassCard {
                    if open_.isEmpty {
                        Text(todos.isEmpty ? "報告裡還沒有待辦事項".tr : "待辦都完成了".tr)
                            .font(.bodyMedium).foregroundStyle(Brand.textMuted)
                    }
                    ForEach(Array(open_.enumerated()), id: \.element.1.key) { i, e in
                        if i > 0 { DividerLine().padding(.vertical, 8) }
                        todoRow(e.0, e.1, checked: false)
                    }
                    if !closed.isEmpty {
                        Button { showDone.toggle() } label: {
                            Text((showDone ? "收起已完成".tr : "顯示已完成".tr) + "（\(closed.count)）")
                                .font(.labelMedium).foregroundStyle(Brand.cyan)
                        }
                        .buttonStyle(.plain).padding(.top, 10)
                        if showDone {
                            ForEach(closed, id: \.1.key) { e in todoRow(e.0, e.1, checked: true).padding(.top, 8) }
                        }
                    }
                }

                if !sources.isEmpty || !people.isEmpty {
                    SectionLabel(text: "來源與人".tr)
                    GlassCard {
                        ForEach(sources, id: \.0) { s in kv(Meeting.label(source: s.0), "\(s.1)") }
                        if !people.isEmpty {
                            DividerLine().padding(.vertical, 8)
                            Text("常出現的人".tr).font(.labelLarge).foregroundStyle(Brand.text)
                            ForEach(Array(people), id: \.0) { p in kv(p.0, "\(p.1)") }
                        }
                        if mode == .tech && !models.isEmpty {
                            DividerLine().padding(.vertical, 8)
                            Text("AI 模型".tr).font(.labelLarge).foregroundStyle(Brand.text)
                            ForEach(models, id: \.0) { p in kv(p.0, "\(p.1)") }
                        }
                    }
                }
                if !failed.isEmpty {
                    SectionLabel(text: "失敗要重試的".tr + "（\(failed.count)）")
                    GlassCard {
                        ForEach(failed) { m in
                            Button { open(m.id) } label: { kv(m.title, Fmt.stamp(m.createdAt, "M/d HH:mm")) }.buttonStyle(.plain)
                        }
                    }
                }
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
        .onAppear { done = ActionItems.done }
    }

    private func todoRow(_ m: Meeting, _ it: ActionItems.Item, checked: Bool) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Button {
                ActionItems.setDone(it, !checked)
                done = ActionItems.done
            } label: {
                Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20)).foregroundStyle(checked ? Brand.success : Brand.textMuted)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(checked ? "標成未完成".tr : "標成完成".tr)
            Button { open(m.id) } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(it.task).font(.bodyMedium).foregroundStyle(checked ? Brand.textMuted : Brand.text)
                        .strikethrough(checked).multilineTextAlignment(.leading)
                    Text([it.owner, it.due, m.title].compactMap { $0 }.joined(separator: " · "))
                        .font(.labelMedium).foregroundStyle(Brand.textFaint).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
        }
    }
}

private func kv(_ k: String, _ v: String) -> some View {
    HStack {
        Text(k).font(.bodyMedium).foregroundStyle(Brand.text).lineLimit(1)
        Spacer()
        Text(v).font(.bodyMedium).foregroundStyle(Brand.textMuted)
    }
    .padding(.vertical, 3)
}

// MARK: - 錄音：設定

struct RecordSettingsView: View {
    @ObservedObject private var langs = LangSettings.shared
    @ObservedObject private var rec = PhoneRecorder.shared
    @AppStorage(ReportLang.key) private var reportLang = ReportLang.auto
    @AppStorage(ReportDomain.key) private var reportDomain = ReportDomain.auto

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                PageTitle(title: "錄音設定".tr, sub: "改了就存".tr)
                GlassCard {
                    Text("錄音語言".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 8)
                    LangPicker(value: langs.lang, disabled: rec.isRecording) { langs.lang = $0 }
                    Text(LangPicker.hint(langs.lang)).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 6)
                }
                GlassCard {
                    Text("報告語言".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 8)
                    Picker("報告語言".tr, selection: $reportLang) {
                        Text("跟介面".tr).tag(ReportLang.auto)
                        ForEach(ReportLang.allCases, id: \.self) { Text($0.label).tag($0.rawValue) }
                    }
                    .pickerStyle(.segmented)
                    DividerLine().padding(.vertical, 14)
                    HStack {
                        Text("領域專家模式".tr).font(.titleSmall).foregroundStyle(Brand.text)
                        Spacer()
                        Picker("領域專家模式".tr, selection: $reportDomain) {
                            ForEach(ReportDomain.options, id: \.id) { Text($0.name).tag($0.id) }
                        }
                        .pickerStyle(.menu).tint(Brand.blue)
                    }
                    Text("法律、醫療、財務等專業內容，報告多一節「專業重點」，術語與重點照該領域整理。自動判斷：一般對話不加。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)
                }
                Text("AI 引擎、大D的主機、手錶等在「設定」頁。".tr).font(.bodySmall).foregroundStyle(Brand.textFaint)
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
    }
}

// MARK: - 飲食：記錄

struct FoodRecordsView: View {
    let open: (String) -> Void
    @EnvironmentObject private var store: MeetingStore
    @State private var csv: URL?

    var body: some View {
        let meals = store.meetings.filter(\.isFood)
        let days = Dictionary(grouping: meals) { Calendar.current.startOfDay(for: $0.createdAt) }.sorted { $0.key > $1.key }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                HStack {
                    PageTitle(title: "飲食記錄".tr, sub: "共 {0} 筆".trf(meals.count))
                    if let csv {
                        ShareLink(item: csv) { Label("匯出 CSV".tr, systemImage: "square.and.arrow.up").font(.labelLarge) }
                            .tint(Brand.cyan)
                    }
                }
                if meals.isEmpty {
                    GlassCard { Text("還沒有飲食記錄".tr).font(.bodyMedium).foregroundStyle(Brand.textMuted) }
                }
                ForEach(days, id: \.key) { day, list in
                    let kcal = list.reduce(0) { $0 + ($1.nutrition?.kcal ?? 0) }
                    SectionLabel(text: Fmt.stamp(day, "M/d EEE") + " · " + "{0} 大卡".trf(kcal))
                    GlassCard {
                        ForEach(Array(list.enumerated()), id: \.element.id) { i, m in
                            if i > 0 { DividerLine().padding(.vertical, 8) }
                            Button { open(m.id) } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(m.title).font(.bodyMedium).foregroundStyle(Brand.text).lineLimit(1)
                                        Text(Fmt.stamp(m.createdAt, "HH:mm")
                                             + (m.nutrition.map { " · " + "蛋白質 {0}g".trf($0.protein) } ?? ""))
                                            .font(.labelMedium).foregroundStyle(Brand.textMuted)
                                    }
                                    Spacer()
                                    Text(m.nutrition.map { "\($0.kcal)" } ?? "—").font(.titleMedium).foregroundStyle(Brand.text)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
        .onAppear { csv = Self.exportCSV(meals) }
    }

    /// CSV（含 BOM，Excel 打得開）。
    static func exportCSV(_ meals: [Meeting]) -> URL? {
        guard !meals.isEmpty else { return nil }
        func q(_ s: String) -> String { "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }
        var rows = [["日期", "時間", "餐別", "標題", "熱量(kcal)", "蛋白質(g)", "碳水(g)", "脂肪(g)", "纖維(g)"].map { q($0.tr) }.joined(separator: ",")]
        for m in meals.sorted(by: { $0.createdAt < $1.createdAt }) {
            let n = m.nutrition
            rows.append([Fmt.stamp(m.createdAt, "yyyy-MM-dd"), Fmt.stamp(m.createdAt, "HH:mm"), n?.meal ?? "", m.title,
                         "\(n?.kcal ?? 0)", "\(n?.protein ?? 0)", "\(n?.carbs ?? 0)", "\(n?.fat ?? 0)", "\(n?.fiber ?? 0)"]
                .map(q).joined(separator: ","))
        }
        let u = FileManager.default.temporaryDirectory.appendingPathComponent("dylan-food.csv")
        let data = Data([0xEF, 0xBB, 0xBF]) + Data(rows.joined(separator: "\r\n").utf8)
        return (try? data.write(to: u, options: .atomic)) != nil ? u : nil
    }
}

// MARK: - 飲食：分析

struct FoodAnalysisView: View {
    @EnvironmentObject private var store: MeetingStore
    @State private var days = 7

    var body: some View {
        // 非飲食內容（全部 0）不算有記錄
        let meals = store.meetings.filter { $0.isFood && inRange($0.createdAt, days) && ($0.nutrition?.kcal ?? 0) > 0 }
        let perDay = dayList(days).map { d -> (Date, [Meeting]) in
            (d, meals.filter { Calendar.current.isDate($0.createdAt, inSameDayAs: d) })
        }
        let logged = perDay.filter { !$0.1.isEmpty }
        let sum = { (ms: [Meeting], k: KeyPath<AppleAI.Nutrition, Int>) in ms.reduce(0) { $0 + ($1.nutrition?[keyPath: k] ?? 0) } }
        let avg = { (k: KeyPath<AppleAI.Nutrition, Int>) -> Int in
            logged.isEmpty ? 0 : logged.reduce(0) { $0 + sum($1.1, k) } / logged.count
        }
        let goal = FoodGoals.kcal
        let under = logged.filter { sum($0.1, \.kcal) <= goal }.count
        let p = avg(\.protein), c = avg(\.carbs), f = avg(\.fat), fi = avg(\.fiber)
        let energy = Double(p * 4 + c * 4 + f * 9)
        let mealsBy = Dictionary(grouping: meals) { $0.nutrition?.meal ?? "" }.map { ($0.key, $0.value.count) }.sorted { $0.1 > $1.1 }

        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                PageTitle(title: "飲食分析".tr, sub: "熱量、營養素與餐別".tr)
                RangePicker(days: $days)
                HStack(spacing: 8) {
                    StatTile(value: logged.isEmpty ? "—" : "\(avg(\.kcal))", label: "平均大卡".tr)
                    StatTile(value: "\(under)/\(logged.count)", label: "未超標天數".tr)
                    StatTile(value: "\(logged.count)", label: "有記錄的天".tr)
                }
                .fixedSize(horizontal: false, vertical: true)
                GlassCard {
                    Text("每天熱量（虛線＝目標 {0}）".trf(goal)).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 8)
                    Bars(points: perDay.map { ($0.0, Double(sum($0.1, \.kcal))) }, goal: Double(goal), unit: "大卡".tr)
                }
                GlassCard {
                    Text("每天平均對目標".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 6)
                    goalRow("蛋白質".tr, p, FoodGoals.protein)
                    goalRow("纖維".tr, fi, FoodGoals.fiber)
                    DividerLine().padding(.vertical, 10)
                    Text("熱量來源".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 6)
                    if energy > 0 {
                        kv("蛋白質".tr, "\(Int((Double(p * 4) / energy * 100).rounded()))%")
                        kv("碳水".tr, "\(Int((Double(c * 4) / energy * 100).rounded()))%")
                        kv("脂肪".tr, "\(Int((Double(f * 9) / energy * 100).rounded()))%")
                    } else {
                        Text("還沒有資料".tr).font(.bodySmall).foregroundStyle(Brand.textMuted)
                    }
                }
                if !mealsBy.isEmpty {
                    GlassCard {
                        Text("餐別".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 6)
                        ForEach(mealsBy, id: \.0) { m in kv(m.0.isEmpty ? "其他".tr : m.0.tr, "\(m.1)") }
                    }
                }
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
    }

    private func goalRow(_ name: String, _ v: Int, _ goal: Int) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            kv(name, "\(v) / \(goal) g")
            ProgressView(value: Double(min(v, goal)), total: Double(max(goal, 1))).tint(v >= goal ? Brand.success : Brand.cyan)
        }
        .padding(.vertical, 2)
    }
}

// MARK: - 飲食：設定

struct FoodSettingsView: View {
    @AppStorage(FoodGoals.kcalKey) private var kcal = 2000
    @AppStorage(FoodGoals.proteinKey) private var protein = 60
    @AppStorage(FoodGoals.fiberKey) private var fiber = 25
    @AppStorage(FoodRegion.key) private var foodCountry = FoodRegion.auto

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                PageTitle(title: "飲食設定".tr, sub: "改了就存".tr)
                GlassCard {
                    Text("每日目標".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 6)
                    Stepper(value: $kcal, in: 800...5000, step: 50) { kv("熱量".tr, "\(kcal) " + "大卡".tr) }
                    Stepper(value: $protein, in: 10...300, step: 5) { kv("蛋白質".tr, "\(protein) g") }
                    Stepper(value: $fiber, in: 5...80, step: 1) { kv("纖維".tr, "\(fiber) g") }
                }
                GlassCard {
                    HStack {
                        Text("用餐的國家".tr).font(.titleSmall).foregroundStyle(Brand.text)
                        Spacer()
                        Picker("用餐的國家".tr, selection: $foodCountry) {
                            Text("自動".tr).tag(FoodRegion.auto)
                            ForEach(FoodRegion.supported, id: \.self) { Text(FoodRegion.name($0)).tag($0) }
                        }
                        .pickerStyle(.menu).tint(Brand.blue)
                    }
                    Text("份量依當地習慣估算。自動：照這一餐的地點，沒有地點就照手機地區（現在：".tr
                         + FoodRegion.name(FoodRegion.of()) + "）。台灣的餐用食藥署資料計算，其他國家為 AI 估算。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)
                }
                HealthSyncCard()
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
    }
}

// MARK: - 翻譯：記錄

struct TranslationRecordsView: View {
    let open: (String) -> Void
    @Binding var toast: String?
    @ObservedObject private var store = TranslationStore.shared
    @State private var showing: TranslationRecord?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                PageTitle(title: "翻譯記錄".tr, sub: "共 {0} 次".trf(store.records.count))
                if store.records.isEmpty {
                    GlassCard {
                        Text("每次開始到停止翻譯會存成一筆記錄（可在翻譯設定關閉）。".tr)
                            .font(.bodyMedium).foregroundStyle(Brand.textMuted)
                    }
                }
                ForEach(store.records) { r in
                    Button { showing = r } label: {
                        GlassCard {
                            HStack {
                                Text(Fmt.stamp(r.start, "M/d HH:mm")).font(.titleSmall).foregroundStyle(Brand.text)
                                Spacer()
                                Text(r.sourceLabel + " → " + r.targetLabel).font(.labelMedium).foregroundStyle(Brand.cyan)
                            }
                            Text(r.preview).font(.bodyMedium).foregroundStyle(Brand.textMuted).lineLimit(2).padding(.top, 4)
                            Text("{0} 句 · {1}".trf(r.lines.count, Fmt.duration(r.durationSec)))
                                .font(.labelMedium).foregroundStyle(Brand.textFaint).padding(.top, 4)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
        .sheet(item: $showing) { r in TranslationDetailView(record: r, open: { id in showing = nil; open(id) }) }
        .onAppear { store.reload() }
    }
}

struct TranslationDetailView: View {
    let record: TranslationRecord
    let open: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var meetings: MeetingStore
    @State private var confirmDelete = false

    var body: some View {
        VStack(spacing: 0) {
            TopBar(title: Fmt.stamp(record.start, "M/d HH:mm"), onBack: { dismiss() }) {
                ShareLink(item: record.text) { Image(systemName: "square.and.arrow.up") }.tint(Brand.cyan)
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    Text(record.sourceLabel + " → " + record.targetLabel + " · " + Fmt.duration(record.durationSec))
                        .font(.labelLarge).foregroundStyle(Brand.textMuted)
                    if let mid = record.meetingId, meetings.meetings.contains(where: { $0.id == mid }) {
                        GhostButton(text: "打開這段錄音的報告".tr, systemImage: "doc.text", tint: Brand.cyan) { open(mid) }
                    }
                    ForEach(Array(record.lines.enumerated()), id: \.offset) { _, l in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(l.original).font(.bodySmall).foregroundStyle(Brand.textMuted).textSelection(.enabled)
                            if let t = l.translated, !t.isEmpty {
                                Text(t).font(.titleMedium).foregroundStyle(Brand.text).textSelection(.enabled)
                            }
                        }
                    }
                    GhostButton(text: "刪除這筆記錄".tr, systemImage: "trash", tint: Brand.warning) { confirmDelete = true }
                        .padding(.top, 12)
                }
                .pageColumn()
                .padding(.vertical, 12)
            }
        }
        .background(AppBackdrop())
        .confirmationDialog("刪除這筆翻譯記錄？".tr, isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("刪除".tr, role: .destructive) { TranslationStore.shared.delete(record); dismiss() }
        }
    }
}

// MARK: - 翻譯：分析

struct TranslateAnalysisView: View {
    @ObservedObject private var store = TranslationStore.shared
    @State private var days = 7

    var body: some View {
        let rs = store.records.filter { inRange($0.start, days) }
        let total = rs.reduce(0) { $0 + $1.durationSec }
        let lines = rs.reduce(0) { $0 + $1.lines.count }
        let translated = rs.reduce(0) { $0 + $1.translatedCount }
        let perDay = dayList(days).map { d in
            (d, rs.filter { Calendar.current.isDate($0.start, inSameDayAs: d) }.reduce(0) { $0 + $1.durationSec } / 60)
        }
        let pairs = Dictionary(grouping: rs) { $0.sourceLabel + " → " + $0.targetLabel }.map { ($0.key, $0.value.count) }
            .sorted { $0.1 > $1.1 }

        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                PageTitle(title: "翻譯分析".tr, sub: "次數、時間與語言".tr)
                RangePicker(days: $days)
                HStack(spacing: 8) {
                    StatTile(value: "\(rs.count)", label: "次".tr)
                    StatTile(value: Fmt.duration(total), label: "時間".tr)
                    StatTile(value: "\(lines)", label: "句".tr)
                }
                .fixedSize(horizontal: false, vertical: true)
                GlassCard {
                    Text("每天翻譯（分鐘）".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 8)
                    Bars(points: perDay, unit: "分鐘".tr)
                }
                GlassCard {
                    Text("語言組合".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 6)
                    if pairs.isEmpty { Text("還沒有資料".tr).font(.bodySmall).foregroundStyle(Brand.textMuted) }
                    ForEach(pairs, id: \.0) { p in kv(p.0, "\(p.1)") }
                    DividerLine().padding(.vertical, 10)
                    kv("翻譯完成率".tr, lines == 0 ? "—" : "\(translated * 100 / lines)%")
                }
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
        .onAppear { store.reload() }
    }
}

// MARK: - 翻譯：設定

struct TranslateSettingsView: View {
    @AppStorage(LiveCaptions.targetKey) private var target = ""
    @AppStorage(LiveCaptions.textSizeKey) private var textSize = 1
    @AppStorage(TranslationStore.autoSaveKey) private var autoSave = true
    @AppStorage(LiveCaptions.watchKey) private var watch = true
    @ObservedObject private var langs = LangSettings.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                PageTitle(title: "翻譯設定".tr, sub: "改了就存".tr)
                GlassCard {
                    Text("預設翻成".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 8)
                    Picker("預設翻成".tr, selection: $target) {
                        Text("自動".tr).tag("")
                        ForEach(LiveCaptions.Target.allCases) { Text($0.label).tag($0.rawValue) }
                    }
                    .pickerStyle(.segmented)
                    Text("自動：跟 App 介面語言；跟說的語言一樣時中↔英。".tr).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 6)
                }
                GlassCard {
                    Text("字幕大小".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 8)
                    Picker("字幕大小".tr, selection: $textSize) {
                        Text("小".tr).tag(0); Text("中".tr).tag(1); Text("大".tr).tag(2); Text("特大".tr).tag(3)
                    }
                    .pickerStyle(.segmented)
                    DividerLine().padding(.vertical, 12)
                    Toggle(isOn: $watch) { Text("手錶顯示字幕".tr).font(.bodyMedium).foregroundStyle(Brand.text) }.tint(Brand.cyan)
                    Toggle(isOn: $autoSave) { Text("自動存翻譯記錄".tr).font(.bodyMedium).foregroundStyle(Brand.text) }.tint(Brand.cyan)
                }
                Text("翻譯用 Apple「翻譯」（iOS 18 以上），第一次用某個語言時系統會問要不要下載。".tr)
                    .font(.bodySmall).foregroundStyle(Brand.textFaint)
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
    }
}
