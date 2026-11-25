import SwiftUI

/// 「錄音」分頁（照 Android `ui/HomeScreen.kt`）：標題 + 搜尋 + 來源篩選 + 依月份分組的清單。
/// 完整 SearchIndex（報告、逐字稿、地點、說話者）在里程碑 3；目前比對標題、日期、來源。
struct RecordsView: View {
    let open: (String) -> Void
    @Binding var toast: String?
    @EnvironmentObject private var store: MeetingStore
    @State private var query = ""
    @State private var debounced = ""
    @State private var origin: String?
    @State private var picking = false
    @State private var deleting: Meeting?
    @AppStorage(UIMode.key) private var mode: UIMode = .business
    @State private var importing = false
    @State private var managing = false

    var body: some View {
        let meetings = store.meetings.filter { !$0.isFood }
        let terms = debounced.lowercased().split(whereSeparator: \.isWhitespace).map(String.init)
        let shown = meetings
            .filter { origin == nil || $0.source == origin }
            .filter { m in terms.allSatisfy { m.searchText.contains($0) } }
        let groups = Dictionary(grouping: shown) { Calendar.current.startOfDay(for: $0.createdAt) }
            .sorted { $0.key > $1.key }
        let origins = ["watch", "phone", "call", "import"].filter { o in meetings.contains { $0.source == o } }

        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                // ---------- 標題 ----------
                HStack {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("資料庫").font(.headlineSmall).foregroundStyle(Brand.text)
                        Text(meetings.isEmpty ? "會議、課堂、訪談、通話、靈感筆記"
                             : "共 \(meetings.count) 段 · \(Fmt.duration(meetings.reduce(0) { $0 + $1.durationSec }))")
                            .font(.bodySmall).foregroundStyle(Brand.textMuted)
                    }
                    Spacer()
                    // 原本的「檔案」分頁：大小、分享錄音檔、只刪錄音檔、刪除整筆
                    GhostButton(text: "管理", tint: Brand.cyan) { managing = true }
                    GhostButton(text: importing ? "匯入中…" : "匯入", systemImage: "plus", tint: Brand.cyan) {
                        if !importing { picking = true }
                    }
                    .fileImporter(isPresented: $picking, allowedContentTypes: AudioImport.types,
                                  allowsMultipleSelection: true) { result in
                        guard case let .success(urls) = result, !urls.isEmpty else { return }
                        importing = true
                        Task {
                            var errors: [String] = []
                            for u in urls { if let e = await AudioImport.run(u) { errors.append(e) } }
                            importing = false
                            let ok = urls.count - errors.count
                            toast = errors.isEmpty ? "已匯入 \(ok) 個檔，正在產生報告"
                                : (ok > 0 ? "已匯入 \(ok) 個；" : "") + errors.joined(separator: "；")
                        }
                    }
                }
                .padding(.bottom, 4)

                if !meetings.isEmpty {
                    SearchField(text: $query)
                    if origins.count > 1 {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                FilterChip(text: "全部", selected: origin == nil) { origin = nil }
                                ForEach(origins, id: \.self) { o in
                                    let n = meetings.filter { $0.source == o }.count
                                    FilterChip(text: "\(Meeting.label(source: o)) \(n)", selected: origin == o) {
                                        origin = origin == o ? nil : o
                                    }
                                }
                            }
                        }
                    }
                }

                if meetings.isEmpty {
                    EmptyState()
                } else if shown.isEmpty {
                    Text("找不到符合「\(debounced.trimmingCharacters(in: .whitespaces))」的錄音")
                        .font(.bodyMedium).foregroundStyle(Brand.textFaint).padding(.vertical, 24)
                } else if !terms.isEmpty {
                    Text("找到 \(shown.count) 筆").font(.labelLarge).foregroundStyle(Brand.cyan)
                }

                ForEach(groups, id: \.key) { day, list in
                    // 日期標題：今天 / 昨天 / 11月20日 週四
                    HStack(spacing: 8) {
                        Text(Fmt.dayLabel(day)).font(.titleSmall).foregroundStyle(Brand.text)
                        Text("\(list.count) 筆").font(.labelMedium).foregroundStyle(Brand.textFaint)
                    }
                    .padding(.top, 6)
                    CardGrid {
                        ForEach(list) { m in
                            MeetingRow(m: m, mode: mode)
                                .contentShape(Rectangle())
                                .onTapGesture { open(m.id) }
                                .contextMenu {
                                    Button { open(m.id) } label: { Label("打開", systemImage: "doc.text") }
                                    Button(role: .destructive) { deleting = m } label: { Label("刪除", systemImage: "trash") }
                                }
                        }
                    }
                }
                Spacer(minLength: 24)
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
        .refreshable { store.reload() }
        .sheet(isPresented: $managing) {
            FilesView(open: { id in managing = false; open(id) }, toast: $toast, onClose: { managing = false })
                .environmentObject(store)
                .background(Brand.bg.ignoresSafeArea())
        }
        .confirmationDialog("刪除「\(deleting?.title ?? "")」？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible) {
            Button("刪除錄音與報告", role: .destructive) {
                if let m = deleting { store.delete(m.id) }
                deleting = nil
            }
        } message: { Text("刪除後無法復原。") }
        .task(id: query) {
            // 打字停 250ms 才搜
            try? await Task.sleep(for: .milliseconds(250))
            debounced = query
        }
    }
}

