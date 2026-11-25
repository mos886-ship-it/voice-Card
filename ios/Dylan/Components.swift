import SwiftUI

// 共用元件，照 Android `ui/Components.kt`。

/// 統一的卡片：深色面 + 細邊框 + 20 圓角；highlight = 漸層邊框 + 淡漸層底。
struct GlassCard<Content: View>: View {
    var highlight = false
    var padding: CGFloat = 16
    @ViewBuilder var content: Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Brand.cardRadius, style: .continuous)
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding)
            .background {
                ZStack {
                    shape.fill(Brand.surface)
                    if highlight { shape.fill(Brand.accentSoft) }
                }
            }
            .overlay {
                if highlight { shape.stroke(Brand.accent, lineWidth: 1) } else { shape.stroke(Brand.outline, lineWidth: 1) }
            }
            .contentShape(shape)
    }
}

/// 小節標題：等寬小字 + 字距。
struct SectionLabel: View {
    let text: String
    var body: some View {
        Text(Brand.isNew ? text : text.uppercased())
            .font(Brand.isNew ? .labelLarge : .labelSmall)
            .tracking(Brand.isNew ? 0 : 1.2)
            .foregroundStyle(Brand.textMuted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
            .padding(.bottom, 4)
    }
}

/// 主要按鈕：漸層底、白字，可帶錄音紅點。
struct GradientButton: View {
    let text: String
    var systemImage: String?
    var recordDot = false
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if recordDot { RecordDot(size: 18) }
                if let systemImage { Image(systemName: systemImage).font(.system(size: 16, weight: .semibold)) }
                Text(text).font(.labelLarge)
            }
            .foregroundStyle(enabled ? Color.white : Brand.textFaint)
            .frame(maxWidth: .infinity, minHeight: 48)
            .padding(.horizontal, 18)
            .background {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(enabled ? AnyShapeStyle(Brand.accent) : AnyShapeStyle(Brand.surfaceHigher))
            }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

/// 次要按鈕：描邊。
struct GhostButton: View {
    let text: String
    var systemImage: String?
    var tint: Color = Brand.text
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage).font(.system(size: 15, weight: .semibold)) }
                Text(text).font(.labelLarge)
            }
            .foregroundStyle(tint)
            .frame(minHeight: 48)
            .padding(.horizontal, 16)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Brand.surfaceHigh))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Brand.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// 圓形圖示按鈕（頂欄用）。
struct IconCircle: View {
    let systemImage: String
    let label: String
    var tint: Color = Brand.text
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(tint)
                .frame(width: 40, height: 40)
                .background(Circle().fill(Brand.surfaceHigh))
                .overlay(Circle().stroke(Brand.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// 狀態標籤的配色 / 文字。
struct StatusStyle {
    let label: String
    let color: Color
    let busy: Bool
}

extension Meeting {
    /// 狀態標籤（照 Android statusStyle）。處理步驟細節（上傳音檔 / AI 聆聽整理…）在里程碑 5。
    var statusStyle: StatusStyle {
        switch status {
        case .recording: return StatusStyle(label: "手錶錄音中", color: Brand.recording, busy: true)
        case .received: return StatusStyle(label: "已收到", color: Brand.violet, busy: false)
        case .summarizing: return StatusStyle(label: "AI 分析中", color: Brand.cyan, busy: true)
        case .done: return StatusStyle(label: "報告完成", color: Brand.success, busy: false)
        case .failed: return StatusStyle(label: "處理失敗", color: Brand.error, busy: false)
        }
    }
}

struct StatusChip: View {
    let style: StatusStyle
    var body: some View {
        HStack(spacing: 6) {
            if style.busy { PulseDot(color: style.color) } else { Circle().fill(style.color).frame(width: 6, height: 6) }
            Text(style.label).font(.labelMedium).foregroundStyle(style.color)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(Capsule().fill(style.color.opacity(0.12)))
        .overlay(Capsule().stroke(style.color.opacity(0.35), lineWidth: 1))
    }
}

/// 呼吸閃爍的圓點（處理中）。
struct PulseDot: View {
    let color: Color
    var size: CGFloat = 6
    @State private var on = false
    var body: some View {
        Circle().fill(color.opacity(on ? 1 : 0.25))
            .frame(width: size, height: size)
            .animation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true), value: on)
            .onAppear { on = true }
    }
}

/// 不定進度條：一道漸層光在軌道上來回掃。
struct ScanningBar: View {
    var body: some View {
        TimelineView(.animation) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.4) / 1.4
            GeometryReader { geo in
                let w = geo.size.width
                let x = max(0, (-0.4 + 1.4 * t)) * w
                ZStack(alignment: .leading) {
                    Capsule().fill(Brand.surfaceHigher)
                    Capsule().fill(Brand.accent).frame(width: w * 0.4).offset(x: x)
                }
                .clipShape(Capsule())
            }
        }
        .frame(height: 4)
    }
}

/// 鍵值資訊小格（統計）。
struct StatTile: View {
    let value: String
    let label: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.system(size: TextScale.size(19, .title3), weight: .bold))
                .foregroundStyle(Brand.text)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label).font(.labelMedium).foregroundStyle(Brand.textMuted).lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, value.count > 5 ? 10 : 14)
        .padding(.vertical, 12)
        .frame(maxHeight: .infinity)   // 同一排的格子一樣高
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Brand.surfaceHigh))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Brand.outline, lineWidth: 1))
    }
}

