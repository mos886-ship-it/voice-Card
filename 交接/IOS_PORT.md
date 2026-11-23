# 大D iPhone + Apple Watch 版：移植說明（給 Mac mini 上的 Claude Code）

更新：2026-11-21（依 `HANDOFF.md` 到 11/21 的內容補齊；原版 2026-11-19）

Android 版是**參考設計**：功能、畫面、提示詞都照它做，程式用 Swift / SwiftUI 重寫（不共用程式碼）。完整功能與踩過的坑先讀同資料夾的 `HANDOFF.md`。

## 範圍與已定的決定
- iPhone App（**iOS 17+**）+ Apple Watch App（**watchOS 10+**），一個 Xcode 專案、兩個 target。開發機：Xcode 27.2，模擬器 iOS / watchOS 27.2。
- 先在模擬器驗證（iPhone + 配對的 Watch 模擬器），真機之後再說。
- **不移植（三星 / Android 專屬）**：存進三星錄音機（`VoiceRecorderExporter`、`SamsungRecordingTag` 的 `saut` 標記）、Galaxy AI、通話錄音自動匯入（`CallRecordingWatcher`、`BootReceiver`）、手錶遙控手機錄音的透明跳板畫面（`RemoteRecordActivity`、全螢幕通知）、Galaxy Watch 支援。
- **手錶「用手機錄音」**：v1 不做（iOS App 在背景不能啟動麥克風）。之後可考慮：iPhone 先在前台開好 audio session 再由手錶遙控開始 / 停止。
- **AI 引擎：預設「自動（依這支 iPhone）」**。偵測在 `ios/Dylan/AiCapability.swift`，開 App 時跑，設定頁「AI 引擎」顯示偵測結果與各用途選到的引擎。
  - 2026-11-22 使用者決定：**Apple 的語音辨識不分說話者 → 錄音報告以 Gemini 為主；Apple 只當輔助，以及負責飲食**（一個人說吃了什麼，不需要分人）。

  | 用途 | 選擇順序 |
  |---|---|
  | 錄音報告 | ① Gemini（有金鑰；聽錄音、分說話者）② 沒金鑰 → Apple 輔助（裝置端逐字稿＋整理，沒有分說話者）③ 只有 Apple 語音 → 只有逐字稿 ④ iOS 17–25 沒金鑰 → 不能產生 |
  | 輔助 | Gemini 沒金鑰 / 連不上 / 額度用完時改用 Apple：iOS 27+ 有 Apple Intelligence → 私有雲 `PrivateCloudComputeLanguageModel`；iOS 26 → 裝置端 `SystemLanguageModel`；只有語音 → 只有逐字稿 |
  | 飲食 | ① Apple（語音＋模型都有時；免金鑰、不上傳）② Gemini（有金鑰）③ 只有 Apple 語音 → 只有逐字稿、沒有熱量 |

  - Apple API：語音 `SpeechAnalyzer` / `SpeechTranscriber`（iOS 26+），模型 `SystemLanguageModel`（iOS 26+）、`PrivateCloudComputeLanguageModel`（iOS 27+，有額度 `quotaUsage`）。
  - 繁中支援依 Apple 的語言清單即時判斷，不寫死。2026-11-22 在這台 Mac（M4，macOS 27.2）實測：`SpeechTranscriber` 支援 zh-TW（也有 zh-HK、zh-CN、yue-CN），模型已安裝、可離線；53 秒兩人對話 1.2 秒轉完，女聲大致正確、合成男聲同音錯字多，**沒有分說話者**。這台 Mac 的 Apple 語言模型與私有雲都回 `deviceNotEligible`，要用支援 Apple Intelligence 的真機測。**模擬器沒有語音模型**（`supportedLocales` 是空的）。
  - 之後設定頁可手動改選：Gemini 一站式、組合式（語音辨識＋報告模型混搭，照 Android）、Apple 裝置端。

