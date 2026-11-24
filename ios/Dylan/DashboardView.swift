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
    @AppStorage(SpeechLang.key) private var lang: SpeechLang = .zh
    @AppStorage(DeviceInfo.askedKey) private var askedName = false
    @AppStorage(DeviceInfo.key) private var deviceName = ""
    @State private var naming = false
    @State private var nameInput = ""

    var body: some View {
        let recordings = store.meetings.filter { !$0.isFood }
        let todayRec = recordings.filter { Calendar.current.isDateInToday($0.createdAt) }
        let busy = store.meetings.filter { $0.statusStyle.busy && $0.status != .recording }

        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // ---------- 問候 + 手錶 ----------
                HStack(spacing: 12) {
                    BrandMark(size: 40)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(greeting).font(.headlineSmall).foregroundStyle(Brand.text)
                        Text(Fmt.stamp(Date(), "M 月 d 日 EEEE")).font(.bodySmall).foregroundStyle(Brand.textMuted)
                        if mode == .tech {
                            Text("AI：" + engineLabel).font(.labelMedium).foregroundStyle(Brand.textFaint).lineLimit(1)
                        }
                    }
                    Spacer()
                    if mode == .tech || hasWatch {
                        WatchPill(state: link.watchState) { select(.settings) }
                    }
                }

                // AI 設定只放在「設定」分頁（使用者要求），主頁不顯示提醒卡。
                TwoColumns(wide: Wide.twoColumns(width)) {

                // ---------- 快捷動作 ----------
                // 手機也能錄（手錶不在身邊、或這支手機沒配對手錶時）
                if rec.isRecording, let since = rec.since {
                    RecordingCard(food: rec.isFood, since: since, interrupted: rec.interrupted, lang: lang) { rec.stop() }
                } else {
                    VStack(alignment: .leading, spacing: 6) {
                        LangPicker(value: lang) { lang = $0 }
                        Text(lang == .zh ? "國語：最快" : "有台語就選這個（國台語夾雜也是），比較慢")
                            .font(.labelMedium).foregroundStyle(Brand.textFaint).padding(.horizontal, 6)
                    }
                    HStack(spacing: 12) {
                        QuickTile(title: "開始錄音", sub: "會議、課堂、訪談、靈感", filled: true) { rec.start() }
                        QuickTile(title: "記錄飲食", sub: "說出吃了什麼", filled: false, bowl: true) { rec.start(food: true) }
                    }
                }
                CallTile(detailed: mode == .tech) { BroadcastPicker.open() }
                    .background(BroadcastPicker().frame(width: 1, height: 1))

                // ---------- 今天 ----------
                GlassCard {
                    Text("今天").font(.labelSmall).tracking(1.2).foregroundStyle(Brand.textMuted)
                    HStack(spacing: 10) {
                        StatTile(value: "\(todayRec.count)", label: "段錄音")
                            .onTapGesture { select(.records) }
                        StatTile(value: Fmt.human(todayRec.reduce(0) { $0 + $1.durationSec }), label: "錄音時長")
                            .onTapGesture { select(.records) }
                        if store.meetings.contains(where: \.isFood) {
                            StatTile(value: "\(FoodView.todayKcal(store.meetings))/\(FoodView.goal)", label: "大卡")
                                .onTapGesture { select(.food) }
                        }
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
                }

                } right: {
                // ---------- 處理中 ----------
                if !busy.isEmpty {
                    SectionLabel(text: "處理中")
                    ForEach(busy) { m in
                        GlassCard(padding: 14) {
                            HStack {
                                Text(m.title).font(.titleSmall).foregroundStyle(Brand.text).lineLimit(1)
                                Spacer()
                                StatusChip(style: m.statusStyle)
                            }
                            ScanningBar().padding(.top, 10)
                        }
                        .onTapGesture { open(m.id) }
                    }
                }

                // ---------- 最新報告（沒有報告時顯示最新錄音） ----------
                if let latest = recordings.first(where: { $0.status == .done }) ?? recordings.first {
                    SectionLabel(text: latest.status == .done ? "最新報告" : "最新錄音")
                    GlassCard {
                        Text(latest.title).font(.titleMedium).foregroundStyle(Brand.text).lineLimit(2)
                        Text("\(latest.sourceLabel) · \(Fmt.shortWhen(latest.createdAt))" + (latest.durationSec < 1 ? "" : " · \(Fmt.human(latest.durationSec))"))
                            .font(.bodySmall).foregroundStyle(Brand.textFaint)
                        let summary = latest.status == .done ? GeneratedReport.summarySection(store.report(latest.id) ?? "") : ""
                        if !summary.isEmpty {
                            Text(summary).font(.bodyMedium).foregroundStyle(Brand.textMuted).lineLimit(4).padding(.top, 10)
                        }
                        Text(latest.status == .done ? "查看完整報告 →" : "查看錄音 →")
                            .font(.labelLarge).foregroundStyle(Brand.cyan).padding(.top, 10)
                    }
                    .onTapGesture { open(latest.id) }
                } else {
                    GlassCard {
                        Text("開始第一段錄音").font(.titleMedium).foregroundStyle(Brand.text)
                        Text("按上面的「開始錄音」，或在手錶按下錄音。錄完會自動整理出重點、待辦與逐字稿。")
                            .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)
                    }
                }
                }
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
        .refreshable { store.reload() }
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

