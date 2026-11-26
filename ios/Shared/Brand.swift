import SwiftUI
#if os(iOS)
import UIKit
#endif

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255)
    }
}

/// 一組主題顏色（照 Android `Palette` / `WatchPalette`）。
struct Palette: Equatable {
    let id: String
    let isDark: Bool
    let bg, bgCenter, backdropTop, surface, surfaceHigh, surfaceHigher, outline: Color
    let cyan, blue, violet, pink: Color
    let success, warning, error, recording: Color
    let text, textMuted, textFaint: Color
    let accent: [Color]
    let record: [Color]

    static func == (a: Palette, b: Palette) -> Bool { a.id == b.id }

    private static let brandAccent = [Color(hex: 0x22D3EE), Color(hex: 0x3B82F6), Color(hex: 0x8B5CF6)]
    private static let brandRecord = [Color(hex: 0xFF6B81), Color(hex: 0xFF4D6D), Color(hex: 0xE11D48)]
    private static let inkAccent = [Color(hex: 0x3A3A3A), Color(hex: 0x1F1F1F), Color(hex: 0x111111)]
    private static let inkRecord = [Color(hex: 0xD9473F), Color(hex: 0xB5121B), Color(hex: 0x8E0E15)]

    /// 深空藍（預設）。手機 DarkPalette；手錶底色改純黑（OLED 省電，照 DarkWatch）。
    static let dark = Palette(
        id: "dark", isDark: true,
        bg: Color(hex: isWatch ? 0x000000 : 0x070B16), bgCenter: Color(hex: 0x0B1630), backdropTop: Color(hex: 0x0B1224),
        surface: Color(hex: 0x0F1526), surfaceHigh: Color(hex: 0x161E33), surfaceHigher: Color(hex: 0x1D2740),
        outline: Color(hex: 0x26314D),
        cyan: Color(hex: 0x22D3EE), blue: Color(hex: 0x3B82F6), violet: Color(hex: 0x8B5CF6), pink: Color(hex: 0xF472B6),
        success: Color(hex: 0x34D399), warning: Color(hex: 0xFBBF24), error: Color(hex: 0xF87171), recording: Color(hex: 0xFF4D6D),
        text: Color(hex: 0xE8ECF5), textMuted: Color(hex: 0x8C97B2), textFaint: Color(hex: 0x5B6684),
        accent: brandAccent, record: brandRecord)

    /// 淺色：白底 + 同一組品牌漸層，文字與強調色加深（只有手機）。
    static let light = Palette(
        id: "light", isDark: false,
        bg: Color(hex: 0xF4F6FB), bgCenter: Color(hex: 0xFFFFFF), backdropTop: Color(hex: 0xE8EEFA),
        surface: Color(hex: 0xFFFFFF), surfaceHigh: Color(hex: 0xF0F3F9), surfaceHigher: Color(hex: 0xE3E8F2),
        outline: Color(hex: 0xD6DDE9),
        cyan: Color(hex: 0x0891B2), blue: Color(hex: 0x2563EB), violet: Color(hex: 0x7C3AED), pink: Color(hex: 0xDB2777),
        success: Color(hex: 0x059669), warning: Color(hex: 0xD97706), error: Color(hex: 0xDC2626), recording: Color(hex: 0xE11D48),
        text: Color(hex: 0x0F172A), textMuted: Color(hex: 0x475569), textFaint: Color(hex: 0x94A3B8),
        accent: brandAccent, record: brandRecord)

    /// 水墨：宣紙底、墨黑字、朱紅／黛藍／赭石點綴，按鈕是墨色漸層（跟 logo 同一套）。
    /// 手機 InkPalette；手錶的底色照 InkWatch。
    static let ink = Palette(
        id: "ink", isDark: false,
        bg: Color(hex: isWatch ? 0xEBDFC8 : 0xF2EADB), bgCenter: Color(hex: 0xFBF6EC), backdropTop: Color(hex: 0xEDE2CC),
        surface: Color(hex: 0xFAF5EA), surfaceHigh: Color(hex: 0xEFE6D4), surfaceHigher: Color(hex: 0xE4D9C3),
        outline: Color(hex: 0xD3C6AE),
        cyan: Color(hex: 0xA8322D), blue: Color(hex: 0x3B4A6B), violet: Color(hex: 0x7A5238), pink: Color(hex: 0xB4566B),
        success: Color(hex: 0x4A7C59), warning: Color(hex: 0xB7791F), error: Color(hex: 0xA61B1B), recording: Color(hex: 0xC8102E),
        text: Color(hex: 0x1A1A1A), textMuted: Color(hex: 0x5A5246), textFaint: Color(hex: 0x9A8F7E),
        accent: inkAccent, record: inkRecord)

