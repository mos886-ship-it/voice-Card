import SwiftUI

/// 「主頁」分頁（照 Android `ui/DashboardScreen.kt`）：問候 + 日期、手錶連線、快捷鍵、今天統計、處理中、最新報告。
struct DashboardView: View {
    let open: (String) -> Void
    let select: (Tab) -> Void
    @Binding var toast: String?
    @EnvironmentObject private var store: MeetingStore
    @EnvironmentObject private var link: PhoneLink
    @ObservedObject private var rec = PhoneRecorder.shared
    @Environment(\.pageWidth) private var width
    @AppStorage(UIMode.key) private var mode: UIMode = .business
    @ObservedObject private var langs = LangSettings.shared
    private var lang: SpeechLang { langs.lang }
    private var dialect: HakkaDialect { langs.dialect }
    @AppStorage(DeviceInfo.askedKey) private var askedName = false
    @AppStorage(DeviceInfo.key) private var deviceName = ""
    @State private var naming = false
    @State private var nameInput = ""

    @State private var picking = false
    @ObservedObject private var translations = TranslationStore.shared
    @ObservedObject private var talks = TalkStore.shared
    @State private var choosing = false

    var body: some View {
        let todays = store.meetings.filter { Calendar.current.isDateInToday($0.createdAt) && $0.status != .recording }
        let busy = store.meetings.filter { $0.statusStyle.busy && $0.status != .recording }
        let kcal = FoodView.todayKcal(store.meetings)

        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // ---------- 日期 + 今天 + 狀態 ----------
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        // 問候（照 Android Greeting.kt）：每分鐘更新，跟著時段換
                        TimelineView(.everyMinute) { ctx in
                            let g = Greeting.now(ctx.date, meetings: store.meetings)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Fmt.stamp(ctx.date, "M 月 d 日 EEEE".tr)).font(.bodySmall).foregroundStyle(Brand.textMuted)
                                Text(g.title).font(.system(size: 30, weight: .bold, design: Brand.serifTitles ? .serif : .default))
                                    .foregroundStyle(Brand.text)
                                Text(g.line).font(.bodyMedium).foregroundStyle(Brand.textMuted)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        if mode == .tech {
                            Text("AI：" + engineLabel).font(.labelMedium).foregroundStyle(Brand.textFaint).lineLimit(1)
                        }
                    }
                    Spacer()
                    if mode == .tech || hasWatch {
                        WatchPill(state: link.watchState) { select(.settings) }
                    }
                    TalkEntryButton()   // 遠距對話（來電、未接、好友邀請有紅色數字）
                    // 快錄的分頁列沒有「設定」：從這裡進
                    IconCircle(systemImage: "gearshape", label: "設定".tr) { select(.settings) }
                }

                TwoColumns(wide: Wide.twoColumns(width)) {
                // ---------- 錄音：一張卡放開始錄音、語言、其他記錄方式 ----------
                if rec.isRecording, let since = rec.since {
                    RecordingCard(food: rec.isFood, since: since, interrupted: rec.interrupted,
                                  lang: lang == .hak ? "客語・".tr + dialect.label : lang.short,
                                  detailed: mode == .tech) { rec.stop() }
                } else {
                    CaptureCard(lang: lang, dialect: dialect, setLang: { langs.lang = $0 }, setDialect: { langs.dialect = $0 },
                                record: { rec.start() }, food: { rec.start(food: true) },
                                call: { BroadcastPicker.open() }, importFile: { choosing = true })
                        .background(BroadcastPicker().frame(width: 1, height: 1))
                }
                } right: {
                // ---------- 處理中 ----------
                if !busy.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        PlainLabel(text: "處理中".tr)
                        ForEach(busy) { m in
                            Button { open(m.id) } label: {
                                GlassCard(padding: 14) {
                                    HStack(alignment: .firstTextBaseline) {
                                        Text(m.title).font(.titleSmall).foregroundStyle(Brand.text).lineLimit(1)
                                        Spacer()
                                        if m.durationSec >= 1 {
                                            Text(Fmt.clock(m.durationSec)).font(.labelMedium).monospacedDigit().foregroundStyle(Brand.textMuted)
                                        }
                                    }
                                    ScanningBar().padding(.top, 10)
                                    Text(Self.busyText(m)).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 8)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                // ---------- 今日回顧（晚上 9 點到清晨 4 點） ----------
                TimelineView(.everyMinute) { ctx in
                    let h = Calendar.current.component(.hour, from: ctx.date)
                    if h >= 21 || h < 4 { DayRecapCard(r: recap, openRecords: { select(.records) }, openFood: { select(.food) }) }
                }

                // ---------- 今天的記錄 ----------
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        PlainLabel(text: "今天的記錄".tr)
                        Spacer()
                        if store.meetings.contains(where: \.isFood) {
                            Button { select(.food) } label: {
                                Text("{0} / {1} 大卡".trf(kcal, FoodView.goal)).font(.bodySmall).foregroundStyle(Brand.textMuted)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    let extras = todayExtras
                    if todays.isEmpty && extras.isEmpty {
                        GlassCard {
                            Text("今天還沒有記錄".tr).font(.titleSmall).foregroundStyle(Brand.text)
                            Text("按「開始錄音」或手錶的錄音鍵，錄完會自動整理出重點、待辦與逐字稿。".tr)
                                .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)
                        }
                    } else {
                        // 錄音、飲食、即時翻譯（沒連錄音的）、遠距對話照時間排在一起
                        let rows: [(Date, AnyView)] = (todays.map { m in
                            (m.createdAt, AnyView(Button { open(m.id) } label: { TodayRow(m: m) }.buttonStyle(.plain)))
                        } + extras.map { e in (e.at, AnyView(Button(action: e.action) { TimelineExtraRow(e: e) }.buttonStyle(.plain))) })
                            .sorted { $0.0 > $1.0 }
                        VStack(spacing: 0) {
                            ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                                r.1
                                if i < rows.count - 1 { Rectangle().fill(Brand.outline).frame(height: 1).padding(.leading, 68) }
                            }
                        }
                        .background(RoundedRectangle(cornerRadius: Brand.cardRadius, style: .continuous).fill(Brand.surface))
                        .overlay(RoundedRectangle(cornerRadius: Brand.cardRadius, style: .continuous).stroke(Brand.outline, lineWidth: 1))
                    }
                    Button { select(.records) } label: {
                        Text("所有記錄 →".tr).font(.labelLarge).foregroundStyle(Brand.cyan)
                            .frame(minHeight: 44)
                    }
                    .buttonStyle(.plain)
                }
                }
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
        .refreshable { store.reload() }
        .background(Color.clear.modifier(ImportSources(choosing: $choosing, pickingFiles: $picking) { errors, total in
            let ok = total - errors.count
            toast = errors.isEmpty ? "已匯入 {0} 個檔，正在產生報告".trf(ok)
                : (ok > 0 ? "已匯入 {0} 個；".trf(ok) : "") + errors.joined(separator: "；")
        }))
        .fileImporter(isPresented: $picking, allowedContentTypes: AudioImport.types, allowsMultipleSelection: true) { result in
            guard case let .success(urls) = result, !urls.isEmpty else { return }
            Task {
                var errors: [String] = []
                for u in urls { if let e = await AudioImport.run(u) { errors.append(e) } }
                let ok = urls.count - errors.count
                toast = errors.isEmpty ? "已匯入 {0} 個檔，正在產生報告".trf(ok)
                    : (ok > 0 ? "已匯入 {0} 個；".trf(ok) : "") + errors.joined(separator: "；")
            }
        }
        .onAppear { if !askedName && DeviceInfo.name.isEmpty { naming = true } }
        .alert(Meeting.isPad ? "這台 iPad 叫什麼名字？".tr : "這支手機叫什麼名字？".tr, isPresented: $naming) {
            TextField(Meeting.isPad ? "例如：Dylan 的 iPad".tr : "例如：Dylan 的 iPhone".tr, text: $nameInput)
            Button("儲存".tr) {
                deviceName = nameInput.trimmingCharacters(in: .whitespacesAndNewlines)
                askedName = true
            }
            Button("以後再說".tr, role: .cancel) { askedName = true }
        } message: {
            Text("會顯示在錄音的「來源」，分得出是哪一台錄的。之後可以在「設定 → 這台裝置」改。".tr)
        }
        .onChange(of: rec.state) { s in
            switch s {
            case .denied: toast = "沒有麥克風權限：到 iPhone 設定 → 大D → 麥克風 打開".tr; rec.clearMessage()
            case .failed(let msg): toast = msg; rec.clearMessage()
            default: break
            }
        }
    }

    private var todayExtras: [TimelineExtra] { TodayData.extras(select: select) }
    private var recap: DayRecap { TodayData.recap(store) }

    /// 處理中卡片下面那一行：排隊位置、或在哪一步。
    static func busyText(_ m: Meeting) -> String {
        if let s = m.aiStep, LocalAI.isQueueText(s) { return s }
        if let s = m.aiStep, s.contains("大D的主機") { return "大D的主機整理中".tr }
        return m.aiStep?.tr ?? m.statusStyle.label
    }

    private var hasWatch: Bool {
        switch link.watchState {
        case .reachable, .connected, .notInstalled: return true
        default: return false
        }
    }

    private var engineLabel: String {
        if LocalAI.active { return LocalAI.modelLabel }
        if MeetingStore.hasGeminiKey && !MeetingStore.privacyMode { return "Google Gemini" }
        return "Apple（手機上）".tr
    }

    static func minutes(_ sec: Double) -> String {
        let m = Int(sec / 60)
        if m >= 60 { return "\(m / 60)h \(m % 60)m" }
        if m >= 1 { return "\(m)m" }
        if sec >= 1 { return "\(Int(sec))s" }
        return "0"
    }
}

