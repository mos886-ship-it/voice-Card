import AVFoundation
import Foundation
import FoundationModels
import Speech

/// Apple 引擎（2026-11-22 使用者決定：Apple 不分說話者 → 用在輔助、即時預覽、隱私模式、飲食）。
///
/// - 逐字稿：`SpeechAnalyzer` + `SpeechTranscriber`（iOS 26+，zh-TW，裝置端、離線、很快；不分說話者）。
/// - 整理：iOS 27+ 有 Apple Intelligence → 私有雲 `PrivateCloudComputeLanguageModel`（模型較大）；
///   否則裝置端 `SystemLanguageModel`（一次能讀的字數有限 → 長逐字稿先分段做筆記再合併）。
/// - 飲食：用 `@Generable` 直接產出結構化的營養數字，不用解析文字。
///
/// 這個檔案不依賴 App 其他部分（除了 GeneratedReport），也能編進 Mac 上的測試程式。
enum AppleAI {
    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// 一段逐字稿（Apple 不分說話者）。
    struct Line {
        let start: Double
        let text: String
    }

    static let zhTW = Locale(identifier: "zh-TW")

    // MARK: - 逐字稿

    /// 錄音 → 帶時間的逐字稿。第一次用時系統可能要下載繁中語音模型（系統元件，App 不另外收費）。
    static func transcribe(_ url: URL) async throws -> [Line] {
        guard #available(iOS 26.0, macOS 26.0, *) else {
            throw Failure(message: "Apple 語音辨識需要 iOS 26 以上")
        }
        let transcriber = SpeechTranscriber(locale: zhTW, transcriptionOptions: [], reportingOptions: [],
                                            attributeOptions: [.audioTimeRange])
        if let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
            try await request.downloadAndInstall()
        }
        let analyzer = SpeechAnalyzer(modules: [transcriber])
        let file = try AVAudioFile(forReading: url)
        let collect = Task { () -> [Line] in
            var out: [Line] = []
            for try await r in transcriber.results {
                let text = String(r.text.characters).trimmingCharacters(in: .whitespacesAndNewlines)
                if !text.isEmpty { out.append(Line(start: r.range.start.seconds, text: text)) }
            }
            return out
        }
        try await analyzer.start(inputAudioFile: file, finishAfterFile: true)
        return try await collect.value
    }

    /// 這支裝置有沒有 Apple 繁中語音辨識（iOS 26+ 且語言清單有 zh-TW；模擬器沒有語音模型）。
    static func speechAvailable() async -> Bool {
        guard #available(iOS 26.0, macOS 26.0, *) else { return false }
        return await SpeechTranscriber.supportedLocales.contains {
            $0.language.languageCode?.identifier == "zh" && ($0.language.region?.identifier == "TW" || $0.language.script?.identifier == "Hant")
        }
    }

    /// 逐字稿文字檔格式：「[mm:ss] 內容」（沒有說話者）。
    static func transcriptText(_ lines: [Line]) -> String {
        lines.map { "[\(stamp($0.start))] \($0.text)" }.joined(separator: "\n")
    }

    static func stamp(_ sec: Double) -> String {
        let s = max(0, Int(sec))
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            : String(format: "%02d:%02d", s / 60, s % 60)
    }

    // MARK: - 選模型

    enum Model: String {
        case pcc = "apple-pcc"            // Apple 私有雲運算
        case onDevice = "apple-ondevice"  // 手機上的模型
    }

    /// 這支裝置能用的 Apple 模型（私有雲優先）；都不能用就 nil。
    static var availableModel: Model? {
        if #available(iOS 27.0, macOS 27.0, *), PrivateCloudComputeLanguageModel().availability == .available,
           SystemLanguageModel.default.supportsLocale(zhTW) {
            return .pcc
        }
        if #available(iOS 26.0, macOS 26.0, *), SystemLanguageModel.default.availability == .available,
           SystemLanguageModel.default.supportsLocale(zhTW) {
            return .onDevice
        }
        return nil
    }

    @available(iOS 26.0, macOS 26.0, *)
    private static func session(_ model: Model, instructions: String) -> LanguageModelSession {
        if model == .pcc, #available(iOS 27.0, macOS 27.0, *) {
            return LanguageModelSession(model: PrivateCloudComputeLanguageModel(), instructions: instructions)
        }
        return LanguageModelSession(model: SystemLanguageModel.default, instructions: instructions)
    }

    /// 一次送進模型的逐字稿長度（字）。裝置端模型一次能讀的不多。
    private static func chunkChars(_ model: Model) -> Int { model == .pcc ? 12_000 : 2_000 }

    // MARK: - 錄音報告（輔助 / 隱私模式）

    static let reportInstructions = """
    你是專業的語音記錄助理。使用者會給你一份裝置端語音辨識產生的逐字稿（可能有同音錯字、沒有標示說話者），請依上下文理解後整理。
    錄音可能是會議、課堂、訪談、通話、演講或個人備忘，請先判斷類型再整理重點。
    不要捏造逐字稿裡沒有的內容；不確定的標註「（待確認）」。一律使用繁體中文（台灣用語）。
    逐字稿沒有區分說話者，報告裡不要猜是誰說的。

    輸出格式（嚴格遵守，不要加其他前言）：
    第 1 行：TITLE: <15 字內的標題>
    第 2 行：SUMMARY: <2～3 句、80 字內的摘要>
    第 3 行起：Markdown 報告，依序包含以下章節（沒有內容的章節寫「無」）：
    ## 摘要
    （3～5 句完整摘要，一定要寫，不可以寫「無」）
    ## 重點
    ## 結論 / 決定
    ## 待辦事項
    （用表格：事項 | 負責人 | 期限；沒提到就寫「未指定」）
    ## 待確認 / 後續問題
    """

    static let notesInstructions = """
    你是語音記錄助理。使用者會給你一段長逐字稿中的一部分（裝置端語音辨識，可能有同音錯字、沒有說話者），
    請用繁體中文條列這段的重點、決定、待辦事項（含負責人與期限，沒提到寫「未指定」）與待確認事項。只輸出條列，不要前言。
    """

    /// 逐字稿 → 報告（長的先分段做筆記再合併）。回傳報告與用的模型代號。
    static func report(transcript: String, durationSec: Double, recordedAt: String) async throws -> (GeneratedReport, String) {
        guard #available(iOS 26.0, macOS 26.0, *), let model = availableModel else {
            throw Failure(message: "這支裝置沒有可用的 Apple Intelligence 模型")
        }
        guard !transcript.isEmpty else { throw Failure(message: "逐字稿是空的，沒有可整理的內容") }
        let header = "錄音時間：\(recordedAt)，長度約 \(String(format: "%.1f", durationSec / 60)) 分鐘。"
        let limit = chunkChars(model)
        var material = transcript
        if transcript.count > limit {
            var notes: [String] = []
            let chunks = Self.chunks(transcript, size: limit)
            for (i, c) in chunks.enumerated() {
                let s = session(model, instructions: notesInstructions)
                let r = try await s.respond(to: "第 \(i + 1)/\(chunks.count) 部分逐字稿：\n\(c)")
                notes.append("【第 \(i + 1) 部分】\n\(r.content)")
            }
            material = "以下是長錄音各部分的筆記（不是逐字稿）：\n" + notes.joined(separator: "\n\n")
        }
        let s = session(model, instructions: reportInstructions)
        let r = try await s.respond(to: "\(header)\n\n\(material)")
        return (GeneratedReport.parse(r.content), model.rawValue)
    }

    /// 依行切成每段不超過 size 字。
    static func chunks(_ text: String, size: Int) -> [String] {
        var out: [String] = [], cur = ""
        for line in text.components(separatedBy: "\n") {
            if cur.count + line.count + 1 > size, !cur.isEmpty { out.append(cur); cur = "" }
            cur += (cur.isEmpty ? "" : "\n") + line
        }
        if !cur.isEmpty { out.append(cur) }
        return out
    }

    // MARK: - 飲食

    /// 一餐的營養估算（照 Android `Nutrition`：AI 依口述估的，不是精確值）。
    struct Nutrition: Codable, Equatable {
        var meal: String
        var kcal: Int
        var protein: Int
        var carbs: Int
        var fat: Int
    }

    static let foodInstructions = """
    你是營養記錄助理。使用者會給你一段口述的逐字稿（裝置端語音辨識，可能有同音錯字），描述剛吃（或喝）了什麼。
    請依台灣常見的份量與做法（便當、小吃、麵店、手搖飲、便利商店等）列出每樣食物並估算熱量與三大營養素。
    使用者明確說出的份量、杯型、甜度、冰塊、加料一定照用；沒說清楚的用常見預設，並寫進假設。
    數字一律取整數；熱量單位大卡，營養素單位公克。餐別依描述與錄音時間判斷：早餐、午餐、晚餐、點心、宵夜、飲料。
    若內容不是在描述飲食，標題寫「非飲食內容」，所有數字填 0。一律使用繁體中文（台灣用語）。這是估算，不做醫療診斷。
    """

    @available(iOS 26.0, macOS 26.0, *)
    @Generable(description: "一餐的飲食記錄與營養估算")
    struct MealEstimate {
        @Guide(description: "餐別：早餐、午餐、晚餐、點心、宵夜、飲料之一")
        var meal: String
        @Guide(description: "15 字內的標題，例如「午餐：牛肉麵＋燙青菜」")
        var title: String
        @Guide(description: "一句話摘要，含總熱量與蛋白質，例如「午餐約 780 大卡，蛋白質 35g」")
        var summary: String
        @Guide(description: "每樣食物一筆")
        var items: [FoodItem]
        @Guide(description: "1～3 點針對這一餐的簡短營養提醒")
        var tips: [String]
        @Guide(description: "沒說清楚、用預設估算的地方")
        var assumptions: [String]
    }

    @available(iOS 26.0, macOS 26.0, *)
    @Generable(description: "一樣食物")
    struct FoodItem {
        var name: String
        @Guide(description: "份量，例如「一碗」「大杯」")
        var portion: String
        var kcal: Int
        @Guide(description: "蛋白質（公克）")
        var protein: Int
        @Guide(description: "碳水化合物（公克）")
        var carbs: Int
        @Guide(description: "脂肪（公克）")
        var fat: Int
    }

    /// 口述逐字稿 → 飲食報告（Markdown，跟 Gemini 飲食報告同格式）＋營養數字＋模型代號。
    static func food(transcript: String, recordedAt: String) async throws -> (GeneratedReport, Nutrition, String) {
        guard #available(iOS 26.0, macOS 26.0, *), let model = availableModel else {
            throw Failure(message: "這支裝置沒有可用的 Apple Intelligence 模型")
        }
        let s = session(model, instructions: foodInstructions)
        let r = try await s.respond(to: "錄音時間：\(recordedAt)。\n逐字稿：\n\(transcript)", generating: MealEstimate.self)
        let e = r.content
        let n = Nutrition(meal: e.meal, kcal: e.items.reduce(0) { $0 + $1.kcal },
                          protein: e.items.reduce(0) { $0 + $1.protein },
                          carbs: e.items.reduce(0) { $0 + $1.carbs }, fat: e.items.reduce(0) { $0 + $1.fat })
        var md = "# \(e.title)\n\n## 飲食內容\n| 食物 | 份量 | 熱量(kcal) | 蛋白質(g) | 碳水(g) | 脂肪(g) |\n|---|---|---|---|---|---|\n"
        for i in e.items { md += "| \(i.name) | \(i.portion) | \(i.kcal) | \(i.protein) | \(i.carbs) | \(i.fat) |\n" }
        md += "\n## 合計\n約 \(n.kcal) 大卡；蛋白質 \(n.protein)g、碳水 \(n.carbs)g、脂肪 \(n.fat)g\n"
        md += "\n## 營養小提醒\n" + (e.tips.isEmpty ? "無" : e.tips.map { "- \($0)" }.joined(separator: "\n")) + "\n"
        md += "\n## 假設與待確認\n" + (e.assumptions.isEmpty ? "無" : e.assumptions.map { "- \($0)" }.joined(separator: "\n")) + "\n"
        return (GeneratedReport(title: e.title, shortSummary: e.summary, markdown: md), n, model.rawValue)
    }
}