    /// A 清晰工作台：淺灰紙底、白卡片、深藍黑字、單一藍色重點（不用漸層），錄音用紅色。
    static let clear = Palette(
        id: "clear", isDark: false,
        bg: Color(hex: 0xF5F6F8), bgCenter: Color(hex: 0xFFFFFF), backdropTop: Color(hex: 0xF5F6F8),
        surface: Color(hex: 0xFFFFFF), surfaceHigh: Color(hex: 0xF0F2F5), surfaceHigher: Color(hex: 0xE4E7EC),
        outline: Color(hex: 0xE3E6EB),
        cyan: Color(hex: 0x1F5BD8), blue: Color(hex: 0x1F5BD8), violet: Color(hex: 0x6941C6), pink: Color(hex: 0xC11574),
        success: Color(hex: 0x1E7A4C), warning: Color(hex: 0xA35A00), error: Color(hex: 0xC4221F), recording: Color(hex: 0xD0312D),
        text: Color(hex: 0x101828), textMuted: Color(hex: 0x5D6675), textFaint: Color(hex: 0x7A8494),
        accent: [Color(hex: 0x1F5BD8), Color(hex: 0x1F5BD8)], record: [Color(hex: 0xD0312D), Color(hex: 0xD0312D)])

    /// 台灣：夜藍底、晶片青與梅花紅，以科技為主題（唯一的深色配色；照 Android TaiwanPalette）。主要按鈕底 #178C9C、白字。只有手機。
    static let taiwan = Palette(
        id: "taiwan", isDark: true,
        bg: Color(hex: 0x08121D), bgCenter: Color(hex: 0x12212F), backdropTop: Color(hex: 0x08121D),
        surface: Color(hex: 0x12212F), surfaceHigh: Color(hex: 0x1A2D3E), surfaceHigher: Color(hex: 0x223A4F),
        outline: Color(hex: 0x24435A),
        cyan: Color(hex: 0x2CC8D8), blue: Color(hex: 0x4F9BE0), violet: Color(hex: 0xE0475B), pink: Color(hex: 0xE0475B),
        success: Color(hex: 0x3DD6A0), warning: Color(hex: 0xE9B949), error: Color(hex: 0xFF6B6B), recording: Color(hex: 0xE0475B),
        text: Color(hex: 0xE6F1F5), textMuted: Color(hex: 0x8FA8B6), textFaint: Color(hex: 0x5E7685),
        accent: [Color(hex: 0x178C9C), Color(hex: 0x178C9C)], record: [Color(hex: 0xE0475B), Color(hex: 0xE0475B)])

    /// 浮世繪：和紙底、普魯士藍與朱紅，木刻版畫的平塗色塊（不用漸層）；標題襯線字（照 Android UkiyoePalette）。只有手機。
    static let ukiyoe = Palette(
        id: "ukiyoe", isDark: false,
        bg: Color(hex: 0xF0E4C8), bgCenter: Color(hex: 0xF8F2E4), backdropTop: Color(hex: 0xF0E4C8),
        surface: Color(hex: 0xF8F2E4), surfaceHigh: Color(hex: 0xEADCBC), surfaceHigher: Color(hex: 0xE0CFA8),
        outline: Color(hex: 0xB9A57F),
        cyan: Color(hex: 0x1E3A6E), blue: Color(hex: 0x3C6AA8), violet: Color(hex: 0xC8402B), pink: Color(hex: 0xB4566B),
        success: Color(hex: 0x2F5E4C), warning: Color(hex: 0x9A6B16), error: Color(hex: 0xB3261E), recording: Color(hex: 0xC8402B),
        text: Color(hex: 0x2A2522), textMuted: Color(hex: 0x5E5245), textFaint: Color(hex: 0x8C7D68),
        accent: [Color(hex: 0x1E3A6E), Color(hex: 0x1E3A6E)], record: [Color(hex: 0xC8402B), Color(hex: 0xC8402B)])

    #if os(watchOS)
    private static let isWatch = true
    #else
    private static let isWatch = false
    #endif
}

