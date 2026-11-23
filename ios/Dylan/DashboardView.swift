import SwiftUI

/// 「主頁」分頁（照 Android `ui/DashboardScreen.kt`）：問候 + 日期、手錶連線、快捷鍵、今天統計、處理中、最新報告。
struct DashboardView: View {
    let open: (String) -> Void
    let select: (Tab) -> Void
    @Binding var toast: String?
    @EnvironmentObject private var store: MeetingStore
    @EnvironmentObject private var link: PhoneLink
    @ObservedObject private var rec = PhoneRecorder.shared

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
                    }
                    Spacer()
                    WatchPill(state: link.watchState) { select(.settings) }
                }

                // AI 設定只放在「設定」分頁（使用者要求），主頁不顯示提醒卡。

                // ---------- 快捷動作 ----------
                // 手機也能錄（手錶不在身邊、或這支手機沒配對手錶時）
                if rec.isRecording, let since = rec.since {
                    RecordingCard(food: rec.isFood, since: since, interrupted: rec.interrupted) { rec.stop() }
                } else {
                    HStack(spacing: 12) {
                        QuickTile(title: "開始錄音", sub: "會議、課堂、訪談、靈感", filled: true) { rec.start() }
                        QuickTile(title: "記錄飲食", sub: "說出吃了什麼", filled: false) { rec.start(food: true) }
                    }
                }

                // ---------- 今天 ----------
                GlassCard {
                    Text("今天").font(.labelSmall).tracking(1.2).foregroundStyle(Brand.textMuted)
                    HStack(spacing: 10) {
                        StatTile(value: "\(todayRec.count)", label: "段錄音")
                            .onTapGesture { select(.records) }
                        StatTile(value: Self.minutes(todayRec.reduce(0) { $0 + $1.durationSec }), label: "錄音時長")
                            .onTapGesture { select(.records) }
                        if store.meetings.contains(where: \.isFood) {
                            StatTile(value: "\(FoodView.todayKcal(store.meetings))/\(FoodView.goal)", label: "大卡")
                                .onTapGesture { select(.food) }
                        }
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
                }

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
                        Text("\(latest.sourceLabel) · \(Fmt.shortWhen(latest.createdAt)) · \(Fmt.duration(latest.durationSec))")
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
            .pageColumn()
            .padding(.vertical, 12)
        }
        .refreshable { store.reload() }
        .onChange(of: rec.state) { s in
            switch s {
            case .denied: toast = "沒有麥克風權限：到 iPhone 設定 → 大D → 麥克風 打開"; rec.clearMessage()
            case .failed(let msg): toast = msg; rec.clearMessage()
            default: break
            }
        }
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
    let action: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                RecordDot(size: 20)
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
    let stop: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
        HStack(spacing: 14) {
            RecordDot(size: 20)
                .frame(width: 44, height: 44)
                .background(Circle().fill(Color.white.opacity(0.18)))
            VStack(alignment: .leading, spacing: 2) {
                Text(food ? "正在記錄飲食" : "手機錄音中").font(.titleMedium).foregroundColor(.white)
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
