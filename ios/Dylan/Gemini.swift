import Foundation

/// 用 Google Gemini API 直接「聽」錄音，一次產出報告 + 分說話者逐字稿（照 Android `GeminiReportGenerator`）。
///
/// - 金鑰：https://aistudio.google.com/apikey（使用者自己在設定頁貼上，存 Keychain）。
/// - 錄音用 Files API 上傳（resumable），用完立刻刪除。
/// - 模型 gemini-3.8-flash；額度用完（429）/ 下架（404）/ 太忙（503、500）改用 gemini-3.5-flash-lite。
/// - 用串流（SSE）：模型邊產生邊傳回，長錄音不會因為太久沒資料而逾時。
/// - 提示詞照抄 Android（TYPE_RULES / SYSTEM_PROMPT），改提示詞兩邊要一起改。
struct GeminiClient {
    let apiKey: String

    static let base = "https://generativelanguage.googleapis.com"
    static let models = ["gemini-3.8-flash", "gemini-3.5-flash-lite"]
    static let uploadMime = "audio/m4a"
    static let transcriptMarker = "===TRANSCRIPT==="

    struct Failure: LocalizedError {
        let message: String
        var code: Int = 0
        var errorDescription: String? { message }
    }

    struct Result {
        let report: GeneratedReport
        let transcript: String
        let model: String
        /// 輸出到了上限（MAX_TOKENS）被截斷。
        var truncated = false
    }

    // MARK: - 提示詞（照抄 Android）

    static let typeRules = """
    錄音可能是會議、課堂、訪談、電話通話、演講、討論、或個人口述的備忘 / 想法，請先判斷是哪一種，再依類型整理重點：
    - 會議 / 討論：討論內容、決定事項、誰負責什麼。
    - 課堂 / 演講：主要概念、重要定義與例子、要複習的地方。
    - 訪談 / 通話：問了什麼、對方怎麼回答、約定事項。
    - 個人備忘 / 想法：把零散的想法整理成清楚的條列與下一步。
    不要捏造錄音裡沒有的內容；聽不清楚的地方標註「（聽不清）」，不確定的標註「（待確認）」。
    一律使用繁體中文（台灣用語）。
    """

    static let systemPrompt = """
    你是專業的語音記錄助理。使用者會給你一段錄音，請仔細聽完整段錄音後整理。
    \(typeRules)

    輸出格式（嚴格遵守，不要加其他前言）：
    第 1 行：TITLE: <15 字內的標題，能看出內容與類型，例如「Q3 預算週會」「統計學第 5 講」「王經理電話訪談」>
    第 2 行：SUMMARY: <2～3 句、80 字內的摘要，會顯示在手錶上>
    第 3 行起：Markdown 報告，依序包含以下章節（沒有內容的章節寫「無」）：
    ## 摘要
    （3～5 句完整摘要：這段錄音在講什麼、誰說了什麼重要的事、結論是什麼。這一節一定要寫，不可以寫「無」；比 SUMMARY 那一行更完整）
    ## 說話者
    （用表格：說話者 | 名字 | 角色 | 主要觀點；每位說話者一列。
     名字盡量從錄音內容推斷：自我介紹、別人怎麼稱呼他（例如「王經理」「李老師」「陳醫師」「媽」「小美」）、新聞的署名（「記者○○○報導」）、會議中點名或提到的姓名與職稱都算；只有職稱或稱謂也可以直接當名字（例如「王經理」）。若能從音量與距離判斷誰是戴著手錶 / 拿著手機錄音的人（通常聲音最大、最清楚），名字寫「本人」。完全沒有線索才寫「未知」，不要亂猜真名；角色依內容推測，例如主持人、老師、受訪者、提問者、本人）
    ## 重點
    （依錄音類型整理；有多位說話者時，每點註明是誰說的，例如「說話者B 提出…」）
    ## 結論 / 決定
    ## 待辦事項
    （用表格：事項 | 負責人 | 期限；負責人用說話者代號，例如「說話者B」；錄音沒提到就寫「未指定」）
    ## 待確認 / 後續問題

    報告之後，單獨一行輸出 \(transcriptMarker)
    之後是完整逐字稿，每段一行，格式：[mm:ss] 說話者X：內容

    說話者辨識規則（非常重要）：
    - 依聲音特徵（音色、音高、語速、口音、男女聲）區分不同的人，不是依內容猜。
    - 依第一次開口的順序編代號：說話者A、說話者B、說話者C…；同一個人從頭到尾都用同一個代號。
    - 逐字稿與報告一律只用代號，不要把名字寫進代號（名字寫在「說話者」表格）。
    - 只要換人說話就開新的一行，即使只講一兩個字（例如「對」「好」）也要獨立一行。
    - 同一人連續說很久時，大約每 30 秒或換話題時分段，時間戳記用該段開始的時間。
    - 兩人同時說話時分成兩行；實在分不出是誰時用「說話者?」。
    - 整段只有一個人說話時，就全部用說話者A，並在說話者表格只列一位。
    """

