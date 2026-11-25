import SwiftUI

/// 配色：清晰 / 暖和日常 / 深色 / 水墨。存在 UserDefaults "theme_mode"，預設清晰；舊的 light / auto 當清晰。
/// 手錶只有兩種：水墨跟手機一起，其他一律深色（OLED 省電）。
@MainActor
final class ThemeStore: ObservableObject {
    static let shared = ThemeStore()
    private static let key = "theme_mode"

    @Published var mode: ThemeMode {
        didSet {
            UserDefaults.standard.set(mode.rawValue, forKey: Self.key)
            PhoneLink.shared.broadcastTheme(watchTheme)
        }
    }

    init() {
        LayoutMode.migrate()
        mode = ThemeMode(rawValue: UserDefaults.standard.string(forKey: Self.key) ?? "") ?? .clear
    }

    func palette(system: ColorScheme) -> Palette { mode.palette }

    /// 手錶的主題："ink"（水墨）或 "dark"。
    var watchTheme: String { mode == .ink ? "ink" : "dark" }
}

/// 最外層：依設定決定 Brand.current，主題一換就整個重畫（Android 是 Compose 狀態自動重畫）。
struct ThemedRoot: View {
    @StateObject private var theme = ThemeStore.shared
    @Environment(\.colorScheme) private var system
    @Environment(\.dynamicTypeSize) private var textSize

    var body: some View {
        let p = theme.palette(system: system)
        Brand.current = p
        return RootView()
            .id("\(p.id)-\(textSize)")   // 換主題或系統文字大小：整個重畫（字級是算出來的）
            .dynamicTypeSize(...DynamicTypeSize.accessibility2)   // 最大到這裡，再大版面會擠壞
            .preferredColorScheme(p.isDark ? .dark : .light)
            .environmentObject(theme)
    }
}
