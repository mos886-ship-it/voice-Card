import Foundation
import FoundationModels
import Speech

/// 這支 iPhone 能用哪些 Apple AI，並依此自動選 AI 引擎（使用者要求：不同 iPhone 用不同的）。
///
/// 2026-11-22 使用者決定：Apple 的語音辨識**不分說話者**，所以錄音報告以 Gemini 為主，
/// Apple 只當「輔助」，以及負責「飲食」（一個人說吃了什麼，不需要分人）。
///
/// 錄音（會議、課堂、訪談…）：
/// | 情況                         | 用什麼                                                    |
/// |-----------------------------|-----------------------------------------------------------|
/// | 有 Gemini 金鑰               | Gemini（聽錄音、分說話者）                                   |
/// | 沒金鑰、有 Apple 語音＋模型    | 輔助：Apple 裝置端逐字稿＋Apple 整理（沒有分說話者），iOS 27+ 用私有雲 |
/// | 沒金鑰、只有 Apple 語音        | 輔助：只有 Apple 逐字稿                                     |
/// | 都沒有（iOS 17–25 沒金鑰）    | 不能產生報告                                               |
/// Gemini 連不上 / 額度用完時也用 Apple 輔助（Apple 引擎做好後接上）。
///
/// 飲食（說出吃了什麼 → 熱量、營養素）：
/// | 有 Apple 語音＋模型（iOS 27+ 私有雲優先） | Apple（免金鑰、不上傳）       |
/// | 否則有 Gemini 金鑰                     | Gemini                      |
/// | 只有 Apple 語音                        | 只有逐字稿，沒有熱量          |
///
/// 繁中支援依 Apple 的語言清單即時判斷，不寫死。
struct AiCapability: Equatable {
    enum ModelState: Equatable {
        case available
        case deviceNotEligible        // 機型不支援 Apple Intelligence
        case notEnabled               // 使用者沒開 Apple Intelligence
        case notReady                 // 模型下載中 / 系統還沒準備好
        case osTooOld                 // iOS 版本太舊，沒有這個 API

        var label: String {
            switch self {
            case .available: return "可用"
            case .deviceNotEligible: return "這支 iPhone 不支援"
            case .notEnabled: return "未開啟（設定 → Apple Intelligence）"
            case .notReady: return "模型下載中"
            case .osTooOld: return "需要更新 iOS"
            }
        }
    }

    enum Engine: Equatable {
        case applePCC          // Apple 裝置端辨識 ＋ Apple 私有雲運算整理
        case appleOnDevice     // 辨識和整理都在手機上
        case appleSpeechGemini // Apple 裝置端辨識 ＋ Gemini 整理
        case gemini            // Gemini 一站式（聽錄音直接出報告）
        case transcriptOnly    // 只有逐字稿（沒有可用的 AI 模型、也沒有金鑰）
        case none              // 什麼都不能做

        var title: String {
            switch self {
            case .applePCC: return "Apple 裝置端辨識 ＋ Apple 私有雲整理"
            case .appleOnDevice: return "Apple 裝置端（不上傳）"
            case .appleSpeechGemini: return "Apple 裝置端辨識 ＋ Gemini 整理"
            case .gemini: return "Google Gemini"
            case .transcriptOnly: return "只有逐字稿"
            case .none: return "尚未設定"
            }
        }

        var detail: String {
            switch self {
            case .applePCC: return "免費、不需金鑰；語音在手機上轉文字，交給 Apple 私有雲運算整理（不保留資料）；沒有分說話者"
            case .appleOnDevice: return "免費、不需金鑰、不上傳；語音和整理都在手機上；沒有分說話者"
            case .appleSpeechGemini: return "語音在手機上轉文字，報告由 Gemini 整理"
            case .gemini: return "Gemini 直接聽錄音，產出報告與分說話者逐字稿"
            case .transcriptOnly: return "只有 Apple 裝置端逐字稿；填入 Gemini 金鑰即可產生分說話者的報告"
            case .none: return "這支 iPhone 需要 Gemini 金鑰才能產生報告"
            }
        }

