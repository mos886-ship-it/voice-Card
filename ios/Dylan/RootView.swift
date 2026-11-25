import SwiftUI

/// 底部分頁：今天 / 資料庫 /（中間錄音鍵）/ 飲食 / 設定。檔案管理在資料庫的「管理」。，從詳情返回回到原分頁。
enum Tab: String, CaseIterable {
    case home, records, food, settings

    var label: String {
        switch self {
        case .home: return "今天"
        case .records: return "資料庫"
        case .food: return "飲食"
        case .settings: return "設定"
        }
    }
}

struct RootView: View {
    // SceneStorage：換主題整個重畫時，仍停在原本的分頁（設定）
    @SceneStorage("tab") private var tab: Tab = .home
    @State private var path: [String] = []
    @State private var toast: String?

    var body: some View {
        GeometryReader { geo in
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                Group {
                    switch tab {
                    case .home: DashboardView(open: open, select: select, toast: $toast)
                    case .records: RecordsView(open: open, toast: $toast)
                    case .food: FoodView(toast: $toast)
                    case .settings: SettingsView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                BottomNav(current: tab, onSelect: select)
            }
            .ignoresSafeArea(.keyboard, edges: .bottom)   // 鍵盤出來時分頁列不要跟著浮上來
            .background(Brand.backdrop.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: String.self) { id in
                DetailView(id: id) { if !path.isEmpty { path.removeLast() } }
                    .toolbar(.hidden, for: .navigationBar)
            }
        }
        .environment(\.pageWidth, geo.size.width)
        }
        .toast($toast)
        .onReceive(NotificationCenter.default.publisher(for: AudioImport.didImport)) { note in
            path = []
            tab = (note.userInfo?["tab"] as? String) == "settings" ? .settings : .records
            toast = note.object as? String
        }
    }

    private func open(_ id: String) { path.append(id) }
    private func select(_ t: Tab) { tab = t }
}

struct BottomNav: View {
    let current: Tab
    let onSelect: (Tab) -> Void
    @ObservedObject private var rec = PhoneRecorder.shared

    var body: some View {
        VStack(spacing: 0) {
            Rectangle().fill(Brand.outline).frame(height: 1)
            HStack(spacing: 0) {
                NavItem(tab: .home, selected: current == .home) { onSelect(.home) }.frame(maxWidth: .infinity)
                NavItem(tab: .records, selected: current == .records) { onSelect(.records) }.frame(maxWidth: .infinity)
                RecordKey(recording: rec.isRecording) {
                    if rec.isRecording { rec.stop() } else { rec.start() }
                    onSelect(.home)   // 錄音卡在「今天」
                }
                .frame(maxWidth: .infinity)
                NavItem(tab: .food, selected: current == .food) { onSelect(.food) }.frame(maxWidth: .infinity)
                NavItem(tab: .settings, selected: current == .settings) { onSelect(.settings) }.frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 4)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background(Brand.surface.ignoresSafeArea(edges: .bottom))
        }
    }
}

/// 中間的錄音鍵：任何分頁一按就錄，錄音中變成停止鍵；長按可以選記錄飲食。
private struct RecordKey: View {
    let recording: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().fill(recording ? Brand.recording : Brand.text)
                if recording {
                    RoundedRectangle(cornerRadius: 4).fill(Color.white).frame(width: 16, height: 16)
                } else {
                    Image(systemName: "mic.fill").font(.system(size: 20, weight: .semibold)).foregroundStyle(Brand.bg)
                }
            }
            .frame(width: 52, height: 52)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(recording ? "停止錄音" : "開始錄音")
        .contextMenu {
            if !recording {
                Button { PhoneRecorder.shared.start(food: true) } label: { Label("記錄飲食", systemImage: "fork.knife") }
            }
        }
    }
}

private struct NavItem: View {
    let tab: Tab
    let selected: Bool
    let action: () -> Void

    var body: some View {
        // 選中的分頁：圖示與字用重點色（新設計不用漸層膠囊）
        let tint = selected ? Brand.cyan : Brand.textMuted
        Button(action: action) {
            VStack(spacing: 4) {
                icon(tint).frame(height: 26)
                Text(tab.label).font(.system(size: 11, weight: selected ? .semibold : .regular))
                    .foregroundStyle(tint)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tab.label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder private func icon(_ tint: Color) -> some View {
        switch tab {
        case .home: Image(systemName: "sun.max").font(.system(size: 17)).foregroundStyle(tint)
        case .records: Image(systemName: "list.bullet.rectangle").font(.system(size: 17)).foregroundStyle(tint)
        case .food: BowlGlyph(color: tint).frame(width: 20, height: 20)
        case .settings: Image(systemName: "gearshape").font(.system(size: 17)).foregroundStyle(tint)
        }
    }
}
