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
            "funasr": "FunASR", "breeze": "Breeze-ASR-26",
            "qwen3-8b": "Qwen3-8B", "sarc-taigi-12b": "SARC 台語 12B", "text": "文字",
        ]
        return "大D的主機：" + parts.map { names[$0] ?? $0 }.joined(separator: " ＋ ")
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

    struct Health: Decodable {
        let ok: Bool
        let asr: String?
        let llm: String?
        let busy: Bool?
        let model: String?
        let state: String?   // ready / loading（換模型、第一次下載中）/ error
        let error: String?
    }

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
        guard let u = URL(string: url + path) else { throw GeminiClient.Failure(message: "位址格式不對") }
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
            throw GeminiClient.Failure(message: "位址格式不對，例如 http://Dylan-Mac-mini.local:8765")
        }
        var r = URLRequest(url: u, timeoutInterval: 5)
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp): (Data, URLResponse)
        do { (data, resp) = try await URLSession.shared.data(for: r) } catch {
            throw GeminiClient.Failure(message: "連不到大D的主機（\(error.localizedDescription)）。請確認主機開著、服務在跑、手機連得到主機（同一個 Wi-Fi 或固定 IP）")
        }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 401 { throw GeminiClient.Failure(message: "存取碼不對", code: 401) }
        guard code == 200, let h = try? JSONDecoder().decode(Health.self, from: data) else {
            throw GeminiClient.Failure(message: "大D的主機 回應錯誤 \(code)", code: code)
        }
        if let m = h.model { UserDefaults.standard.set(m, forKey: modelKey) }
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
    }
    struct Devices: Decodable {
        let devices: [Device]
        let password_set: Bool
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
            throw GeminiClient.Failure(message: "位址格式不對，例如 http://Dylan-Mac-mini.local:8765")
        }
        var r = URLRequest(url: u, timeoutInterval: 10)
        r.httpMethod = "POST"
        var body = ["name": deviceLabel]
        if let password { body["password"] = password }
        if let code { body["code"] = code }
        r.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, resp): (Data, URLResponse)
        do { (data, resp) = try await URLSession.shared.data(for: r) } catch {
            throw GeminiClient.Failure(message: "連不到大D的主機（\(error.localizedDescription)）")
        }
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200, let p = try? JSONDecoder().decode(Paired.self, from: data) else {
            let msg = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw GeminiClient.Failure(message: msg ?? "配對失敗（\(status)）", code: status)
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
            throw GeminiClient.Failure(message: "這不是大D的主機的配對 QR 碼")
        }
        let urls = ["url", "lan"].compactMap { k in items.first(where: { $0.name == k })?.value }.filter { !$0.isEmpty }
        var last: Error = GeminiClient.Failure(message: "QR 碼裡沒有位址")
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
        return d
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
    static func processRequest(kind: String, recordedAt: String, durationSec: Double, text: Bool = false) -> URLRequest? {
        guard let u = URL(string: url + "/v1/process") else { return nil }
        var r = URLRequest(url: u, timeoutInterval: 1800)   // 長錄音在 Mac 上可能要好幾分鐘
        r.httpMethod = "POST"
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        r.setValue(kind, forHTTPHeaderField: "X-Kind")
        // 標頭只能放 ASCII：錄音時間用數字格式
        r.setValue(recordedAt, forHTTPHeaderField: "X-Recorded-At")
        r.setValue(String(Int(durationSec)), forHTTPHeaderField: "X-Duration")
        r.setValue(text ? "text/plain; charset=utf-8" : "audio/m4a", forHTTPHeaderField: "Content-Type")
        if text { r.setValue("text", forHTTPHeaderField: "X-Input") }   // 匯入的文字：Mac 跳過語音辨識
        return r
    }

    /// Mac 的回應 → (Gemini 格式文字, 模型代號)。
    static func parse(_ data: Data) -> (text: String, model: String)? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let text = json["text"] as? String else { return nil }
        return (text, json["model"] as? String ?? "local-funasr|qwen3-8b")
    }

    static func error(_ data: Data, status: Int) -> String {
        let msg = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
        if status == 401 { return "大D的主機 的存取碼不對，請到設定重新貼上" }
        return msg ?? "大D的主機 回應錯誤 \(status)"
    }
}