/// 「開始錄音」卡：主要動作一個（紅色錄音鍵）、辨識語言、其他記錄方式（飲食／通話／匯入）。
private struct CaptureCard: View {
    let lang: SpeechLang
    let dialect: HakkaDialect
    let setLang: (SpeechLang) -> Void
    let setDialect: (HakkaDialect) -> Void
    let record: () -> Void
    let food: () -> Void
    let call: () -> Void
    let importFile: () -> Void

    var body: some View {
        GlassCard {
            Button(action: record) {
                HStack(spacing: 14) {
                    Circle().fill(Brand.recording).frame(width: 56, height: 56)
                        .overlay(Circle().fill(Color.white).frame(width: 18, height: 18))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("開始錄音".tr).font(.titleMedium).foregroundStyle(Brand.text)
                        Text("會議、課堂、訪談、靈感".tr).font(.bodySmall).foregroundStyle(Brand.textMuted)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("開始錄音".tr)

            VStack(alignment: .leading, spacing: 6) {
                LangPicker(value: lang, pick: setLang)
                Text(LangPicker.hint(lang)).font(.labelMedium).foregroundStyle(Brand.textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.trailing, lang == .hak ? 110 : 0)
                    .overlay(alignment: .trailing) {
                    // 一直留在畫面上只是藏起來（iOS 16 反覆插入／拿掉 Menu 會點不動）
                    DialectMenu(value: dialect, pick: setDialect)
                        .opacity(lang == .hak ? 1 : 0)
                        .allowsHitTesting(lang == .hak)
                        .accessibilityHidden(lang != .hak)
                    }
                .padding(.horizontal, 4)
            }
            .padding(.top, 14)

            HStack(spacing: 8) {
                mini("飲食".tr, food) { BowlGlyph(color: Brand.cyan).frame(width: 18, height: 18) }
                mini("通話".tr, call) { Image(systemName: "phone").font(.system(size: 15)).foregroundStyle(Brand.cyan) }
                mini("匯入".tr, importFile) { Image(systemName: "square.and.arrow.down").font(.system(size: 15)).foregroundStyle(Brand.cyan) }
                mini("翻譯".tr, { TranslatePage.shared.open() }) { Text("文A").font(.labelMedium).foregroundStyle(Brand.cyan) }
            }
            .padding(.top, 12)
        }
    }

    private func mini<Icon: View>(_ title: String, _ action: @escaping () -> Void, @ViewBuilder icon: () -> Icon) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                icon()
                Text(title).font(.bodyMedium).foregroundStyle(Brand.text)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Brand.surface))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Brand.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// 今天的記錄一列：時間、語言或「餐」標籤、標題、摘要。
private struct TodayRow: View {
    let m: Meeting
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(Fmt.stamp(m.createdAt, "HH:mm")).font(.labelMedium).monospacedDigit().foregroundStyle(Brand.textMuted)
                .frame(width: 44, alignment: .leading).padding(.top, 2)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(m.isFood ? "餐".tr : m.speechLang.tag)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(m.isFood ? Brand.success : Brand.textMuted)
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(RoundedRectangle(cornerRadius: 4).fill(m.isFood ? Brand.success.opacity(0.12) : Brand.surfaceHigh))
                    Text(m.title).font(.titleSmall).foregroundStyle(Brand.text).lineLimit(1)
                }
                if let line = subline {
                    Text(line).font(.bodySmall).foregroundStyle(Brand.textMuted).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .contentShape(Rectangle())
    }

