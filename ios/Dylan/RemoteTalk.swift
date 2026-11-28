import SwiftUI
import Combine
#if canImport(Translation)
import Translation
#endif

/// 遠距對話（兩地即時翻譯，照 Android `RemoteTalk.kt`、交接/HANDOFF.md「遠距對話」「遠距對話 v2」）：兩支手機透過大D的主機（local-ai/talk.py）轉送文字與原聲。
/// - 自己說的話：即時翻譯同一套辨識（`LiveCaptions`），每一句（含還在說的暫時句，最多每 0.6 秒）送原文＋辨識代碼；說完的一句剪原聲（`TalkClip`）先上傳再送定稿。
/// - 對方說的話：在自己手機上用 Apple 翻譯翻成自己的語言；先播對方原聲、再唸翻譯（`TalkPlayer`）。
/// - 通話對象：主機給一個長期身分（token 存鑰匙圈）；同房待過的人互相成為對象，可以看上線綠點、直接撥打；App 開著時每 20 秒回報上線、收來電。
/// - 對話記錄存在手機（`TalkStore`），可以回播。
@MainActor
final class RemoteTalk: ObservableObject {
    static let shared = RemoteTalk()

    struct Member: Equatable { let id: String, name: String, lang: String, owner: Bool, contact: String? }
    /// 一句：mine = 自己說的；translated = 翻成自己語言的（自己那句顯示對方看到的語言）；system = 加入／離開
    struct Line: Identifiable, Equatable {
        let key: String
        var from: String, name: String, original: String, lang: String
        var translated: String?
        var mine: Bool, final: Bool
        var system: String?
        var audio: String?
        var id: String { key }
    }
    struct Call: Identifiable, Equatable {
        let base: String, room: String, code: String, from: String, name: String, lang: String, live: Bool, at: Date
        var id: String { room }
    }

    @Published private(set) var room: String?
    @Published private(set) var link: String?
    @Published private(set) var me: String?
    @Published private(set) var members: [Member] = []
    @Published private(set) var lines: [Line] = []
    @Published private(set) var connected = false
    @Published private(set) var ended = false
    @Published var error: String?
    @Published private(set) var busy = false
    /// 正在打給誰（對方還沒接）
    @Published private(set) var calling: String?
    /// 收到的邀請連結（dylan://talk）：對話頁看到就問要不要加入
    @Published var pendingInvite: String?
    /// 要翻成自己語言的句子（TalkTranslateHost 拿去翻）
    @Published private(set) var toTranslate: [(key: String, text: String, lang: String)] = []
    /// 通話對象誰在線上（key = id@base）、來電
    @Published private(set) var online: [String: Bool] = [:]
    @Published private(set) var calls: [Call] = []

    static let nameKey = "talk_name", readAloudKey = "talk_speak", playOriginalKey = "talk_play_original"
    static var myName: String {
        get { UserDefaults.standard.string(forKey: nameKey).flatMap { $0.isEmpty ? nil : $0 } ?? DeviceInfo.name }
        set { UserDefaults.standard.set(newValue, forKey: nameKey) }
    }
    static var speakTranslation: Bool { UserDefaults.standard.object(forKey: readAloudKey) as? Bool ?? true }
    static var playOriginal: Bool { UserDefaults.standard.object(forKey: playOriginalKey) as? Bool ?? true }

    /// 連上的位址、主機記住身分用的代表位址、家裡網路的位址
    private var base: String?
    private var canon: String?
    private var lan: String?
    private var token: String?
    private var pollTask: Task<Void, Never>?
    private var presenceTask: Task<Void, Never>?
    private var captionSub: AnyCancellable?
    private var sent: [Int: (String, Bool)] = [:]
    private var sentAt: [Int: Date] = [:]
    private var lineStart: [Int: Int64] = [:]
    private var lastEnd: Int64 = 0
    private var talk: TalkStore.Talk?
    /// 對方定稿的句子：等翻譯好再播、存
    private var finals: [String: (seq: Int, audio: String?)] = [:]

    /// 我說的（辨識代碼）與我看的（翻譯代碼）。
    var mySource: String { TranslateLangs.source }
    var myReading: String { TranslateLangs.translationId(source: mySource) }

    private func resetState() {
        room = nil; link = nil; me = nil; members = []; lines = []; connected = false; ended = false; error = nil
        calling = nil; sent = [:]; sentAt = [:]; lineStart = [:]; toTranslate = []; finals = [:]
    }

    func isOnline(_ k: TalkStore.Contact) -> Bool { online[k.key] == true }

    // MARK: HTTP

