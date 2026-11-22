import Foundation

/// 自架引擎（使用者自己的 Mac）：錄音 → FunASR 逐字稿＋分說話者 → Qwen（MLX）寫報告。
/// Mac 上的服務在 `~/dylan-local/server.py`（不在這個 repo；見 交接/HANDOFF.md），回傳跟 Gemini 一樣的格式，
/// 所以 App 直接用 Gemini 的解析（GeminiClient.split / parseNutrition / Speakers）。
///
/// 好處：免費、沒有額度、錄音不出你的設備、能分說話者。限制：Mac 要開著、連得到（同一個 Wi-Fi 或 Tailscale）。
/// 位址存 UserDefaults，存取碼存 Keychain。
enum LocalAI {
    static let urlKey = "local_ai_url"
    static let tokenAccount = "local_ai_token"
    static let modelLabel = "這台 Mac：FunASR ＋ Qwen3-8B"

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
            throw GeminiClient.Failure(message: "連不到這台 Mac（\(error.localizedDescription)）。請確認 Mac 開著、服務在跑、手機和 Mac 在同一個網路")
        }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 401 { throw GeminiClient.Failure(message: "存取碼不對", code: 401) }
        guard code == 200, let h = try? JSONDecoder().decode(Health.self, from: data) else {
            throw GeminiClient.Failure(message: "這台 Mac 回應錯誤 \(code)", code: code)
        }
        return h
    }

    /// 背景上傳用的請求（GeminiJobs 交給系統做，App 暗了也會完成）。
    static func processRequest(kind: String, recordedAt: String, durationSec: Double) -> URLRequest? {
        guard let u = URL(string: url + "/v1/process") else { return nil }
        var r = URLRequest(url: u, timeoutInterval: 1800)   // 長錄音在 Mac 上可能要好幾分鐘
        r.httpMethod = "POST"
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        r.setValue(kind, forHTTPHeaderField: "X-Kind")
        // 標頭只能放 ASCII：錄音時間用數字格式
        r.setValue(recordedAt, forHTTPHeaderField: "X-Recorded-At")
        r.setValue(String(Int(durationSec)), forHTTPHeaderField: "X-Duration")
        r.setValue("audio/m4a", forHTTPHeaderField: "Content-Type")
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
        if status == 401 { return "這台 Mac 的存取碼不對，請到設定重新貼上" }
        return msg ?? "這台 Mac 回應錯誤 \(status)"
    }
}
