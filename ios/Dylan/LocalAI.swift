import Foundation

/// 自架引擎（使用者自己的 Mac）：錄音 → 語音辨識＋分說話者 → 語言模型寫報告。
/// 用哪個模型在設定頁選（Mac 上的 /v1/models、/v1/config），例如 FunASR / Breeze-ASR-26（台語）＋ Qwen3-8B / SARC 台語 12B。
/// Mac 上的服務在 `~/dylan-local/server.py`（不在這個 repo；見 交接/HANDOFF.md），回傳跟 Gemini 一樣的格式，
/// 所以 App 直接用 Gemini 的解析（GeminiClient.split / parseNutrition / Speakers）。
///
/// 好處：免費、沒有額度、錄音不出你的設備、能分說話者。限制：Mac 要開著、連得到（同一個 Wi-Fi 或 Tailscale）。
/// 位址存 UserDefaults，存取碼存 Keychain。
enum LocalAI {
    static let urlKey = "local_ai_url"
    static let tokenAccount = "local_ai_token"
    static let modelKey = "local_ai_model"   // 最後一次從 Mac 得知的模型代號（local-<辨識>|<報告>）

    /// 設定頁、狀態列顯示用：「這台 Mac：FunASR ＋ Qwen3-8B」。
    static var modelLabel: String { label(UserDefaults.standard.string(forKey: modelKey) ?? "local-funasr|qwen3-8b") }

    /// 模型代號 → 好讀的名字。認不得的代號照原樣顯示（Mac 上可以自己加模型）。
    static func label(_ tag: String) -> String {
        let parts = tag.replacingOccurrences(of: "local-", with: "").split(separator: "|").map(String.init)
        let names: [String: String] = [
            "funasr": "FunASR", "breeze": "Breeze-ASR-26", "taiwan-tongues": "Taiwan Tongues", "hakka-nycu": "客語 Whisper（陽明交大）".tr,
            "qwen3-8b": "Qwen3-8B", "sarc-taigi-12b": "SARC 台語 12B".tr, "text": "文字".tr,
        ]
        return "大D的主機：".tr + parts.map { names[$0] ?? $0 }.joined(separator: " ＋ ")
    }

    /// 預設位址：模擬器跟 Mac 是同一台，用 localhost；真機要填 Mac 的區網名稱（例如 http://Dylan-Mac-mini.local:8765）或 Tailscale 位址。
    static var defaultURL: String {
        #if targetEnvironment(simulator)
        return "http://localhost:8765"
        #else
        return ""
        #endif
    }

    static var url: String {
        let v = normalize(UserDefaults.standard.string(forKey: urlKey) ?? "")
        return v.isEmpty ? defaultURL : v
    }

    /// 使用者打的位址整理成「http://主機:埠」：去掉空白與結尾的「/」，沒寫 http:// 就補上。
    static func normalize(_ raw: String) -> String {
        var v = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while v.hasSuffix("/") { v.removeLast() }
        if !v.isEmpty, !v.lowercased().hasPrefix("http://"), !v.lowercased().hasPrefix("https://") { v = "http://" + v }
        return v
    }

    static var token: String { Keychain.get(tokenAccount) ?? "" }

    /// 位址和存取碼都填了才算設定好。
    static var configured: Bool { !url.isEmpty && !token.isEmpty && URL(string: url) != nil }

    /// AI 模式選雲端或離線時暫停：報告不交給主機（遠距對話照常用主機）。
    static let pausedKey = "host_paused"
    static var paused: Bool { UserDefaults.standard.bool(forKey: pausedKey) }
    /// 報告要不要交給大D的主機。
    static var active: Bool { configured && !paused }

    struct Health: Decodable {
        let ok: Bool
        let asr: String?
        let llm: String?
        let busy: Bool?
        let model: String?
        let state: String?   // ready / loading（換模型、第一次下載中）/ error
        let error: String?
        let admin: Bool?     // 這台是不是管理者（舊主機沒有 = 管理者）
        let jobs: Bool?      // 有工作佇列（/v1/jobs）
        let ahead: Int?      // 現在送的話前面有幾筆
        let wait_sec: Int?
        let langs: [String]? // 能辨識的語言；舊主機沒有這欄 = 國語、台語、客語