/// 配色（設定 → 配色，跟排布分開）：清晰（預設）/ 台灣 / 浮世繪 / 水墨。
/// 舊的「深色」讀成浮世繪、「暖和日常」讀成台灣；「淺色」「自動」當清晰。Palette.dark 只剩手錶用。
enum ThemeMode: String, CaseIterable, Identifiable {
    case clear, taiwan, ukiyoe, ink
    var id: String { rawValue }
    var label: String {
        switch self {
        case .clear: return "清晰"
        case .taiwan: return "台灣"
        case .ukiyoe: return "浮世繪"
        case .ink: return "水墨"
        }
    }
    var palette: Palette {
        switch self {
        case .clear: return .clear
        case .taiwan: return .taiwan
        case .ukiyoe: return .ukiyoe
        case .ink: return .ink
        }
    }
}

/// 品牌色。數值來自目前的主題（Brand.current，由最外層畫面依設定換掉並整個重畫）。
enum Brand {
    static var current: Palette = .dark
    #if os(iOS)
    /// 水墨背景畫（nil = 沒有）：有的時候卡片 86%、分頁列 90% 不透明，畫隱約透出來。
    static var painting: UIImage?
    static var cardFill: Color { painting == nil ? surface : surface.opacity(0.86) }
    #endif
    static var isInk: Bool { current.id == "ink" }
    /// 標題用襯線字：水墨、浮世繪。
    static var serifTitles: Bool { current.id == "ink" || current.id == "ukiyoe" }
    /// 卡片圓角：清晰 16、暖和日常 22（比較圓潤）。
    static var cardRadius: CGFloat {
        switch current.id { case "clear", "taiwan": 16; case "ukiyoe": 12; default: 20 }
    }
    /// 新設計（清晰 / 暖和日常）：不用漸層、標題不用等寬字。
    static var isNew: Bool { ["clear", "taiwan", "ukiyoe"].contains(current.id) }
    private static var p: Palette { current }

    static var bg: Color { p.bg }
    static var bgCenter: Color { p.bgCenter }
    static var backdropTop: Color { p.backdropTop }
    static var surface: Color { p.surface }
    static var surfaceHigh: Color { p.surfaceHigh }
    static var surfaceHigher: Color { p.surfaceHigher }
    static var outline: Color { p.outline }

    static var cyan: Color { p.cyan }
    static var blue: Color { p.blue }
    static var violet: Color { p.violet }
    static var pink: Color { p.pink }

    static var success: Color { p.success }
    static var warning: Color { p.warning }
    static var error: Color { p.error }
    static var recording: Color { p.recording }

    static var text: Color { p.text }
    static var textMuted: Color { p.textMuted }
    static var textFaint: Color { p.textFaint }