## v1 範圍 / 里程碑
1. 手錶錄音 → `WatchConnectivity` 傳檔（分段、斷線補傳）→ 手機接成一筆錄音存檔。
2. Gemini 報告 + 詳情頁（報告 / 逐字稿 / 播放器）+ 手錶「報告書」清單與明細。
3. **Apple 引擎＋飲食＋即時預覽＋隱私模式**（2026-11-22 使用者決定，先做）：
   - Apple 引擎（`ios/Dylan/AppleAI.swift`）：`SpeechTranscriber` 逐字稿（帶時間）、`PrivateCloudComputeLanguageModel` / `SystemLanguageModel` 整理（長逐字稿分段）、飲食用 `@Generable` 直接產出結構化營養數字。
   - 飲食記錄：手錶「飲食」鍵錄音（id `food_` 開頭，照 Android）→ Apple 優先、Gemini 備援（提示詞照抄 Android `FoodLog.PROMPT`）→ 飲食分頁今日熱量圓環、三大營養素、每一餐。
   - 即時預覽：錄音收齊後先用 Apple 秒出逐字稿（標「預覽，未分說話者」），Gemini 報告出來再換掉。
   - 隱私模式（設定頁開關）：錄音只在手機上處理，一律用 Apple，不上傳 Gemini。
   - Apple 輔助：沒金鑰 / 連不上 / 額度用完時，用 Apple 的逐字稿與重點。
4. GPS 戳記、搜尋（`SearchIndex`）、組合式引擎（多家語音辨識 + 報告模型）。
5. AI 處理步驟視覺化 + 中斷處理。

每個里程碑完成就更新 `HANDOFF.md` 的 iOS 段落並 commit。

## Android → Apple 對照

### 錄音與傳輸
| Android | Apple 版做法 |
|---|---|
| 手錶錄音 `wear/.../RecordingService.kt`（AAC 48kbps，每 5 分鐘一段 `pending/<id>__<seq>__<樣本數>__more\|end.m4a`，手機確認才刪） | `AVAudioEngine` / `AVAudioRecorder` 錄 AAC m4a 分段；錄音中用 audio background mode / `WKExtendedRuntimeSession`。開始前檢查空間（≥100MB，剩 <30MB 自動停止並傳送） |
| Data Layer `MessageClient` / `ChannelClient`（`shared/.../WristAiProtocol.kt`） | `WatchConnectivity`：分段用 `transferFile`（排隊、斷線自動補傳，metadata 帶 id / seq / 樣本數 / more\|end）；小訊息 `sendMessage`（可連時）或 `transferUserInfo`。**保留同樣的路徑名當 message type** |
| 手機收錄音 `AudioReceiverService`、`SegmentAssembler` | `session(_:didReceive file:)`；依 seq 接起（重複 / 亂序都安全），用樣本數切掉 AAC 開頭補零；最後一段到齊狀態 RECORDING → RECEIVED |
| 手機錄音 `PhoneRecordingService`（PCM + 每 5 分鐘 AAC 分段） | `AVAudioSession` `.record` + background audio，同一套分段流程 |
| 匯入音檔 `AudioImporter`（`recordedAtOf` 推回錄音時間、`importedAt` 分開記） | `.fileImporter` / 分享延伸；錄音時間推算規則照抄（`ImportTimeTest`） |

