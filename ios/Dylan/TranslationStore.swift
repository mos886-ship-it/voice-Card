import Foundation

/// 翻譯記錄（照 Android `TranslationStore`）：每次開始到停止存成 Documents/translations/<id>.json
/// （原文、譯文、語言、時間、同時錄的那段錄音）。停止時存一次，3 秒後補存晚到的翻譯。
struct TranslationRecord: Codable, Identifiable, Equatable {
    struct Line: Codable, Equatable { var original: String; var translated: String? }
    var id: String
    var start: Date
    var end: Date
    var source: String       // SpeechLang rawValue
    var target: String       // LiveCaptions.Target rawValue
    var lines: [Line]
    var meetingId: String?   // 同時錄的那段錄音（有的話可以打開報告）
    var reportId: String?    // 用這筆記錄產生的報告

    var durationSec: Double { max(0, end.timeIntervalSince(start)) }
    var translatedCount: Int { lines.filter { ($0.translated ?? "").isEmpty == false }.count }
    var sourceLabel: String { (SpeechLang(rawValue: source) ?? .zh).label }
    var targetLabel: String { LiveCaptions.Target(rawValue: target)?.label ?? target }
    var preview: String { lines.first(where: { !$0.original.isEmpty })?.original ?? "" }

    /// 分享 / 做報告用的文字：原文一行、→ 譯文一行。
    var text: String {
        lines.map { l in l.original + ((l.translated ?? "").isEmpty ? "" : "\n→ " + (l.translated ?? "")) }
            .joined(separator: "\n\n")
    }
}

@MainActor
final class TranslationStore: ObservableObject {
    static let shared = TranslationStore()
    static let autoSaveKey = "translate_autosave"
    static var autoSave: Bool {
        get { UserDefaults.standard.object(forKey: autoSaveKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: autoSaveKey) }
    }

    @Published private(set) var records: [TranslationRecord] = []

    nonisolated static let dir: URL = {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("translations", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    private init() { reload() }

    func reload() {
        let files = (try? FileManager.default.contentsOfDirectory(at: Self.dir, includingPropertiesForKeys: nil)) ?? []
        let dec = JSONDecoder()
        records = files.filter { $0.pathExtension == "json" }
            .compactMap { try? dec.decode(TranslationRecord.self, from: Data(contentsOf: $0)) }
            .sorted { $0.start > $1.start }
    }

    func save(_ r: TranslationRecord) {
        guard !r.lines.isEmpty else { return }
        if let d = try? JSONEncoder().encode(r) {
            try? d.write(to: Self.dir.appendingPathComponent(r.id + ".json"), options: .atomic)
        }
        reload()
    }

    func delete(_ r: TranslationRecord) {
        try? FileManager.default.removeItem(at: Self.dir.appendingPathComponent(r.id + ".json"))
        reload()
    }

    static func newId(_ d: Date = Date()) -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyyMMdd-HHmmss"
        return "tr_" + f.string(from: d)
    }
}
