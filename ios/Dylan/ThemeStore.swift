import SwiftUI

/// 外觀設定（照 Android `Settings.themeMode` + `Brand.isDark / isInk`）。存在 UserDefaults "theme_mode"，預設深色。
/// 換主題時把「ink」或「dark」推給手錶（手錶只有這兩種：水墨跟手機一起，其他一律深色省電）。
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
        mode = ThemeMode(rawValue: UserDefaults.standard.string(forKey: Self.key) ?? "") ?? .dark
    }

    func palette(system: ColorScheme) -> Palette {
        switch mode {
        case .dark: return .dark
        case .light: return .light
        case .ink: return .ink
        case .auto: return system == .dark ? .dark : .light
        }
    }

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
            .preferredColorScheme(theme.mode == .auto ? nil : (p.isDark ? .dark : .light))
            .environmentObject(theme)
    }
}