    /// 飲食記錄（照抄 Android `FoodLog.PROMPT`）：第 3 行 NUTRITION 機讀行。
    static let nutritionPrefix = "NUTRITION:"
    static let foodPrompt = """
    你是營養記錄助理。使用者會用口語描述剛吃（或喝）了什麼，請整理成飲食記錄，並估算熱量與三大營養素。
    - 依台灣常見的份量與做法估算（便當、小吃、麵店、手搖飲、便利商店等）。
    - 使用者明確說出的份量、杯型、甜度、冰塊、加料一定照用，不可以改成別的（例如說「大杯半糖」就是大杯、半糖），只有沒說的部分才用預設。
    - 沒說清楚的地方（份量、甜度、加料、烹調方式）用常見預設，並在「假設與待確認」寫出來。
    - 數字一律取整數；熱量單位大卡（kcal），營養素單位公克（g）。
    - 餐別依描述與錄音時間判斷：早餐、午餐、晚餐、點心、宵夜、飲料。
    - 若錄音內容不是在描述飲食，TITLE 寫「非飲食內容」，NUTRITION 全部填 0，並照實摘要內容。
    - 一律使用繁體中文（台灣用語）。這是估算，不要做醫療診斷。

    輸出格式（嚴格遵守，不要加其他前言）：
    第 1 行：TITLE: <餐別：主要食物，15 字內，例如「午餐：牛肉麵＋燙青菜」>
    第 2 行：SUMMARY: <一句話，含總熱量與蛋白質，例如「午餐約 780 大卡，蛋白質 35g」>
    第 3 行：\(nutritionPrefix) meal=<餐別>; kcal=<整數>; protein=<整數>; carbs=<整數>; fat=<整數>
    第 4 行起：Markdown，依序包含：
    ## 飲食內容
    （用表格：食物 | 份量 | 熱量(kcal) | 蛋白質(g) | 碳水(g) | 脂肪(g)；每樣食物一列）
    ## 合計
    （一行：約 X 大卡；蛋白質 Xg、碳水 Xg、脂肪 Xg）
    ## 營養小提醒
    （1～3 點，針對這一餐的簡短、實際建議）
    ## 假設與待確認

    之後單獨一行輸出 \(transcriptMarker)
    之後是逐字稿，每段一行：[mm:ss] 內容
    """