        /// 主機能不能辨識這個語言
        func supports(_ l: SpeechLang) -> Bool { (langs.flatMap { $0.isEmpty ? nil : $0 } ?? ["zh", "nan", "hak"]).contains(l.rawValue) }
    }

    static let jobsKey = "local_ai_jobs"
    static let adminKey = "local_ai_admin"
    /// 最後一次得知：主機有沒有工作佇列。
    static var hasJobs: Bool { UserDefaults.standard.bool(forKey: jobsKey) }
    /// 最後一次得知：這台是不是管理者（舊主機沒回 = 管理者）。
    static var isAdmin: Bool { UserDefaults.standard.object(forKey: adminKey) as? Bool ?? true }

    struct Option: Decodable, Hashable {
        let id: String
        let label: String
    }

    struct Models: Decodable {
        let asr_id: String?
        let llm_id: String?
        let state: String?
        let model: String?
        let asr_options: [Option]
        let llm_options: [Option]
    }

    private static func request(_ path: String, method: String = "GET", body: [String: String]? = nil) throws -> URLRequest {
        guard let u = URL(string: url + path) else { throw GeminiClient.Failure(message: "位址格式不對".tr) }
        var r = URLRequest(url: u, timeoutInterval: 10)
        r.httpMethod = method
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let body { r.httpBody = try JSONSerialization.data(withJSONObject: body) }
        return r
    }

    /// Mac 上可選的模型（設定頁的選單）。
    static func models() async throws -> Models {
        let (data, resp) = try await URLSession.shared.data(for: request("/v1/models"))
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200, let m = try? JSONDecoder().decode(Models.self, from: data) else {
            throw GeminiClient.Failure(message: error(data, status: code), code: code)
        }
        if let tag = m.model, m.state != "loading" { UserDefaults.standard.set(tag, forKey: modelKey) }
        return m
    }

    /// 換模型：Mac 存起來並在背景載入（第一次要下載），之後用 health() 看 state 何時變回 ready。
    static func select(asr: String, llm: String) async throws {
        let (data, resp) = try await URLSession.shared.data(for: request("/v1/config", method: "POST", body: ["asr": asr, "llm": llm]))
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 || code == 202 else { throw GeminiClient.Failure(message: error(data, status: code), code: code) }
    }

