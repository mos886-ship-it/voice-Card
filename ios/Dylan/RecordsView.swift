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

    var body: some View {
        let meetings = store.meetings.filter { !$0.isFood }
        let terms = debounced.lowercased().split(whereSeparator: \.isWhitespace).map(String.init)
        let shown = meetings
            .filter { origin == nil || $0.source == origin }
            .filter { m in terms.allSatisfy { m.searchText.contains($0) } }
        let groups = Dictionary(grouping: shown) { Fmt.stamp($0.createdAt, "yyyy年M月") }
            .sorted { ($0.value.first?.createdAt ?? .distantPast) > ($1.value.first?.createdAt ?? .distantPast) }
        let origins = ["watch", "phone", "import"].filter { o in meetings.contains { $0.source == o } }

        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                // ---------- 標題 ----------
                HStack {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("錄音").font(.headlineSmall).foregroundStyle(Brand.text)
                        Text(meetings.isEmpty ? "會議、課堂、訪談、通話、靈感筆記"
                             : "共 \(meetings.count) 段 · \(Fmt.duration(meetings.reduce(0) { $0 + $1.durationSec }))")
                            .font(.bodySmall).foregroundStyle(Brand.textMuted)
                    }
                    Spacer()
                    GhostButton(text: "匯入", systemImage: "plus", tint: Brand.cyan) {
                        toast = "匯入音檔下一版加上"
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

                ForEach(groups, id: \.key) { month, list in
                    // 月份標題（只有一個月、又沒在搜尋時就不顯示）
                    if groups.count > 1 || !terms.isEmpty {
                        HStack(spacing: 8) {
                            Text(month).font(.titleSmall).foregroundStyle(Brand.text)
                            Text("\(list.count) 筆").font(.labelMedium).foregroundStyle(Brand.textFaint)
                        }
                        .padding(.top, 6)
                    }
                    CardGrid {
                        ForEach(list) { m in
                            MeetingRow(m: m).onTapGesture { open(m.id) }
                        }
                    }
                }
                Spacer(minLength: 24)
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
        .refreshable { store.reload() }
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

private struct MeetingRow: View {
    let m: Meeting
    var body: some View {
        let style = m.statusStyle
        GlassCard(padding: 14) {
            HStack(spacing: 12) {
                GlyphTile { Waveform(color: style.busy ? Brand.cyan : Brand.text).frame(width: 20, height: 20) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(m.title).font(.titleMedium).foregroundStyle(Brand.text).lineLimit(1)
                    Text("\(m.sourceLabel) · \(Fmt.shortWhen(m.createdAt)) · \(Fmt.duration(m.durationSec))")
                        .font(.bodySmall).foregroundStyle(Brand.textMuted)
                    StatusChip(style: style).padding(.top, 6)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(Brand.textFaint)
            }
            if style.busy { ScanningBar().padding(.top, 12) }
        }
    }
}

private struct EmptyState: View {
    var body: some View {
        GlassCard {
            VStack(spacing: 0) {
                GlyphTile(size: 64, filled: true) { Waveform(color: .white).frame(width: 28, height: 28) }
                Text("還沒有錄音").font(.titleMedium).foregroundStyle(Brand.text).padding(.top, 16)
                Text("在手錶按下「錄音」，結束後會自動傳到這裡")
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

private struct FilterChip: View {
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