/// 漸層圖示塊（清單前綴）。
struct GlyphTile<Content: View>: View {
    var size: CGFloat = 44
    var filled = false
    @ViewBuilder var content: Content
    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        content
            .frame(width: size, height: size)
            .background(shape.fill(filled ? AnyShapeStyle(Brand.accent) : AnyShapeStyle(Brand.accentSoft)))
            .overlay(shape.stroke(Brand.outline, lineWidth: 1))
    }
}

struct DividerLine: View {
    var body: some View { Rectangle().fill(Brand.outline).frame(height: 1) }
}

/// 頂欄右邊的手錶狀態：綠點 = 已連線；點一下到設定。
struct WatchPill: View {
    let state: PhoneLink.WatchState
    let action: () -> Void
    var body: some View {
        let on = state == .reachable || state == .connected
        Button(action: action) {
            HStack(spacing: 6) {
                Circle().fill(on ? Brand.success : Brand.textFaint).frame(width: 8, height: 8)
                Text(on ? "手錶已連線" : "手錶未連線").font(.labelMedium).foregroundStyle(on ? Brand.text : Brand.textMuted)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Capsule().fill(Brand.surface))
            .overlay(Capsule().stroke(Brand.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// 頂欄：返回 + 標題 + 右側動作（詳情頁用）。
struct TopBar<Trailing: View>: View {
    let title: String
    let onBack: () -> Void
    @ViewBuilder var trailing: Trailing
    var body: some View {
        HStack(spacing: 12) {
            IconCircle(systemImage: "chevron.left", label: "返回", action: onBack)
            Text(title).font(.titleMedium).foregroundStyle(Brand.text)
            Spacer()
            trailing
        }
        .pageColumn()
        .padding(.vertical, 10)
    }
}

/// 輕量提示（取代 Android Toast）。
struct ToastModifier: ViewModifier {
    @Binding var text: String?
    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let text {
                Text(text)
                    .font(.bodyMedium)
                    .foregroundStyle(Brand.text)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(Brand.surfaceHigher))
                    .overlay(Capsule().stroke(Brand.outline, lineWidth: 1))
                    .padding(.bottom, 96)
                    .transition(.opacity)
            }
        }
        // 換了新的提示就重新計時（之前第二個提示會跟著第一個的計時提早消失）
        .task(id: text) {
            guard text != nil else { return }
            try? await Task.sleep(for: .seconds(2.2))
            guard !Task.isCancelled else { return }
            withAnimation { self.text = nil }
        }
    }
}

extension View {
    func toast(_ text: Binding<String?>) -> some View { modifier(ToastModifier(text: text)) }
}

/// 每一頁的內容欄：iPhone 左右留 20；iPad（寬螢幕）左右留 32、幾乎用滿整個寬度（最寬 1240，超大螢幕才置中）。
struct PageColumn: ViewModifier {
    @Environment(\.horizontalSizeClass) private var size
    var maxWidth: CGFloat = 1240

    func body(content: Content) -> some View {
        content
            .padding(.horizontal, size == .regular ? 32 : 20)
            .frame(maxWidth: size == .regular ? maxWidth : .infinity)
            .frame(maxWidth: .infinity)
    }
}

extension View {
    func pageColumn(maxWidth: CGFloat = 1240) -> some View { modifier(PageColumn(maxWidth: maxWidth)) }
}

/// 整個畫面的寬度（RootView 量好放進環境）：各頁用來決定要不要分欄。
private struct PageWidthKey: EnvironmentKey { static let defaultValue: CGFloat = 0 }
extension EnvironmentValues {
    var pageWidth: CGFloat {
        get { self[PageWidthKey.self] }
        set { self[PageWidthKey.self] = newValue }
    }
}

enum Wide {
    /// 夠寬就分兩欄（iPad 橫向、13 吋直向）；iPhone 和 11 吋直向維持一欄。
    static func twoColumns(_ width: CGFloat) -> Bool { width >= 980 }
}

/// 寬螢幕左右兩欄、窄螢幕上下排。leftWidth = nil 時兩欄一樣寬。
struct TwoColumns<L: View, R: View>: View {
    let wide: Bool
    var leftWidth: CGFloat? = nil
    var spacing: CGFloat = 14
    @ViewBuilder let left: () -> L
    @ViewBuilder let right: () -> R

    var body: some View {
        if wide {
            HStack(alignment: .top, spacing: 20) {
                VStack(alignment: .leading, spacing: spacing) { left() }
                    .frame(width: leftWidth)
                    .frame(maxWidth: leftWidth == nil ? .infinity : nil, alignment: .topLeading)
                VStack(alignment: .leading, spacing: spacing) { right() }
                    .frame(maxWidth: .infinity, alignment: .topLeading)
            }
        } else {
            VStack(alignment: .leading, spacing: spacing) {
                left()
                right()
            }
        }
    }
}

/// 卡片清單：寬螢幕排成多欄格子，窄螢幕一張一列。
struct CardGrid<Content: View>: View {
    @Environment(\.pageWidth) private var width
    var minimum: CGFloat = 360
    @ViewBuilder let content: () -> Content

    var body: some View {
        if width >= 760 {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: minimum), spacing: 12, alignment: .top)],
                      alignment: .leading, spacing: 12) { content() }
        } else {
            LazyVStack(alignment: .leading, spacing: 12) { content() }
        }
    }
}