    /// 從模型輸出找出 NUTRITION 行（照 Android `FoodLog.parseNutrition`）。
    static func parseNutrition(_ text: String) -> AppleAI.Nutrition? {
        guard let line = text.components(separatedBy: "\n").map({ $0.trimmingCharacters(in: .whitespaces) })
            .first(where: { $0.uppercased().hasPrefix(nutritionPrefix) }) else { return nil }
        var kv: [String: String] = [:]
        for part in line.dropFirst(nutritionPrefix.count).split(whereSeparator: { $0 == ";" || $0 == "；" }) {
            let pair = part.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            if pair.count == 2 { kv[pair[0].lowercased()] = pair[1] }
        }
        func num(_ k: String) -> Int {
            guard let v = kv[k], let r = v.range(of: #"\d+"#, options: .regularExpression) else { return 0 }
            return Int(v[r]) ?? 0
        }
        return AppleAI.Nutrition(meal: kv["meal"] ?? "", kcal: num("kcal"), protein: num("protein"), carbs: num("carbs"), fat: num("fat"))
    }

    /// 報告裡不顯示機讀行。
    static func stripNutrition(_ markdown: String) -> String {
        markdown.components(separatedBy: "\n").filter { !$0.trimmingCharacters(in: .whitespaces).uppercased().hasPrefix(nutritionPrefix) }
            .joined(separator: "\n")
    }

    // MARK: - 主流程

    /// 上傳 → 等 ACTIVE → 依序試模型 → 刪除上傳的檔案。onListening：上傳完、AI 開始聆聽時呼叫。
    func generate(audio: URL, durationSec: Double, recordedAt: String,
                  onListening: @Sendable () async -> Void = {}) async throws -> Result {
        guard !apiKey.isEmpty else { throw Failure(message: "尚未設定 Gemini API Key（請到設定頁貼上）") }
        let t0 = Date()
        let file = try await upload(audio)
        NSLog("[Gemini] uploaded \(file.name) in \(Int(Date().timeIntervalSince(t0)))s")
        defer { Task { [name = file.name] in try? await delete(name) } }
        try await waitUntilActive(file.name)
        await onListening()
        let prompt = "錄音時間：\(recordedAt)，長度約 \(String(format: "%.1f", durationSec / 60)) 分鐘。請依格式整理這段錄音。"
        var last: Failure?
        for model in Self.models {
            do {
                let t0 = Date()
                NSLog("[Gemini] calling \(model)")
                let (text, truncated) = try await generateContent(model: model, fileURI: file.uri, prompt: prompt)
                NSLog("[Gemini] \(model) replied \(text.count) chars in \(Int(Date().timeIntervalSince(t0)))s truncated=\(truncated)")
                let (report, transcript) = Self.split(text)
                return Result(report: report, transcript: transcript, model: model, truncated: truncated)
            } catch let e as Failure {
                last = e
                // 額度用完(429) / 模型下架(404) / 伺服器太忙重試後仍失敗(503,500) → 換下一個模型
                guard [429, 404, 503, 500, 504].contains(e.code) else { throw e }
                NSLog("[Gemini] \(model) unavailable (\(e.code)), trying next model")
            }
        }
        throw last ?? Failure(message: "沒有可用的 Gemini 模型")
    }

    /// 匯入的文字（會議紀錄 / 逐字稿）→ 報告。逐字稿不請模型重抄，直接接上原文。
    func generateText(_ text: String, recordedAt: String) async throws -> (text: String, model: String) {
        guard !apiKey.isEmpty else { throw Failure(message: "尚未設定 Gemini API Key（請到設定頁貼上）") }
        let prompt = """
        這是一份匯入的文字記錄（會議紀錄、逐字稿或筆記，不是錄音），時間：\(recordedAt)。
        請依格式整理成報告；不需要輸出逐字稿區段。

        <document>
        \(text)
        </document>
        """
        var last: Failure?
        for model in Self.models {
            do {
                let (out, _) = try await generateContent(model: model, fileURI: nil, prompt: prompt)
                let report = out.range(of: Self.transcriptMarker).map { String(out[..<$0.lowerBound]) } ?? out
                return (report.trimmingCharacters(in: .whitespacesAndNewlines) + "\n\n" + Self.transcriptMarker + "\n" + text, model)
            } catch let e as Failure {
                last = e
                guard [429, 404, 503, 500, 504].contains(e.code) else { throw e }
            }
        }
        throw last ?? Failure(message: "沒有可用的 Gemini 模型")
    }

    /// 把模型輸出拆成報告和逐字稿。
    static func split(_ text: String) -> (GeneratedReport, String) {
        guard let r = text.range(of: transcriptMarker) else {
            return (GeneratedReport.parse(text.trimmingCharacters(in: .whitespacesAndNewlines)), "")
        }
        return (GeneratedReport.parse(String(text[..<r.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)),
                String(text[r.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines))
    }

    // MARK: - 背景處理用（GeminiJobs：系統代為上傳 / 等回應，App 暗了也能完成）

    /// resumable 上傳的第一步（小請求，前景/背景短時間內完成）：回傳實際上傳用的網址。
    func uploadStart(size: Int) async throws -> URL {
        var start = request("\(Self.base)/upload/v1beta/files", "POST")
        start.setValue("resumable", forHTTPHeaderField: "X-Goog-Upload-Protocol")
        start.setValue("start", forHTTPHeaderField: "X-Goog-Upload-Command")
        start.setValue(String(size), forHTTPHeaderField: "X-Goog-Upload-Header-Content-Length")
        start.setValue(Self.uploadMime, forHTTPHeaderField: "X-Goog-Upload-Header-Content-Type")
        start.setValue("application/json", forHTTPHeaderField: "Content-Type")
        start.httpBody = try JSONSerialization.data(withJSONObject: ["file": ["display_name": "Dylan recording"]])
        let (data, resp) = try await send(start)
        guard let s = (resp as? HTTPURLResponse)?.value(forHTTPHeaderField: "x-goog-upload-url"), let url = URL(string: s) else {
            throw Self.failure(resp, data)
        }
        return url
    }

    /// 上傳完成的回應 → (files/xxx, uri)。
    static func parseUploaded(_ data: Data) -> (name: String, uri: String)? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let file = json["file"] as? [String: Any],
              let name = file["name"] as? String, let uri = file["uri"] as? String else { return nil }
        return (name, uri)
    }

    /// generateContent（非串流）的請求內容。
    static func generateBody(fileURI: String, prompt: String, system: String = systemPrompt) throws -> Data {
        let body: [String: Any] = [
            "systemInstruction": ["parts": [["text": system]]],
            "contents": [["role": "user", "parts": [
                ["file_data": ["mime_type": uploadMime, "file_uri": fileURI]],
                ["text": prompt],
            ]]],
            "generationConfig": ["temperature": 0.2],
        ]
        return try JSONSerialization.data(withJSONObject: body)
    }

    /// generateContent（非串流）的回應 → 文字（跳過思考過程）＋是否被截斷；沒有內容就丟錯。
    static func parseGenerated(_ data: Data) throws -> (text: String, truncated: Bool) {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw Failure(message: "Gemini 回應看不懂")
        }
        let block = (json["promptFeedback"] as? [String: Any])?["blockReason"] as? String ?? ""
        let cand = (json["candidates"] as? [[String: Any]])?.first
        let finish = cand?["finishReason"] as? String ?? ""
        let parts = (cand?["content"] as? [String: Any])?["parts"] as? [[String: Any]] ?? []
        let text = parts.filter { $0["thought"] as? Bool != true }.compactMap { $0["text"] as? String }.joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            let why = block.isEmpty ? finish : block
            throw Failure(message: "Gemini 沒有回傳內容" + (why.isEmpty ? "" : "（\(why)）"))
        }
        return (text, finish == "MAX_TOKENS")
    }

