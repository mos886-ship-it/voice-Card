# 大D：iOS 版程式總結，交接給 Android 版

更新：2026-11-23（Mac mini；含大D的主機配對）

iOS 版原本照 Android 版移植（見 `IOS_PORT.md`），之後在 iOS 上**多做了一批功能和介面改版**。這份文件把 iOS 版目前的程式整理一遍，並逐項寫出 Android 版要怎麼跟上，讓兩邊功能、用詞、畫面一致。

- 程式位置：`ios/`（iPhone `Dylan`、手錶 `DylanWatch`、通話錄音擴充 `DylanBroadcast`、共用 `Shared`）、自架主機服務 `local-ai/`。
- 開發與測試狀態見 `HANDOFF.md` 的「iOS 版」段落與 `REAL_DEVICE_TEST.md`。
- 用詞：使用者自架的 AI 電腦一律叫 **「大D的主機」**（不要叫「這台 Mac」、「自架 Mac」）。

---

## 1. Android 要跟上的清單（摘要）

| # | 功能 | iOS 狀態 | Android 要做的事 | 優先 |
|---|---|---|---|---|
| 1 | **大D的主機**（使用者自己的電腦跑 AI：分說話者、免費、不出設備） | ✅ 真機 iPad 經固定 IP 實測 | 新增一個 AI 引擎，打同一組 HTTP API（第 3 節）；有設定時優先，連不到改 Gemini | 高 |
| 1b | **配對大D的主機**：掃 QR 碼或輸入配對密碼，每台裝置自己的存取碼，可個別取消（不用再手動貼 43 字元存取碼） | ✅ 主機 curl 全測、模擬器 QR 連結配對；真機相機掃描未測 | 實作 `/v1/pair` 流程、`dylan://pair` 連結、相機掃 QR、裝置清單（第 3 節） | 高 |
| 2 | 主機模型切換（語音辨識 / 報告模型） | ✅ | 設定頁兩個選單，資料來自 `/v1/models`，選了打 `/v1/config` | 中 |
| 3 | **匯入文字檔**（會議紀錄、逐字稿 → 報告） | ✅ 模擬器 | 匯入接受 txt / md / rtf；跳過語音辨識，主機用 `X-Input: text`，Gemini 直接送文字 | 中 |
| 4 | 匯入音檔 / 影片、**從別的 App 分享進來** | ✅ 模擬器＋iPad | Android 已有 `AudioImporter`；補上 `ACTION_SEND` / `ACTION_VIEW` intent-filter（audio/*、video/*、text/plain）＋文字檔 | 中 |
| 5 | **錄通話 / 線上會議**（LINE、Zoom、Teams 雙方聲音，戴耳機也行） | 🟡 已裝到 iPad，尚未實際通話測試 | 用 `AudioPlaybackCaptureConfiguration`（Android 10+，MediaProjection）收其他 App 的聲音＋麥克風（第 5 節） | 中 |
| 6 | **商用 / 科技 兩種介面模式** | ✅ | 設定頁加「介面模式」；各畫面依模式顯示細節（第 6 節） | 高 |
| 7 | 錄音清單改版：依來源的圖示、一行摘要、只在處理中 / 失敗顯示狀態、依「今天 / 昨天 / 日期」分組、長按刪除 | ✅ | 照做（第 7 節） | 高 |
| 8 | **來源顯示裝置名字＋型號**：「iPad · Dylan 的 iPad（iPad Pro 11 吋 第 2 代）」 | ✅ | 存 `Meeting.device`；Android 可直接讀裝置名稱（第 8 節） | 中 |
| 9 | 大螢幕版面：寬螢幕兩欄、卡片格狀排列、內容欄不拉滿 | ✅ iPad 直橫向 | Fold5 展開、平板套同樣規則（第 9 節） | 中 |
| 10 | 字級跟系統文字大小走 | ✅ | Compose 用 `sp` 已會縮放；檢查固定寬度的標籤會不會被截斷 | 低 |
| 11 | 基本資料：兩種模式都顯示「AI」（用了哪個模型） | ✅ | `ReportFacts` 確認 AI 那行不要被藏 | 高 |
| 12 | **辨識語言二選一：國語 / 台語・國台語**（主頁錄音鍵上方，記住上次；錄音詳情頁可換語言重新產生，方便對照） | ✅ 模擬器＋主機實測 | 存 `Meeting.lang`（`zh`/`nan`），送主機加 `X-Lang`；Gemini 提示詞加台語說明（第 3b 節） | 高 |

---

## 2. iOS 程式結構（給 Android 對照）

| iOS 檔案 | 做什麼 | 對應 Android |
|---|---|---|
| `Dylan/MeetingStore.swift` | 錄音資料夾、分段接合、AI 路由（主機 → Gemini → Apple） | `MeetingStore`、`ProcessMeetingWorker`、`ai/Pipeline.kt` |
| `Dylan/Meeting.swift` | 資料模型；`sourceLabel`、`aiModelLabel` | `Meeting`（`MeetingStore.kt`） |
| `Dylan/LocalAI.swift` | 大D的主機：設定、`/v1/health`、`/v1/models`、`/v1/config`、送錄音 / 文字 | **新增**（建議 `ai/LocalHost.kt`） |
| `Dylan/GeminiJobs.swift` | 背景上傳與等待結果（Gemini、主機），App 暗了也會完成 | WorkManager 任務 |
| `Dylan/Gemini.swift` | Gemini REST、提示詞（照抄 Android）、`generateText`（文字輸入） | `GeminiReportGenerator` |
| `Dylan/PhoneRecorder.swift` | 手機 / iPad 錄音：16kHz AAC、每 5 分鐘一段、背景續錄、被打斷自動接 | `PhoneRecordingService` |
| `Shared/SegmentWriter.swift` | 手錶和手機共用的分段寫檔 | 手錶 `RecordingService` 的分段邏輯 |
| `Dylan/AudioImport.swift` | 匯入音檔 / 影片 / 文字（編碼偵測） | `AudioImporter` |
| `Dylan/CallInbox.swift` ＋ `DylanBroadcast/SampleHandler.swift` | 通話錄音：系統廣播只留聲音，兩軌對齊混成一筆 | **新增**（見第 5 節） |
| `Dylan/DeviceInfo.swift` | 裝置名字（使用者自訂）＋型號 | **新增**（`Build.MODEL`＋型號表 / `Settings.Global.DEVICE_NAME`） |
| `Dylan/UIMode.swift` | 商用 / 科技模式、`Fmt.human`（16 秒 / 2 分）、`Fmt.dayLabel`（今天 / 昨天） | **新增** |
| `Dylan/Components.swift` | `PageColumn`、`TwoColumns`、`CardGrid`（寬螢幕規則） | `ui/Components.kt` |
| `Dylan/DashboardView.swift`、`RecordsView.swift`、`DetailView.swift`、`FoodView.swift`、`SettingsView.swift`、`ReportViews.swift` | 四個分頁＋詳情 | `ui/DashboardScreen.kt`、`HomeScreen.kt`、`DetailScreen.kt`、`FoodScreen.kt`、`SettingsScreen.kt`、`ReportViews.kt` |
| `local-ai/server.py` | 大D的主機服務（Python，MLX / llama.cpp） | 兩邊共用，不用改 |

---

## 3. 大D的主機（自架 AI）— Android 要新增的引擎

主機服務在使用者自己的電腦（目前是 M4 Mac mini 16GB），安裝見 `local-ai/README.md`，**連線、自動啟動、路由器、測試與排錯見 `HOST_CONNECT.md`**。App 這邊只要會打 HTTP。

### 設定（設定頁「大D的主機」卡）
- **位址**：在家用 `http://<主機名>.local:8765` 或區網 IP；出門用固定 IP ＋路由器轉發的埠（實際 IP 不寫進 repo）。使用者沒打 `http://` 就自動補上、去掉結尾 `/`。
- **配對（取代手動貼存取碼）**：
  - **QR**：主機的 `/pair` 頁（只給區網 / 本機）顯示 `dylan://pair?url=<對外位址>&lan=<區網位址>&code=<一次性配對碼>`；App 註冊 `dylan://` 連結（Android：intent-filter `scheme="dylan" host="pair"`），也可在 App 內用相機掃（Android：CameraX ＋ ML Kit barcode）。先用 `url`，連不到再用 `lan`。
  - **密碼**：使用者填位址＋配對密碼。
  - 兩種都打 `POST /v1/pair`，拿到這台裝置自己的存取碼（43 字元）＋裝置 id，存 **Android Keystore / EncryptedSharedPreferences**。畫面顯示「已連上大D的主機」＋「取消配對」。
  - 已連上時：「配對密碼」設定 / 更換（`POST /v1/password`）；科技模式顯示「已配對的裝置」（`GET /v1/devices`）可個別取消（`POST /v1/devices/revoke`）。
  - 送給主機的裝置名：「名字（型號）」，例「Dylan 的 Fold5（Galaxy Z Fold5）」。
  - 科技模式保留「直接貼主存取碼」的進階方法。
- **測試連線**按鈕 → `GET /v1/health`，成功顯示「連線成功：FunASR…、Qwen3-8B…」，並載入模型選單（科技模式才顯示選單）。
- Android 要允許 http 明文連線：`network_security_config` 開 `cleartextTrafficPermitted`（固定 IP 不能用網域白名單，iOS 也是整個打開 ATS）。

### API（全部帶 `Authorization: Bearer <存取碼>`）
| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/v1/health` | `{ok, asr, llm, asr_id, llm_id, model, state: ready\|loading\|error, error, busy}` |
| GET | `/v1/models` | health 的內容＋`asr_options`、`llm_options`（`[{id, label}]`） |
| POST | `/v1/config` | `{"asr": id, "llm": id}` → 回 202，主機在背景換模型（第一次會下載）；App 每 3 秒查 `/v1/models` 直到 `state != loading`，期間顯示「主機正在載入模型（第一次會先下載，可能要幾分鐘）…」 |
| POST | `/v1/pair` | **不用存取碼**。body `{"password": …, "name": 裝置名}` 或 `{"code": QR 配對碼, "name": …}` → `{"token", "id"}`；錯回 401 `{"error"}`、太多次回 429 |
| GET | `/v1/devices` | `{"devices": [{id, name, created, last_seen, via: password\|qr, current}], "password_set": bool}` |
| POST | `/v1/devices/revoke` | `{"id": …}` → 取消那台裝置的存取碼 |
| POST | `/v1/password` | `{"password": …}`（至少 8 個字）→ 設定 / 更換配對密碼 |
| POST | `/v1/chat/completions` | OpenAI 相容的聊天（給 VPS / 其他程式；App 目前不用）。見 `HOST_CONNECT.md` 5b |
| POST | `/v1/process` | body 是錄音檔（m4a 等）；標頭 `X-Kind: meeting\|food`、`X-Recorded-At`（例 `2026/11/23 21:20`；標頭只能放 ASCII）、`X-Duration`（秒，整數）、`X-Lang: zh\|nan`（第 3b 節）。**文字輸入**：加 `X-Input: text`、body 是 UTF-8 文字、`Content-Type: text/plain; charset=utf-8` |

`/v1/process` 回應：主機先回 200，處理期間每 20 秒送一個空白（防逾時），最後送 JSON：`{"text": "<報告>\n\n===TRANSCRIPT===\n<逐字稿>", "model": "local-funasr|qwen3-8b"}` 或 `{"error": "大D的主機處理失敗：…"}`。`text` 的格式和 Gemini 輸出一樣（TITLE / SUMMARY 行＋Markdown 報告＋`===TRANSCRIPT===`＋`[mm:ss] 說話者A：…`），**直接用現有的 Gemini 解析**。逾時設長（iOS 用 30 分鐘請求、1 小時總時限）。

### 路由（照 iOS `MeetingStore.process`）
1. 飲食且有 Apple 模型 → Apple（Android：沒有對應，照原本 Android 的飲食路由）。
2. **有設定大D的主機 → 主機**。送出前先做 5 秒的 `/v1/health`（背景傳輸遇到連不到的主機不會失敗、只會一直等）。
3. 主機連不到 / 失敗 → 有 Gemini 金鑰就改 Gemini，並在記錄上寫「大D的主機連不到（原因），改用 Gemini」。
4. 隱私模式：主機可以用（錄音不出使用者的設備），Gemini 不行。

### 模型代號與顯示名稱
- 代號 `local-<辨識>|<報告>`；文字輸入時辨識部分是 `text`。
- 顯示：`funasr`→FunASR、`breeze`→Breeze-ASR-26、`text`→文字、`qwen3-8b`→Qwen3-8B、`sarc-taigi-12b`→SARC 台語 12B；認不得的照原樣。
- 完整名稱：**「大D的主機：FunASR ＋ Qwen3-8B」**（全形＋號）。Android 的 `aiModelLabel` 要認 `local-` 開頭。

### 主機端的保護（已做，Android 不用管，但要知道）
- 存取碼錯 10 次封該 IP 一小時（回 429）；主機重開服務即解除。
- 實測速度（M4 16GB）：35 秒錄音 → 辨識 4 秒＋報告 23 秒；文字 131 字 → 報告 42 秒（Qwen3-8B）。

---

## 3b. 辨識語言：國語 / 台語・國台語

台灣的國台語夾雜常常在**同一句裡**，所以不逐句切換模型，也不用兩個模型合併，而是每筆錄音選一次語言：

| 選項 | `lang` | 主機語音模型 | 說明 |
|---|---|---|---|
| 國語 | `zh`（預設；舊資料沒有這欄＝國語） | FunASR | 最快 |
| 台語・國台語 | `nan` | Breeze-ASR-26（MLX；說話者仍由 FunASR 分） | 有台語就選，國台語夾雜也是；較慢（50 秒的錄音約 1 分鐘辨識）；輸出是華語字 |

- 主頁錄音鍵上方一個二選一（記在 `speech_lang`）；新錄音、通話、匯入、手錶送來的錄音建立時，存當下的選擇到 `Meeting.lang`。
- 錄音詳情頁也有同一個選擇：已有報告時換語言 → 先確認 →「用新語言重新產生」（舊報告會被取代）。這是給使用者 A/B 對照用的。
- 主機：`POST /v1/process` 加 `X-Lang: zh|nan`（文字輸入不用帶）。沒帶就用設定頁選的語音模型（舊 App 相容）。`nan` 時主機會在報告提示詞加一段「逐字稿有台語、可能有同音或意思相近的字」。
- Gemini：`nan` 時提示詞加「逐字稿用台灣繁體華語字寫出意思，不用台羅、不寫同音錯字」。
- Apple / 裝置端語音辨識不會台語：`nan` 的錄音不出 Apple 預覽逐字稿。

## 4. 匯入（音檔、影片、文字）與分享進來

- **選檔**：音檔（m4a、mp3、wav、aiff…）、影片（mp4、mov，只取聲音）、文字（txt、md、rtf），可多選。
- **錄音時間**：用檔案建立時間；同一秒已有錄音就往後推一秒。標題先用檔名，AI 報告出來後換成 AI 標題（使用者改過的不覆蓋）。
- **音檔 / 影片**：先複製到暫存再轉成 m4a（雲端檔案讀到一半會失去權限）。
- **文字**：
  - 編碼依序試：UTF-8 → UTF-16（**只在有 BOM 時**；沒有 BOM 的任何偶數長度資料都能「解」成 UTF-16，會變亂碼，iOS 踩過）→ **Big5**（台灣舊 Windows 檔）→ GB18030。
  - 存成記錄資料夾的 `input.txt`，同時當逐字稿；沒有錄音檔、沒有長度（畫面不要顯示「0:00」）。
  - 報告：主機（`X-Input: text`）→ Gemini（純文字請求，請模型不要重抄逐字稿，App 自己接上原文）→ 都沒有就提示「文字檔要用大D的主機或 Gemini 產生報告」。Apple / 裝置端不處理文字。
- **從別的 App 分享進來**（語音備忘錄、檔案、LINE、Email 附件）：iOS 註冊成音檔 / 影片 / 文字的檢視器，收到檔就匯入、切到「錄音」分頁、顯示「已匯入「檔名」，正在產生報告」。Android：intent-filter `ACTION_SEND`、`ACTION_SEND_MULTIPLE`、`ACTION_VIEW`，mimeType `audio/*`、`video/*`、`text/plain`。
- 來源標示 `import`（匯入）；文字檔在清單用文件圖示。

---

## 5. 錄通話 / 線上會議

### iOS 做法
- 主頁「錄通話 / 線上會議」卡 → 系統的「開始廣播」面板（只列大D）→ 使用者打開麥克風、按開始 → 切去 LINE / Zoom / Teams 通話（戴 AirPods 也可以）→ 點紅色狀態列停止 → 回到大D，記錄出現在清單（來源「通話」）。
- 廣播擴充只收聲音、丟掉畫面：其他 App 的聲音（對方）和麥克風（自己）各寫一個 m4a 到 App Group；App 開啟時依兩軌第一筆的時間對齊、混成一個 `audio.m4a`。擴充記憶體只有約 50 MB，所以擴充裡只寫檔。
- 限制：**一般電話和 FaceTime 錄不到**（系統規定，任何 App 都不行）；有些 App 禁止錄製，那時只會錄到自己。iPhone 的一般電話可用系統內建的通話錄音（iOS 18+，存在備忘錄）再分享進大D。

### Android 對應
- 用 `MediaProjection` ＋ `AudioPlaybackCaptureConfiguration`（Android 10+）收其他 App 的聲音（`USAGE_MEDIA`、`USAGE_GAME`、`USAGE_UNKNOWN`；VoIP 通話常是 `USAGE_VOICE_COMMUNICATION`，**通常不能被收**，要實測 LINE / Zoom / Teams），同時另開一路麥克風，兩路混成一個檔。前台服務類型 `mediaProjection`。
- 一般電話：Android 沒有公開方法，維持現有的 `CallRecordingWatcher`（三星內建通話錄音自動匯入）。
- 來源值統一用 `call`，顯示「通話」。

---

## 6. 商用 / 科技 兩種介面模式

設定頁最上面「介面模式」：**商用**（預設）＝簡潔，**科技**＝細節。存在偏好設定 `ui_mode = business | tech`。

| 畫面 | 商用（簡潔） | 科技（細節） |
|---|---|---|
| 主頁 | 問候、日期；手錶膠囊只在有配對手錶時顯示；「錄通話」卡一行說明 | 多一行「AI：大D的主機：FunASR ＋ Qwen3-8B」（目前會用的引擎）；手錶膠囊一律顯示；錄通話卡多一行步驟與限制 |
| 錄音清單 | 標題＋時間、一行摘要 | 摘要兩行＋一行細節：來源 · 長度 · AI 模型 · 耗時 N 秒 |
| 詳情「基本資料」 | 時間、來源、**AI** | 再加 耗時、編號（錄音 id） |
| 詳情 | — | 「這次為什麼改用 Gemini / Apple」的說明 |
| 設定 | 介面模式、系統狀態（AI 引擎；手錶只在有配對時）、大D的主機（位址、存取碼、測試連線）、隱私、Gemini 金鑰、這台裝置、外觀 | 再加「AI 引擎」能力卡（系統版本、Apple 模型等）與主機的模型選單 |

系統狀態燈：有設定大D的主機就是綠燈（之前會誤顯示黃燈）。

---

## 7. 錄音清單改版（兩種模式共用）

- 每列：左邊**來源圖示**（手錶、手機、iPad、通話、匯入音檔、文字檔）、標題、右上時間（HH:mm）、下面一行摘要（報告的 SUMMARY）；還沒有報告時顯示「來源 · 長度」。
- 狀態膠囊**只在處理中 / 失敗**時顯示（不再每列都寫「報告完成」）；處理中多一條掃描進度條。
- 分組：**今天 / 昨天 / 11月20日 週四**（不是今年再加年份），每組顯示筆數。
- 長按 → 打開、刪除（刪除要確認：「刪除後無法復原」）。
- 長度顯示用「16 秒 / 2 分 / 1 小時 5 分」（主頁今日錄音時長也是）。
- 篩選膠囊：全部 / 手錶 / 手機 / 通話 / 匯入（有資料的才出現）。

---

## 8. 來源顯示裝置名字＋型號

- 格式：`<來源> · <名字>（<型號>）`，例：「iPad · Dylan 的 iPad（iPad Pro 11 吋 第 2 代）」、「通話 · Dylan 的 iPhone（iPhone 15 Pro Max）」。沒名字就「iPad · iPad Pro 11 吋 第 2 代」。手錶錄的只寫「手錶」。
- 在 iPad 上錄的來源寫 **iPad**（不是「手機」），清單圖示也換成 iPad。
- 名字存在每筆記錄的 `device` 欄位（錄 / 匯入當下的名字）。
- iOS 16 起 App 讀不到使用者在系統設定取的名字（要 Apple 核准的權限，個人免費帳號不能申請），所以第一次開 App 跳出「這台 iPad 叫什麼名字？」，之後在「設定 → 這台裝置」改。
- **Android 可以直接讀**：`Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME)`（讀不到再用 `Settings.Secure "bluetooth_name"`），型號用 `Build.MODEL` 對照表（例 SM-F946B → Galaxy Z Fold5）。仍保留讓使用者自訂。

---

## 9. 大螢幕版面規則（iPad → Fold5 展開 / 平板）

- 內容欄：窄螢幕左右留 20；寬螢幕（size class regular）左右留 32、最寬 1240 置中。
- **寬度 ≥ 980**（iPad 橫向、13 吋直向）分兩欄：主頁左「錄音按鈕＋今天」、右「處理中＋最新報告」；詳情左「標題、狀態、播放器」（寬 400）、右「報告 / 逐字稿」；設定左「狀態＋AI」、右其他。
- **寬度 ≥ 760**：錄音、飲食清單改成卡片格狀（每格最小 360 / 320）。
- iPad 字級放大 15%；手機維持設計稿大小。
- 底部分頁列在寬螢幕不拉滿（最寬 560 置中）。
- iPad 支援四個方向，iPhone 只直向。

---

## 10. 資料欄位（meta.json）

iOS 新增 / 確定的欄位，Android 建議同名，以後資料可以互通：

| 欄位 | 說明 |
|---|---|
| `source` | `watch` / `phone` / `call` / `import`（飲食用 id 前綴 `food_` 判斷） |
| `device` | 錄 / 匯入的裝置名字（手錶錄的沒有）；舊資料沒有這欄 |
| `lang` | 辨識語言 `zh`（國語）/ `nan`（台語・國台語）；舊資料沒有這欄＝國語 |
| `aiModel` | 例 `local-funasr|qwen3-8b`、`local-text|qwen3-8b`、`gemini-3.8-flash` |
| `engineNote` | 為什麼改用別的引擎（科技模式顯示） |
| `titleEdited` | 使用者改過標題，AI 不再覆蓋 |
| `processStartedAt` / `processEndedAt` | 算耗時 |
| 檔案 `input.txt` | 匯入的文字（有這個檔＝文字記錄，沒有錄音） |

---

## 11. 已知限制與還沒驗證的

- 通話錄音在 iPad 上**還沒實際通話測試**（哪些 App 的聲音收得到要實測）。
- iPhone 上「連大D的主機」還沒在 iPhone 真機成功過（當時存取碼沒按「儲存」）；iPad 已成功。
- 免費 Apple 開發者帳號：裝的 App 7 天後失效要重裝；Xcode 27 不能裝到 iOS 16 的手機（例 iPhone X）。
- 還沒做（iOS）：手錶報告書清單 / 明細、手錶今日熱量環、長錄音分段分析、GPS、搜尋報告內文、組合式雲端引擎、處理步驟視覺化、說話者改名。這些 Android 已有，iOS 之後照 Android 補。

## 12. 規矩（兩邊都要遵守）

- 介面繁體中文，品牌「大D」（英文 Dylan）。不要用台積電、Alan Walker 相關商標。
- 任何金鑰、存取碼、固定 IP 都不能寫進程式或 repo；金鑰存系統的安全儲存（iOS Keychain / Android Keystore）。
- 使用者自架的 AI 電腦一律叫「大D的主機」。