    private func http(_ method: String, _ url: String, body: [String: Any]? = nil, auth: String? = nil, talkToken: String? = nil,
                      contactToken: String? = nil, raw: Data? = nil, timeout: TimeInterval = 15) async throws -> (Int, [String: Any]) {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        var r = URLRequest(url: u, timeoutInterval: timeout)
        r.httpMethod = method
        r.setValue(raw != nil ? "audio/mp4" : "application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        if let auth { r.setValue("Bearer \(auth)", forHTTPHeaderField: "Authorization") }
        if let talkToken { r.setValue(talkToken, forHTTPHeaderField: "X-Talk-Token") }
        if let contactToken { r.setValue(contactToken, forHTTPHeaderField: "X-Contact-Token") }
        if let raw { r.httpBody = raw } else if let body { r.httpBody = try JSONSerialization.data(withJSONObject: body) }
        let (data, resp) = try await URLSession.shared.data(for: r)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        return (code, (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:])
    }

    /// 主機回的錯誤 → 給人看的文字。舊版主機沒有遠距對話（回 404「not found」）→ 請在 Mac 上更新。
    private func errorText(_ code: Int, _ j: [String: Any]) -> String {
        let e = j["error"] as? String ?? ""
        if code == 404 && (e.isEmpty || e.lowercased() == "not found") {
            return "大D的主機還沒有遠距對話功能：請在 Mac 上重新部署大D的主機（更新到最新版）".tr
        }
        return e.isEmpty ? "連線失敗（{0}）".trf(code) : e
    }

    nonisolated private static func trim(_ u: String) -> String { u.trimmingCharacters(in: CharacterSet(charactersIn: "/")) }

    /// 主機回的身分 → 存起來（token 只有第一次給）。
    private func keepIdentity(_ b: String, _ j: [String: Any]) {
        guard let ct = j["contact"] as? [String: Any], let id = ct["id"] as? String else { return }
        TalkStore.saveIdentity(b, id: id, token: ct["token"] as? String)
    }

    /// 進房：b 代表位址、connectedURL 實際連上的位址。
    private func enter(_ b: String, lan l: String?, _ j: [String: Any], link lk: String?, calling who: String? = nil, connectedURL: String? = nil) {
        resetState()
        canon = b; base = connectedURL ?? b; lan = l; token = j["token"] as? String
        keepIdentity(b, j)
        room = j["room"] as? String; link = lk; me = j["member"] as? String
        calling = who
        connected = true
        let now = Date()
        talk = TalkStore.Talk(id: "\(room ?? "talk")_\(Int(now.timeIntervalSince1970 * 1000))", startedAt: now, endedAt: now, partners: [], lines: [])
        if let a = j["members"] as? [[String: Any]] { handle(["members": a]) }
        startLoops()
        startPresence()
    }

    // MARK: 開房 / 加入 / 打給對象 / 離開

    func create() {
        guard LocalAI.configured else {
            error = "開遠距對話要先跟大D的主機配對（設定 → 大D的主機）".tr
            return
        }
        resetState(); busy = true
        let url = Self.trim(LocalAI.url)
        Task {
            defer { busy = false }
            var body: [String: Any] = ["name": Self.myName, "lang": mySource]
            if let id = TalkStore.identity(url) { body["contact"] = id.token }
            do {
                let (code, j) = try await http("POST", url + "/v1/talk", body: body, auth: LocalAI.token)
                guard code == 200 else { error = errorText(code, j); return }
                let lk = j["link"] as? String
                let l = lk.flatMap { URLComponents(string: $0)?.queryItems?.first { $0.name == "lan" }?.value }.map(Self.trim)
                enter(url, lan: l, j, link: lk)
            } catch {
                self.error = "連不到大D的主機：{0}".trf(error.localizedDescription)
            }
        }
    }

    /// 用邀請連結加入（先試家裡網路的位址，再試外面的）。
    func join(_ linkText: String) {
        let comps = URLComponents(string: linkText.trimmingCharacters(in: .whitespacesAndNewlines))
        func q(_ k: String) -> String? { comps?.queryItems?.first { $0.name == k }?.value.flatMap { $0.isEmpty ? nil : $0 } }
        guard let r = q("room"), let c = q("code") else { error = "邀請連結看不懂".tr; return }
        let url = q("url").flatMap { $0.hasPrefix("http") ? Self.trim($0) : nil }
        let l = q("lan").flatMap { $0.hasPrefix("http") ? Self.trim($0) : nil }
        guard let b = url ?? l else { error = "邀請連結看不懂".tr; return }
        pendingInvite = nil
        joinRoom(b, lan: l, room: r, code: c)
    }

    private func joinRoom(_ b: String, lan l: String?, room r: String, code c: String) {
        resetState(); busy = true
        Task {
            defer { busy = false }
            var last: String?
            var urls: [String] = []
            for u in [l, b].compactMap({ $0 }) where !urls.contains(u) { urls.append(u) }
            for url in urls {
                var body: [String: Any] = ["room": r, "code": c, "name": Self.myName, "lang": mySource]
                if let id = TalkStore.identity(b) { body["contact"] = id.token }
                do {
                    let (code, j) = try await http("POST", url + "/v1/talk/join", body: body, timeout: 6)
                    guard code == 200 else { error = errorText(code, j); return }
                    enter(b, lan: l, j, link: nil, connectedURL: url)
                    return
                } catch { last = error.localizedDescription }
            }
            error = "連不到對方的主機：{0}".trf(last ?? "")
        }
    }

    /// 接來電。
    func answer(_ call: Call) {
        calls.removeAll { $0.room == call.room }
        let k = TalkStore.shared.contacts.first { $0.id == call.from && $0.base == call.base }
        joinRoom(call.base, lan: k?.lan, room: call.room, code: call.code)
    }

    /// 拒接／看過的未接來電拿掉。
    func dismiss(_ call: Call) {
        calls.removeAll { $0.room == call.room }
        guard let id = TalkStore.identity(call.base) else { return }
        Task { _ = try? await http("POST", "\(call.base)/v1/talk/dismiss", body: ["room": call.room], contactToken: id.token) }
    }

    /// 直接打給通話過的對象：開房並在對方那邊留來電。
    func call(_ k: TalkStore.Contact) {
        guard let id = TalkStore.identity(k.base) else {
            error = "這台主機上沒有你的通話身分，請先用邀請連結對話一次".tr
            return
        }
        resetState(); busy = true
        Task {
            defer { busy = false }
            var last: String?
            var urls: [String] = []
            for u in [k.lan, k.base].compactMap({ $0 }) where !urls.contains(u) { urls.append(u) }
            for url in urls {
                do {
                    let (code, j) = try await http("POST", url + "/v1/talk/call", body: ["to": k.id, "name": Self.myName, "lang": mySource],
                                                   contactToken: id.token, timeout: 6)
                    guard code == 200 else { error = errorText(code, j); return }
                    enter(k.base, lan: k.lan, j, link: nil, calling: k.name, connectedURL: url)
                    return
                } catch { last = error.localizedDescription }
            }
            error = "連不到主機：{0}".trf(last ?? "")
        }
    }

    func leave() {
        let b = base, t = token, r = room
        pollTask?.cancel(); pollTask = nil
        captionSub = nil
        stopMic()
        TalkPlayer.shared.stopAll()
        if let b, let t, let r { Task { _ = try? await http("DELETE", "\(b)/v1/talk/\(r)", talkToken: t) } }
        base = nil; canon = nil; token = nil; talk = nil
        connected = false; ended = true; calling = nil
    }

    func reset() { if connected { leave() }; resetState() }

    // MARK: 說話（麥克風開關）

    /// 開始／暫停聽自己說話（翻成對方的語言，自己的畫面也看得到對方大概會看到什麼）。
    func toggleMic() {
        let lc = LiveCaptions.shared
        if lc.running { stopMic(); return }
        let other = members.first { $0.id != me }
        var target = other.map { TranslateLangs.translationId(source: $0.lang) } ?? ""
        if target.isEmpty || TranslateLangs.same(source: mySource, target: target) { target = myReading.hasPrefix("en") ? "zh-Hant" : "en" }
        TalkClip.enabled = true
        lastEnd = TalkClip.samples
        lc.start(sourceId: mySource, targetId: target)
    }

    private func stopMic() {
        if LiveCaptions.shared.running { LiveCaptions.shared.stop() }
        TalkClip.enabled = false
    }

    // MARK: 上線回報（App 開著時每 20 秒；回這些對象誰在線上、誰打給我）

    func startPresence() {
        guard presenceTask == nil, !TalkStore.bases.isEmpty else { return }
        presenceTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.presenceOnce()
                try? await Task.sleep(nanoseconds: 20_000_000_000)
            }
        }
    }

    func stopPresence() { presenceTask?.cancel(); presenceTask = nil }

    private func presenceOnce() async {
        var on: [String: Bool] = [:]
        var found: [Call] = []
        let store = TalkStore.shared
        for b in TalkStore.bases {
            guard let id = TalkStore.identity(b) else { continue }
            let watch = store.contacts.filter { $0.base == b }
            var urls: [String] = []
            for u in [watch.compactMap(\.lan).first, b].compactMap({ $0 }) where !urls.contains(u) { urls.append(u) }
            for url in urls {
                guard let (code, j) = try? await http("POST", url + "/v1/talk/presence",
                                                      body: ["watch": watch.map(\.id), "name": Self.myName, "lang": mySource],
                                                      contactToken: id.token, timeout: 8) else { continue }
                if code != 200 { break }
                for (k, v) in (j["peers"] as? [String: [String: Any]]) ?? [:] {
                    on["\(k)@\(b)"] = v["online"] as? Bool ?? false
                    if let old = watch.first(where: { $0.id == k }), let n = v["name"] as? String, !n.isEmpty, n != old.name {
                        var c = old; c.name = n; if let l = v["lang"] as? String, !l.isEmpty { c.lang = l }
                        store.upsert(c)
                    }
                }
                for o in (j["calls"] as? [[String: Any]]) ?? [] {
                    found.append(Call(base: b, room: o["room"] as? String ?? "", code: o["code"] as? String ?? "",
                                      from: o["from"] as? String ?? "", name: o["name"] as? String ?? "", lang: o["lang"] as? String ?? "",
                                      live: o["live"] as? Bool ?? false, at: Date(timeIntervalSince1970: o["at"] as? Double ?? 0)))
                }
                break
            }
        }
        online = on
        // 正在通話中的那通不算來電
        calls = found.filter { $0.room != room }
    }

    // MARK: 背景迴圈

    private func startLoops() {
        pollTask?.cancel()
        // 收：長輪詢（最多等 25 秒），斷線就過幾秒再連
        pollTask = Task { [weak self] in
            var after = 0, fails = 0
            while !Task.isCancelled {
                guard let self, let b = self.base, let t = self.token, let r = self.room else { break }
                do {
                    let (code, j) = try await self.http("GET", "\(b)/v1/talk/\(r)?after=\(after)&wait=25", talkToken: t, timeout: 35)
                    if code != 200 {
                        self.error = self.errorText(code, j)
                        if code == 404 || code == 401 { self.connected = false; break }
                        try? await Task.sleep(nanoseconds: 3_000_000_000); continue
                    }
                    fails = 0
                    after = (j["seq"] as? Int) ?? after
                    self.handle(j)
                    if j["ended"] as? Bool == true { self.ended = true; self.connected = false; self.stopMic(); break }
                } catch {
                    if Task.isCancelled { break }
                    fails += 1
                    if fails >= 2 { self.error = "連線中斷，重新連線中…".tr }
                    try? await Task.sleep(nanoseconds: UInt64(min(10.0, 1.5 * Double(fails)) * 1_000_000_000))
                }
            }
        }
        // 送：看 LiveCaptions 的句子，有新的或變了就送（暫時句最多每 0.6 秒一次）
        captionSub = LiveCaptions.shared.$lines.sink { [weak self] ls in
            Task { @MainActor in self?.sendMine(ls) }
        }
    }

    private func sendMine(_ ls: [LiveCaptions.Line]) {
        guard connected, LiveCaptions.shared.running else { return }
        let lang = LiveCaptions.shared.source
        for l in ls.suffix(6) {
            if lineStart[l.id] == nil {
                // 辨識器出字比開口晚：往前抓 1.2 秒，但不早於上一句結束
                lineStart[l.id] = max(lastEnd, TalkClip.samples - Int64(1.2 * TalkClip.sampleRate))
            }
            let final = !l.partial
            if let prev = sent[l.id], prev.0 == l.original, prev.1 == final {
                // 自己那句的翻譯晚到：更新畫面
                if final, let me, let i = lines.firstIndex(where: { $0.key == "\(me):m\(l.id)" }), lines[i].translated != l.translated {
                    lines[i].translated = l.translated
                }
                continue
            }
            if l.partial, let at = sentAt[l.id], Date().timeIntervalSince(at) < 0.6 { continue }
            sentAt[l.id] = Date()
            sent[l.id] = (l.original, final)
            if let me { upsert(Line(key: "\(me):m\(l.id)", from: me, name: "", original: l.original, lang: lang,
                                    translated: l.translated ?? l.draft, mine: true, final: final)) }
            if final { finishMine(l.id, l.original, lang, l.translated) } else { post(l.id, l.original, lang, false, nil) }
        }
    }

    /// 自己說完一句：剪原聲 → 上傳 → 送定稿（帶原聲）→ 存進記錄。
    private func finishMine(_ id: Int, _ text: String, _ lang: String, _ translated: String?) {
        let start = lineStart[id] ?? lastEnd
        let end = TalkClip.samples
        lastEnd = end
        let b = base, t = token, r = room, tk = talk
        Task {
            var aid: String?
            var file: URL?
            if let tk {
                let out = TalkStore.dir(tk.id).appendingPathComponent("m\(id).m4a")
                file = await Task.detached { TalkClip.clip(from: start, to: end, out: out) }.value
                if let file, let b, let t, let r, let data = try? Data(contentsOf: file),
                   let (code, j) = try? await http("POST", "\(b)/v1/talk/\(r)/audio", talkToken: t, raw: data), code == 200 {
                    aid = j["audio"] as? String
                }
            }
            post(id, text, lang, true, aid)
            record(.init(mine: true, name: Self.myName, text: text, lang: lang, translated: translated, audio: file?.lastPathComponent, at: Date()))
        }
    }

    private func post(_ id: Int, _ text: String, _ lang: String, _ final: Bool, _ audio: String?) {
        guard let b = base, let t = token, let r = room else { return }
        var body: [String: Any] = ["line": "m\(id)", "text": text, "lang": lang, "final": final]
        if let audio { body["audio"] = audio }
        Task { _ = try? await http("POST", "\(b)/v1/talk/\(r)/msg", body: body, talkToken: t) }
    }

    private func upsert(_ line: Line) {
        if let i = lines.firstIndex(where: { $0.key == line.key }) { lines[i] = line } else { lines.append(line) }
        if lines.count > 300 { lines.removeFirst(lines.count - 300) }
    }

    /// 存進這次對話的記錄（定稿的句子）。
    private func record(_ line: TalkStore.TalkLine) {
        guard var tk = talk else { return }
        let partners = members.filter { $0.id != me }.map(\.name)
        tk.partners = partners.isEmpty ? tk.partners : partners
        tk.endedAt = Date()
        tk.lines.append(line)
        talk = tk
        TalkStore.shared.save(tk)
    }

    private func handle(_ j: [String: Any]) {
        if let a = j["members"] as? [[String: Any]] {
            members = a.map { Member(id: $0["id"] as? String ?? "", name: $0["name"] as? String ?? "",
                                     lang: $0["lang"] as? String ?? "", owner: $0["owner"] as? Bool ?? false,
                                     contact: ($0["contact"] as? String).flatMap { $0.isEmpty ? nil : $0 }) }
            if members.count > 1 { calling = nil }
            // 記住通話對象（記在這台主機的代表位址下）
            if let host = canon {
                for m in members where m.id != me {
                    if let c = m.contact { TalkStore.shared.upsert(.init(id: c, name: m.name, lang: m.lang, base: host, lan: lan, lastTalk: Date())) }
                }
            }
        }
        if let m = j["me"] as? String, !m.isEmpty { me = m }
        connected = true; error = nil
        for m in (j["msgs"] as? [[String: Any]]) ?? [] {
            let from = m["from"] as? String ?? "", name = m["name"] as? String ?? ""
            if let sys = m["sys"] as? String {
                if from != me {
                    upsert(Line(key: "sys\(m["seq"] as? Int ?? 0)", from: from, name: name, original: "", lang: "", translated: nil,
                                mine: false, final: true,
                                system: sys == "join" ? "{0} 加入了對話".trf(name) : "{0} 離開了對話".trf(name)))
                }
                continue
            }
            if from == me { continue }
            let key = "\(from):\(m["line"] as? String ?? "")"
            let text = m["text"] as? String ?? "", lang = m["lang"] as? String ?? "", final = m["final"] as? Bool ?? false
            let old = lines.first { $0.key == key }?.translated
            upsert(Line(key: key, from: from, name: name, original: text, lang: lang, translated: old, mine: false, final: final,
                        audio: m["audio"] as? String))
            if final { finals[key] = (m["seq"] as? Int ?? 0, m["audio"] as? String) }
            if TranslateLangs.same(source: lang, target: myReading) || text.isEmpty {
                setTranslated(key, text)
            } else {
                toTranslate.append((key, text, lang))
            }
        }
    }

    /// 拿走要翻的句子（空的時候不要再設一次，免得 onReceive 一直被叫）。
    func takeTranslations() -> [(key: String, text: String, lang: String)] {
        guard !toTranslate.isEmpty else { return [] }
        defer { toTranslate = [] }
        return toTranslate
    }

    func translationFailed(_ key: String, _ original: String, _ why: String) {
        if let i = lines.firstIndex(where: { $0.key == key }), lines[i].translated == nil { setTranslated(key, original, apply: false) }
        error = "這支手機翻不了對方的語言（{0}）。可以到「翻譯」分頁用同樣的語言翻一次，系統會問要不要下載。".trf(why)
    }

    func setTranslated(_ key: String, _ raw: String, apply: Bool = true) {
        guard let i = lines.firstIndex(where: { $0.key == key }) else { return }
        let text = apply ? TranslateFixes.apply(raw, lang: myReading) : raw
        lines[i].translated = text
        let l = lines[i]
        guard l.final, !l.mine, let f = finals.removeValue(forKey: key) else { return }
        if LiveCaptions.watchCaptions { PhoneLink.shared.sendCaption(text, original: l.original) }
        // 說完的一句：下載原聲 → 先播原聲、再唸翻譯；存進記錄
        let b = base, t = token, r = room, tk = talk, reading = myReading
        Task {
            var clip: URL?
            if let aid = f.audio, let tk, let b, let t, let r, let u = URL(string: "\(b)/v1/talk/\(r)/audio/\(aid)") {
                var req = URLRequest(url: u, timeoutInterval: 20)
                req.setValue(t, forHTTPHeaderField: "X-Talk-Token")
                if let (data, resp) = try? await URLSession.shared.data(for: req), (resp as? HTTPURLResponse)?.statusCode == 200 {
                    let out = TalkStore.dir(tk.id).appendingPathComponent("r\(f.seq).m4a")
                    if (try? data.write(to: out)) != nil { clip = out }
                }
            }
            TalkPlayer.shared.enqueue(clip: Self.playOriginal ? clip : nil,
                                      text: Self.speakTranslation && text != l.original ? text : nil, lang: reading)
            record(.init(mine: false, name: l.name, text: l.original, lang: l.lang, translated: text, audio: clip?.lastPathComponent, at: Date()))
        }
    }

    // MARK: 回播

    /// 回播一次對話：照順序播每句的原聲，對方的句子再唸翻譯。
    func replay(_ t: TalkStore.Talk, from: Int = 0) {
        TalkPlayer.shared.stopAll()
        let dir = TalkStore.dir(t.id)
        for l in t.lines.dropFirst(from) {
            TalkPlayer.shared.enqueue(clip: l.audio.map { dir.appendingPathComponent($0) },
                                      text: !l.mine && l.translated != l.text ? l.translated : nil, lang: myReading)
        }
    }

    func replayLine(_ t: TalkStore.Talk, _ l: TalkStore.TalkLine) {
        TalkPlayer.shared.stopAll()
        TalkPlayer.shared.enqueue(clip: l.audio.map { TalkStore.dir(t.id).appendingPathComponent($0) },
                                  text: !l.mine && l.translated != l.text ? l.translated : nil, lang: myReading)
    }
}

