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

    var body: some View {
        let todays = store.meetings.filter { Calendar.current.isDateInToday($0.createdAt) && $0.status != .recording }
        let busy = store.meetings.filter { $0.statusStyle.busy && $0.status != .recording }
        let kcal = FoodView.todayKcal(store.meetings)

        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // ---------- 日期 + 今天 + 狀態 ----------
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(Fmt.stamp(Date(), "M 月 d 日 EEEE")).font(.bodySmall).foregroundStyle(Brand.textMuted)
                        Text("今天").font(.system(size: 30, weight: .bold)).foregroundStyle(Brand.text)
                        if mode == .tech {
                            Text("AI：" + engineLabel).font(.labelMedium).foregroundStyle(Brand.textFaint).lineLimit(1)
                        }
                    }
                    Spacer()
                    if mode == .tech || hasWatch {
                        WatchPill(state: link.watchState) { select(.settings) }
                    }
                }

                TwoColumns(wide: Wide.twoColumns(width)) {
                // ---------- 錄音：一張卡放開始錄音、語言、其他記錄方式 ----------
                if rec.isRecording, let since = rec.since {
                    RecordingCard(food: rec.isFood, since: since, interrupted: rec.interrupted,
                                  lang: lang == .hak ? "客語・" + dialect.label : lang.short,
                                  detailed: mode == .tech) { rec.stop() }
                } else {
                    CaptureCard(lang: lang, dialect: dialect, setLang: { langs.lang = $0 }, setDialect: { langs.dialect = $0 },
                                record: { rec.start() }, food: { rec.start(food: true) },
                                call: { BroadcastPicker.open() }, importFile: { picking = true })
                        .background(BroadcastPicker().frame(width: 1, height: 1))
                }
                } right: {
                // ---------- 處理中 ----------
                if !busy.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        PlainLabel(text: "處理中")
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

                // ---------- 今天的記錄 ----------
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        PlainLabel(text: "今天的記錄")
                        Spacer()
                        if store.meetings.contains(where: \.isFood) {
                            Button { select(.food) } label: {
                                Text("\(kcal) / \(FoodView.goal) 大卡").font(.bodySmall).foregroundStyle(Brand.textMuted)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    if todays.isEmpty {
                        GlassCard {
                            Text("今天還沒有記錄").font(.titleSmall).foregroundStyle(Brand.text)
                            Text("按「開始錄音」或手錶的錄音鍵，錄完會自動整理出重點、待辦與逐字稿。")
                                .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)
                        }
                    } else {
                        VStack(spacing: 0) {
                            ForEach(Array(todays.enumerated()), id: \.element.id) { i, m in
                                Button { open(m.id) } label: { TodayRow(m: m) }.buttonStyle(.plain)
                                if i < todays.count - 1 { Rectangle().fill(Brand.outline).frame(height: 1).padding(.leading, 68) }
                            }
                        }
                        .background(RoundedRectangle(cornerRadius: Brand.cardRadius, style: .continuous).fill(Brand.surface))
                        .overlay(RoundedRectangle(cornerRadius: Brand.cardRadius, style: .continuous).stroke(Brand.outline, lineWidth: 1))
                    }
                    Button { select(.records) } label: {
                        Text("所有記錄 →").font(.labelLarge).foregroundStyle(Brand.cyan)
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
        .fileImporter(isPresented: $picking, allowedContentTypes: AudioImport.types, allowsMultipleSelection: true) { result in
            guard case let .success(urls) = result, !urls.isEmpty else { return }
            Task {
                var errors: [String] = []
                for u in urls { if let e = await AudioImport.run(u) { errors.append(e) } }
                let ok = urls.count - errors.count
                toast = errors.isEmpty ? "已匯入 \(ok) 個檔，正在產生報告"
                    : (ok > 0 ? "已匯入 \(ok) 個；" : "") + errors.joined(separator: "；")
            }
        }
        .onAppear { if !askedName && DeviceInfo.name.isEmpty { naming = true } }
        .alert(Meeting.isPad ? "這台 iPad 叫什麼名字？" : "這支手機叫什麼名字？", isPresented: $naming) {
            TextField(Meeting.isPad ? "例如：Dylan 的 iPad" : "例如：Dylan 的 iPhone", text: $nameInput)
            Button("儲存") {
                deviceName = nameInput.trimmingCharacters(in: .whitespacesAndNewlines)
                askedName = true
            }
            Button("以後再說", role: .cancel) { askedName = true }
        } message: {
            Text("會顯示在錄音的「來源」，分得出是哪一台錄的。之後可以在「設定 → 這台裝置」改。")
        }
        .onChange(of: rec.state) { s in
            switch s {
            case .denied: toast = "沒有麥克風權限：到 iPhone 設定 → 大D → 麥克風 打開"; rec.clearMessage()
            case .failed(let msg): toast = msg; rec.clearMessage()
            default: break
            }
        }
    }

    /// 處理中卡片下面那一行：排隊位置、或在哪一步。
    static func busyText(_ m: Meeting) -> String {
        if let s = m.aiStep, s.hasPrefix("排隊中") || s.hasPrefix("輪到你了") { return s }
        if let s = m.aiStep, s.contains("大D的主機") { return "大D的主機整理中" }
        return m.aiStep ?? m.statusStyle.label
    }

    private var hasWatch: Bool {
        switch link.watchState {
        case .reachable, .connected, .notInstalled: return true
        default: return false
        }
    }

    private var engineLabel: String {
        if LocalAI.configured { return LocalAI.modelLabel }
        if MeetingStore.hasGeminiKey && !MeetingStore.privacyMode { return "Google Gemini" }
        return "Apple（手機上）"
    }

    private var greeting: String {
        switch Calendar.current.component(.hour, from: Date()) {
        case 5...10: return "早安"
        case 11...13: return "午安"
        case 14...17: return "下午好"
        default: return "晚安"
        }
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
                        Text("開始錄音").font(.titleMedium).foregroundStyle(Brand.text)
                        Text("會議、課堂、訪談、靈感").font(.bodySmall).foregroundStyle(Brand.textMuted)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("開始錄音")

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
                mini("飲食", food) { BowlGlyph(color: Brand.cyan).frame(width: 18, height: 18) }
                mini("通話", call) { Image(systemName: "phone").font(.system(size: 15)).foregroundStyle(Brand.cyan) }
                mini("匯入", importFile) { Image(systemName: "square.and.arrow.down").font(.system(size: 15)).foregroundStyle(Brand.cyan) }
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
                    Text(m.isFood ? "餐" : m.speechLang.tag)
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
        if m.isFood, let n = m.nutrition { return "\(n.kcal) 大卡 · 蛋白質 \(n.protein) g · 碳水 \(n.carbs) g" }
        if m.status == .failed { return "處理失敗，點開重試" }
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
                Text(food ? "正在記錄飲食" : (Meeting.isPad ? "iPad 錄音中" : "手機錄音中"))
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
                .accessibilityLabel("停止並產生報告")
            }
            .padding(.top, 8)
            LevelMeter(paused: interrupted, detailed: detailed, onSurface: true)
                .padding(.top, 12)
            Text("可以鎖螢幕，會繼續錄；停止後自動整理").font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 10)
        }
    }
}
