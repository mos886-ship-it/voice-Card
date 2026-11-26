import SwiftUI

/// 配色：清晰 / 台灣 / 浮世繪 / 水墨。存在 UserDefaults "theme_mode"，預設清晰；舊的 light / auto 當清晰。
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

    /// 台灣的背景畫（配色選台灣才出現）。
    @Published var taiwanBg: TaiwanBg {
        didSet { UserDefaults.standard.set(taiwanBg.rawValue, forKey: TaiwanBg.key) }
    }

    /// 浮世繪的背景畫（配色選浮世繪才出現）。
    @Published var ukiyoeBg: UkiyoeBg {
        didSet { UserDefaults.standard.set(ukiyoeBg.rawValue, forKey: UkiyoeBg.key) }
    }

    /// 目前要鋪的背景畫（水墨或浮世繪；nil = 沒有）。
    var art: UIImage? {
        switch mode {
        case .ink: inkBg.image
        case .ukiyoe: ukiyoeBg.image
        case .taiwan: taiwanBg.image
        default: nil
        }
    }
    /// 浮世繪的畫最上面是靛藍（狀態列後面）→ 狀態列用淺色圖示。
    /// 台灣（深色）整個配色都用淺色圖示。
    var statusOnDark: Bool { mode.palette.isDark || (mode == .ukiyoe && art != nil) }

    /// 水墨的背景畫（設定 → 介面 → 配色選水墨才出現）。
    @Published var inkBg: InkBg {
        didSet { UserDefaults.standard.set(inkBg.rawValue, forKey: InkBg.key) }
    }

    init() {
        inkBg = InkBg(rawValue: UserDefaults.standard.string(forKey: InkBg.key) ?? "") ?? .boat
        ukiyoeBg = UkiyoeBg(rawValue: UserDefaults.standard.string(forKey: UkiyoeBg.key) ?? "") ?? .geisha
        taiwanBg = TaiwanBg(rawValue: UserDefaults.standard.string(forKey: TaiwanBg.key) ?? "") ?? .island
        LayoutMode.migrate()
        let saved = UserDefaults.standard.string(forKey: Self.key) ?? ""
        // 1.1.95 以前的「深色」→ 浮世繪；1.1.98 以前的「暖和日常」→ 台灣
        mode = saved == "dark" ? .ukiyoe : saved == "warm" ? .taiwan : ThemeMode(rawValue: saved) ?? .clear
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
        Brand.painting = theme.art
        return RootView()
            .id("\(p.id)-\(textSize)-\(Brand.painting != nil)")   // 換背景畫：只有「有 / 沒有」會改卡片透明度才整個重畫   // 換主題或系統文字大小：整個重畫（字級是算出來的）
            .dynamicTypeSize(...DynamicTypeSize.accessibility2)   // 最大到這裡，再大版面會擠壞
            .preferredColorScheme(p.isDark ? .dark : .light)
            .environmentObject(theme)
            .background(StatusBarStyler(lightContent: theme.statusOnDark))
    }
}