    private var subline: String? {
        if m.isFood, let n = m.nutrition { return "{0} 大卡 · 蛋白質 {1} g · 碳水 {2} g".trf(n.kcal, n.protein, n.carbs) }
        if m.status == .failed { return "處理失敗，點開重試".tr }
        return m.summary
    }
}

/// 沒有大寫、沒有等寬字的小標題（新設計用）。
struct PlainLabel: View {
    let text: String
    var body: some View { Text(text).font(.labelLarge).foregroundStyle(Brand.textMuted) }
}

/// 手機錄音中（主頁收起時）：計時、語言、音量條、停止鍵。
private struct RecordingCard: View {
    let food: Bool
    let since: Date
    let interrupted: Bool
    let lang: String
    let detailed: Bool
    let stop: () -> Void

    var body: some View {
        GlassCard {
            HStack(spacing: 8) {
                Circle().fill(Brand.recording).frame(width: 8, height: 8)
                Text(food ? "正在記錄飲食".tr : (Meeting.isPad ? "iPad 錄音中".tr : "手機錄音中".tr))
                    .font(.labelLarge).foregroundStyle(Brand.recording)
                Text(lang).font(.labelMedium).foregroundStyle(Brand.cyan)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(RoundedRectangle(cornerRadius: 6).fill(Brand.cyan.opacity(0.1)))
                Spacer()
            }
            HStack(alignment: .center) {
                TimelineView(.periodic(from: since, by: 1)) { ctx in
                    Text(Fmt.duration(ctx.date.timeIntervalSince(since)))
                        .font(.system(size: 40, weight: .semibold)).monospacedDigit().foregroundStyle(Brand.text)
                }
                Spacer()
                Button(action: stop) {
                    RoundedRectangle(cornerRadius: 5).fill(Color.white).frame(width: 20, height: 20)
                        .frame(width: 60, height: 60)
                        .background(Circle().fill(Brand.recording))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("停止並產生報告".tr)
            }
            .padding(.top, 8)
            LevelMeter(paused: interrupted, detailed: detailed, onSurface: true)
                .padding(.top, 12)
            Text("可以鎖螢幕，會繼續錄；停止後自動整理".tr).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 10)
            if !food {
                GhostButton(text: "即時翻譯".tr, tint: Brand.cyan) { TranslatePage.shared.open() }.padding(.top, 10)
            }
        }
    }
}

