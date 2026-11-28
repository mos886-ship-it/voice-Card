import Foundation

/// 遠距對話的使用者 ID 與好友（照 Android `TalkFriends.kt`）：
/// - 自己的 ID（像 K7MX-29QP）：在大D的主機註冊拿到，給朋友看。
/// - 輸入對方的 ID → 對方收到好友邀請；對方接受後雙方都在「通話對象」裡，可以直接撥打。
/// 身分跟著主機走（`TalkStore.identity`）；沒配對的人先用邀請連結加入過一次對話就有身分，也能拿 ID。
@MainActor
final class TalkFriends: ObservableObject {
    static let shared = TalkFriends()

    struct Request: Identifiable, Equatable {
        let base: String, id: String, name: String, lang: String, uid: String
        var key: String { "\(id)@\(base)" }
    }

    @Published private(set) var myUid: String?
    @Published private(set) var requests: [Request] = []
    /// 我加了、對方還沒接受：「id@主機」
    @Published private(set) var pending: Set<String> = []

    init() { if let b = base { myUid = UserDefaults.standard.string(forKey: "talk_uid:\(b)") } }

    /// 用哪台主機：配對過的那台；沒配對就用有身分的第一台。
    var base: String? {
        LocalAI.configured ? LocalAI.url.trimmingCharacters(in: CharacterSet(charactersIn: "/")) : TalkStore.bases.first
    }

    struct Failure: LocalizedError { let message: String; var errorDescription: String? { message } }

    private func error(_ code: Int, _ j: [String: Any]) -> Failure {
        let e = j["error"] as? String ?? ""
        return Failure(message: !e.isEmpty ? e : (code == 404 ? "大D的主機還沒有加好友功能：請在 Mac 上重新部署大D的主機".tr : "連線失敗（{0}）".trf(code)))
    }

    /// 取得（或第一次註冊）自己的 ID。
    @discardableResult
    func register() async throws -> String {
        guard let b = base else {
            throw Failure(message: "要先跟大D的主機配對（設定 → 大D的主機），或先用邀請連結加入過一次對話".tr)
        }
        let id = TalkStore.identity(b)
        let (code, j) = try await RemoteTalk.shared.http("POST", b + "/v1/talk/register",
                                                         body: ["name": RemoteTalk.myName, "lang": RemoteTalk.shared.mySource],
                                                         auth: id == nil && LocalAI.configured ? LocalAI.token : nil,
                                                         contactToken: id?.token)
        guard code == 200 else { throw error(code, j) }
        if let ct = j["contact"] as? [String: Any], let cid = ct["id"] as? String {
            TalkStore.saveIdentity(b, id: cid, token: ct["token"] as? String)
        }
        let uid = j["uid"] as? String ?? ""
        UserDefaults.standard.set(uid, forKey: "talk_uid:\(b)")
        myUid = uid
        RemoteTalk.shared.startPresence()
        return uid
    }

    /// 輸入對方 ID 加好友；回傳給人看的結果。
    func add(_ uid: String) async throws -> String {
        guard let b = base else { throw Failure(message: "要先跟大D的主機配對".tr) }
        if TalkStore.identity(b) == nil { try await register() }
        guard let id = TalkStore.identity(b) else { throw Failure(message: "沒有通話身分".tr) }
        let (code, j) = try await RemoteTalk.shared.http("POST", b + "/v1/talk/friend", body: ["id": uid], contactToken: id.token)
        guard code == 200, let f = j["friend"] as? [String: Any], let fid = f["id"] as? String else { throw error(code, j) }
        let name = f["name"] as? String ?? ""
        TalkStore.shared.upsert(.init(id: fid, name: name, lang: f["lang"] as? String ?? "", base: b, lan: nil, lastTalk: Date(),
                                      uid: (f["uid"] as? String).flatMap { $0.isEmpty ? nil : $0 }))
        if j["status"] as? String == "pending" {
            pending.insert("\(fid)@\(b)")
            return "已送出好友邀請給 {0}，對方接受後就能互相撥打".trf(name)
        }
        pending.remove("\(fid)@\(b)")
        return "{0} 已經是你的好友".trf(name)
    }

    func answer(_ r: Request, accept: Bool) async throws {
        guard let id = TalkStore.identity(r.base) else { return }
        let (code, j) = try await RemoteTalk.shared.http("POST", r.base + "/v1/talk/friend/answer",
                                                         body: ["from": r.id, "accept": accept], contactToken: id.token)
        guard code == 200 else { throw error(code, j) }
        requests.removeAll { $0.key == r.key }
        if accept {
            TalkStore.shared.upsert(.init(id: r.id, name: r.name, lang: r.lang, base: r.base, lan: nil, lastTalk: Date(),
                                          uid: r.uid.isEmpty ? nil : r.uid))
        }
    }

    /// 刪除好友（主機上雙方都拿掉；手機上的對話記錄留著）。
    func remove(_ k: TalkStore.Contact) async {
        if let id = TalkStore.identity(k.base) {
            _ = try? await RemoteTalk.shared.http("POST", k.base + "/v1/talk/unfriend", body: ["id": k.id], contactToken: id.token)
        }
        pending.remove(k.key)
        TalkStore.shared.remove(k)
    }

    func isPending(_ k: TalkStore.Contact) -> Bool { pending.contains(k.key) }

    /// 上線回報的結果（RemoteTalk 每 20 秒）。
    func onPresence(base b: String, uid: String?, pending ids: Set<String>, requests reqs: [Request]) {
        if let uid, !uid.isEmpty {
            UserDefaults.standard.set(uid, forKey: "talk_uid:\(b)")
            if b == base { myUid = uid }
        }
        pending = pending.filter { !$0.hasSuffix("@\(b)") }.union(ids.map { "\($0)@\(b)" })
        requests = requests.filter { $0.base != b } + reqs
    }

    /// 使用者打的 ID → 顯示格式（大寫、只留可能的字、4-4）。
    static func format(_ raw: String) -> String {
        let ok = Set("ABCDEFGHJKMNPQRSTUVWXYZ23456789")
        let t = String(raw.uppercased().filter { ok.contains($0) }.prefix(8))
        return t.count > 4 ? String(t.prefix(4)) + "-" + String(t.dropFirst(4)) : t
    }
}
