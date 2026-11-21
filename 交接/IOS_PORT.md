# 大D iPhone + Apple Watch 版：移植說明（給 Mac mini 上的 Claude Code）

這份是 Windows 上 Android 版的開發 session 寫的。Android 版是**參考設計**：功能、畫面、Gemini 提示詞都照它做，程式用 Swift / SwiftUI 重寫（不共用程式碼）。完整功能與踩過的坑先讀同資料夾的 `HANDOFF.md`。

## 範圍
- iPhone App（iOS 17+）+ Apple Watch App（watchOS 10+），一個 Xcode 專案、兩個 target。
- 不支援 Galaxy Watch（不能配 iPhone）。也沒有三星錄音機 / Galaxy AI / 通話錄音自動匯入（iOS 不給）。
- 先在模擬器驗證（iPhone + 配對的 Watch 模擬器），真機之後再說。

## Android → Apple 對照
| Android（檔案） | Apple 版做法 |
|---|---|
| 手錶錄音 `wear/.../RecordingService.kt`（AAC 分段、邊錄邊傳） | `AVAudioRecorder` / `AVAudioEngine` 錄 AAC m4a；錄音中用 `WKExtendedRuntimeSession` 或 audio background mode |
| Data Layer `MessageClient` / `ChannelClient`（`shared/.../WristAiProtocol.kt` 的路徑） | `WatchConnectivity`：檔案用 `transferFile`（會排隊、斷線自動補傳），小訊息用 `sendMessage` / `transferUserInfo`；保留同樣的路徑名當 message type |
| 手機收錄音 `AudioReceiverService.kt`、`SegmentAssembler.kt`、`LongMeeting.kt` | `WCSessionDelegate.session(_:didReceive file:)`，分段接成一個 m4a |
| 手機錄音 `PhoneRecordingService.kt` | `AVAudioSession` `.record` + background audio |
| Gemini 報告 `GeminiReportGenerator.kt`、`ProcessMeetingWorker.kt` | `URLSession` 打 Gemini REST（Files API 上傳 → generateContent → 刪檔）；**提示詞照抄**；模型 `gemini-3.8-flash`，429 時改 `gemini-3.5-flash-lite`；背景用 `BGProcessingTask` / background `URLSession` |
| 報告解析 `ReportGenerator.kt`（TITLE / SUMMARY 行、`fillEmptySummary`）、`Speakers.kt` | 同邏輯移植 |
| 儲存 `MeetingStore.kt`（每筆一資料夾：audio、meta.json、transcript.txt、report.md、geo.json） | 同結構放 Application Support，`Codable` |
| GPS `shared/.../GeoFix.kt`、`MeetingStore.placeName` | `CLLocationManager` 開始錄音時取一次；手錶錄音以 iPhone 定位為主；`CLGeocoder` 反查地名，`placeName` 規則照抄 |
| 搜尋 `SearchIndex.kt` | 照抄（多關鍵字 AND、不分大小寫、日期多格式、snippet） |
| 飲食記錄 `FoodLog.kt`、`ui/FoodScreen.kt` | 同功能 |
| 手機 UI `ui/*.kt`（Dashboard、錄音列表、詳情 ReportFacts/ReportView、設定） | SwiftUI，配色照 `ui/theme` |
| 手錶 UI `wear/.../MainActivity.kt`（兩頁：錄音頁 / 「報告書」清單→點進明細，月份、搜尋、篩選） | SwiftUI `TabView(.page)`；明細從手機要（`/wristai/detail/req`） |
| Gemini API Key（設定頁使用者自己填） | 存 Keychain。**不要寫死任何 key、不要放進 repo** |

## 測試
- 單元測試把 `app/src/test/` 的案例（ImportTime、PlaceName、SummaryFill、AiLabel、SearchIndex）改寫成 XCTest。
- 模擬器：Watch 錄音 → iPhone 收到 → 報告 → 摘要回 Watch。GPS 用模擬器的自訂位置。

## 規矩（使用者定的）
- 介面繁體中文，品牌「大D」。不要用台積電、Alan Walker 相關商標。
- Apple ID 登入、簽章、同意條款、權限允許都由使用者自己按。
- 使用者希望你自主完成、少問；做完更新 `HANDOFF.md` 的 iOS 段落。