/// 「今天」時間軸上的非錄音項目（即時翻譯、遠距對話）。
struct TimelineExtra {
    let at: Date, tag: String, title: String, sub: String?
    let action: () -> Void
}

private struct TimelineExtraRow: View {
    let e: TimelineExtra
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(Fmt.stamp(e.at, "HH:mm")).font(.labelMedium).monospacedDigit().foregroundStyle(Brand.textMuted)
                .frame(width: 44, alignment: .leading).padding(.top, 2)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(e.tag).font(.system(size: 11, weight: .semibold)).foregroundStyle(Brand.cyan)
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(RoundedRectangle(cornerRadius: 4).fill(Brand.cyan.opacity(0.12)))
                    Text(e.title).font(.titleSmall).foregroundStyle(Brand.text).lineLimit(1)
                }
                if let s = e.sub, !s.isEmpty { Text(s).font(.bodySmall).foregroundStyle(Brand.textMuted).lineLimit(1) }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .contentShape(Rectangle())
    }
}

/// 今日回顧（晚上 9 點後）：幾場錄音、熱量與蛋白質達標、還沒做的待辦。
struct DayRecap {
    let recordings: Int, translations: Int, kcal: Int, kcalGoal: Int, protein: Int, proteinGoal: Int
    let usesFood: Bool, openTodos: [String]
}

struct DayRecapCard: View {
    let r: DayRecap
    let openRecords: () -> Void
    let openFood: () -> Void