/// 對方說的話翻成自己的語言（iOS 18 以上 Apple 翻譯；以下只顯示原文）。依來源語言換翻譯 session。
struct TalkTranslateHost: View {
    @ObservedObject private var talk = RemoteTalk.shared

    var body: some View {
        if #available(iOS 18.0, *) {
            Worker(talk: talk)
        } else {
            Color.clear.onReceive(talk.$toTranslate) { q in
                guard !q.isEmpty else { return }
                for t in talk.takeTranslations() { talk.setTranslated(t.key, t.text) }
            }
        }
    }

    @available(iOS 18.0, *)
    private struct Worker: View {
        @ObservedObject var talk: RemoteTalk
        @State private var config: TranslationSession.Configuration?
        @State private var waiting: [(key: String, text: String, lang: String)] = []
        @State private var currentSource = ""

        var body: some View {
            Color.clear
                .onReceive(talk.$toTranslate) { q in
                    guard !q.isEmpty else { return }
                    waiting += talk.takeTranslations()
                    pick()
                }
                .translationTask(config) { session in
                    let src = currentSource
                    NSLog("[RemoteTalk] translate session \(src) → \(talk.myReading), \(waiting.count) waiting")
                    let batch = waiting.filter { TranslateLangs.translationId(source: $0.lang) == src }
                    waiting.removeAll { TranslateLangs.translationId(source: $0.lang) == src }
                    for item in batch {
                        do {
                            let r = try await session.translate(item.text)
                            talk.setTranslated(item.key, r.targetText)
                        } catch {
                            NSLog("[RemoteTalk] translate failed: \(error)")
                            // 翻不了（語言沒下載、不支援）：先顯示原文，並說明
                            talk.translationFailed(item.key, item.text, error.localizedDescription)
                        }
                    }
                    config = nil
                    pick()
                }
        }

        /// 下一批：同一個來源語言一起翻。
        private func pick() {
            guard config == nil, let first = waiting.first else { return }
            currentSource = TranslateLangs.translationId(source: first.lang)
            NSLog("[RemoteTalk] pick \(currentSource) → \(talk.myReading)")
            config = TranslationSession.Configuration(source: Locale.Language(identifier: currentSource),
                                                      target: Locale.Language(identifier: talk.myReading))
        }
    }
}

