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
/// 辨識語言（照 Android `SpeechLang.kt`，代號、文字一樣）。國語 / 台語 / 客語，加上「其他 ▾」的七種語言：
/// Gemini 照原文寫逐字稿（粵語寫粵語字），標題、摘要、報告用繁體中文。
enum SpeechLang: String, CaseIterable, Identifiable {
    case zh, nan, hak
    case en, ja, ko, yue, vi, th, id

    static let key = "speech_lang"
    /// 現在選的（新錄音用）。
    static var current: SpeechLang { SpeechLang(rawValue: UserDefaults.standard.string(forKey: key) ?? "") ?? .zh }

    /// 國語 / 台語 / 客語以外的「其他語言」
    var other: Bool { ![.zh, .nan, .hak].contains(self) }
    static var main: [SpeechLang] { allCases.filter { !$0.other } }
    static var others: [SpeechLang] { allCases.filter(\.other) }

    var id: String { rawValue }
    var label: String {
        switch self {
        case .zh: return "國語"; case .nan: return "台語・國台語"; case .hak: return "客語"
        case .en: return "英語"; case .ja: return "日語"; case .ko: return "韓語"; case .yue: return "粵語"
        case .vi: return "越南語"; case .th: return "泰語"; case .id: return "印尼語"
        }
    }
    var short: String {
        switch self {
        case .nan: return "台語"
        case .vi: return "越語"
        default: return label
        }
    }
    /// 逐字稿每段前的語言標籤（一個字）。
    var tag: String {
        switch self {
        case .zh: return "國"; case .nan: return "台"; case .hak: return "客"
        case .en: return "英"; case .ja: return "日"; case .ko: return "韓"; case .yue: return "粵"
        case .vi: return "越"; case .th: return "泰"; case .id: return "印"
        }
    }
    /// 選了之後下面那一行說明。
    var hint: String {
        switch self {
        case .zh: return "國語（夾雜英文也選這個，英文照原文寫）"
        case .nan: return "有台語就選這個（國台語夾雜也是），比較慢"
        case .hak: return "客語（國客夾雜也可以），比較慢"
        case .en: return "整段英文用這個；中文夾英文選國語就好"
        case .ja: return "日語：逐字稿寫日文，報告用中文"
        case .ko: return "韓語：逐字稿寫韓文，報告用中文"
        case .yue: return "粵語（廣東話）：逐字稿寫繁體粵語字"
        case .vi: return "越南語：逐字稿寫越南文，報告用中文"
        case .th: return "泰語：逐字稿寫泰文，報告用中文"
        case .id: return "印尼語：逐字稿寫印尼文，報告用中文"
        }
    }
    /// Apple 語音辨識用的語言（國語、台語、客語都用 zh-TW）。
    var locale: Locale {
        switch self {
        case .en: return Locale(identifier: "en-US"); case .ja: return Locale(identifier: "ja-JP")
        case .ko: return Locale(identifier: "ko-KR"); case .yue: return Locale(identifier: "yue-Hant-HK")
        case .vi: return Locale(identifier: "vi-VN"); case .th: return Locale(identifier: "th-TH")
        case .id: return Locale(identifier: "id-ID")
        default: return Locale(identifier: "zh-TW")
        }
    }
    /// 給 Gemini 的語言名稱
    var spoken: String { self == .yue ? "粵語（廣東話）" : label }

    /// 給 Gemini 的額外說明（跟 Android geminiNote 一字不差）；國語沒有。
    func geminiNote(_ dialect: HakkaDialect = .sixian) -> String {
        switch self {
        case .zh: return ""
        case .nan:
            return "這段錄音有台語（可能和國語夾雜在同一句裡）。逐字稿請一律用台灣繁體中文的華語字寫出意思（台語的部分翻成通順的華語，"
                + "國語照說的寫），不要用台羅拼音，也不要寫成同音的錯字。"
        case .hak:
            return "這段錄音是客語（\(dialect.label)腔，可能和國語夾雜）。逐字稿請一律用台灣繁體中文的華語字寫出意思（客語的部分翻成通順的華語，"
                + "國語照說的寫），不要用客語拼音，也不要寫成同音的錯字。"
        case .yue:
            return "這段錄音主要是粵語（廣東話，可能夾雜國語或英文）。逐字稿照說的寫：粵語用繁體中文粵語字（例如「佢」「唔係」「咗」），"
                + "國語照說的寫，英文寫英文，不要翻譯。標題、摘要和報告仍用繁體中文（台灣用語）。"
        default:
            return "這段錄音主要是\(spoken)（可能夾雜國語或其他語言）。逐字稿照原本的語言寫：\(spoken)的部分用\(spoken)原文，"
                + "國語寫繁體中文，不要翻譯。標題、摘要和報告仍用繁體中文（台灣用語），報告裡引用的原話、專有名詞可以保留原文。"
        }
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
            ForEach(SpeechLang.main) { l in
                segment(l)
            }
            otherSegment
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
    static func hint(_ lang: SpeechLang) -> String { lang.hint }

    /// 第 4 格「其他 ▾」：點了開選單（英語、日語、韓語、粵語、越南語、泰語、印尼語），選了那格顯示語言名。
    private var otherSegment: some View {
        let on = value.other
        let fg: Color = on ? (Brand.isNew ? Brand.text : .white) : Brand.textMuted
        let bg = on ? (Brand.isNew ? AnyShapeStyle(Brand.surface) : AnyShapeStyle(Brand.accent)) : AnyShapeStyle(Color.clear)
        let shape = RoundedRectangle(cornerRadius: Brand.isNew ? 8 : 100, style: .continuous)
        return Menu {
            ForEach(SpeechLang.others) { l in
                Button { if l != value { pick(l) } } label: {
                    if l == value { Label(l.label, systemImage: "checkmark") } else { Text(l.label) }
                }
            }
        } label: {
            Text((on ? value.label : "其他") + " ▾").font(on ? .labelLarge : .bodyMedium).foregroundColor(fg)
                .lineLimit(1).minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity).padding(.vertical, Brand.isNew ? 8 : 9)
                .background(shape.fill(bg).shadow(color: on && Brand.isNew ? Color.black.opacity(0.12) : .clear, radius: 1.5, y: 1))
                .contentShape(shape)
        }
        .accessibilityLabel(on ? "其他語言：\(value.label)" : "其他語言")
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    /// 新設計（清晰 / 暖和日常）：灰底軌道上一塊白色滑塊（iOS 分段控制的樣子）；舊主題：漸層膠囊。
    private var track: RoundedRectangle { RoundedRectangle(cornerRadius: Brand.isNew ? 10 : 100, style: .continuous) }

    private func segment(_ l: SpeechLang) -> some View {
        let on = value == l
        let fg: Color = on ? (Brand.isNew ? Brand.text : .white) : Brand.textMuted
        let bg = on ? (Brand.isNew ? AnyShapeStyle(Brand.surface) : AnyShapeStyle(Brand.accent)) : AnyShapeStyle(Color.clear)
        let shape = RoundedRectangle(cornerRadius: Brand.isNew ? 8 : 100, style: .continuous)
        return Button { if !on { pick(l) } } label: {
            Text(l.short).font(on ? .labelLarge : .bodyMedium).foregroundColor(fg)
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
