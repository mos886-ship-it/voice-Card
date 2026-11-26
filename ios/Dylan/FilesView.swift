import SwiftUI

/// 「資料庫 → 管理」（照 Android `ui/FilesScreen.kt` 的「檔案」分頁）：一行摘要、雲端備份卡、全部／錄音／飲食篩選、
/// 每一筆的大小；點一筆可以打開、分享錄音檔（m4a）、只刪錄音檔（保留報告）、刪除整筆（都要先確認）。
struct FilesView: View {
    let open: (String) -> Void
    @Binding var toast: String?
    var onClose: (() -> Void)? = nil
    @EnvironmentObject private var store: MeetingStore
    @State private var kind = Kind.all
    @State private var sizes: [String: Size] = [:]
    @State private var acting: Meeting?
    @State private var confirm: (meeting: Meeting, audioOnly: Bool)?

    enum Kind: String, CaseIterable { case all = "全部", meetings = "錄音", food = "飲食" }

    /// 一筆佔多少：錄音檔、其他（報告、逐字稿、meta…）。
    struct Size: Equatable { var audio: Int64 = 0; var other: Int64 = 0 }

    var body: some View {
        let all = store.meetings
        let shown = all.filter { kind == .all || (kind == .food) == $0.isFood }
        let audio = all.reduce(Int64(0)) { $0 + (sizes[$1.id]?.audio ?? 0) }
        let other = all.reduce(Int64(0)) { $0 + (sizes[$1.id]?.other ?? 0) }

        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("管理檔案".tr).font(.headlineSmall).foregroundStyle(Brand.text)
                        Text("{0} 筆 · 錄音檔 {1} · 報告等 {2}".trf(all.count, Self.bytes(audio), Self.bytes(other)))
                            .font(.bodySmall).foregroundStyle(Brand.textMuted)
                    }
                    Spacer()
                    if let onClose {
                        Button("完成".tr, action: onClose).font(.labelLarge).foregroundStyle(Brand.cyan).frame(minHeight: 44)
                    }
                }

                GlassCard {
                    HStack(spacing: 8) {
                        Image(systemName: "icloud.and.arrow.up").foregroundStyle(Brand.cyan)
                        Text("雲端備份".tr).font(.titleSmall).foregroundStyle(Brand.text)
                    }
                    Text("Google 雲端硬碟備份（跟 Android 共用同一份）還在準備中。錄音和報告目前只存在這台裝置上，刪除前請先確認。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)
                }

                HStack(spacing: 8) {
                    ForEach(Kind.allCases, id: \.self) { k in
                        let n = k == .all ? all.count : all.filter { (k == .food) == $0.isFood }.count
                        FilterChip(text: "\(k.rawValue.tr) \(n)", selected: kind == k) { kind = k }
                    }
                }

                if shown.isEmpty {
                    Text("沒有檔案".tr).font(.bodyMedium).foregroundStyle(Brand.textMuted).padding(.vertical, 24)
                }
                ForEach(shown) { m in
                    row(m)
                        .contentShape(Rectangle())
                        .onTapGesture { acting = m }
                }
                Spacer(minLength: 24)
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
        .refreshable { store.reload(); measure() }
        .task(id: all.map(\.id)) { measure() }
        .confirmationDialog(acting.map { $0.title } ?? "", isPresented: Binding(get: { acting != nil }, set: { if !$0 { acting = nil } }),
                            titleVisibility: .visible, presenting: acting) { m in
            Button("打開".tr) { open(m.id) }
            if hasAudio(m) {
                ShareLink(item: MeetingStore.audioURL(m.id)) { Text("分享錄音檔（m4a）".tr) }
                Button("只刪錄音檔（保留報告）".tr, role: .destructive) { confirm = (m, true) }
            }
            Button("刪除整筆".tr, role: .destructive) { confirm = (m, false) }
            Button("關閉".tr, role: .cancel) {}
        }
        .alert(confirm.map { $0.audioOnly ? "只刪錄音檔？".tr : "刪除整筆？".tr } ?? "",
               isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }), presenting: confirm) { c in
            Button("刪除".tr, role: .destructive) {
                if c.audioOnly {
                    store.deleteAudio(c.meeting.id)
                    toast = "已刪除「{0}」的錄音檔".trf(c.meeting.title)
                } else {
                    store.delete(c.meeting.id)
                    toast = "已刪除「{0}」".trf(c.meeting.title)
                }
                measure()
            }
            Button("返回".tr, role: .cancel) {}
        } message: { c in
            Text((c.audioOnly ? "報告和逐字稿會留著，但之後不能再播放、也不能重新產生報告。".tr : "錄音、報告、逐字稿都會從這台裝置刪除。".tr)
                 + "\n還沒有雲端備份，刪除後無法復原。".tr)
        }
    }

    private func row(_ m: Meeting) -> some View {
        let s = sizes[m.id] ?? Size()
        return GlassCard(padding: 14) {
            Text(m.title).font(.titleSmall).foregroundStyle(Brand.text).lineLimit(2)
            Text([Fmt.stamp(m.createdAt, "M/d HH:mm"), m.isFood ? "飲食".tr : nil,
                  s.audio == 0 ? "沒有錄音檔 · {0}".trf(Self.bytes(s.other)) : "錄音檔 {0} · 報告等 {1}".trf(Self.bytes(s.audio), Self.bytes(s.other))]
                    .compactMap { $0 }.joined(separator: " · "))
                .font(.labelMedium).foregroundStyle(Brand.textFaint).padding(.top, 2)
        }
    }

    private func hasAudio(_ m: Meeting) -> Bool {
        m.status != .recording && FileManager.default.fileExists(atPath: MeetingStore.audioURL(m.id).path)
    }

    /// 算每一筆資料夾的大小（錄音檔 = audio.m4a 與分段；其他都算報告等）。
    private func measure() {
        let ids = store.meetings.map(\.id)
        Task.detached(priority: .utility) {
            var out: [String: Size] = [:]
            for id in ids { out[id] = Self.size(of: id) }
            await MainActor.run { sizes = out }
        }
    }

    nonisolated static func size(of id: String) -> Size {
        var s = Size()
        let dir = MeetingStore.dir(id)
        guard let e = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey]) else { return s }
        for case let u as URL in e {
            guard let v = try? u.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey]), v.isRegularFile == true else { continue }
            let n = Int64(v.fileSize ?? 0)
            let audio = ["m4a", "wav", "caf"].contains(u.pathExtension.lowercased())
            if audio { s.audio += n } else { s.other += n }
        }
        return s
    }

    static func bytes(_ n: Int64) -> String {
        let f = ByteCountFormatter()
        f.countStyle = .file
        return f.string(fromByteCount: n)
    }
}