/// 遠距對話頁（「翻譯」分頁右上「遠距對話」）。
struct TalkView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var talk = RemoteTalk.shared
    @ObservedObject private var store = TalkStore.shared
    @ObservedObject private var captions = LiveCaptions.shared
    @ObservedObject private var player = TalkPlayer.shared
    @AppStorage(RemoteTalk.readAloudKey) private var speak = true
    @AppStorage(RemoteTalk.playOriginalKey) private var playOriginal = true
    @State private var name = RemoteTalk.myName
    @State private var invite = ""
    @State private var openTalk: TalkStore.Talk?
    @State private var removing: TalkStore.Contact?
    @State private var deleting: TalkStore.Talk?

    var body: some View {
        VStack(spacing: 0) {
            TopBar(title: "遠距對話".tr, onBack: { dismiss() }) {
                if talk.connected {
                    GhostButton(text: "掛斷".tr, tint: Brand.warning) { talk.leave() }
                }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let e = talk.error {
                        Text(e).font(.bodyMedium).foregroundStyle(Color.white)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .background(RoundedRectangle(cornerRadius: 12).fill(Brand.warning))
                    }
                    if talk.connected { roomView } else { startView }
                }
                .pageColumn()
                .padding(.vertical, 12)
            }
            if talk.connected { micBar }
        }
        .background(AppBackdrop())
        .background(TalkTranslateHost())
        .onAppear {
            if let p = talk.pendingInvite { invite = p }
            talk.startPresence()
        }
        .onChange(of: talk.pendingInvite) { p in if let p { invite = p } }
        .sheet(item: $openTalk) { t in TalkRecordView(talk: t) }
        .confirmationDialog("要把 {0} 從通話對象移除嗎？以前的對話記錄會留著。".trf(removing?.name ?? ""),
                            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
            Button("移除".tr, role: .destructive) { if let k = removing { store.remove(k) }; removing = nil }
            Button("取消".tr, role: .cancel) { removing = nil }
        }
        .confirmationDialog("刪除這次對話？".tr, isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible) {
            Button("刪除".tr, role: .destructive) { if let t = deleting { store.delete(t.id) }; deleting = nil }
            Button("取消".tr, role: .cancel) { deleting = nil }
        } message: { Text("文字和雙方的原聲都會從這支手機刪除。".tr) }
    }

    // MARK: 還沒進房

    private var startView: some View {
        VStack(alignment: .leading, spacing: 12) {
            if talk.ended { Text("對話已結束。".tr).font(.bodyMedium).foregroundStyle(Brand.textMuted) }
            ForEach(talk.calls) { c in callCard(c) }
            GlassCard {
                Text("兩個人在不同地方、用各自的手機說各自的語言。對方每說完一句，你會先聽到他的原聲，再聽到翻譯。文字和原聲經過你的大D的主機轉送，主機不存檔；對話記錄存在你的手機。建議戴有麥克風的耳機。".tr)
                    .font(.bodySmall).foregroundStyle(Brand.textMuted)
                Text("你的名字（對方會看到）".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.top, 10)
                TextField("你的名字（對方會看到）".tr, text: $name).textFieldStyle(.roundedBorder)
                    .onChange(of: name) { RemoteTalk.myName = $0 }
                Text("你說的語言：{0}（在「翻譯」頁的「說的」可以改）".trf(TranslateLangs.name(talk.mySource)))
                    .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 6)
                Toggle(isOn: $playOriginal) { Text("對方原聲".tr).font(.bodyMedium).foregroundStyle(Brand.text) }.tint(Brand.cyan).padding(.top, 6)
                Toggle(isOn: $speak) { Text("唸翻譯".tr).font(.bodyMedium).foregroundStyle(Brand.text) }.tint(Brand.cyan)
            }
            if !store.contacts.isEmpty {
                GlassCard {
                    Text("通話對象".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 4)
                    ForEach(store.contacts) { k in contactRow(k) }
                }
            }
            GlassCard {
                Text("新的對話".tr).font(.titleSmall).foregroundStyle(Brand.text)
                Text("把邀請連結傳給對方（LINE、簡訊…）。對方要先裝大D，點連結就會加入。".tr)
                    .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.vertical, 6)
                GradientButton(text: talk.busy ? "連線中…".tr : "開新對話，邀請對方".tr, enabled: !talk.busy) { talk.create() }
                Text("或貼上對方傳來的邀請連結".tr).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 10)
                TextField("dylan://talk?…", text: $invite, axis: .vertical).textFieldStyle(.roundedBorder).padding(.vertical, 6)
                    .autocorrectionDisabled().textInputAutocapitalization(.never)
                HStack(spacing: 8) {
                    GhostButton(text: "貼上".tr, systemImage: "doc.on.clipboard", tint: Brand.cyan) {
                        invite = UIPasteboard.general.string ?? invite
                    }
                    GradientButton(text: "加入對話".tr, enabled: !talk.busy && !invite.isEmpty) { talk.join(invite) }
                }
            }
            if !store.talks.isEmpty {
                GlassCard {
                    Text("以前的對話".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 4)
                    ForEach(store.talks.prefix(30)) { t in
                        Button { openTalk = t } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(t.partners.isEmpty ? "遠距對話".tr : t.partners.joined(separator: "、"))
                                        .font(.bodyMedium).foregroundStyle(Brand.text)
                                    Text(Fmt.stamp(t.startedAt, "M/d HH:mm") + " · " + "{0} 句".trf(t.lines.count))
                                        .font(.labelMedium).foregroundStyle(Brand.textMuted)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(Brand.textFaint)
                            }
                            .padding(.vertical, 6)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .contextMenu { Button(role: .destructive) { deleting = t } label: { Label("刪除".tr, systemImage: "trash") } }
                    }
                }
            }
        }
    }

    private func contactRow(_ k: TalkStore.Contact) -> some View {
        HStack(spacing: 10) {
            Circle().fill(talk.isOnline(k) ? Brand.success : Brand.textFaint.opacity(0.4)).frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text(k.name.isEmpty ? "?" : k.name).font(.bodyMedium).foregroundStyle(Brand.text)
                Text((talk.isOnline(k) ? "上線中".tr : "離線".tr) + " · " + TranslateLangs.name(k.lang))
                    .font(.labelMedium).foregroundStyle(Brand.textMuted)
            }
            Spacer()
            GhostButton(text: "撥打".tr, systemImage: "phone.fill", tint: Brand.cyan) { talk.call(k) }
                .disabled(talk.busy)
        }
        .padding(.vertical, 4)
        .contextMenu { Button(role: .destructive) { removing = k } label: { Label("移除".tr, systemImage: "person.badge.minus") } }
    }

    private func callCard(_ c: RemoteTalk.Call) -> some View {
        GlassCard {
            Text(c.live ? "{0} 打來了".trf(c.name) : "未接來電".tr + "：" + c.name)
                .font(.titleSmall).foregroundStyle(c.live ? Brand.text : Brand.warning)
            Text(Fmt.stamp(c.at, "M/d HH:mm") + " · " + TranslateLangs.name(c.lang)).font(.labelMedium).foregroundStyle(Brand.textMuted)
            HStack(spacing: 8) {
                if c.live {
                    GradientButton(text: "接聽".tr, systemImage: "phone.fill") { talk.answer(c) }
                    GhostButton(text: "拒接".tr, tint: Brand.warning) { talk.dismiss(c) }
                } else {
                    if let k = store.contacts.first(where: { $0.id == c.from && $0.base == c.base }) {
                        GradientButton(text: "回撥".tr, systemImage: "phone.arrow.up.right") { talk.dismiss(c); talk.call(k) }
                    }
                    GhostButton(text: "移除".tr, tint: Brand.textMuted) { talk.dismiss(c) }
                }
            }
            .padding(.top, 6)
        }
    }

    // MARK: 房間裡

    private var roomView: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let who = talk.calling {
                Text("正在打給 {0}，等對方接聽…".trf(who)).font(.bodyMedium).foregroundStyle(Brand.textMuted)
            }
            if let link = talk.link {
                GlassCard {
                    Text("傳送邀請".tr).font(.titleSmall).foregroundStyle(Brand.text)
                    Text("把邀請連結傳給對方（LINE、簡訊…）。對方要先裝大D，點連結就會加入。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.vertical, 6)
                    Text("從外面連線是一般 http，請只在需要時開房，用完離開。".tr).font(.bodySmall).foregroundStyle(Brand.textFaint)
                    HStack(spacing: 8) {
                        ShareLink(item: "大D 遠距對話邀請：".tr + link) { Label("傳送邀請".tr, systemImage: "square.and.arrow.up") }.tint(Brand.cyan)
                        Spacer()
                        GhostButton(text: "複製".tr, systemImage: "doc.on.doc", tint: Brand.cyan) { UIPasteboard.general.string = link }
                    }
                }
            }
            HStack {
                Text(talk.members.map { $0.name + ($0.id == talk.me ? "（我）".tr : "") + " · " + TranslateLangs.name($0.lang) }
                    .joined(separator: "、"))
                    .font(.labelMedium).foregroundStyle(Brand.textMuted).lineLimit(2)
                Spacer()
                Toggle(isOn: $playOriginal) { Image(systemName: "waveform") }.toggleStyle(.button).tint(Brand.cyan)
                    .accessibilityLabel("對方原聲".tr)
                Toggle(isOn: $speak) { Image(systemName: "speaker.wave.2") }.toggleStyle(.button).tint(Brand.cyan)
                    .accessibilityLabel("唸翻譯".tr)
            }
            ForEach(talk.lines) { l in row(l) }
            if talk.lines.isEmpty {
                Text(talk.members.count < 2 ? "等對方加入…".tr : "按麥克風開始說話".tr)
                    .font(.bodyMedium).foregroundStyle(Brand.textFaint)
            }
        }
    }

    @ViewBuilder private func row(_ l: RemoteTalk.Line) -> some View {
        if let s = l.system {
            Text(s).font(.labelMedium).foregroundStyle(Brand.textFaint).frame(maxWidth: .infinity)
        } else {
            VStack(alignment: l.mine ? .trailing : .leading, spacing: 2) {
                if !l.mine {
                    HStack(spacing: 4) {
                        Text(l.name).font(.labelSmall).foregroundStyle(Brand.textFaint)
                        if l.audio != nil { Image(systemName: "waveform").font(.labelSmall).foregroundStyle(Brand.textFaint) }
                    }
                }
                Text(l.mine ? l.original : (l.translated ?? "…"))
                    .font(.system(size: 22, weight: l.final ? .medium : .regular))
                    .italic(!l.final)
                    .foregroundStyle(l.final ? Brand.text : Brand.textMuted)
                Text(l.mine ? (l.translated ?? "") : l.original).font(.bodySmall).foregroundStyle(Brand.textMuted)
            }
            .frame(maxWidth: .infinity, alignment: l.mine ? .trailing : .leading)
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 14).fill(l.mine ? Brand.surfaceHigh : Brand.cardFill))
            .contextMenu {
                Button { UIPasteboard.general.string = [l.original, l.translated ?? ""].filter { !$0.isEmpty }.joined(separator: "\n") } label: {
                    Label("複製".tr, systemImage: "doc.on.doc")
                }
            }
        }
    }

    private var micBar: some View {
        VStack(spacing: 4) {
            if let s = captions.error ?? captions.status { Text(s).font(.labelMedium).foregroundStyle(Brand.textMuted) }
            else if captions.running { Text("正在聽你說話…".tr).font(.labelMedium).foregroundStyle(Brand.textMuted) }
            GradientButton(text: captions.running ? "停止".tr : "按麥克風開始說話".tr,
                           systemImage: captions.running ? "pause.fill" : "mic.fill") { talk.toggleMic() }
        }
        .pageColumn()
        .padding(.vertical, 10)
        .background(Brand.surface.opacity(0.95))
    }
}

