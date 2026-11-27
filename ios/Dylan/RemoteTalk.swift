import SwiftUI
import Combine
#if canImport(Translation)
import Translation
#endif

/// 遠距對話（兩地即時翻譯，照 Android `RemoteTalk.kt`、交接/HANDOFF.md「遠距對話」）：兩支手機透過大D的主機（local-ai/talk.py）轉送文字。
/// - 自己說的話：即時翻譯同一套辨識（`LiveCaptions`，只翻譯模式、照停頓切句），每一句（含還在說的暫時句，最多每 0.6 秒）送原文＋辨識代碼。
/// - 對方說的話：在自己手機上用 Apple 翻譯翻成自己的語言（主機不翻譯、不存檔），可以自動朗讀。
/// - 開房要跟大D的主機配對過；對方用邀請連結（dylan://talk?url=…&lan=…&room=…&code=…）加入，不用配對。
@MainActor
final class RemoteTalk: ObservableObject {
    static let shared = RemoteTalk()

    struct Member: Equatable { let id: String, name: String, lang: String, owner: Bool }
    /// 一句：mine = 自己說的；translated = 翻成自己語言的（自己那句顯示對方看到的語言）；system = 加入／離開
    struct Line: Identifiable, Equatable {
        let key: String
        var from: String, name: String, original: String, lang: String
        var translated: String?
        var mine: Bool, final: Bool
        var system: String?
        var id: String { key }
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
    /// 收到的邀請連結（dylan://talk）：對話頁看到就問要不要加入
    @Published var pendingInvite: String?
    /// 要翻成自己語言的句子（TalkTranslateHost 拿去翻）
    @Published private(set) var toTranslate: [(key: String, text: String, lang: String)] = []

    static let nameKey = "talk_name", readAloudKey = "talk_read_aloud"
    static var myName: String {
        get { UserDefaults.standard.string(forKey: nameKey).flatMap { $0.isEmpty ? nil : $0 } ?? DeviceInfo.name }
        set { UserDefaults.standard.set(newValue, forKey: nameKey) }
    }

    private var base: String?
    private var token: String?
    private var pollTask: Task<Void, Never>?
    private var captionSub: AnyCancellable?
    private var sent: [Int: (String, Bool)] = [:]
    private var sentAt: [Int: Date] = [:]

    /// 我說的（辨識代碼）與我看的（翻譯代碼）。
    var mySource: String { TranslateLangs.source }
    var myReading: String { TranslateLangs.translationId(source: mySource) }

    private func resetState() {
        room = nil; link = nil; me = nil; members = []; lines = []; connected = false; ended = false; error = nil
        sent = [:]; sentAt = [:]; toTranslate = []
    }

    // MARK: HTTP