    /// 主要按鈕 / 選中狀態的漸層（Compose linearGradient 預設是左上 → 右下），按鈕上是白字。
    static var accent: LinearGradient { LinearGradient(colors: p.accent, startPoint: .topLeading, endPoint: .bottomTrailing) }
    static var accentSoft: LinearGradient {
        isNew ? LinearGradient(colors: [cyan.opacity(0.06), cyan.opacity(0.06)], startPoint: .top, endPoint: .bottom)
            : LinearGradient(colors: [cyan.opacity(0.18), violet.opacity(0.18)], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
    static var record: LinearGradient { LinearGradient(colors: p.record, startPoint: .topLeading, endPoint: .bottomTrailing) }
    static var backdrop: LinearGradient { LinearGradient(colors: [backdropTop, bg, bg], startPoint: .top, endPoint: .bottom) }
}

/// 字級照 Android `ui/theme/Type.kt`；水墨主題的標題改用襯線字（InkTypography）。
/// iPhone / iPad 上跟著系統「文字大小」縮放（預設大小時跟原本一樣）；手錶維持固定大小。
extension Font {
    private static var titleDesign: Font.Design { Brand.serifTitles ? .serif : .default }
    static var headlineMedium: Font { .system(size: TextScale.size(26, .title1), weight: .bold, design: titleDesign) }
    static var headlineSmall: Font { .system(size: TextScale.size(22, .title2), weight: .bold, design: titleDesign) }
    static var titleLarge: Font { .system(size: TextScale.size(19, .title3), weight: .semibold, design: titleDesign) }
    static var titleMedium: Font { .system(size: TextScale.size(16, .headline), weight: .semibold, design: titleDesign) }
    static var titleSmall: Font { .system(size: TextScale.size(14, .subheadline), weight: .semibold, design: titleDesign) }
    static var bodyLarge: Font { .system(size: TextScale.size(16, .body)) }
    static var bodyMedium: Font { .system(size: TextScale.size(14, .callout)) }
    static var bodySmall: Font { .system(size: TextScale.size(12, .footnote)) }
    static var labelLarge: Font { .system(size: TextScale.size(14, .subheadline), weight: .semibold) }
    static var labelMedium: Font { .system(size: TextScale.size(12, .caption1), weight: .medium) }
    static var labelSmall: Font {
        Brand.isNew ? .system(size: TextScale.size(12, .caption1), weight: .semibold)
            : .system(size: TextScale.size(10, .caption2), weight: .semibold, design: Brand.isInk ? .serif : .monospaced)
    }
}

/// 字級縮放：設計稿的大小 × 系統文字大小的比例。
enum TextScale {
    enum Style { case title1, title2, title3, headline, subheadline, body, callout, footnote, caption1, caption2 }

    static func size(_ base: CGFloat, _ style: Style) -> CGFloat {
        #if os(iOS)
        let ui: UIFont.TextStyle
        switch style {
        case .title1: ui = .title1
        case .title2: ui = .title2
        case .title3: ui = .title3
        case .headline: ui = .headline
        case .subheadline: ui = .subheadline
        case .body: ui = .body
        case .callout: ui = .callout
        case .footnote: ui = .footnote
        case .caption1: ui = .caption1
        case .caption2: ui = .caption2
        }
        // iPad 螢幕大、拿得比較遠：字放大一點
        let device: CGFloat = UIDevice.current.userInterfaceIdiom == .pad ? 1.15 : 1
        return UIFontMetrics(forTextStyle: ui).scaledValue(for: base * device)
        #else
        return base
        #endif
    }
}

enum Fmt {
    /// 1:05、1:02:03（照 Android formatDuration）
    static func duration(_ sec: Double) -> String {
        let s = max(0, Int(sec))
        let h = s / 3600, m = (s % 3600) / 60, r = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, r) : String(format: "%d:%02d", m, r)
    }

    /// 計時器 / 播放器：00:05、1:02:03
    static func clock(_ sec: Double) -> String {
        let s = max(0, Int(sec))
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            : String(format: "%02d:%02d", s / 60, s % 60)
    }

    static func stamp(_ date: Date, _ pattern: String) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_Hant_TW")
        f.dateFormat = pattern
        return f.string(from: date)
    }

    /// 清單用短日期：今年 → 11/21 19:03；其他年 → 2025/11/21 19:03
    static func shortWhen(_ date: Date) -> String {
        let sameYear = Calendar.current.component(.year, from: date) == Calendar.current.component(.year, from: Date())
        return stamp(date, sameYear ? "M/d HH:mm" : "yyyy/M/d HH:mm")
    }
}

// MARK: - 圖示（照 Android 的 Canvas 自繪圖形）

/// dw 書法印章 logo（Assets 的 Logo，由 design/gen/ios_assets.py 產生）。
struct BrandMark: View {
    var size: CGFloat = 36
    var body: some View {
        Image("Logo").resizable().scaledToFit().frame(width: size, height: size).clipShape(Circle())
    }
}

/// 聲波條（靜態）。
struct Waveform: View {
    var color: Color
    var bars = 5
    var body: some View {
        Canvas { ctx, size in
            let heights: [CGFloat] = [0.35, 0.7, 1, 0.6, 0.4, 0.8, 0.5]
            let gap = size.width / CGFloat(bars * 2 - 1)
            for i in 0..<bars {
                let h = size.height * heights[i % heights.count]
                let r = CGRect(x: CGFloat(i) * gap * 2, y: (size.height - h) / 2, width: gap, height: h)
                ctx.fill(Path(roundedRect: r, cornerRadius: gap / 2), with: .color(color))
            }
        }
    }
}

/// 錄音鍵的紅點（白色細框）。所有「開始錄音 / 記錄」按鈕都用它，不用播放三角形。
struct RecordDot: View {
    var size: CGFloat
    var body: some View {
        ZStack {
            Circle().fill(.white)
            Circle().fill(Brand.recording).padding(size * 0.14)
        }
        .frame(width: size, height: size)
    }
}