    static func prompt(durationSec: Double, recordedAt: String, lang: SpeechLang = .zh,
                       dialect: HakkaDialect = .sixian) -> String {
        let base = "錄音時間：\(recordedAt)，長度約 \(String(format: "%.1f", durationSec / 60)) 分鐘。請依格式整理這段錄音。"
        if lang == .hak {
            return base + "這段錄音是客語（\(dialect.label)腔，可能和國語夾雜）。逐字稿請一律用台灣繁體中文的華語字寫出意思（客語的部分翻成通順的華語，"
                + "國語照說的寫），不要用客語拼音，也不要寫成同音的錯字。"
        }
        guard lang == .nan else { return base }
        return base + "這段錄音有台語（可能和國語夾雜在同一句裡）。逐字稿請一律用台灣繁體中文的華語字寫出意思（台語的部分翻成通順的華語，"
            + "國語照說的寫），不要用台羅拼音，也不要寫成同音的錯字。"
    }

    // MARK: - REST

    private struct UploadedFile { let name: String; let uri: String }

    func request(_ url: String, _ method: String, timeout: TimeInterval = 60) -> URLRequest {
        var r = URLRequest(url: URL(string: url)!, timeoutInterval: timeout)
        r.httpMethod = method
        r.setValue(apiKey, forHTTPHeaderField: "x-goog-api-key")
        return r
    }