### AI 處理
| Android | Apple 版做法 |
|---|---|
| Gemini `GeminiReportGenerator`（Files API 上傳 → generateContent 串流 → 刪檔；`gemini-3.8-flash`，429 / 503 改 `gemini-3.5-flash-lite`） | `URLSession` 打 REST；**提示詞照抄**（含 `TEXT_REPORT_PROMPT`、說話者命名規則、「摘要 3～5 句不可寫無」） |
| 長錄音 `LongMeeting`（每 30 分鐘一段，結果存 `gemini/chunk_<k>.txt` 可斷點續做；前段說話者名單帶給下一段；最後純文字合成；>1 小時逐字稿時間用 h:mm:ss；錄音中先分析已滿的段） | 同邏輯。429 等 40 秒重試 3 次 |
| WorkManager 10 分鐘上限 + 接續任務 | `BGProcessingTask` + background `URLSession`；**斷點檔照留**（`gemini/chunk_<k>.txt`、`stt_state.json`），App 被殺 / 背景時間到都能接著做 |
| 語音辨識 `ai/Stt.kt`：阿里百煉 Fun-ASR（getPolicy → OSS 臨時空間 → 非同步任務 diarization → 查詢 → 下載 JSON；區域 intl / cn）、科大訊飛錄音文件轉寫（signa = Base64(HmacSHA1(secret, MD5(appId+ts)))，roleType=1；getResult 先 POST、405 再 GET）、OpenAI `gpt-4o-transcribe-diarize`（diarized_json，25MB 上限） | 同 API、同簽章；HMAC / MD5 用 CryptoKit |
| 報告模型 `ai/Llm.kt`：OpenAI 相容 chat/completions（通義千問 qwen-plus、訊飛星火 4.0Ultra、Meta Llama、OpenAI gpt-5-mini）；Claude 走官方 Java SDK | 一個 OpenAI 相容 client 搞定前四家；Claude 改走 REST Messages API（`claude-opus-5-5`，串流）。模型名稱、端點都可在設定改（留空用預設） |
| 流程 `ai/Pipeline.kt`（依上限切段：Fun-ASR 110 分、OpenAI 85 分、訊飛 4.5 小時；逐字稿「[mm:ss] 說話者A：…」每段代號重編並提示合併；超過字數先分段筆記再合併；重新產生時同家同模型沿用逐字稿） | 照抄 |
| 報告解析 `ReportGenerator`（TITLE / SUMMARY 行、`fillEmptySummary`）、`Speakers`（認「說話者」與舊「與會者」） | 照抄（`SummaryFillTest`） |
| `Meeting.aiModel`（「語音辨識模型\|報告模型」）、`aiModelLabel` | 照抄（`AiLabelTest`）；裝置端顯示「Apple 裝置端」 |
| 處理步驟（11/21）：`Meeting.steps / step / stepDetail / stepStartedAt / processStartedAt / processEndedAt`；`plan()` 依模式排步驟、`stepTo()`、`finishSteps()`；「中斷處理」→ FAILED + error `CANCELED`，「重試」接著做 | 同資料欄位與步驟規劃；中斷 = 取消 Task / URLSession task，錯誤被中斷時不覆寫狀態 |

### 資料、定位、搜尋
| Android | Apple 版做法 |
|---|---|
| `MeetingStore`（每筆一資料夾：audio、meta.json、transcript.txt、report.md、geo.json、gemini/、stt_state.json；meta 含 `speakerNames`、`importedAt`、`nutrition`、steps…） | 同結構放 Application Support，`Codable` |
| GPS（11/19）：手錶開始錄音 → `PATH_GEO_REQ`（/wristai/geo/req）→ 手機定位（20 秒）存 `geo.json`（source = phone）回 `PATH_GEO_ACK`（"id\|1/0"）；手錶最多等 30 秒，失敗才用手錶 GPS（source = watch）並送 `PATH_GEO`；**手機定位不被手錶蓋掉** | `CLLocationManager`。手機錄音用手機定位。手錶錄音時手機多半在背景 → 需要「永遠允許」定位；設定頁加「錄音地點」狀態卡（已開啟 / 只在使用時 / 未開啟 + 按鈕，系統對話框由使用者按）。拒絕也能錄 |
| 地名 `MeetingStore.placeName`（去重、中文相連、外文用「, 」） | `CLGeocoder` 反查，組法照抄（`PlaceNameTest`）。地點顯示在詳情、分享、手錶明細「📍」、搜尋、AI 提示。**不顯示 GPS 來源** |
| `SearchIndex`（標題、日期多格式 2026/11/19・11/19・11月19日、來源、地點、改名後的說話者、報告、逐字稿；空白分隔多關鍵字 AND、不分大小寫；快取；`snippet` 標亮） | 照抄（`SearchIndexTest`）。手機錄音頁：停 250ms 才搜、「找到 N 筆」、依月份分組 |
| 飲食 `FoodLog`（id `food_`；`PROMPT` / `TEXT_PROMPT`；`NUTRITION: meal=…; kcal=…; …` 機讀行；每日目標預設 2000）、`PATH_FOOD_TODAY` 推手錶（"已吃/目標/餐數"，跨日歸零） | 同功能；需要雲端 AI（裝置端模式提示改用雲端） |