/// 以前的一次對話：每句可以播（原聲＋翻譯），也可以全部播放。
struct TalkRecordView: View {
    @Environment(\.dismiss) private var dismiss
    let talk: TalkStore.Talk
    @ObservedObject private var player = TalkPlayer.shared

    var body: some View {
        VStack(spacing: 0) {
            TopBar(title: talk.partners.isEmpty ? "記錄".tr : talk.partners.joined(separator: "、"), onBack: {
                player.stopAll(); dismiss()
            }) {
                GhostButton(text: player.playing ? "停止".tr : "全部播放".tr, systemImage: player.playing ? "stop.fill" : "play.fill",
                            tint: Brand.cyan) {
                    if player.playing { player.stopAll() } else { RemoteTalk.shared.replay(talk) }
                }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text(Fmt.stamp(talk.startedAt, "yyyy/M/d HH:mm")).font(.labelMedium).foregroundStyle(Brand.textMuted)
                    ForEach(talk.lines) { l in
                        VStack(alignment: l.mine ? .trailing : .leading, spacing: 2) {
                            Text(l.mine ? "你".tr : l.name).font(.labelSmall).foregroundStyle(Brand.textFaint)
                            Text(l.mine ? l.text : (l.translated ?? l.text)).font(.bodyLarge).foregroundStyle(Brand.text)
                            if let t = l.mine ? l.translated : l.text, t != (l.mine ? l.text : l.translated) {
                                Text(t).font(.bodySmall).foregroundStyle(Brand.textMuted)
                            }
                            Button { RemoteTalk.shared.replayLine(talk, l) } label: {
                                Label("播放這句".tr, systemImage: l.audio != nil ? "waveform" : "speaker.wave.2").font(.labelMedium)
                            }
                            .tint(Brand.cyan)
                        }
                        .frame(maxWidth: .infinity, alignment: l.mine ? .trailing : .leading)
                        .padding(10)
                        .background(RoundedRectangle(cornerRadius: 14).fill(l.mine ? Brand.surfaceHigh : Brand.cardFill))
                    }
                }
                .pageColumn()
                .padding(.vertical, 12)
            }
        }
        .background(AppBackdrop())
        .onDisappear { player.stopAll() }
    }
}