/// 水墨背景畫（照 Android：ink_bg = boat / lotus / shrimp / none）。原創、程式產生（tools/ink_bg.py），1080×2400 WebP。
enum InkBg: String, CaseIterable, Identifiable {
    case boat, lotus, shrimp, none
    static let key = "ink_bg"
    var id: String { rawValue }
    var label: String {
        switch self { case .boat: "孤舟".tr; case .lotus: "荷塘".tr; case .shrimp: "魚蝦".tr; case .none: "無".tr }
    }
    var note: String {
        switch self {
        case .boat: "孤舟寒江：淡月、遠岸、一葉小舟與蘆葦。".tr
        case .lotus: "荷塘清趣：荷葉、淡荷花與三尾小魚。".tr
        case .shrimp: "魚蝦戲水：荷葉一角、水草，小蝦與小魚。".tr
        case .none: "不放背景畫，只有宣紙底。".tr
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
            if let img = theme.art {
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

/// 浮世繪背景畫（照 Android：ukiyoe_bg = geisha / crane / castle / none）。原創、程式產生（tools/ukiyoe_bg.py），1080×2400 WebP。
enum UkiyoeBg: String, CaseIterable, Identifiable {
    case geisha, crane, castle, none
    static let key = "ukiyoe_bg"
    var id: String { rawValue }
    var label: String {
        switch self { case .geisha: "藝伎武士".tr; case .crane: "靈鶴".tr; case .castle: "大阪城".tr; case .none: "無".tr }
    }
    var note: String {
        switch self {
        case .geisha: "藝伎武士：朱紅拱橋上，撐紙傘的藝伎與佩雙刀的武士。".tr
        case .crane: "靈鶴：三隻丹頂鶴、朱紅的日與松枝。".tr
        case .castle: "大阪城：白牆、銅綠屋頂的天守，石垣、護城河與櫻花。".tr
        case .none: "不放背景畫，只有和紙底。".tr
        }
    }
    private static var cache: [UkiyoeBg: UIImage] = [:]
    var image: UIImage? {
        if let img = Self.cache[self] { return img }
        guard self != .none, let url = Bundle.main.url(forResource: "ukiyoe_bg_\(rawValue)", withExtension: "webp"),
              let img = UIImage(contentsOfFile: url.path) else { return nil }
        Self.cache[self] = img
        return img
    }
}

/// 狀態列圖示顏色：台灣（深色）整個配色、浮世繪有背景畫時（狀態列後面是靛藍）用淺色圖示（照 Android statusOnDark）。
/// SwiftUI 沒有直接設定狀態列樣式的方法：把根畫面控制器的 preferredStatusBarStyle 換成我們決定的值。
private struct StatusBarStyler: UIViewControllerRepresentable {
    let lightContent: Bool
    func makeUIViewController(context: Context) -> UIViewController { Probe() }
    func updateUIViewController(_ vc: UIViewController, context: Context) {
        StatusBar.light = lightContent
        DispatchQueue.main.async { StatusBar.apply(from: vc) }
    }
    final class Probe: UIViewController {
        override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); StatusBar.apply(from: self) }
    }
}

enum StatusBar {
    nonisolated(unsafe) static var light = false
    private static var swizzled = false

    /// 第一次用到時，讓所有 UIHostingController 的 preferredStatusBarStyle 依 StatusBar.light 回答。
    static func apply(from vc: UIViewController) {
        guard let root = vc.view.window?.rootViewController else { return }
        if !swizzled {
            swizzled = true
            let cls: AnyClass = type(of: root)
            let sel = #selector(getter: UIViewController.preferredStatusBarStyle)
            let block: @convention(block) (UIViewController) -> UIStatusBarStyle = { _ in light ? .lightContent : .darkContent }
            let imp = imp_implementationWithBlock(block)
            if let m = class_getInstanceMethod(cls, sel) {
                if !class_addMethod(cls, sel, imp, method_getTypeEncoding(m)) { method_setImplementation(m, imp) }
            }
        }
        root.setNeedsStatusBarAppearanceUpdate()
    }
}

/// 台灣背景畫（照 Android：taiwan_bg = island / wafer / city / none）。原創、程式產生（tools/taiwan_bg.py），不用任何公司名稱或商標。
enum TaiwanBg: String, CaseIterable, Identifiable {
    case island, wafer, city, none
    static let key = "taiwan_bg"
    var id: String { rawValue }
    var label: String {
        switch self { case .island: "島嶼電路".tr; case .wafer: "晶圓".tr; case .city: "夜城".tr; case .none: "無".tr }
    }
    var note: String {
        switch self {
        case .island: "島嶼電路：點陣組成的台灣、北回歸線與五座城市的連線。".tr
        case .wafer: "晶圓：一片晶圓與往上延伸的電路，點綴梅花。".tr
        case .city: "夜城：夜晚的城市天際線、遠山與河面倒影。".tr
        case .none: "不放背景畫，只有夜藍底。".tr
        }
    }
    private static var cache: [TaiwanBg: UIImage] = [:]
    var image: UIImage? {
        if let img = Self.cache[self] { return img }
        guard self != .none, let url = Bundle.main.url(forResource: "taiwan_bg_\(rawValue)", withExtension: "webp"),
              let img = UIImage(contentsOfFile: url.path) else { return nil }
        Self.cache[self] = img
        return img
    }
}