### 介面
| Android | Apple 版做法 |
|---|---|
| 手機底部四分頁 主頁 / 錄音 / 飲食 / 設定（Dashboard、HomeScreen、FoodScreen、SettingsScreen） | SwiftUI `TabView` |
| 第一次使用引導（介紹 → 權限 → 手錶 → 選 AI 模式） | 同流程；「裝置端」改成 Apple |
| 詳情：狀態卡、`ProcessSteps`、報告 / 逐字稿切換、`ReportFacts` 基本資料（時間起–訖、長度、地點、來源、AI、匯入時間、耗時）、說話者卡片可命名、播放器（±15 秒、1/1.5/2 倍速、點逐字稿跳轉並反白）、標題改名、分享（加錄音時間 / 地點 / 報告整理） | SwiftUI + `AVAudioPlayer`；分享用 `ShareLink` |
| 主題：深色 / 淺色 / 水墨 / 自動（`Brand`、`InkPalette`）；手錶跟手機水墨，其他時手錶深色（`/wristai/theme`） | 同配色數值；水墨要襯線字可內嵌字型（之後再說） |
| 錄音鍵一律紅點（`RecordDot`） | 照做 |
| 手錶兩頁：① 錄音頁（中央大鈕＝手錶錄音、左小鈕＝飲食＋熱量環；v1 不放「用手機錄音」）② 「報告書」 | `TabView(.page)`；錄音中是不能滑走的全螢幕錄音畫面 |
| 報告書（11/19）：`PATH_LIST_REQ` 查詢 JSON {q, kind(all/rec/food), month, offset, limit≤60, key} → `PATH_LIST` {key, total, offset, months:[{ym,count}], items}；一次 30 筆「顯示更多」；類別膠囊、📅 月份清單、🔍 搜尋（語音 / 手寫）；依 key 快取；分組「今天 26/11/19」，時間戳 yy/M/d HH:mm | 同協定；搜尋輸入用 watchOS 聽寫 / 隨手寫（`TextField`） |
| 明細：`PATH_DETAIL_REQ` → `PATH_DETAIL`（JSON，>80 KB 截斷，套用說話者名字），存本機離線可看；左右滑回清單 | 同協定；存 watch 的 Application Support |
| 品牌 Dylan / 大D；logo 由 `design/gen/logo_final.py` 產生 | App 名稱用語系切換；圖示從腳本輸出轉 Asset Catalog（**不要手改**，改腳本重跑） |
| API Key（設定頁使用者自己填） | 全部存 Keychain。**不要寫死任何 key、不要放進 repo** |

## 測試
- 單元測試把 `app/src/test/` 的案例改寫成 XCTest：ImportTime、PlaceName、SummaryFill、AiLabel、SearchIndex、AiProviders（依官方文件範例格式解析）、報告解析 / Speakers。
- 模擬器：Watch 錄音 → iPhone 收到 → 報告 → 摘要回 Watch → 報告書清單 / 明細 / 搜尋。GPS 用模擬器的自訂位置。
- 長錄音用短分段參數加速測試，測完改回（5 分鐘分段 / 30 分鐘分析段）。

## 規矩（使用者定的）
- 介面繁體中文，品牌「大D」（英文 Dylan）。不要用台積電、Alan Walker 相關商標。
- Apple ID 登入、簽章、同意條款、權限允許都由使用者自己按。
- 使用者希望你自主完成、少問；做完更新 `HANDOFF.md` 的 iOS 段落。