/// 快捷按鈕：大方塊，主要的用漸層，次要的用外框。
private struct QuickTile: View {
    let title: String
    let sub: String
    let filled: Bool
    var bowl = false
    let action: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                Group {
                    if bowl { BowlGlyph(color: Brand.success).frame(width: 22, height: 22) } else { RecordDot(size: 20) }
                }
                .frame(width: 40, height: 40)
                .background(Circle().fill(filled ? Color.white.opacity(0.18) : Brand.surfaceHigh))
                Text(title).font(.titleMedium).foregroundStyle(filled ? Color.white : Brand.text).padding(.top, 14)
                Text(sub).font(.bodySmall).foregroundStyle(filled ? Color.white.opacity(0.8) : Brand.textMuted).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(shape.fill(filled ? AnyShapeStyle(Brand.accent) : AnyShapeStyle(Brand.surface)))
            .overlay(shape.stroke(filled ? Color.clear : Brand.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// 手機錄音中：計時、停止鍵。
private struct RecordingCard: View {
    let food: Bool
    let since: Date
    let interrupted: Bool
    let lang: SpeechLang
    let stop: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
        HStack(spacing: 14) {
            RecordDot(size: 20)
                .frame(width: 44, height: 44)
                .background(Circle().fill(Color.white.opacity(0.18)))
            VStack(alignment: .leading, spacing: 2) {
                Text((food ? "正在記錄飲食" : (Meeting.isPad ? "iPad 錄音中" : "手機錄音中")) + " · " + lang.short)
                    .font(.titleMedium).foregroundColor(.white)
                TimelineView(.periodic(from: since, by: 1)) { ctx in
                    Text(interrupted ? "收音中斷，正在恢復…" : Fmt.duration(ctx.date.timeIntervalSince(since)))
                        .font(.bodySmall).monospacedDigit().foregroundColor(Color.white.opacity(0.85))
                }
                Text("可以鎖螢幕，會繼續錄").font(.bodySmall).foregroundColor(Color.white.opacity(0.7))
            }
            Spacer()
            Button(action: stop) {
                Text("停止").font(.labelLarge).foregroundColor(Brand.text)
                    .padding(.horizontal, 18).padding(.vertical, 10)
                    .background(Capsule().fill(Color.white))
            }
            .buttonStyle(.plain)
        }
        .padding(16)
        .background(shape.fill(Brand.accent))
    }
}

/// 錄通話：LINE / Zoom / Teams 等 App 裡雙方的聲音（iOS 畫面廣播，只留聲音）。
private struct CallTile: View {
    let detailed: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: "phone.bubble.fill")
                    .font(.system(size: 18)).foregroundStyle(Brand.cyan)
                    .frame(width: 40, height: 40)
                    .background(Circle().fill(Brand.surfaceHigh))
                VStack(alignment: .leading, spacing: 2) {
                    Text("錄通話 / 線上會議").font(.titleMedium).foregroundStyle(Brand.text)
                    Text("LINE、Zoom、Teams 雙方的聲音").font(.bodySmall).foregroundStyle(Brand.textMuted)
                    if detailed {
                        Text("iOS 畫面廣播只留聲音：面板選「大D」、開麥克風 → 開始廣播；按紅色狀態列停止。一般電話與 FaceTime 錄不到。")
                            .font(.labelMedium).foregroundStyle(Brand.textFaint)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Brand.surface))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Brand.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