/// 手錶圖示：圓形錶面＋上下錶帶，中間一個錄音點（＝用手錶錄）。
struct WatchGlyph: View {
    var color: Color
    var dot: Color?
    var body: some View {
        Canvas { ctx, s in
            let w = s.width, h = s.height, sw = w * 0.085
            let strapW = w * 0.40, r = w * 0.30
            ctx.fill(Path(roundedRect: CGRect(x: (w - strapW) / 2, y: 0, width: strapW, height: h * 0.24), cornerRadius: w * 0.06), with: .color(color))
            ctx.fill(Path(roundedRect: CGRect(x: (w - strapW) / 2, y: h * 0.76, width: strapW, height: h * 0.24), cornerRadius: w * 0.06), with: .color(color))
            ctx.fill(Path(ellipseIn: CGRect(x: w / 2 - r - sw / 2, y: h / 2 - r - sw / 2, width: 2 * r + sw, height: 2 * r + sw)), with: .color(color))
            ctx.blendMode = .clear
            ctx.fill(Path(ellipseIn: CGRect(x: w / 2 - r + sw / 2, y: h / 2 - r + sw / 2, width: 2 * r - sw, height: 2 * r - sw)), with: .color(.black))
            ctx.blendMode = .normal
            var side = Path()
            side.move(to: CGPoint(x: w / 2 + r + sw * 0.2, y: h * 0.44))
            side.addLine(to: CGPoint(x: w / 2 + r + sw * 0.9, y: h * 0.44))
            ctx.stroke(side, with: .color(color), style: StrokeStyle(lineWidth: sw * 0.9, lineCap: .round))
            let dr = r * 0.40
            ctx.fill(Path(ellipseIn: CGRect(x: w / 2 - dr, y: h / 2 - dr, width: 2 * dr, height: 2 * dr)), with: .color(dot ?? color))
        }
    }
}

/// 手機圖示：直立圓角長方形＋聽筒，螢幕中間一個錄音點（＝用手機錄）。
struct PhoneGlyph: View {
    var color: Color
    var dot: Color?
    var body: some View {
        Canvas { ctx, s in
            let w = s.width, h = s.height, sw = w * 0.085
            let bw = w * 0.56, left = (w - bw) / 2
            ctx.stroke(Path(roundedRect: CGRect(x: left, y: h * 0.06, width: bw, height: h * 0.88), cornerRadius: w * 0.12),
                       with: .color(color), lineWidth: sw)
            var ear = Path()
            ear.move(to: CGPoint(x: w * 0.43, y: h * 0.17))
            ear.addLine(to: CGPoint(x: w * 0.57, y: h * 0.17))
            ctx.stroke(ear, with: .color(color), style: StrokeStyle(lineWidth: sw * 0.9, lineCap: .round))
            let dr = bw * 0.24
            ctx.fill(Path(ellipseIn: CGRect(x: w / 2 - dr, y: h * 0.53 - dr, width: 2 * dr, height: 2 * dr)), with: .color(dot ?? color))
        }
    }
}

/// 飲食圖示：一個碗＋三縷直立的 S 形熱氣。
struct BowlGlyph: View {
    var color: Color
    var body: some View {
        Canvas { ctx, s in
            let w = s.width, h = s.height, st = w * 0.07
            var bowl = Path()
            bowl.addArc(center: CGPoint(x: w * 0.5, y: h * 0.61), radius: w * 0.42,
                        startAngle: .degrees(0), endAngle: .degrees(180), clockwise: false)
            bowl.closeSubpath()
            ctx.fill(bowl.applying(CGAffineTransform(translationX: 0, y: -h * 0.61)
                .scaledBy(x: 1, y: 0.62 / 0.84)).applying(CGAffineTransform(translationX: 0, y: h * 0.61)),
                     with: .color(color))
            var rim = Path()
            rim.move(to: CGPoint(x: w * 0.02, y: h * 0.61))
            rim.addLine(to: CGPoint(x: w * 0.98, y: h * 0.61))
            ctx.stroke(rim, with: .color(color), style: StrokeStyle(lineWidth: st, lineCap: .round))
            for x in [0.32, 0.5, 0.68] as [CGFloat] {
                var p = Path()
                p.move(to: CGPoint(x: w * x, y: h * 0.50))
                p.addCurve(to: CGPoint(x: w * x, y: h * 0.16),
                           control1: CGPoint(x: w * (x - 0.09), y: h * 0.40),
                           control2: CGPoint(x: w * (x + 0.09), y: h * 0.28))
                ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: st * 0.75, lineCap: .round))
            }
        }
    }
}