    private func http(_ method: String, _ url: String, body: [String: Any]? = nil, auth: String? = nil, talkToken: String? = nil,
                      timeout: TimeInterval = 15) async throws -> (Int, [String: Any]) {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        var r = URLRequest(url: u, timeoutInterval: timeout)
        r.httpMethod = method
        r.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        if let auth { r.setValue("Bearer \(auth)", forHTTPHeaderField: "Authorization") }
        if let talkToken { r.setValue(talkToken, forHTTPHeaderField: "X-Talk-Token") }
        if let body { r.httpBody = try JSONSerialization.data(withJSONObject: body) }
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

    // MARK: 開房 / 加入 / 離開

    func create() {
        guard LocalAI.configured else {
            error = "開遠距對話要先跟大D的主機配對（設定 → 大D的主機）".tr
            return
        }
        resetState(); busy = true
        let url = LocalAI.url.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        Task {
            defer { busy = false }
            do {
                let (code, j) = try await http("POST", url + "/v1/talk", body: ["name": Self.myName, "lang": mySource], auth: LocalAI.token)
                guard code == 200 else { error = errorText(code, j); return }
                base = url; token = j["token"] as? String
                room = j["room"] as? String; link = j["link"] as? String; me = j["member"] as? String
                connected = true
                startLoops()
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
        var urls: [String] = []
        for u in [q("lan"), q("url")].compactMap({ $0 }) where u.hasPrefix("http") {
            let t = u.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            if !urls.contains(t) { urls.append(t) }
        }
        guard !urls.isEmpty else { error = "邀請連結看不懂".tr; return }
        resetState(); busy = true; pendingInvite = nil
        Task {
            defer { busy = false }
            var last: String?
            for url in urls {
                do {
                    let (code, j) = try await http("POST", url + "/v1/talk/join",
                                                   body: ["room": r, "code": c, "name": Self.myName, "lang": mySource], timeout: 6)
                    guard code == 200 else { error = errorText(code, j); return }
                    base = url; token = j["token"] as? String; room = r; me = j["member"] as? String
                    connected = true
                    startLoops()
                    return
                } catch { last = error.localizedDescription }
            }
            error = "連不到對方的主機：{0}".trf(last ?? "")
        }
    }

    func leave() {
        let b = base, t = token, r = room
        pollTask?.cancel(); pollTask = nil
        captionSub = nil
        if LiveCaptions.shared.running { LiveCaptions.shared.stop() }
        if let b, let t, let r { Task { _ = try? await http("DELETE", "\(b)/v1/talk/\(r)", talkToken: t) } }
        base = nil; token = nil
        connected = false; ended = true
    }

    func reset() { if connected { leave() }; resetState() }

    // MARK: 說話（麥克風開關）

    /// 開始／暫停聽自己說話（翻成對方的語言，自己的畫面也看得到對方大概會看到什麼）。
    func toggleMic() {
        let lc = LiveCaptions.shared
        if lc.running { lc.stop(); return }
        let other = members.first { $0.id != me }
        var target = other.map { TranslateLangs.translationId(source: $0.lang) } ?? ""
        if target.isEmpty || TranslateLangs.same(source: mySource, target: target) { target = myReading.hasPrefix("en") ? "zh-Hant" : "en" }
        lc.start(sourceId: mySource, targetId: target)
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
                    if j["ended"] as? Bool == true { self.ended = true; self.connected = false; break }
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
            let final = !l.partial
            if let prev = sent[l.id], prev.0 == l.original, prev.1 == final { continue }
            if l.partial, let at = sentAt[l.id], Date().timeIntervalSince(at) < 0.6 { continue }
            sentAt[l.id] = Date()
            sent[l.id] = (l.original, final)
            post(l.id, l.original, lang, final)
            if let me { upsert(Line(key: "\(me):m\(l.id)", from: me, name: "", original: l.original, lang: lang,
                                    translated: l.translated ?? l.draft, mine: true, final: final)) }
        }
    }

    private func post(_ id: Int, _ text: String, _ lang: String, _ final: Bool) {
        guard let b = base, let t = token, let r = room else { return }
        Task { _ = try? await http("POST", "\(b)/v1/talk/\(r)/msg", body: ["line": "m\(id)", "text": text, "lang": lang, "final": final], talkToken: t) }
    }

    private func upsert(_ line: Line) {
        if let i = lines.firstIndex(where: { $0.key == line.key }) { lines[i] = line } else { lines.append(line) }
        if lines.count > 300 { lines.removeFirst(lines.count - 300) }
    }

    private func handle(_ j: [String: Any]) {
        if let a = j["members"] as? [[String: Any]] {
            members = a.map { Member(id: $0["id"] as? String ?? "", name: $0["name"] as? String ?? "",
                                     lang: $0["lang"] as? String ?? "", owner: $0["owner"] as? Bool ?? false) }
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
            upsert(Line(key: key, from: from, name: name, original: text, lang: lang, translated: old, mine: false, final: final))
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
        if let i = lines.firstIndex(where: { $0.key == key }), lines[i].translated == nil { lines[i].translated = original }
        error = "這支手機翻不了對方的語言（{0}）。可以到「翻譯」分頁用同樣的語言翻一次，系統會問要不要下載。".trf(why)
    }

    func setTranslated(_ key: String, _ text: String) {
        guard let i = lines.firstIndex(where: { $0.key == key }) else { return }
        lines[i].translated = text
        let l = lines[i]
        if l.final && !l.mine {
            if UserDefaults.standard.bool(forKey: Self.readAloudKey) { Speaker.shared.speak(text, lang: myReading) }
            if LiveCaptions.watchCaptions { PhoneLink.shared.sendCaption(text) }
        }
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
    @ObservedObject private var captions = LiveCaptions.shared
    @AppStorage(RemoteTalk.readAloudKey) private var readAloud = false
    @State private var name = RemoteTalk.myName
    @State private var invite = ""

    var body: some View {
        VStack(spacing: 0) {
            TopBar(title: "遠距對話".tr, onBack: { dismiss() }) {
                if talk.connected {
                    GhostButton(text: "離開".tr, tint: Brand.warning) { talk.leave() }
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
                    if talk.connected || talk.ended { roomView } else { startView }
                }
                .pageColumn()
                .padding(.vertical, 12)
            }
            if talk.connected { micBar }
        }
        .background(AppBackdrop())
        .background(TalkTranslateHost())
        .onAppear { if let p = talk.pendingInvite { invite = p } }
        .onChange(of: talk.pendingInvite) { p in if let p { invite = p } }
    }

    // MARK: 還沒進房

    private var startView: some View {
        VStack(alignment: .leading, spacing: 12) {
            GlassCard {
                Text("兩個人在不同地方，各自用自己的手機、說自己的語言；大D的主機只轉送文字，每支手機把對方的話翻成自己的語言。".tr)
                    .font(.bodySmall).foregroundStyle(Brand.textMuted)
                Text("我的名字".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.top, 10)
                TextField("我的名字".tr, text: $name).textFieldStyle(.roundedBorder)
                    .onChange(of: name) { RemoteTalk.myName = $0 }
                Text("我說的語言：{0}（在翻譯頁改）".trf(TranslateLangs.name(talk.mySource)))
                    .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 6)
            }
            GlassCard {
                Text("開新對話".tr).font(.titleSmall).foregroundStyle(Brand.text)
                Text("要先跟大D的主機配對。開好後把邀請連結傳給對方。".tr).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.vertical, 6)
                GradientButton(text: talk.busy ? "連線中…".tr : "開新對話".tr, enabled: !talk.busy) { talk.create() }
            }
            GlassCard {
                Text("用邀請連結加入".tr).font(.titleSmall).foregroundStyle(Brand.text)
                TextField("dylan://talk?…", text: $invite, axis: .vertical).textFieldStyle(.roundedBorder).padding(.vertical, 6)
                    .autocorrectionDisabled().textInputAutocapitalization(.never)
                HStack(spacing: 8) {
                    GhostButton(text: "貼上".tr, systemImage: "doc.on.clipboard", tint: Brand.cyan) {
                        invite = UIPasteboard.general.string ?? invite
                    }
                    GradientButton(text: "加入".tr, enabled: !talk.busy && !invite.isEmpty) { talk.join(invite) }
                }
            }
            if talk.ended {
                Text("對話已經結束".tr).font(.bodyMedium).foregroundStyle(Brand.textMuted)
            }
        }
    }

    // MARK: 房間裡

    private var roomView: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let link = talk.link, talk.connected {
                GlassCard {
                    Text("邀請對方".tr).font(.titleSmall).foregroundStyle(Brand.text)
                    Text("把連結傳給對方，對方用大D 打開就能加入（不用跟主機配對）。從外面連線是一般 http，請只在需要時開房，用完離開。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.vertical, 6)
                    HStack(spacing: 8) {
                        ShareLink(item: link) { Label("分享邀請".tr, systemImage: "square.and.arrow.up") }.tint(Brand.cyan)
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
                Toggle(isOn: $readAloud) { Image(systemName: "speaker.wave.2") }.toggleStyle(.button).tint(Brand.cyan)
                    .accessibilityLabel("朗讀對方的話".tr)
            }
            if talk.ended { Text("對話已經結束".tr).font(.bodyMedium).foregroundStyle(Brand.warning) }
            ForEach(talk.lines) { l in row(l) }
            if talk.lines.isEmpty {
                Text(talk.members.count < 2 ? "等對方加入…".tr : "按下面的麥克風開始說話".tr)
                    .font(.bodyMedium).foregroundStyle(Brand.textFaint)
            }
        }
    }

    @ViewBuilder private func row(_ l: RemoteTalk.Line) -> some View {
        if let s = l.system {
            Text(s).font(.labelMedium).foregroundStyle(Brand.textFaint).frame(maxWidth: .infinity)
        } else {
            VStack(alignment: l.mine ? .trailing : .leading, spacing: 2) {
                if !l.mine { Text(l.name).font(.labelSmall).foregroundStyle(Brand.textFaint) }
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
            GradientButton(text: captions.running ? "暫停說話".tr : "開始說話".tr,
                           systemImage: captions.running ? "pause.fill" : "mic.fill") { talk.toggleMic() }
        }
        .pageColumn()
        .padding(.vertical, 10)
        .background(Brand.surface.opacity(0.95))
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