        var ready: Bool { self != .none }
    }

    var osVersion: String
    /// iOS 26+ 的裝置端語音辨識支援繁體中文（台灣）。
    var speechZhTW: Bool
    var onDeviceModel: ModelState
    /// 裝置端模型支援繁體中文。
    var modelZhTW: Bool
    var pcc: ModelState

    /// 這支 iPhone 能用的 Apple 整理方式（私有雲優先）；沒有就 nil。
    private var appleModel: Engine? {
        guard speechZhTW && modelZhTW else { return nil }
        if pcc == .available { return .applePCC }
        if onDeviceModel == .available { return .appleOnDevice }
        return nil
    }

    /// 錄音報告：Gemini 為主（分說話者）；沒金鑰才用 Apple 輔助。
    func recordingEngine(hasGeminiKey: Bool) -> Engine {
        if hasGeminiKey { return .gemini }
        if let apple = appleModel { return apple }
        return speechZhTW ? .transcriptOnly : .none
    }

    /// 輔助：Gemini 不能用（沒金鑰、連不上、額度用完）時改用的 Apple 方式；nil = 沒有可用的輔助。
    var helperEngine: Engine? { appleModel ?? (speechZhTW ? .transcriptOnly : nil) }

    /// 飲食：Apple 優先（一個人說話，不需要分人；免金鑰、不上傳），否則 Gemini。
    func foodEngine(hasGeminiKey: Bool) -> Engine {
        if let apple = appleModel { return apple }
        if hasGeminiKey { return .gemini }
        return speechZhTW ? .transcriptOnly : .none
    }

    static let zhTW = Locale(identifier: "zh-TW")

    /// 偵測這支 iPhone（語言清單要非同步查）。
    static func detect() async -> AiCapability {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        var cap = AiCapability(osVersion: "iOS \(v.majorVersion).\(v.minorVersion)",
                               speechZhTW: false, onDeviceModel: .osTooOld, modelZhTW: false, pcc: .osTooOld)
        if #available(iOS 26.0, *) {
            let locales = await SpeechTranscriber.supportedLocales
            cap.speechZhTW = locales.contains { isZhTW($0) }
            NSLog("[AiCapability] speech locales=\(locales.count) zh=\(locales.map { $0.identifier(.bcp47) }.filter { $0.hasPrefix("zh") })")
            let model = SystemLanguageModel.default
            switch model.availability {
            case .available: cap.onDeviceModel = .available
            case .unavailable(.deviceNotEligible): cap.onDeviceModel = .deviceNotEligible
            case .unavailable(.appleIntelligenceNotEnabled): cap.onDeviceModel = .notEnabled
            case .unavailable: cap.onDeviceModel = .notReady
            }
            cap.modelZhTW = model.supportsLocale(zhTW)
        }
        if #available(iOS 27.0, *) {
            switch PrivateCloudComputeLanguageModel().availability {
            case .available: cap.pcc = .available
            case .unavailable(.deviceNotEligible): cap.pcc = .deviceNotEligible
            case .unavailable: cap.pcc = .notReady
            }
        }
        return cap
    }

    private static func isZhTW(_ l: Locale) -> Bool {
        let lang = l.language
        guard lang.languageCode?.identifier == "zh" else { return false }
        return lang.region?.identifier == "TW" || lang.script?.identifier == "Hant"
    }
}

/// 偵測結果放在這裡，畫面共用。
@MainActor
final class AiStatus: ObservableObject {
    static let shared = AiStatus()
    @Published private(set) var capability: AiCapability?
    var hasGeminiKey: Bool { !(Keychain.get(Keychain.geminiKey) ?? "").isEmpty }

    /// 錄音報告用的引擎。
    var engine: AiCapability.Engine? { capability?.recordingEngine(hasGeminiKey: hasGeminiKey) }
    var helper: AiCapability.Engine? { capability?.helperEngine }
    var foodEngine: AiCapability.Engine? { capability?.foodEngine(hasGeminiKey: hasGeminiKey) }

    func refresh() async {
        capability = await AiCapability.detect()
    }
}