    /// 測試連線（設定頁用）：回傳 Mac 上的模型，或丟出看得懂的錯誤。
    static func health(url: String, token: String) async throws -> Health {
        guard let u = URL(string: normalize(url) + "/v1/health") else {
            throw GeminiClient.Failure(message: "位址格式不對，例如 http://Dylan-Mac-mini.local:8765".tr)
        }
        var r = URLRequest(url: u, timeoutInterval: 5)
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp): (Data, URLResponse)
        do { (data, resp) = try await URLSession.shared.data(for: r) } catch {
            throw GeminiClient.Failure(message: "連不到大D的主機（{0}）。請確認主機開著、服務在跑、手機連得到主機（同一個 Wi-Fi 或固定 IP）".trf(error.localizedDescription))
        }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 401 { throw GeminiClient.Failure(message: "存取碼不對".tr, code: 401) }
        guard code == 200, let h = try? JSONDecoder().decode(Health.self, from: data) else {
            throw GeminiClient.Failure(message: "大D的主機 回應錯誤 {0}".trf(code), code: code)
        }
        if let m = h.model { UserDefaults.standard.set(m, forKey: modelKey) }
        UserDefaults.standard.set(h.jobs == true, forKey: jobsKey)
        UserDefaults.standard.set(h.admin ?? true, forKey: adminKey)
        return h
    }

    // MARK: - 配對（不用手動貼存取碼）

    static let deviceIdKey = "local_ai_device_id"
    /// 已和主機配對（或貼了存取碼）、可以從 App 裡通知別的畫面重新讀狀態。
    static let didPair = Notification.Name("dylan.didPair")

    struct Paired: Decodable { let token: String; let id: String }
    struct Device: Decodable, Identifiable {
        let id: String
        let name: String
        let created: Double
        let last_seen: Double?
        let via: String?
        let current: Bool
        let admin: Bool?
    }
    struct Devices: Decodable {
        let devices: [Device]
        let password_set: Bool
        let admin: Bool?
    }

    /// 給主機看的裝置名字：「Dylan 的 iPad（iPad Pro 11 吋 第 2 代）」。
    static var deviceLabel: String {
        let n = DeviceInfo.name, m = DeviceInfo.modelShort
        return n.isEmpty ? m : "\(n)（\(m)）"
    }

    /// 用配對密碼或 QR 配對碼，向主機換這台裝置自己的存取碼，連同位址一起存起來。
    static func pair(url: String, password: String? = nil, code: String? = nil) async throws {
        let base = normalize(url)
        guard !base.isEmpty, let u = URL(string: base + "/v1/pair") else {
            throw GeminiClient.Failure(message: "位址格式不對，例如 http://Dylan-Mac-mini.local:8765".tr)
        }
        var r = URLRequest(url: u, timeoutInterval: 10)
        r.httpMethod = "POST"
        var body = ["name": deviceLabel]
        if let password { body["password"] = password }
        if let code { body["code"] = code }
        r.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, resp): (Data, URLResponse)
        do { (data, resp) = try await URLSession.shared.data(for: r) } catch {
            throw GeminiClient.Failure(message: "連不到大D的主機（{0}）".trf(error.localizedDescription))
        }
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200, let p = try? JSONDecoder().decode(Paired.self, from: data) else {
            let msg = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw GeminiClient.Failure(message: msg ?? "配對失敗（{0}）".trf(status), code: status)
        }
        UserDefaults.standard.set(base, forKey: urlKey)
        Keychain.set(p.token, for: tokenAccount)
        UserDefaults.standard.set(p.id, forKey: deviceIdKey)
        NotificationCenter.default.post(name: didPair, object: nil)
    }

    /// 相機掃到的 dylan://pair?url=…&lan=…&code=…：先試對外位址，連不到再試區網位址。回傳用的位址。
    @discardableResult
    static func pair(link: URL) async throws -> String {
        guard link.scheme == "dylan", link.host == "pair",
              let items = URLComponents(url: link, resolvingAgainstBaseURL: false)?.queryItems,
              let code = items.first(where: { $0.name == "code" })?.value else {
            throw GeminiClient.Failure(message: "這不是大D的主機的配對 QR 碼".tr)
        }
        let urls = ["url", "lan"].compactMap { k in items.first(where: { $0.name == k })?.value }.filter { !$0.isEmpty }
        var last: Error = GeminiClient.Failure(message: "QR 碼裡沒有位址".tr)
        for u in urls {
            do {
                try await pair(url: u, code: code)
                return u
            } catch let e as GeminiClient.Failure where e.code == 0 {
                last = e   // 連不到：換下一個位址（配對碼還沒用掉）
            }
        }
        throw last
    }

    static func devices() async throws -> Devices {
        let (data, resp) = try await URLSession.shared.data(for: request("/v1/devices"))
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200, let d = try? JSONDecoder().decode(Devices.self, from: data) else {
            throw GeminiClient.Failure(message: error(data, status: code), code: code)
        }
        UserDefaults.standard.set(d.admin ?? true, forKey: adminKey)
        return d
    }

    /// 指定 / 取消管理者（只有管理者能做）。
    static func setAdmin(_ id: String, _ admin: Bool) async throws {
        var r = try request("/v1/devices/admin", method: "POST")
        r.httpBody = try JSONSerialization.data(withJSONObject: ["id": id, "admin": admin] as [String: Any])
        let (data, resp) = try await URLSession.shared.data(for: r)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else { throw GeminiClient.Failure(message: error(data, status: code), code: code) }
    }

    static func revoke(_ id: String) async throws {
        let (data, resp) = try await URLSession.shared.data(for: request("/v1/devices/revoke", method: "POST", body: ["id": id]))
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else { throw GeminiClient.Failure(message: error(data, status: code), code: code) }
    }

    static func setPassword(_ password: String) async throws {
        let (data, resp) = try await URLSession.shared.data(for: request("/v1/password", method: "POST", body: ["password": password]))
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else { throw GeminiClient.Failure(message: error(data, status: code), code: code) }
    }

    /// 取消這台裝置的配對：通知主機作廢這台的存取碼（連不到也照樣清掉本機的）。
    static func unpair() async {
        if let id = UserDefaults.standard.string(forKey: deviceIdKey), !token.isEmpty { try? await revoke(id) }
        Keychain.set("", for: tokenAccount)
        UserDefaults.standard.removeObject(forKey: deviceIdKey)
    }

    /// 背景上傳用的請求（GeminiJobs 交給系統做，App 暗了也會完成）。
    /// [queue]：新主機送到工作佇列（/v1/jobs，馬上回工作編號）；舊主機 /v1/process（連著等結果）。
    static func processRequest(kind: String, recordedAt: String, durationSec: Double, text: Bool = false,
                               lang: SpeechLang = .zh, dialect: HakkaDialect = .sixian, queue: Bool = false,
                               country: String? = nil) -> URLRequest? {
        guard let u = URL(string: url + (queue ? "/v1/jobs" : "/v1/process")) else { return nil }
        var r = URLRequest(url: u, timeoutInterval: 1800)   // 長錄音在 Mac 上可能要好幾分鐘
        r.httpMethod = "POST"
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        r.setValue(kind, forHTTPHeaderField: "X-Kind")
        r.setValue(ReportLang.current.rawValue, forHTTPHeaderField: "X-Report-Lang")   // 新主機照這個寫報告；舊的照舊中文
        r.setValue(ReportDomain.setting, forHTTPHeaderField: "X-Domain")   // 領域專家模式（舊主機忽略）
        if let country { r.setValue(country, forHTTPHeaderField: "X-Food-Country") }   // 飲食：這一餐的國家（份量依當地）
        if kind == "food" { r.setValue(FoodGoals.mode, forHTTPHeaderField: "X-Food-Goal") }   // 健康目標：營養小提醒照目標講
        if let v = Vocab.header() { r.setValue(v, forHTTPHeaderField: "X-Vocab") }   // 個人詞庫＋常用人名（base64）
        // 標頭只能放 ASCII：錄音時間用數字格式
        r.setValue(recordedAt, forHTTPHeaderField: "X-Recorded-At")
        r.setValue(String(Int(durationSec)), forHTTPHeaderField: "X-Duration")
        r.setValue(text ? "text/plain; charset=utf-8" : "audio/m4a", forHTTPHeaderField: "Content-Type")
        if text { r.setValue("text", forHTTPHeaderField: "X-Input") }   // 匯入的文字：Mac 跳過語音辨識
        else {   // 國語 → FunASR；台語 → Breeze-ASR-26；客語 → 陽明交大客語 Whisper（加腔調）
            r.setValue(lang.rawValue, forHTTPHeaderField: "X-Lang")
            if lang == .hak { r.setValue(dialect.rawValue, forHTTPHeaderField: "X-Dialect") }
        }
        return r
    }

    // MARK: - 工作佇列（多人共用一台主機）

    /// GET /v1/jobs/<id> 的回應。
    struct JobView: Decodable {
        let id: String?
        let state: String       // queued / running / done / error
        let ahead: Int?
        let wait_sec: Int?
        let text: String?
        let model: String?
        let error: String?
        let purged: Bool?       // 主機上的錄音（含轉檔）已刪
    }

    static func parseJob(_ data: Data) -> JobView? { try? JSONDecoder().decode(JobView.self, from: data) }

    /// 查一筆工作；[wait] 秒內還沒做完就先等著（主機做完或逾時才回）。
    static func jobRequest(_ id: String, wait: Int) -> URLRequest? {
        guard let r = try? request("/v1/jobs/\(id)?wait=\(wait)") else { return nil }
        var q = r
        q.timeoutInterval = TimeInterval(wait + 60)
        return q
    }

    /// 拿到結果後刪掉主機上的報告（排隊中就是取消）。主機已經沒有這筆也算成功。
    @discardableResult
    static func deleteJob(_ id: String) async -> Bool {
        guard let r = try? request("/v1/jobs/\(id)", method: "DELETE"),
              let (_, resp) = try? await URLSession.shared.data(for: r) else { return false }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        return code == 200 || code == 404
    }

    /// 「排隊中：前面還有 2 筆，約 6 分鐘」（跟 Android LocalHost.queueText 一樣）。
    /// 排隊的步驟文字（存進 aiStep 時已經是當時的介面語言；中文與現在語言的開頭都認得）。
    static func isQueueText(_ s: String) -> Bool {
        let heads = ["排隊中", "輪到你了"] + ["排隊中：前面還有 {0} 筆，{1}".tr, "輪到你了，{0}".tr].map { $0.components(separatedBy: "{0}")[0] }
        return heads.contains { !$0.isEmpty && s.hasPrefix($0) }
    }

    static func queueText(ahead: Int, waitSec: Int) -> String {
        let wait = waitSec < 60 ? "不到 1 分鐘".tr
            : waitSec < 3600 ? "約 {0} 分鐘".trf((waitSec + 59) / 60)
            : "約 {0} 小時 {1} 分".trf(waitSec / 3600, (waitSec % 3600 + 59) / 60)
        return ahead <= 0 ? "輪到你了，{0}".trf(wait) : "排隊中：前面還有 {0} 筆，{1}".trf(ahead, wait)
    }

    static let cleanupAll = "大D的主機上的錄音與報告都已刪除".tr

    /// 報告「基本資料」的「隱私」：主機回報錄音刪了沒、報告拿走後刪了沒（跟 Android cleanupText 一樣）。
    static func cleanupText(purged: Bool, reportGone: Bool) -> String {
        switch (purged, reportGone) {
        case (true, true): return cleanupAll
        case (true, false): return "大D的主機上的錄音已刪除；報告會在 24 小時內自動刪除".tr
        case (false, true): return "大D的主機上的報告已刪除；錄音會在 24 小時內自動刪除".tr
        default: return "大D的主機會在 24 小時內自動刪除錄音與報告".tr
        }
    }

    /// Mac 的回應 → (Gemini 格式文字, 模型代號)。
    static func parse(_ data: Data) -> (text: String, model: String)? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let text = json["text"] as? String else { return nil }
        return (text, json["model"] as? String ?? "local-funasr|qwen3-8b")
    }

    /// 翻譯錯誤回報送到主機（存在主機的 feedback.jsonl，只有原文、譯文、更正、語言、版本、裝置 id）。
    static func feedback(_ body: [String: String]) async throws {
        var r = try request("/v1/feedback", method: "POST", body: body)
        r.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        let (data, resp) = try await URLSession.shared.data(for: r)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else {
            throw GeminiClient.Failure(message: code == 404 ? "大D的主機還沒有這個功能：請在 Mac 上重新部署大D的主機（更新到最新版）".tr : error(data, status: code), code: code)
        }
    }

    /// 從連結抓音訊（主機用 yt-dlp，只抓音訊不轉檔）→ 存到暫存檔，回傳檔案與標題。
    static func fetchURL(_ link: String) async throws -> (file: URL, title: String?) {
        var r = try request("/v1/fetch_url", method: "POST", body: ["url": link])
        r.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        r.timeoutInterval = 15 * 60
        let (tmp, resp) = try await URLSession.shared.download(for: r)
        let http = resp as? HTTPURLResponse
        let code = http?.statusCode ?? 0
        guard code == 200 else {
            let data = (try? Data(contentsOf: tmp)) ?? Data()
            throw GeminiClient.Failure(message: code == 404 ? "大D的主機還沒有這個功能：請在 Mac 上重新部署大D的主機（更新到最新版）".tr : error(data, status: code), code: code)
        }
        let title = (http?.value(forHTTPHeaderField: "X-Title")).flatMap { Data(base64Encoded: $0) }.flatMap { String(data: $0, encoding: .utf8) }
        let mime = http?.value(forHTTPHeaderField: "Content-Type") ?? ""
        let ext = mime.contains("webm") ? "webm" : (mime.contains("mpeg") ? "mp3" : (mime.contains("ogg") ? "ogg" : "m4a"))
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("link-\(UUID().uuidString.prefix(8)).\(ext)")
        try? FileManager.default.removeItem(at: out)
        try FileManager.default.moveItem(at: tmp, to: out)
        return (out, title)
    }

    static func error(_ data: Data, status: Int) -> String {
        let msg = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
        if status == 401 { return "大D的主機 的存取碼不對，請到設定重新貼上".tr }
        return msg ?? "大D的主機 回應錯誤 {0}".trf(status)
    }
}
