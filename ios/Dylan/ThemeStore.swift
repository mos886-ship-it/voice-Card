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

    /// 水墨的背景畫（設定 → 介面 → 配色選水墨才出現）。
    @Published var inkBg: InkBg {
        didSet { UserDefaults.standard.set(inkBg.rawValue, forKey: InkBg.key) }
    }

    init() {
        inkBg = InkBg(rawValue: UserDefaults.standard.string(forKey: InkBg.key) ?? "") ?? .boat
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
        Brand.painting = theme.mode == .ink ? theme.inkBg.image : nil
        return RootView()
            .id("\(p.id)-\(textSize)-\(Brand.painting != nil)")   // 換背景畫：只有「有 / 沒有」會改卡片透明度才整個重畫   // 換主題或系統文字大小：整個重畫（字級是算出來的）
            .dynamicTypeSize(...DynamicTypeSize.accessibility2)   // 最大到這裡，再大版面會擠壞
            .preferredColorScheme(p.isDark ? .dark : .light)
            .environmentObject(theme)
    }
}

/// 水墨背景畫（照 Android：ink_bg = boat / lotus / shrimp / none）。原創、程式產生（tools/ink_bg.py），1080×2400 WebP。
enum InkBg: String, CaseIterable, Identifiable {
    case boat, lotus, shrimp, none
    static let key = "ink_bg"
    var id: String { rawValue }
    var label: String {
        switch self { case .boat: "孤舟"; case .lotus: "荷塘"; case .shrimp: "魚蝦"; case .none: "無" }
    }
    var note: String {
        switch self {
        case .boat: "孤舟寒江：淡月、遠岸、一葉小舟與蘆葦。"
        case .lotus: "荷塘清趣：荷葉、淡荷花與三尾小魚。"
        case .shrimp: "魚蝦戲水：荷葉一角、水草，小蝦與小魚。"
        case .none: "不放背景畫，只有宣紙底。"
        }
    }
    private static var cache: [InkBg: UIImage] = [:]
    var image: UIImage? {
        if let img = Self.cache[self] { return img }
        guard self != .none, let url = Bundle.main.url(forResource: "ink_bg_\(rawValue)", withExtension: "webp"),
              let img = UIImage(contentsOfFile: url.path) else { return nil }
        Self.cache[self] = img
        return img
    }
}

/// 頁面底：品牌底色，水墨有背景畫時鋪滿整個螢幕（含狀態列後面），捲動時不動。
struct AppBackdrop: View {
    @EnvironmentObject private var theme: ThemeStore
    var body: some View {
        ZStack {
            Brand.backdrop
            if theme.mode == .ink, let img = theme.inkBg.image {
                GeometryReader { g in
                    Image(uiImage: img).resizable().scaledToFill()
                        .frame(width: g.size.width, height: g.size.height).clipped()
                }
                .accessibilityHidden(true)
            }
        }
        .ignoresSafeArea()
    }
}