/// 來電（App 任何畫面上方）：接聽 → 打開遠距對話頁。
struct IncomingCallBanner: View {
    @ObservedObject private var talk = RemoteTalk.shared

    var body: some View {
        if let c = talk.calls.first(where: { $0.live }), !talk.connected {
            HStack(spacing: 10) {
                Image(systemName: "phone.arrow.down.left.fill").foregroundStyle(Brand.success)
                Text("{0} 打來了".trf(c.name)).font(.titleSmall).foregroundStyle(Brand.text).lineLimit(1)
                Spacer()
                Button("拒接".tr) { talk.dismiss(c) }.tint(Brand.warning)
                Button("接聽".tr) { talk.answer(c); TalkPage.shared.presented = true }
                    .buttonStyle(.borderedProminent).tint(Brand.success)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 16).fill(Brand.surface).shadow(radius: 8))
            .padding(.horizontal, 12)
            .transition(.move(edge: .top).combined(with: .opacity))
        }
    }
}

/// 打開遠距對話頁（翻譯分頁右上的按鈕、dylan://talk 連結）。
@MainActor
final class TalkPage: ObservableObject {
    static let shared = TalkPage()
    /// 設成 true：RootView 推出遠距對話頁（然後改回 false）
    @Published var presented = false
    static let route = "__talk__"
}

private extension View {
    @ViewBuilder func italic(_ on: Bool) -> some View { if on { self.italic() } else { self } }
}
