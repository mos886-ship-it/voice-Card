import SwiftUI

/// 介面模式：商用＝簡潔（只顯示要用的），科技＝細節（模型、耗時、裝置能力、進階設定）。
enum UIMode: String, CaseIterable, Identifiable {
    case business, tech

    static let key = "ui_mode"

    var id: String { rawValue }
    var label: String { self == .business ? "商用" : "科技" }
    var icon: String { self == .business ? "briefcase.fill" : "cpu" }
    var note: String {
        self == .business
            ? "簡潔：只顯示要用的。報告、清單和設定都精簡，適合每天使用。"
            : "細節：顯示 AI 模型、處理耗時、裝置能力與進階設定（換模型），方便測試和調整。"
    }
}

/// 錄音的語言（錄音前選，記住上次）：國語 → FunASR（快）；台語・國台語 → Breeze-ASR-26（整段交給它，不逐句切）。
enum SpeechLang: String, CaseIterable, Identifiable {
    case zh, nan, hak

    static let key = "speech_lang"
    /// 現在選的（新錄音用）。
    static var current: SpeechLang { SpeechLang(rawValue: UserDefaults.standard.string(forKey: key) ?? "") ?? .zh }

    var id: String { rawValue }
    var label: String {
        switch self { case .zh: return "國語"; case .nan: return "台語・國台語"; case .hak: return "客語" }
    }
    var short: String {
        switch self { case .zh: return "國語"; case .nan: return "台語"; case .hak: return "客語" }
    }
    /// 逐字稿每段前的語言標籤（一個字）。
    var tag: String {
        switch self { case .zh: return "國"; case .nan: return "台"; case .hak: return "客" }
    }
}

/// 客語腔調（陽明交大客語 Whisper 用腔調 id 當提示）。存在 `hakka_dialect`，錄音時跟著存到 Meeting.dialect。
enum HakkaDialect: String, CaseIterable, Identifiable {
    case sixian, hailu, dapu, raoping, zhaoan, nansixian

    static let key = "hakka_dialect"
    static var current: HakkaDialect { HakkaDialect(rawValue: UserDefaults.standard.string(forKey: key) ?? "") ?? .sixian }

    var id: String { rawValue }
    var label: String {
        switch self {
        case .sixian: return "四縣"
        case .hailu: return "海陸"
        case .dapu: return "大埔"
        case .raoping: return "饒平"
        case .zhaoan: return "詔安"
        case .nansixian: return "南四縣"
        }
    }
}

/// 錄音語言與客語腔調的設定（存 UserDefaults）。
/// 不用 @AppStorage：iOS 16 上連續切換時畫面不會跟著更新（要換頁回來才對），改用 ObservableObject 自己通知。
@MainActor
final class LangSettings: ObservableObject {
    static let shared = LangSettings()

    @Published var lang: SpeechLang {
        didSet { UserDefaults.standard.set(lang.rawValue, forKey: SpeechLang.key) }
    }
    @Published var dialect: HakkaDialect {
        didSet { UserDefaults.standard.set(dialect.rawValue, forKey: HakkaDialect.key) }
    }

    private init() {
        lang = SpeechLang.current
        dialect = HakkaDialect.current
    }
}

extension Fmt {
    /// 給人看的時長：16 秒、2 分、1 小時 5 分。
    static func human(_ sec: Double) -> String {
        let s = Int(sec.rounded())
        if s < 60 { return "\(s) 秒" }
        if s < 3600 { return "\(s / 60) 分" }
        return s % 3600 / 60 == 0 ? "\(s / 3600) 小時" : "\(s / 3600) 小時 \(s % 3600 / 60) 分"
    }

    /// 清單的日期分組：今天、昨天、11月20日 週四（不是今年再加年份）。
    static func dayLabel(_ day: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(day) { return "今天" }
        if cal.isDateInYesterday(day) { return "昨天" }
        let sameYear = cal.component(.year, from: day) == cal.component(.year, from: Date())
        return stamp(day, sameYear ? "M月d日 EEEE" : "yyyy年M月d日 EEEE")
    }
}

/// 辨識語言的二選一（膠囊分段）：主頁錄音前、錄音詳情頁都用。
struct LangPicker: View {
    let value: SpeechLang
    var disabled = false
    let pick: (SpeechLang) -> Void

    var body: some View {
        HStack(spacing: 4) {
            ForEach(SpeechLang.allCases) { l in
                segment(l)
            }
        }
        .padding(Brand.isNew ? 3 : 4)
        .background(track.fill(Brand.surfaceHigh))
        .overlay(track.stroke(Brand.isNew ? Color.clear : Brand.outline, lineWidth: 1))
        .opacity(disabled ? 0.5 : 1)
        .disabled(disabled)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("辨識語言")
    }

    /// 選了客語時下面那一行：說明＋腔調選單。
    static func hint(_ lang: SpeechLang) -> String {
        switch lang {
        case .zh: return "國語：最快"
        case .nan: return "有台語就選這個（國台語夾雜也是），比較慢"
        case .hak: return "客語（國客夾雜也可以），比較慢"
        }
    }

    /// 新設計（清晰 / 暖和日常）：灰底軌道上一塊白色滑塊（iOS 分段控制的樣子）；舊主題：漸層膠囊。
    private var track: RoundedRectangle { RoundedRectangle(cornerRadius: Brand.isNew ? 10 : 100, style: .continuous) }

    private func segment(_ l: SpeechLang) -> some View {
        let on = value == l
        let fg: Color = on ? (Brand.isNew ? Brand.text : .white) : Brand.textMuted
        let bg = on ? (Brand.isNew ? AnyShapeStyle(Brand.surface) : AnyShapeStyle(Brand.accent)) : AnyShapeStyle(Color.clear)
        let shape = RoundedRectangle(cornerRadius: Brand.isNew ? 8 : 100, style: .continuous)
        return Button { if !on { pick(l) } } label: {
            Text(l.label).font(on ? .labelLarge : .bodyMedium).foregroundColor(fg)
                .frame(maxWidth: .infinity).padding(.vertical, Brand.isNew ? 8 : 9)
                .background(shape.fill(bg).shadow(color: on && Brand.isNew ? Color.black.opacity(0.12) : .clear, radius: 1.5, y: 1))
                .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// 客語腔調選單（小膠囊：「四縣腔 ▾」）。
struct DialectMenu: View {
    let value: HakkaDialect
    let pick: (HakkaDialect) -> Void

    var body: some View {
        Menu {
            ForEach(HakkaDialect.allCases) { d in
                Button { pick(d) } label: {
                    if d == value { Label(d.label, systemImage: "checkmark") } else { Text(d.label) }
                }
            }
        } label: {
            HStack(spacing: 4) {
                Text(value.label + "腔").font(.labelLarge)
                Image(systemName: "chevron.down").font(.system(size: 10, weight: .semibold))
            }
            .foregroundStyle(Brand.cyan)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(Capsule().fill(Brand.surfaceHigh))
            .overlay(Capsule().stroke(Brand.outline, lineWidth: 1))
        }
        .accessibilityLabel("客語腔調：" + value.label)
    }
}