    private func upload(_ audio: URL) async throws -> UploadedFile {
        let size = (try? audio.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        var start = request("\(Self.base)/upload/v1beta/files", "POST")
        start.setValue("resumable", forHTTPHeaderField: "X-Goog-Upload-Protocol")
        start.setValue("start", forHTTPHeaderField: "X-Goog-Upload-Command")
        start.setValue(String(size), forHTTPHeaderField: "X-Goog-Upload-Header-Content-Length")
        start.setValue(Self.uploadMime, forHTTPHeaderField: "X-Goog-Upload-Header-Content-Type")
        start.setValue("application/json", forHTTPHeaderField: "Content-Type")
        start.httpBody = try JSONSerialization.data(withJSONObject: ["file": ["display_name": "Dylan recording"]])
        let (startData, startResp) = try await send(start)
        guard let uploadURL = (startResp as? HTTPURLResponse)?.value(forHTTPHeaderField: "x-goog-upload-url") else {
            throw Self.failure(startResp, startData)
        }

        var put = URLRequest(url: URL(string: uploadURL)!, timeoutInterval: 300)
        put.httpMethod = "POST"
        put.setValue("0", forHTTPHeaderField: "X-Goog-Upload-Offset")
        put.setValue("upload, finalize", forHTTPHeaderField: "X-Goog-Upload-Command")
        let (data, resp) = try await sendUpload(put, file: audio)
        try Self.check(resp, data)
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let file = json["file"] as? [String: Any],
              let name = file["name"] as? String, let uri = file["uri"] as? String
        else { throw Failure(message: "Gemini 上傳回應看不懂") }
        return UploadedFile(name: name, uri: uri)
    }

    /// 上傳後檔案可能還在 PROCESSING，等到 ACTIVE 才能用。
    func waitUntilActive(_ name: String) async throws {
        for _ in 0..<60 {
            let (data, resp) = try await send(request("\(Self.base)/v1beta/\(name)", "GET"))
            try Self.check(resp, data)
            let state = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["state"] as? String ?? "ACTIVE"
            if state == "ACTIVE" { return }
            if state == "FAILED" { throw Failure(message: "Gemini 無法處理這個錄音檔") }
            try await Task.sleep(for: .seconds(2))
        }
        throw Failure(message: "Gemini 處理錄音檔逾時")
    }

    private func generateContent(model: String, fileURI: String?, prompt: String) async throws -> (String, Bool) {
        var parts: [[String: Any]] = []
        if let fileURI { parts.append(["file_data": ["mime_type": Self.uploadMime, "file_uri": fileURI]]) }
        parts.append(["text": prompt])
        let body: [String: Any] = [
            "systemInstruction": ["parts": [["text": Self.systemPrompt]]],
            "contents": [["role": "user", "parts": parts]],
            "generationConfig": ["temperature": 0.2],
        ]
        var attempt = 0
        while true {
            // 兩段資料之間最多等 3 分鐘（模型先「聽」完錄音才開始輸出）
            var r = request("\(Self.base)/v1beta/models/\(model):streamGenerateContent?alt=sse", "POST", timeout: 180)
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try JSONSerialization.data(withJSONObject: body)
            do {
                return try await readStream(r)
            } catch let e as Failure where (e.code == 503 || e.code == 500) && attempt < 2 {
                attempt += 1   // 伺服器忙，退避重試
                NSLog("[Gemini] \(model) busy (\(e.code)), retry \(attempt)")
                try await Task.sleep(for: .seconds(5 * attempt))
            }
        }
    }

    /// 讀 SSE：每行 `data: {json}`，每個 json 是一段 candidates[0].content.parts（跳過思考過程）。
    private func readStream(_ r: URLRequest) async throws -> (String, Bool) {
        let bytes: URLSession.AsyncBytes, resp: URLResponse
        do { (bytes, resp) = try await URLSession.shared.bytes(for: r) } catch {
            // 逾時 / 斷線：當成「太忙」（code 504），換下一個模型
            throw Failure(message: "Gemini 回應逾時（\(error.localizedDescription)），請稍後按「重試」", code: 504)
        }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            var data = Data()
            for try await b in bytes { data.append(b) }
            throw Self.failure(resp, data)
        }
        var text = "", finish = "", block = ""
        do {
            for try await line in bytes.lines {
                guard line.hasPrefix("data:"),
                      let json = try? JSONSerialization.jsonObject(with: Data(line.dropFirst(5).utf8)) as? [String: Any]
                else { continue }
                if let fb = json["promptFeedback"] as? [String: Any], let reason = fb["blockReason"] as? String { block = reason }
                guard let cand = (json["candidates"] as? [[String: Any]])?.first else { continue }
                for p in ((cand["content"] as? [String: Any])?["parts"] as? [[String: Any]]) ?? [] {
                    if p["thought"] as? Bool == true { continue }
                    text += p["text"] as? String ?? ""
                }
                if let f = cand["finishReason"] as? String, !f.isEmpty { finish = f }
            }
        } catch {
            throw Failure(message: "Gemini 回應逾時（\(error.localizedDescription)），請稍後按「重試」", code: 504)
        }
        text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            let why = block.isEmpty ? finish : block
            throw Failure(message: "Gemini 沒有回傳內容" + (why.isEmpty ? "" : "（\(why)）"))
        }
        if finish == "MAX_TOKENS" { NSLog("[Gemini] output truncated at MAX_TOKENS") }
        return (text, finish == "MAX_TOKENS")
    }

    func delete(_ name: String) async throws {
        _ = try await send(request("\(Self.base)/v1beta/\(name)", "DELETE"))
    }

    func send(_ r: URLRequest) async throws -> (Data, URLResponse) {
        do { return try await URLSession.shared.data(for: r) } catch {
            throw Failure(message: "連不上 Gemini（\(error.localizedDescription)），請檢查網路後重試")
        }
    }

    private func sendUpload(_ r: URLRequest, file: URL) async throws -> (Data, URLResponse) {
        do { return try await URLSession.shared.upload(for: r, fromFile: file) } catch {
            throw Failure(message: "上傳錄音失敗（\(error.localizedDescription)），請檢查網路後重試")
        }
    }

    static func check(_ resp: URLResponse, _ data: Data) throws {
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if !(200..<300).contains(code) { throw failure(resp, data) }
    }

    static func failure(_ resp: URLResponse, _ data: Data) -> Failure {
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let raw = String(data: data, encoding: .utf8) ?? ""
        let detail = ((try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? [String: Any])?["message"] as? String ?? raw
        let msg: String
        switch code {
        case 400: msg = detail.localizedCaseInsensitiveContains("API key") ? "Gemini API Key 無效，請到設定重新貼上" : "Gemini 拒絕這個請求：\(detail)"
        case 401, 403: msg = "Gemini API Key 無效或沒有權限，請到設定重新貼上"
        case 429: msg = "今天的 Gemini 免費額度用完了，明天再按「重試」"
        case 500, 503: msg = "Gemini 目前太忙（Google 伺服器需求量大），過幾分鐘再按「重試」"
        default: msg = "Gemini 錯誤 \(code)：\(detail)"
        }
        return Failure(message: msg, code: code)
    }
}