private extension Meeting {
    var searchText: String {
        [title, sourceLabel, Fmt.stamp(createdAt, "yyyy/MM/dd"), Fmt.stamp(createdAt, "M/d"), Fmt.stamp(createdAt, "M月d日")]
            .joined(separator: " ").lowercased()
    }
}

/// 清單一列：圖示（依來源）、標題＋時間、一行摘要；處理中 / 失敗才顯示狀態。科技模式多一行：來源、長度、模型、耗時。
private struct MeetingRow: View {
    let m: Meeting
    let mode: UIMode

    var body: some View {
        let style = m.statusStyle
        let text = MeetingStore.isText(m.id)
        GlassCard(padding: 12) {
            HStack(alignment: .top, spacing: 12) {
                SourceIcon(source: m.source, text: text, busy: style.busy)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(m.title).font(.titleMedium).foregroundStyle(Brand.text).lineLimit(1)
                        Spacer(minLength: 4)
                        Text(Fmt.stamp(m.createdAt, "HH:mm")).font(.labelMedium).foregroundStyle(Brand.textFaint)
                    }
                    if m.status == .done, let s = m.summary, !s.isEmpty {
                        Text(s).font(.bodySmall).foregroundStyle(Brand.textMuted)
                            .lineLimit(mode == .tech ? 2 : 1)
                    } else {
                        Text(m.sourceLabel + (text ? "" : " · " + Fmt.human(m.durationSec)))
                            .font(.bodySmall).foregroundStyle(Brand.textMuted)
                    }
                    if mode == .tech { Text(details(text)).font(.labelMedium).foregroundStyle(Brand.textFaint).lineLimit(1) }
                    if m.status != .done { StatusChip(style: style).padding(.top, 4) }
                }
            }
            if style.busy { ScanningBar().padding(.top, 10) }
        }
    }

    private func details(_ text: Bool) -> String {
        var parts = [m.sourceLabel]
        if !text { parts.append(Fmt.duration(m.durationSec)) }
        if m.aiModel != nil { parts.append(Meeting.aiModelLabel(m.aiModel)) }
        if let s = m.processStartedAt, let e = m.processEndedAt { parts.append("耗時 \(Int(e.timeIntervalSince(s).rounded())) 秒") }
        return parts.joined(separator: " · ")
    }
}

/// 來源圖示：手錶、手機、通話、匯入音檔、匯入文字。
struct SourceIcon: View {
    let source: String
    var text = false
    var busy = false

    var body: some View {
        let name: String = {
            if text { return "doc.text.fill" }
            switch source {
            case "watch": return "applewatch"
            case "phone": return Meeting.isPad ? "ipad" : "iphone"
            case "call": return "phone.fill"
            case "import": return "square.and.arrow.down.fill"
            default: return "waveform"
            }
        }()
        Image(systemName: name)
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(busy ? Brand.cyan : Brand.text)
            .frame(width: 40, height: 40)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Brand.surfaceHigh))
    }
}

private struct EmptyState: View {
    var body: some View {
        GlassCard {
            VStack(spacing: 0) {
                GlyphTile(size: 64, filled: true) { Waveform(color: .white).frame(width: 28, height: 28) }
                Text("還沒有錄音").font(.titleMedium).foregroundStyle(Brand.text).padding(.top, 16)
                Text("在主頁按「開始錄音」、用手錶錄音，或按右上的「匯入」選音檔")
                    .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 20)
        }
    }
}

private struct SearchField: View {
    @Binding var text: String
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(Brand.textMuted)
            TextField("", text: $text, prompt: Text("搜尋：關鍵字、日期（空白分隔）").foregroundColor(Brand.textFaint))
                .foregroundStyle(Brand.text)
                .tint(Brand.cyan)
                .submitLabel(.search)
            if !text.isEmpty {
                Button { text = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(Brand.textMuted) }
                    .accessibilityLabel("清除")
            }
        }
        .font(.bodyLarge)
        .padding(.horizontal, 14)
        .frame(minHeight: 54)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Brand.surface))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Brand.outline, lineWidth: 1))
    }
}

struct FilterChip: View {
    let text: String
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(text).font(.labelLarge).foregroundStyle(selected ? Color.white : Brand.textMuted)
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(Capsule().fill(selected ? AnyShapeStyle(Brand.accent) : AnyShapeStyle(Brand.surface)))
                .overlay(Capsule().stroke(selected ? Color.clear : Brand.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
