import Foundation
import FoundationModels
import Speech

/// 這支 iPhone 能用哪些 Apple AI，並依此自動選 AI 引擎（使用者要求：不同 iPhone 用不同的）。
///
/// | 這支 iPhone                                    | 語音 → 逐字稿                     | 逐字稿 → 報告                           |
/// |-----------------------------------------------|----------------------------------|----------------------------------------|
/// | iOS 27+、有 Apple Intelligence                 | Apple 裝置端（SpeechTranscriber） | Apple 私有雲運算（PCC，模型較大、免金鑰），不能用時改裝置端模型 |
/// | iOS 26、有 Apple Intelligence                  | Apple 裝置端                      | Apple 裝置端模型（SystemLanguageModel）  |
/// | iOS 26+、沒有 Apple Intelligence（舊機型 / 未開啟） | Apple 裝置端                  | Gemini（有金鑰時），否則只有逐字稿         |
/// | iOS 17–25                                     | Gemini 一站式（有金鑰時，聽錄音直接出報告），否則系統語音辨識只出逐字稿 |
///
/// 飲食熱量一律用 Gemini（裝置端小模型估熱量不可靠）。繁中支援依 Apple 的語言清單即時判斷，不寫死。
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
            case .applePCC: return "免費、不需金鑰；語音在手機上轉文字，報告交給 Apple 私有雲運算（不保留資料）"
            case .appleOnDevice: return "免費、不需金鑰、不上傳；長錄音會分段整理，沒有分說話者"
            case .appleSpeechGemini: return "語音在手機上轉文字，報告由 Gemini 整理"
            case .gemini: return "Gemini 直接聽錄音，產出報告與分說話者逐字稿"
            case .transcriptOnly: return "這支 iPhone 沒有可用的 Apple 語言模型；填入 Gemini 金鑰即可產生報告"
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

    /// 自動選擇（hasGeminiKey：使用者有沒有填 Gemini 金鑰，里程碑 2 加上設定欄位）。
    func autoEngine(hasGeminiKey: Bool) -> Engine {
        if speechZhTW && modelZhTW && pcc == .available { return .applePCC }
        if speechZhTW && modelZhTW && onDeviceModel == .available { return .appleOnDevice }
        if speechZhTW { return hasGeminiKey ? .appleSpeechGemini : .transcriptOnly }
        return hasGeminiKey ? .gemini : .none
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

    var engine: AiCapability.Engine? { capability?.autoEngine(hasGeminiKey: hasGeminiKey) }

    func refresh() async {
        capability = await AiCapability.detect()
    }
}