    var body: some View {
        GlassCard {
            Text("今日回顧".tr).font(.titleMedium).foregroundStyle(Brand.text)
            HStack(spacing: 10) {
                stat("{0} 場".trf(r.recordings), "錄音整理".tr, openRecords)
                if r.usesFood {
                    stat(pct(r.kcal, r.kcalGoal), "熱量 {0} / {1}".trf(r.kcal, r.kcalGoal), openFood)
                    stat(pct(r.protein, r.proteinGoal), "蛋白質 {0} / {1} g".trf(r.protein, r.proteinGoal), openFood)
                } else {
                    stat("{0} 次".trf(r.translations), "翻譯".tr, {})
                }
            }
            .padding(.vertical, 10)
            if r.openTodos.isEmpty {
                Text("今天的待辦都處理完了，早點休息。".tr).font(.bodySmall).foregroundStyle(Brand.textMuted)
            } else {
                Text("明天記得：".tr).font(.labelLarge).foregroundStyle(Brand.textMuted)
                ForEach(r.openTodos.prefix(3), id: \.self) { t in
                    Text("・" + t).font(.bodySmall).foregroundStyle(Brand.text).lineLimit(1)
                }
                if r.openTodos.count > 3 {
                    Text("還有 {0} 項".trf(r.openTodos.count - 3)).font(.labelSmall).foregroundStyle(Brand.textFaint)
                }
            }
        }
    }

    private func pct(_ v: Int, _ goal: Int) -> String { goal <= 0 ? "—" : "\(v * 100 / goal)%" }

    private func stat(_ big: String, _ small: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(big).font(.titleLarge).fontWeight(.semibold).foregroundStyle(Brand.text)
                Text(small).font(.labelSmall).foregroundStyle(Brand.textMuted).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 12).fill(Brand.surfaceHigh))
        }
        .buttonStyle(.plain)
    }
}

/// 「今天」兩種排布共用：時間軸上的翻譯、遠距對話，與今日回顧的數字。
@MainActor
enum TodayData {
    static func extras(select: @escaping (Tab) -> Void) -> [TimelineExtra] {
        let tr = TranslationStore.shared.records.filter { Calendar.current.isDateInToday($0.start) && $0.meetingId == nil && !$0.lines.isEmpty }.map { r in
            TimelineExtra(at: r.start, tag: "翻譯".tr, title: r.sourceLabel + " → " + r.targetLabel, sub: r.preview) {
                UserDefaults.standard.set(1, forKey: "hub_" + Pillar.translate.rawValue)
                select(.translate)
            }
        }
        let tk = TalkStore.shared.today().map { t in
            TimelineExtra(at: t.startedAt, tag: "遠距".tr, title: t.partners.isEmpty ? "遠距對話".tr : t.partners.joined(separator: "、"),
                          sub: "{0} 句".trf(t.lines.count)) { TalkPage.shared.presented = true }
        }
        return (tr + tk).sorted { $0.at > $1.at }
    }

    static func recap(_ store: MeetingStore) -> DayRecap {
        let today = store.meetings.filter { Calendar.current.isDateInToday($0.createdAt) && $0.status == .done }
        let food = today.filter(\.isFood).compactMap(\.nutrition)
        let open = ActionItems.all(store).filter { Calendar.current.isDateInToday($0.0.createdAt) }
            .flatMap { $0.1 }.filter { !ActionItems.done.contains($0.key) }.map(\.task)
        return DayRecap(recordings: today.filter { !$0.isFood }.count,
                        translations: TranslationStore.shared.records.filter { Calendar.current.isDateInToday($0.start) }.count,
                        kcal: food.reduce(0) { $0 + $1.kcal }, kcalGoal: FoodGoals.kcal,
                        protein: food.reduce(0) { $0 + $1.protein }, proteinGoal: FoodGoals.protein,
                        usesFood: store.meetings.contains(where: \.isFood), openTodos: open)
    }
}

/// 今天的翻譯、遠距對話（總覽排布用；快錄排布直接排進「今天的記錄」）。
struct TodayExtrasCard: View {
    let extras: [TimelineExtra]
    var body: some View {
        if !extras.isEmpty {
            VStack(spacing: 0) {
                ForEach(Array(extras.enumerated()), id: \.offset) { i, e in
                    Button(action: e.action) { TimelineExtraRow(e: e) }.buttonStyle(.plain)
                    if i < extras.count - 1 { Rectangle().fill(Brand.outline).frame(height: 1).padding(.leading, 68) }
                }
            }
            .background(RoundedRectangle(cornerRadius: Brand.cardRadius, style: .continuous).fill(Brand.surface))
            .overlay(RoundedRectangle(cornerRadius: Brand.cardRadius, style: .continuous).stroke(Brand.outline, lineWidth: 1))
        }
    }
}
