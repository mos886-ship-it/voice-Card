import SwiftUI

/// 底部分頁（照 Android `ui/BottomNav.kt`）：主頁 / 錄音 / 飲食 / 設定，從詳情返回回到原分頁。
enum Tab: String, CaseIterable {
    case home, records, food, settings

    var label: String {
        switch self {
        case .home: return "主頁"
        case .records: return "錄音"
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
            .background(Brand.backdrop.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: String.self) { id in
                DetailView(id: id) { if !path.isEmpty { path.removeLast() } }
                    .toolbar(.hidden, for: .navigationBar)
            }
        }
        .toast($toast)
    }

    private func open(_ id: String) { path.append(id) }
    private func select(_ t: Tab) { tab = t }
}

struct BottomNav: View {
    let current: Tab
    let onSelect: (Tab) -> Void

    var body: some View {
        VStack(spacing: 0) {
            Rectangle().fill(Brand.outline).frame(height: 1)
            HStack {
                ForEach(Tab.allCases, id: \.self) { t in
                    NavItem(tab: t, selected: t == current) { onSelect(t) }
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Brand.bg.ignoresSafeArea(edges: .bottom))
        }
    }
}

private struct NavItem: View {
    let tab: Tab
    let selected: Bool
    let action: () -> Void

    var body: some View {
        let tint = selected ? Color.white : Brand.textMuted
        Button(action: action) {
            VStack(spacing: 4) {
                ZStack {
                    // 選中的分頁：圖示後面一個漸層膠囊
                    if selected { Capsule().fill(Brand.accent) }
                    icon(tint)
                }
                .frame(width: 56, height: 30)
                Text(tab.label).font(.labelMedium).foregroundStyle(selected ? Brand.text : Brand.textMuted)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tab.label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder private func icon(_ tint: Color) -> some View {
        switch tab {
        case .home: Image(systemName: "house.fill").font(.system(size: 17)).foregroundStyle(tint)
        case .records: Waveform(color: tint).frame(width: 20, height: 16)
        case .food: BowlGlyph(color: tint).frame(width: 20, height: 20)
        case .settings: Image(systemName: "gearshape.fill").font(.system(size: 17)).foregroundStyle(tint)
        }
    }
}
