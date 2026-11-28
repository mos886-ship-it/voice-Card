import Foundation

/// 遠距對話存在手機上的東西（照 Android `TalkStore.kt`）：
/// - 通話對象（`Contact`）：對方的聯絡人 id、名字、語言、在哪台大D的主機（base）。
/// - 自己在每台主機的身分（id＋token；token 只有主機第一次給，存在鑰匙圈）。
/// - 對話記錄（`Talk`）：Documents/talks/<id>/talk.json＋每句的原聲片段，可以回播。
@MainActor
final class TalkStore: ObservableObject {
    static let shared = TalkStore()

    struct Contact: Codable, Identifiable, Equatable {
        var id: String, name: String, lang: String, base: String
        var lan: String?
        var lastTalk: Date
        /// 對方的使用者 ID（K7MX-29QP；加好友來的才有）
        var uid: String?
        var key: String { "\(id)@\(base)" }
    }
    struct TalkLine: Codable, Equatable, Identifiable {
        var mine: Bool, name: String, text: String, lang: String
        var translated: String?
        var audio: String?
        var at: Date
        var id: String { "\(at.timeIntervalSince1970)-\(text.hashValue)" }
    }
    struct Talk: Codable, Identifiable, Equatable {
        var id: String, startedAt: Date, endedAt: Date
        var partners: [String]
        var lines: [TalkLine]
    }

    @Published private(set) var contacts: [Contact] = []
    @Published private(set) var talks: [Talk] = []

    private static let contactsKey = "talk_contacts", basesKey = "talk_bases"

    init() { load() }

    func load() {
        if let d = UserDefaults.standard.data(forKey: Self.contactsKey),
           let list = try? JSONDecoder().decode([Contact].self, from: d) {
            contacts = list.sorted { $0.lastTalk > $1.lastTalk }
        }
        reloadTalks()
    }

    // MARK: 通話對象

    private func saveContacts(_ list: [Contact]) {
        contacts = list.sorted { $0.lastTalk > $1.lastTalk }
        if let d = try? JSONEncoder().encode(contacts) { UserDefaults.standard.set(d, forKey: Self.contactsKey) }
    }

    func upsert(_ k: Contact) {
        let old = contacts.first { $0.id == k.id && $0.base == k.base }
        var m = k
        if m.name.isEmpty { m.name = old?.name ?? "" }
        if m.lang.isEmpty { m.lang = old?.lang ?? "" }
        if m.lan == nil { m.lan = old?.lan }
        if m.uid == nil { m.uid = old?.uid }
        m.lastTalk = max(k.lastTalk, old?.lastTalk ?? .distantPast)
        if m == old { return }
        saveContacts(contacts.filter { !($0.id == k.id && $0.base == k.base) } + [m])
    }

    func remove(_ k: Contact) { saveContacts(contacts.filter { !($0.id == k.id && $0.base == k.base) }) }

    // MARK: 自己在每台主機的身分（token 在鑰匙圈）

    struct Identity { let id: String, token: String }

    nonisolated static func identity(_ base: String) -> Identity? {
        guard let id = UserDefaults.standard.string(forKey: "talk_id:\(base)"),
              let t = Keychain.get("talk_contact:\(base)"), !t.isEmpty else { return nil }
        return Identity(id: id, token: t)
    }

    static func saveIdentity(_ base: String, id: String, token: String?) {
        if let token, !token.isEmpty { Keychain.set(token, for: "talk_contact:\(base)") }
        guard Keychain.get("talk_contact:\(base)") != nil else { return }
        UserDefaults.standard.set(id, forKey: "talk_id:\(base)")
        var b = bases
        if !b.contains(base) { b.append(base); UserDefaults.standard.set(b, forKey: basesKey) }
    }

    /// 有身分的主機（上線回報要報到這些）。
    static var bases: [String] { UserDefaults.standard.stringArray(forKey: basesKey) ?? [] }

    // MARK: 對話記錄

    nonisolated static var root: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("talks", isDirectory: true)
    }

    nonisolated static func dir(_ id: String) -> URL {
        let d = root.appendingPathComponent(id, isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    private static func coder() -> (JSONEncoder, JSONDecoder) {
        let e = JSONEncoder(); e.dateEncodingStrategy = .millisecondsSince1970
        let d = JSONDecoder(); d.dateDecodingStrategy = .millisecondsSince1970
        return (e, d)
    }

    func reloadTalks() {
        let fm = FileManager.default
        let dirs = (try? fm.contentsOfDirectory(at: Self.root, includingPropertiesForKeys: nil)) ?? []
        let dec = Self.coder().1
        talks = dirs.compactMap { d in
            (try? Data(contentsOf: d.appendingPathComponent("talk.json"))).flatMap { try? dec.decode(Talk.self, from: $0) }
        }
        .filter { !$0.lines.isEmpty }
        .sorted { $0.startedAt > $1.startedAt }
    }

    func save(_ t: Talk) {
        guard let d = try? Self.coder().0.encode(t) else { return }
        try? d.write(to: Self.dir(t.id).appendingPathComponent("talk.json"), options: .atomic)
        if let i = talks.firstIndex(where: { $0.id == t.id }) { talks[i] = t } else { reloadTalks() }
    }

    func delete(_ id: String) {
        try? FileManager.default.removeItem(at: Self.root.appendingPathComponent(id))
        reloadTalks()
    }

    /// 今天的遠距對話（給「今天」時間軸）。
    func today() -> [Talk] { talks.filter { Calendar.current.isDateInToday($0.startedAt) } }
}
