# WristAI 交接文件

更新：2026-11-17

## 一句话

**預設（使用者手機 Galaxy Z Fold5）：** 手錶按「錄音」→ 停止後錄音自動傳到手機 → 轉成 m4a 存進三星「錄音機」（`Recordings/Voice Recorder/`）→ 通知「點此開啟錄音機」→ 使用者在錄音機按「轉錄」「摘要」，由 Galaxy AI 處理。

**自動報告（有填免費 Gemini API Key 時，優先）：** 存進錄音機之後，再把錄音（16kHz/32kbps m4a）上傳 Gemini Files API → Gemini 直接聽錄音，一次產出報告 + 分說話者逐字稿 → 推摘要回手錶 + 手機通知「會議報告完成」。上傳的檔案用完立刻刪除。模型 `gemini-3.8-flash`，額度用完（429）自動改用 `gemini-3.5-flash-lite`。不需要手機的語音辨識，也不限 Android 13。

**選用（只填 Claude API Key 時）：** 系統語音辨識 → Claude 整理報告 → 摘要推回手錶。

### 為什麼不直接呼叫 Galaxy AI（2026-11-17 調查）
- 三星沒有開放 Galaxy AI 給第三方 App 呼叫的公開 API。
- Google ML Kit GenAI（Gemini Nano，免 Key、離線）只支援 S25 / S26 / Z Fold7 / Flip7 之後的機型，**Z Fold5 不在名單**。
- 所以在 Fold5 上用 Galaxy AI 的唯一方法是交給三星自己的「錄音機」App（Transcript Assist，支援繁中、分說話者、摘要需登入三星帳號）。每場會議要在錄音機手動按兩下。
- 若之後換到支援機型，可改用 ML Kit GenAI Prompt API（`com.google.mlkit:genai-prompt`）做全自動離線報告。

## 目前狀態

| 項目 | 狀態 |
|---|---|
| `gradlew :app:assembleDebug :wear:assembleDebug` | ✅ 編譯通過（2026-11-17） |
| 模擬器：轉 m4a 存進 `Recordings/Voice Recorder/` + 通知 | ✅ 12 秒測試檔 → 101KB m4a，系統讀得到長度 12.2 秒 |
| **Fold5 真機：匯入 → m4a 存進錄音機 → 錄音機列出 → Galaxy AI 轉錄** | ✅ 2026-11-17 實測，繁中轉錄正確、自動標「語者 1」（摘要需三星帳號，未測） |
| 單元測試 `gradlew :app:testDebugUnitTest` | ✅ 8/8（WAV 檔頭、分段數、報告解析、空 Key 檢查、三星錄音標記 ×2） |
| 模擬器：手機 App 匯入音檔 → 重採樣 → 系統語音辨識（線上） | ✅ 12 秒中文測試音檔辨識成功，走到報告步驟（缺 API Key 停下，符合預期） |
| 模擬器：手錶錄音 → 存檔 → 找不到手機時保留並可重送 | ✅ |
| 手錶 → 手機實際傳輸（Data Layer） | ❌ 模擬器未配對（需 Wear OS App + Google 帳號），要真機驗證 |
| **真機：手錶錄音 → Data Layer 傳到手機 → 存進錄音機 → 手錶顯示結果** | ✅ 2026-11-18，Galaxy Watch Ultra（SM-L705F, API 36）+ Fold5，29 秒錄音全自動跑通 |
| 轉檔速度 | ✅ 29 秒錄音 1.1 秒（修正前 13.7 秒），一小時約 2 分多 |
| Gemini 報告：上傳端點、錯誤處理 | ✅ Fold5 用假 Key 實測，Google 回「API key not valid」，App 顯示「Gemini API Key 無效，請到設定重新貼上」 |
| Gemini 報告：用真的 Key 產生報告 | ✅ 2026-11-18 13:38 Fold5 實測：上傳 6 秒 + gemini-3.8-flash 串流 8.5 秒，全程約 17 秒產生完整報告（標題、摘要、重點、逐字稿）。503 時會自動換 `gemini-3.5-flash-lite` |
| 手錶錄音 → 手機 Gemini 報告 → 手錶摘要卡（完整流程） | ✅ 2026-11-18 13:44 真機實測：30 秒錄音，手機收到後約 21 秒出報告，手錶摘要卡已由使用者確認 |
| 分說話者記錄 | ✅ 2026-11-18：Prompt 加強說話者辨識規則 + 報告新增「與會者」表格；逐字稿頁有說話者面板（發言占比、點名字改名，名字存 meta.json `speakerNames`，報告／分享／手錶摘要都會換成名字）。Fold5 實測：單人錄音正確判為 1 人；2 分 36 秒的雙人談話節目正確分出 A（主持）／B（來賓），換人時間點正確，上傳＋分析約 23 秒 |
| 商業化 UI（手機 + 手錶） | ✅ 2026-11-18 模擬器截圖驗證：首頁、詳情（報告 / 逐字稿）、設定、啟動畫面、App 圖示、手錶待機 / 錄音中 / 失敗 / 摘要卡 |
| Claude API 實際呼叫 | ❌ 尚未用真的 API Key 跑過 |
| Release（R8 混淆）版本 | ❌ 沒測，Jackson/anthropic-java 可能需要 keep 規則 |
| 長會議（4～6 小時）：手錶分段 AAC 邊錄邊傳 + Gemini 分段分析 | ✅ 2026-11-18 真機實測（用 20 秒分段 / 60 秒分析段加速測試）：4 分 29 秒錄成 14 段，中途手機收不到時分段留在手錶、修好後自動補傳且順序正確；錄音中已先分析 4/5 段，停止後約 1 分鐘出完整報告；分段直接接成 m4a 存進錄音機（1.6MB，長度正確）。⚠️ 真實 5 分鐘分段 / 30 分鐘分析段、以及數小時的耐久（電量、熄屏）尚未實測 |
| 手機錄音（`PhoneRecordingService`） | ✅ 2026-11-18 Fold5：3 分 07 秒手機錄音，停止後約 1 分鐘出報告 |
| 手錶遙控手機錄音（手機鎖屏） | ✅ 2026-11-18 Fold5 鎖屏 + 未開「顯示在其他應用程式上層」：手錶按「用手機錄音」→ 全螢幕通知直接開啟透明畫面 → 前台啟動麥克風服務（不用解鎖、不用點）→ 手錶顯示「手機錄音中」→ 手錶按停止，34 秒錄音有正常音量（RMS 3123，無削波） |
| 自動整理通話錄音（`CallRecordingWatcher`） | ✅ 2026-11-18 模擬器：模擬電話 App 在 Recordings/Call 新增錄音 → 約 8 秒內自動匯入成「通話」會議；App 在背景連續兩通都各匯入一次；App 被強制停止期間的錄音，下次開 App 會補匯入。Fold5 尚未開啟此功能（需使用者在設定開啟並允許讀取音訊）|
| 單元測試 | ✅ 14/14 |

## 架構

```
手錶 wear/                         手機 app/
─────────────────                  ─────────────────────────────────
MainActivity  按鈕 錄音/停止
RecordingService 前台服務
  AudioRecord 16kHz mono PCM → WAV
  └─ PhoneLink ── ChannelClient ──→ AudioReceiverService  存成 meetings/<id>/audio.wav
                  /wristai/audio/<id>   └─ ProcessMeetingWorker (WorkManager)
                                             0. VoiceRecorderExporter  WAV→m4a 存進三星錄音機 + ReadyNotification
                                                （沒填 Claude Key 就到此為止，狀態 EXPORTED）
                                             1. SpeechTranscriber  每 120 秒一段辨識 → transcript.txt
                                             2. ReportGenerator    Claude → report.md
PhoneMessageListenerService ←─ MessageClient ─┘ 3. WatchNotifier    進度 / 摘要
  /wristai/status   進度文字
  /wristai/summary  標題+摘要 JSON → 手錶震動並顯示
```

`shared/`：協議常數（`WristAiProtocol`）、音訊格式（`AudioSpec`）、WAV 讀寫（`WavFile`）、AAC 邊錄邊壓 / 解碼 / 不重編碼串接（`AacCodec`）、手錶摘要模型（`MeetingSummary`）。

### 長會議（4～6 小時）設計（2026-11-18）

```
手錶 RecordingService                         手機
AudioRecord → AacWriter（48kbps，約 22MB/小時）
每 5 分鐘一段 pending/<id>__<seq>__<樣本數>__more|end.m4a
  └─ PhoneLink.sendPending ── /wristai/seg/<id>/<seq>/<樣本數>/<more|end> ──→ AudioReceiverService
       手機存好後回傳 1 byte 確認，手錶收到才刪檔                             └─ SegmentAssembler
       失敗：留在手錶，20 秒後重試；手機重新連上也會自動補傳                       解碼、依序接進 audio.wav（重複/亂序都安全）
                                                                                 狀態 RECORDING → 最後一段到齊改 RECEIVED
                                                                          ProcessMeetingWorker
                                                                            錄音中：LongMeeting 先分析已滿 30 分鐘的段
                                                                            錄完：分段直接接成 m4a 存錄音機（不重編碼）
                                                                                  → 剩下的段 → Gemini 合成報告
```

- **手錶空間**：開始錄音前至少要 100MB，錄音中剩不到 30MB 會自動停止並傳送（已錄的都保留）。正常情況手錶上只有正在錄的那一段；手機整場不在身邊時 6 小時約 130MB。
- **為什麼要分段給 Gemini**：6 小時的逐字稿約 7 萬字，超過 Gemini 一次能輸出的長度。每 30 分鐘一段（`LongMeeting`，結果存 `gemini/chunk_<k>.txt`，可斷點續做），前段認出的說話者名單（代號、名字、聲音特徵）帶給下一段讓代號一致，最後用純文字把各段筆記合成報告。超過 1 小時的會議逐字稿時間用 h:mm:ss。
- **WorkManager 10 分鐘上限**：每開始新的一段前檢查，這次任務跑超過 5 分鐘就排接續任務。免費額度每分鐘次數用完（429）會等 40 秒再試 3 次。
- **AAC 每段開頭的補零**：路徑裡帶了每段的 PCM 樣本數，手機解碼後把多出來的開頭靜音切掉，接起來不會累積時間誤差。
- **測試用設定（沒有 UI）**：手錶 `shared_prefs/wristai_watch.xml` 的 `segment_seconds`、手機 `shared_prefs/wristai_settings.xml` 的 `gemini_chunk_sec`，可改短加速測試。**測完要刪掉**。

## 關鍵決定與原因

- **手機和手錶的 applicationId 都是 `com.example.wristai`。** Wear Data Layer 只在「同 applicationId、同簽名」的 App 之間傳資料。兩個 APK 必須用同一個簽名金鑰（debug 版預設就是同一把）。
- **錄音檔用 ChannelClient 傳**，不用 MessageClient（MessageClient 上限約 100KB，錄音 1 分鐘就約 1.9MB）。
- **語音辨識用手機系統的 `SpeechRecognizer` 讀檔案**（`EXTRA_AUDIO_SOURCE`），不用 API Key，但**需要 Android 13+**。先試離線辨識，失敗再用線上辨識。要換成 Whisper / 雲端 STT，只需換掉 `SpeechTranscriber.transcribeChunk()`。
- **Claude API 不接受音訊輸入**，所以必須先轉文字再給 Claude。
- **WorkManager 單次任務上限 10 分鐘**，所以辨識每跑約 7 分鐘就存進度並排接續任務。長會議可以做完，App 被殺也能從斷點繼續。
- **Claude 模型 `claude-opus-5-5`**，用官方 Java SDK（`com.anthropic:anthropic-java`），用串流（長逐字稿不會逾時）、自動重試 4 次，並開啟 server-side fallback（被安全分類器拒絕時自動改用備用模型）。
- **API Key 由使用者在手機 App 的設定欄輸入**，存在本機 SharedPreferences，不寫在程式碼裡。
- **手機 App 需要麥克風權限**：雖然只是辨識檔案，系統語音辨識服務仍要求呼叫方有 RECORD_AUDIO，否則回 `ERROR_INSUFFICIENT_PERMISSIONS`（模擬器實測發現並已修正）。
- **三星錄音機只列出帶「錄音標記」的檔案**（Fold5 實機查出）：錄音機只認 m4a 的 `moov/udta/smta` 裡有 `saut` box 的檔案（它自己錄的、Galaxy Watch 同步來的都有）。沒有這個標記，就算放在 `Recordings/Voice Recorder/` 也不會出現；檔名、擁有者 App、取樣率都不是原因（對照實驗排除）。`SamsungRecordingTag` 在轉檔後補上這個標記，只改檔尾的 moov，不動音訊資料。三星錄音機改版後若又不列出，先比對新版錄音檔的 udta。
- **匯出格式 48kHz / 128kbps AAC**：跟錄音機自己錄的一致，Galaxy AI 轉錄是用這個格式驗證的。
- **免費 API 選 Gemini**（使用者要求免費）：Google AI Studio 免費方案、不用信用卡，而且能直接收音訊（Claude API 不收音訊）。代價：免費方案的內容會被 Google 用於改進產品（App 設定畫面有寫明），每日次數有限。用 REST `generateContent` + Files API，只靠 Android 內建 HttpURLConnection/org.json，不加依賴。Google 新推 Interactions API，但官方說 generateContent 仍完整支援。
- **轉檔迴圈不能每輪等待輸出**：送資料時用 timeout 0 把輸出全部取走，只有送完後才等。原本每輪等 10ms，一小時會議會超過 WorkManager 10 分鐘上限。
- **手錶連電腦**：Galaxy Watch 只能 Wi‑Fi 無線偵錯。配對埠以 `adb mdns services` 裡 `_adb-tls-pairing` 顯示的為準（使用者看到的可能是連線埠），配對後再 `adb connect <IP>:<連線埠>`。
- **電腦連不到手機 / 手錶的 Wi‑Fi（2026-11-18）**：有些 Wi‑Fi（公司、訪客網路）會阻擋裝置互連，ping 回「目的地主機無法連線」、`adb mdns services` 也是空的。解法：開 Fold5 行動熱點，電腦和手錶都連上去；手錶 IP 用 `adb -s <手機> shell ip neigh` 查（swlan0 介面），無線偵錯的埠號改變時可以掃 30000–49999 找開著的埠。手錶螢幕一關，無線偵錯常會自己斷：請使用者停在「無線偵錯」畫面再連。手錶 Wear OS 5 不支援藍牙除錯。
- **Fold5 是雙螢幕**：`adb exec-out screencap` 要加 `-d <display id>`（`dumpsys SurfaceFlinger --display-id` 查）。
- **手機重新連上時手錶自動補傳**：`PhoneMessageListenerService.onCapabilityChanged`，不用手動按重送。
- **模擬器的離線辨識不支援 zh-TW**，會自動退回線上辨識；線上辨識輸出可能是簡體、有同音錯字，Claude 會依上下文修正並一律用繁體輸出。

## UI 設計（2026-11-18 商業化改版）

品牌風格：深空藍底 + 青→藍→紫漸層（`Brand` 物件，手機 `app/.../ui/theme/Color.kt`、手錶 `wear/.../ui/theme/Brand.kt` 數值一致），固定深色主題，不跟系統動態取色。

**手機（3 層）**
- 首頁 `ui/HomeScreen.kt`：品牌頂欄 + 設定鈕 → 「系統狀態」卡（AI 報告引擎、手錶連線，未設定時有「啟用免費 AI 自動報告」主按鈕）→ 統計（會議數 / 總時長 / 報告數）→ 會議列表（狀態標籤，處理中會有掃描進度條）。
- 會議詳情 `ui/DetailScreen.kt`：標題 + 時間 + 狀態 → 狀態卡（分析中 / 失敗可重試 / 已存錄音機引導）→ 主要操作（產生 / 重新產生報告、開啟錄音機）→ 「報告 / 逐字稿」分段切換。報告用 `ui/ReportViews.kt` 排版（章節色條、條列、待辦表格改成卡片），逐字稿依說話者分色。分享、刪除（有確認對話框）在頂欄。
- 設定 `ui/SettingsScreen.kt`：AI 報告引擎（Gemini 免費、Claude 選用）、Galaxy AI 同步開關、隱私說明。
- 共用元件 `ui/Components.kt`：GlassCard、GradientButton、GhostButton、StatusChip、ScanningBar、BrandMark…

**2026-11-18 商業化方向**：目標個人專業人士、台灣 / 繁中優先、AI 以裝置端（Galaxy AI）為主，雲端 Gemini 為選用；收費方式之後再談，先把 UI 做好。據此新增：
- 第一次使用引導 `ui/OnboardingScreen.kt`：介紹 → 權限（麥克風、通知）→ 手錶連線 → 選擇「裝置端 Galaxy AI（推薦）」或「雲端自動報告」（選雲端會直接帶去設定填金鑰）。完成旗標 `Settings.onboarded`。
- 會議詳情加播放器 `ui/AudioPlayer.kt`（播放 / 暫停、拖拉、±15 秒、1 / 1.5 / 2 倍速），逐字稿點一句就跳到該時間播放，播放中的那句會反白；標題點一下可改名。
- 首頁：搜尋（標題 + 報告 / 逐字稿內容）、來源篩選（手錶 / 手機 / 通話 / 匯入，`Meeting.origin()`），列表標示來源。
- 設定：「裝置端 AI（推薦）」排第一，「雲端自動報告（選用）」第二；首頁 Galaxy AI 視為已就緒，不再顯示「啟用免費 AI」按鈕（非三星手機才會）。
- 模擬器截圖確認：引導第 1、4 頁、首頁搜尋與篩選、詳情播放器、逐字稿點擊播放與反白。

**2026-11-18 用詞改為多用途**：介面上的「會議」都改成「錄音」（例如最近的錄音、錄音詳情、預設標題「錄音 11/18 15:00」），品牌副標改為 AI VOICE INTELLIGENCE，手錶標題改「一鍵錄音」、摘要卡改「最新摘要」。AI 報告格式也改成通用：提示詞先判斷錄音類型（會議、課堂、訪談、通話、演講、個人備忘），章節改為 摘要 / 說話者 / 重點 / 結論・決定 / 待辦事項 / 待確認；`Speakers` 同時認新版「說話者」與舊版「與會者」章節。程式內部的類別名稱（Meeting、ProcessMeetingWorker…）沒改。

**2026-11-18 分頁**：使用者覺得首頁太雜，改成底部三個分頁（`ui/BottomNav.kt`，路由 `tab:records / tab:food / tab:settings`，從詳情返回回到原分頁）：
- 錄音（`HomeScreen`）：頂欄品牌 + 手錶連線小標籤（點了到設定）、手機錄音卡、搜尋、來源篩選、列表；不含飲食記錄。AI 沒設定時才顯示提醒卡。
- 飲食（`ui/FoodScreen.kt`）：「記錄這一餐」大按鈕、今日熱量圓環、依日期分組的歷史（每天合計）。`PhoneRecordCard(foodMode = true)`。
- 設定：最上面是系統狀態卡（AI 引擎、手錶），沒有返回鍵。
- Fold5 實測三個分頁截圖正常；使用者已從手錶實際記錄兩餐（蛋糕＋奶茶 450 大卡、西瓜＋啤酒 270 大卡）。

**2026-11-18 四個分頁 + 主題**：
- 底部分頁改為 主頁 / 錄音 / 飲食 / 設定（`Tab`，預設 `tab:home`）。主頁（`ui/DashboardScreen.kt`）只放總覽：問候 + 日期、手錶連線標籤、「開始錄音 / 記錄飲食」快捷鍵（錄音中變成計時卡）、今天統計、處理中、最新報告摘要。錄音分頁只放錄音按鈕、搜尋、篩選、列表。
- 設定「外觀」可選 深色 / 淺色 / 水墨 / 自動（跟隨系統）（`Settings.themeMode`，預設深色）。**水墨**（`InkPalette`）：宣紙底、墨黑字、朱紅／黛藍／赭石點綴、按鈕是墨色漸層，和 logo 同一套；標題設成 serif，但三星系統沒有中文襯線字型，實際會顯示一般字體（要真的宋體需內嵌字型，約 5–10MB）。`Brand` 的顏色改成依 `Brand.isDark`（Compose 狀態）回傳深 / 淺色盤，所以全部畫面自動跟著換；狀態列圖示也跟著換（`MainActivity.applySystemBars`）。漸層按鈕兩種主題共用。啟動畫面與手錶維持深色。Fold5 已截圖確認淺色主頁與設定。

**2026-11-18 品牌改名**：英文 **Dylan**、中文 **大D**。App 名稱用語系切換（`values/strings.xml` = Dylan；`values-zh-rTW / zh-rHK / zh` = 大D，手機與手錶都是），介面文字的「WristAI」都改成「大D」，手錶頂端改「DYLAN」，存進錄音機的檔名前綴改成 `Dylan_`（舊檔仍是 `WristAI_`）。程式套件名、類別名、資料夾名都沒改。Logo 定稿（2026-11-18）：**王羲之草書風格 dw（更奔放版）直書——d 在上、w 在下——宣紙底黑墨，左下一枚直立長方白文印（紅底白字，「大」在上、「D」在下）**（dw = Dylan Wang，印 = 大D）。全部由 `design/gen/logo_final.py` 產生（預覽 `design/logo_final.html` ＋手機／手錶的 `ic_launcher_background / foreground / monochrome.xml` 與 App 內 `ic_logo.xml`），要改就改腳本重跑，不要手改 XML。注意：印裡的「大」用等粗圓頭線條、頂端那筆必須從正中央垂直上來，用毛筆斜筆尖畫會變成像「犬」。過程中的選項在 `design/logo_*.html`。

**App 圖示 / 啟動畫面**：兩個 App 都用 adaptive icon（`res/drawable/ic_launcher_background.xml` 漸層 + `ic_launcher_foreground.xml` 五道聲波），`themes.xml` 改為深色底 + Android 12 splash，啟動不再閃白屏；手機狀態列用淺色圖示（`SystemBarStyle.dark`）。

**手錶主題（2026-11-18）**：手錶跟著手機的主題——手機選「水墨」→ 手錶水墨（宣紙底、墨黑錄音鍵、錄音中朱紅、松綠／朱紅膠囊鍵）；其他（深色／淺色／自動）→ 手錶深色（OLED 省電）。手機在換主題、App 啟動、手錶打開時（CMD_QUERY）用 `/wristai/theme` 訊息推 "ink"/"dark"（`WatchNotifier.broadcastTheme`），手錶 `Brand.apply()` 存在 `wristai_watch` 偏好。頂端改成 dw 印章小 logo ＋「大D」（`wear/res/drawable/ic_logo.xml`，由 `design/gen/logo_final.py` 一起產生）。真機已截圖確認兩種主題與來回切換。

**手錶**（`wear/.../MainActivity.kt`，單一畫面，內容都落在圓形錶面內）：品牌字 → 標題 / 錄音計時 → 中央圓形鍵（待機漸層麥克風、錄音中紅色停止 + 擴散光環、傳送中旋轉弧）→ 一行狀態說明 → 往下滑：重送按鈕、最新會議摘要卡。手錶文字一律要短，長訊息在圓形邊緣會被切掉。

## 手機錄音 / 手錶遙控 / 通話錄音（2026-11-18）

- **手機錄音** `PhoneRecordingService`（前台服務，microphone 類型）：PCM 寫進 `audio.wav`，同時每 5 分鐘寫一段 AAC 到 `segments/`，跟手錶長會議同一套流程（錄音中先分析、錄完分段直接接成 m4a 存錄音機）。用 `AudioSource.MIC`（有自動增益，手機放桌上收整個會議室）。首頁「手機錄音」卡（`ui/PhoneRecordCard.kt`）、通知列有「停止錄音」。
- **手錶遙控**：手錶按「用手機錄音」→ MessageClient `/wristai/phone_rec/cmd`（start / stop / query）→ 手機 `AudioReceiverService.onMessageReceived`；手機把狀態 `/wristai/phone_rec/state`（開始時間）推回手錶，手錶主按鈕變成「停止手機錄音」並顯示計時。
- **為什麼要透明跳板畫面** `RemoteRecordActivity`：Android 14 起 App 在背景不能開麥克風。使用者在設定開「顯示在其他應用程式上層」後，App 可從背景開 Activity，由它在前台啟動錄音再關掉（鎖屏也行，螢幕會亮一下）。沒開時用**全螢幕通知**（`setFullScreenIntent`，像鬧鐘 / 來電）：手機鎖屏或熄屏時系統直接開啟這個畫面，不用解鎖、不用點（Fold5 實測可用）；手機正在使用中時會變成浮動通知，點一下開始。⚠️ 用 adb 拉二進位檔要用 `adb exec-out`，`adb shell cat` 會把換行改掉、資料變成雜訊（量音量時踩過）。
- **通話錄音**：Android 10 起第三方 App 錄不到通話聲音；LINE / WhatsApp / Telegram / WeChat 等網路通話中，其他 App 的麥克風會被靜音，連三星電話也錄不了。能做的是：
  - 一般電話 → 三星「電話」→ 設定 →「錄製通話」自動錄音（存 `Recordings/Call/`），`CallRecordingWatcher` 用 WorkManager content URI 觸發監看媒體庫，新錄音自動匯入（id `call_…`、`source = "call"`），Gemini 提示詞會說明是兩人通話；通話錄音不會再另存一份到錄音機。開機由 `BootReceiver` 重新排程。
  - 網路通話 → 開擴音，用手錶錄（另一台裝置，不受限制）。設定頁有寫明。

## 飲食記錄（2026-11-18）

- 手錶「飲食記錄」按鈕 / 手機錄音卡「記錄飲食」→ 錄音 id 以 `food_` 開頭（`FoodLog.ID_PREFIX`），其餘傳輸流程照舊。
- `ProcessMeetingWorker.processFood`：不存錄音機，直接用 `FoodLog.PROMPT` 送 Gemini。模型第 3 行輸出機讀行 `NUTRITION: meal=…; kcal=…; protein=…; carbs=…; fat=…`，解析後存 `Meeting.nutrition`，報告裡移除這行。沒有 Gemini 金鑰時標為失敗並提示啟用雲端 AI（Galaxy AI 做不到結構化熱量）。
- 首頁「今日飲食」卡（`ui/FoodCard.kt`）：熱量 / 每日目標圓環、三大營養素、今天每一餐；有用過飲食記錄才顯示，點卡片篩選「飲食」。詳情頁上方顯示這餐的熱量與營養素。每日目標在設定「飲食記錄」（預設 2000 大卡）。
- ✅ 2026-11-18 Fold5 實測：用 Windows 內建 zh-TW 語音（Microsoft Hanhan）合成「中午吃了一碗紅燒牛肉麵，加了一份燙青菜，還有一杯半糖的珍珠奶茶，大杯的」→ 980 大卡、蛋白質 28g、碳水 140g、脂肪 34g，首頁「今日飲食」卡正確加總。同時首次在真機看到 gemini-3.8-flash 503 → 自動改用 gemini-3.5-flash-lite 成功。
- ⚠️ flash-lite 把使用者講的「大杯半糖」改成「中杯微糖」，已在提示詞加上「明確說出的份量、杯型、甜度一定照用」，新版提示詞尚未重跑驗證。

## 不用手錶也能用：匯入音檔

手機 App 首頁的「匯入音檔」可選任何音訊檔（m4a / mp3 / wav…），或從錄音機 App 用「分享 → WristAI」。
`AudioImporter` 會用 MediaCodec 解碼、混成單聲道、重採樣到 16kHz，之後走同一條辨識 + 報告流程。

## 一鍵安裝

```powershell
.\install.ps1            # 編譯並安裝到所有已連線設備（自動分辨手機 / 手錶）
.\install.ps1 -NoBuild   # 直接安裝
.\install.ps1 -Logs      # 安裝後持續顯示兩邊的 log
```

## 怎麼測

1. 手機（Android 13+）安裝 `app/build/outputs/apk/debug/app-debug.apk`，手錶安裝 `wear/build/outputs/apk/debug/wear-debug.apk`。
2. 手機 App 輸入 Claude API Key、辨識語言（預設 `zh-TW`）並按儲存。
3. 手錶 App 按「錄音」，允許麥克風權限，說話一段時間後按「停止」。
4. 手錶應顯示「已傳送」→ 進度訊息 → 最後收到摘要並震動；手機 App 清單會出現該錄音，點進去看「報告」和「逐字稿」。
5. 看 log：`adb logcat -s RecordingService PhoneLink AudioReceiver ProcessMeetingWorker SpeechTranscriber`

## 已知風險 / 待辦

- [ ] 真機跑一次完整流程（最優先）。
- [ ] 各廠牌手機的語音辨識服務對 `EXTRA_AUDIO_SOURCE` + 分段模式的支援程度不一；若辨識結果是空的或報錯，考慮換 Whisper 等雲端 STT。
- [ ] 離線辨識需要先在系統設定下載對應語言包，否則會退回線上辨識。
- [ ] 長會議用真實參數（5 分鐘分段 / 30 分鐘分析段）實測 1 小時以上，觀察手錶電量與熄屏下錄音是否穩定。
- [ ] 舊版手錶 App 傳來的整段 WAV 仍可接收（向下相容），但手錶端已全面改為分段。
- [ ] Release 版加 R8 keep 規則並測試。
- [x] 手錶 UI 已改用 Wear Compose Material3 1.6.2（2026-11-18）：AppScaffold＋彎曲時間（TimeText，顏色跟主題）、ScreenScaffold＋TransformingLazyColumn（表圈可捲動、邊緣縮放），中央大圓錄音鈕 RecordCircle、下方 飲食記錄／用手機錄音／重送 按鈕、最新摘要 TitleCard。錄音中改成不捲動的全螢幕錄音畫面（RecordingScreen：錄音中紅點、大計時、停止鈕、同步提示），避免內容被捲到時間底下。已在 Galaxy Watch Ultra 實機驗證（水墨）。

## 之前的問題（給接手的人）

Locmodel 先前產生的 `shared` 程式碼無法編譯（用了不存在的 API `Wearable.getClient()`、不存在的套件 `wear-phone-connect`、錯誤的 `play-services-wear:19.0.0`），app / wear 也沒有接上 shared；它還聲稱建立了 `交接` 資料夾，但實際上並不存在。這些已全部重寫，舊檔備份在 session 暫存區，不需要保留。

- **匯入時間與錄音時間分開記（2026-11-18）**：Meeting 新增 `importedAt`（null = 不是匯入的）；`createdAt` 一律是「錄音時間」。匯入時 `AudioImporter.recordedAtOf` 依序從 檔名日期（`timeFromName`，支援 20241118_195656、241118_195656、2025-01-02 14.30）→ MediaStore DATE_TAKEN → 音檔 metadata 建立日期 → 檔案修改時間減長度 → 呼叫端 fallbackEnd 減長度 推回錄音時間，都沒有才用匯入時間。id 改用匯入時間（不會撞名）。詳情頁顯示「錄音 …」與「匯入 …」兩行；匯入 Toast 會說出推回的錄音時間。通話錄音也走同一套（fallbackEnd = DATE_ADDED）。舊的匯入紀錄沒有原檔資訊，維持原樣。單元測試 `ImportTimeTest`。

- **手錶改成左右滑三頁（2026-11-18）**：HorizontalPagerScaffold＋HorizontalPager＋AnimatedPage，底部頁點指示。① 錄音（主頁，不捲動：logo、狀態、中央大圓鈕）② 飲食（綠色碗鈕＋外圈今天熱量/目標進度環，超標變橘）③ 最近（可捲動：用手機錄音、重送、最近 5 筆摘要 TitleCard）。錄音中仍是不能滑走的全螢幕 RecordingScreen；停止後回到原本那頁。新協定 `PATH_FOOD_TODAY`（手機→手錶，"已吃/目標/餐數"）：手機在手錶查詢（CMD_QUERY）和每次飲食記錄完成時推送（`WatchNotifier.broadcastFoodToday`）；手錶存在 prefs `food_today`（附當天 0 點，跨日自動歸零）。手錶摘要改存最近 5 筆（prefs `summaries`）。已在 Wear OS 模擬器驗證兩種主題、三頁、飲食錄音開始/停止；2026-11-19 在 Watch Ultra 實機驗證：三頁顯示正常、手錶開啟時收到手機推來的熱量（跨午夜正確歸零 0/2000/0）；非零熱量的同步路徑尚待下次飲食記錄時確認。
- **手錶改為兩頁（2026-11-19，取代上面的三頁）**：① 錄音頁＝所有錄音都在這頁：中央大圓鈕＝手錶錄音（自繪 WatchGlyph：錶面＋錶帶＋錄音點）、左小鈕＝飲食記錄（外圈今天熱量進度環，下方顯示「N 大卡」）、右小鈕＝用手機錄音（自繪 PhoneGlyph：手機＋錄音點）；② 摘要頁＝重送（有待傳時）＋最近 5 筆摘要（TitleCard，日期在 subtitle）。元件：`RecordPage`、`SideAction`、`SummaryPage`。已在 Watch Ultra 實機驗證兩頁與手錶錄音開始/停止。
- **手錶看摘要明細（2026-11-19）**：摘要頁點卡片 → `DetailScreen`，手錶送 `PATH_DETAIL_REQ`（payload = 錄音 id），手機 `WatchNotifier.sendDetail` 回 `PATH_DETAIL`（JSON：id/title/status/markdown/durationSec/createdAt，報告套用說話者名字，>80 KB 截斷）。手錶把 Markdown 轉成小塊（`reportBlocks`：段落標題、項目符號、表格每列一張小卡；只有「無」的段落略過），整理好的明細存 `files/details/<id>.json`，沒連手機也能看；向右滑回摘要頁（BackHandler）。手機 manifest 加 `/wristai/detail` message filter。
- **錄音時間戳記與報告時間分開（2026-11-19）**：`MeetingSummary` 加 `recordedAt`、`durationSec`（`sendSummary` 從 Meeting 補上）。手錶摘要卡顯示「錄音 M/d HH:mm · 長度」；明細頁兩行「錄音 M/d HH:mm:ss · 長度」「報告 M/d HH:mm」。舊摘要沒有錄音時間先顯示「整理於」，打開明細後自動補上。
- **手錶摘要清單精簡（2026-11-19）**：清單每筆只顯示標題＋錄音時間戳記（Wear `Button`，飲食記錄有碗圖示），摘要文字與完整報告點進去（`DetailScreen`）才顯示。
- **錄音鍵一律用紅點（2026-11-19）**：手機 `RecordDot`（白框紅點，Components.kt）用在「開始手機錄音」「記錄這一餐」按鈕（`GradientButton(recordDot = true)`）與主頁「開始錄音 / 記錄飲食」磚，不再用播放三角形；手錶的手錶 / 手機圖示中間的點改紅色。
- **手錶摘要頁改用手機的錄音清單（2026-11-19，取代「最近 5 筆摘要」）**：滑到摘要頁就送 `PATH_LIST_REQ`（/wristai/detail/list_req），手機 `WatchNotifier.sendList` 回 `PATH_LIST`：最近 40 筆 [{id,title,recordedAt,durationSec,kind,status}]（kind = watch/phone/food/call/import；status = DONE/整理中/錄音中/失敗/已存錄音機）。手錶存 prefs `recordings`，依「今天 / 昨天 / M/d 週X」分組，每列＝來源圖示＋標題（2 行）＋「HH:mm · 長度」（未完成加狀態），點了才開 `DetailScreen`。手機每送出一筆摘要也會順便推清單。
- **錄音起訖時間＋左右滑返回（2026-11-19）**：手機 `recordingRange(m)`（HomeScreen.kt）＝「yyyy/MM/dd HH:mm:ss – HH:mm:ss（長度）」，結束＝開始＋長度（最後錄到的時間），跨日會寫結束日期；詳情頁標題下顯示「錄音 起 – 訖（長度）」，分享的報告用 `withRecordingTime` 在標題下加「錄音時間：…」（匯入的再加匯入時間）。手錶明細頁顯示「錄音 M/d HH:mm:ss – HH:mm:ss」與「長度 · 報告時間」；明細頁往左或往右滑（橫向 >56dp）都回到清單（`detectHorizontalDragGestures`，系統返回手勢也照舊）。
- **手錶用詞與時間格式（2026-11-19）**：摘要頁標題改為「報告書」；手錶所有時間戳記一律帶兩位數年份（`stamp()` = yy/M/d HH:mm，分組標題「今天 26/11/19」「昨天 26/11/18」「26/11/17 週一」）。
- **報告書可搜尋 / 依月份 / 分類 / 分頁（2026-11-19）**：`PATH_LIST_REQ` payload 改成查詢 JSON {q, kind(all/rec/food), month("2026-11"), offset, limit(≤60), key}，手機 `sendList` 回 {key, total, offset, months:[{ym,count}], items}。關鍵字以空白分隔、每個都要出現，比對標題＋報告＋逐字稿＋說話者名字（在手機端做）。手錶：標題列「🔍 報告書 📅」（`HeaderIconButton`），🔍 開 Wear 標準輸入（`androidx.wear:wear-input` RemoteInputIntentHelper：語音 / 注音鍵盤 / 手寫），📅 切換月份清單（每月幾筆），類別膠囊 全部 / 錄音 / 飲食，篩選中顯示「「關鍵字」· 26年11月　共 N 筆 · 點此清除」，一次載入 30 筆、底部「顯示更多（還有 N 筆）」。各查詢結果依 key 存在 `WatchState.lists`，預設清單存 prefs。返回手勢先關月份模式 / 清除篩選。實機驗證：月份、飲食篩選、搜尋「x」找到 3 筆（含報告內容）、返回手勢；「顯示更多」因目前只有 14 筆尚未實測。
- **錄音 GPS 戳記（2026-11-19）**：shared `GeoFix`（lat/lon/accuracy/at/place，`current()` 同時問融合 / GPS / 網路定位、HIGH_ACCURACY、最多 30 秒，拿不到就用 10 分鐘內的最後位置）。手錶：開始錄音時用手錶 GPS 定位，存 `pending/<id>.geo`，送 `PATH_GEO`（/wristai/geo）給手機，送不出去會在 sendPending 補傳。手機錄音：PhoneRecordingService 開始時定位。兩邊錄音服務在有定位權限時加 `FOREGROUND_SERVICE_TYPE_LOCATION`（manifest microphone|location＋FOREGROUND_SERVICE_LOCATION），螢幕關了也能完成定位。手機存在會議資料夾 `geo.json`（`MeetingStore.saveGeo` 會用 Geocoder 反查地名）。顯示：手機詳情頁「地點 …（±N m）›」點了開地圖；分享報告加「錄音地點：… 地圖連結」；手錶明細頁「📍 地名」；報告書搜尋也比對地名；AI 提示的錄音時間後面加「錄音地點：…」。權限：手錶在錄音時一併請求；手機在第一次按開始錄音時請求一次（`rememberRecordStarter`，拒絕也能錄）。Wear 模擬器實測 4 秒拿到 GPS 並存檔；實機尚待使用者授權後驗證。
- **GPS 以聯動手機為主要來源（2026-11-19）**：手錶開始錄音 → 送 `PATH_GEO_REQ`（/wristai/geo/req，payload = 錄音 id）→ 手機 `GeoFix.current`（20 秒）存 `geo.json`（source = "phone"）並回 `PATH_GEO_ACK`（"id|1/0"）。手錶等回報最多 30 秒；手機連不上 / 回 0 / 沒回，才用手錶 GPS（source = "watch"）。`MeetingStore.saveGeo`：已有手機定位就不讓手錶的蓋掉。手機錄音一樣用手機定位。手錶錄音時手機 App 多半在背景，要「一律允許」（ACCESS_BACKGROUND_LOCATION）才拿得到 → 設定頁新增「錄音地點」區塊：顯示目前狀態（已開啟 / 只在使用時 / 未開啟），按鈕「允許定位」→「改成「一律允許」」（系統權限頁由使用者選）。注意：上架 Google Play 時背景定位需要在 Play Console 說明用途。實機待使用者授權後驗證。
- **GPS 實機驗證（2026-11-19）**：手機開「一律允許」後，手錶錄音（20261119_011900）由手機定位：source = phone、±13 m、開始錄音後約 1 秒取得；手機詳情頁與手錶明細頁都顯示地點。地名組法改為 `MeetingStore.placeName`：去重（忽略大小寫與空白）、中文直接相連、外文用「, 」分隔（單元測試 `PlaceNameTest`）。
- **說話者名字與摘要「無」（2026-11-19）**：①名字規則放寬：自我介紹、別人稱呼、職稱/稱謂（王經理、李老師、媽）、新聞署名都可當名字；判斷得出錄音者就寫「本人」；完全沒線索才寫「未知」（單段與長錄音分段提示都改）。手機報告的說話者卡片：「未知」等空白欄位不顯示，卡片可點來命名（「＋ 命名」/「改名」，沿用逐字稿的改名對話框，`ReportView(names, onRenameSpeaker)`）。②摘要常被寫「無」：原因是 SUMMARY 行已先寫過，模型以為「## 摘要」是空章節。提示改為「3～5 句、不可寫無」；`fillEmptySummary` 在解析時若摘要仍是「無」就用 SUMMARY 行補上（單元測試 `SummaryFillTest`）。舊報告沒存原始輸出，無法自動補，摘要是「無」時改顯示「這份報告沒有摘要，按上方「重新產生」可以補上」。重新產生驗證尚未做（手機鎖屏）。
- **報告基本資料、AI 欄位（2026-11-19）**：報告分頁最上面「基本資料」卡（`ReportFacts`）：時間（起 – 訖、長度）、地點（點了開地圖）、來源、AI、匯入時間；資料來自錄音本身，舊報告也有。不顯示 GPS 來源（使用者要求）。`Meeting.aiModel` 在四條產生路徑（單段 / 長錄音 / Claude / 飲食）完成時記錄（Gemini 的 `lastModel`），`aiModelLabel` 轉成「Google Gemini 3.8 Flash」等；舊報告顯示「未記錄（舊報告）」；分享報告加「報告整理：…」。實機驗證：手機錄音 20261119_013229 記到 gemini-3.5-flash-lite（3.8 忙碌時退回）、摘要完整、地點 Phnom Penh, Khan Sen Sok。
- **搜尋優化（2026-11-19）**：新 `SearchIndex`（手機錄音頁與手錶報告書共用）：可搜標題、日期（2026/11/19、11/19、11月19日）、來源、地點、說話者名字（套用改名）、報告、逐字稿；有快取（檔案沒變不重讀）；空白分隔多關鍵字、全部要出現、順序不拘、不分大小寫。手機錄音頁：打字停 250ms 才搜、「找到 N 筆」、依月份分組（「2026年11月 · N 筆」）、每筆顯示在哪找到＋前後文並標亮關鍵字（`SearchIndex.snippet`）。單元測試 `SearchIndexTest`。
- **多家 AI 引擎（2026-11-19）**：設定頁「AI 引擎」三種模式（`ai/AiConfig.kt`）：Gemini 一站式／組合式（語音辨識＋報告模型，可混搭）／只用 Galaxy AI。語音辨識（`ai/Stt.kt`）：①阿里百煉 Fun-ASR（getPolicy → 上傳 OSS 臨時空間 oss:// → 非同步任務 diarization_enabled → 查詢 → 下載結果 JSON；區域 intl 新加坡 / cn 北京，金鑰不通用；模型預設 fun-asr，可改）②科大訊飛錄音文件轉寫（raasr.xfyun.cn/v2/api upload＋getResult，signa = Base64(HmacSHA1(secret, MD5(appId+ts)))，roleType=1）③OpenAI（gpt-4o-transcribe-diarize、diarized_json、chunking_strategy=auto，25MB 上限 → 每段 85 分）。報告模型（`ai/Llm.kt`）：OpenAI 相容 chat/completions 一套搞定 通義千問（dashscope(-intl)/compatible-mode/v1，qwen-plus）、訊飛星火（spark-api-open.xf-yun.com/v1，Bearer APIPassword，4.0Ultra）、Meta Llama（api.llama.com/compat/v1，端點可改）、OpenAI GPT（gpt-5-mini）；Claude 走官方 Java SDK（`ReportGenerator.chat`）。模型名稱都可在設定改。流程（`ai/Pipeline.kt`）：依服務上限切段（Fun-ASR 110 分、OpenAI 85 分、訊飛 4.5 小時）→ 進度與排隊中的任務編號存 `stt_state.json`（WorkManager 10 分鐘上限，下次接著做；重新產生時同家同模型沿用逐字稿）→ 逐字稿「[mm:ss] 說話者A：…」（每段代號重編並提示 AI 合併）→ 報告（`GeminiReportGenerator.TEXT_REPORT_PROMPT`，跟 Gemini 同章節；超過模型字數上限就先分段筆記再合併）。飲食記錄也支援（`FoodLog.TEXT_PROMPT`）。Meeting.aiModel 存「語音辨識模型|報告模型」，報告 AI 欄位顯示兩段。單元測試 `AiProvidersTest`（依官方文件範例格式解析）；測試加 `org.json:json`。尚未用真實金鑰實測任何新服務商（需使用者自行申請金鑰）；訊飛 getResult 文件寫 GET/POST 不一致，程式先 POST、405 再 GET。
- **手機 UI 檢查修正（2026-11-21，封面螢幕 904×2316）**：AI 引擎選項改 FlowRow 換行（原本橫捲，第三個選項看不到）；模型欄標籤改「模型（留空用預設）」＋灰色預設值提示；Gemini / Claude 金鑰打了就存；報告表格只有「無」時顯示一行淡色「無」不再是空卡片；今天錄音時長不到 1 分鐘顯示秒（21s）；熱量格改「0/2000」＋「大卡」、StatTile 標籤一行、長數字縮小字；清單日期今年省略年份（`shortWhen`）；飲食清單營養素不在數字中間斷行。手錶也檢查過：錄音頁、報告書（清單、日期分組、搜尋輸入）、明細頁都正常；報告書類別膠囊縮小，「飲食」不再被圓形螢幕切掉。
- **AI 處理步驟視覺化＋中斷（2026-11-21）**：Meeting 加 `steps`、`step`、`stepDetail`、`stepStartedAt`、`processStartedAt`、`processEndedAt`。Worker 開始時 `plan()` 依模式排出步驟：Gemini 短錄音「接收錄音→(存入錄音機)→上傳音檔→AI 聆聽整理」、Gemini 長錄音「…→分段分析→整合報告」、組合式「…→語音辨識→報告整理」、飲食「接收錄音→上傳音檔/語音辨識→估算熱量」；各步 `stepTo()`、完成 `finishSteps()`。Gemini `generate(onListening=)` 在上傳完成後回呼。詳情頁 `ProcessSteps`（ui/ProcessSteps.kt）：完成打勾、進行中閃爍＋這步秒數＋說明、失敗打叉、中斷黃色停止方塊；標題列總經過時間；清單狀態膠囊顯示目前步驟名；基本資料加「耗時」。「中斷處理」按鈕：`ProcessMeetingWorker.cancel()` 取消 unique work、標 FAILED＋error = `CANCELED`（「已手動中斷處理」），顯示「已中斷」、按「重試」接著做；worker 的 catch 遇到 isStopped 不覆寫狀態。實機驗證：步驟即時更新、中斷後維持已中斷、失敗卡顯示「「AI 聆聽整理」這一步失敗」。完成狀態與耗時列因當時 Gemini 連線逾時未驗證。

## iOS 版（iPhone + Apple Watch，2026-11-22，Mac mini）

計畫與 Android 對照見 `交接/IOS_PORT.md`。程式在 `ios/`（Xcode 27.2，iOS 17+ / watchOS 10+）：`Dylan`（iPhone）、`DylanWatch`（手錶）、`Shared`（協議、配色、logo）。用 Xcode 開 `ios/Dylan.xcodeproj`：跑 iPhone 選 scheme **Dylan**，跑手錶選 **DylanWatch**（選錯 scheme，另一種裝置會顯示「平台不符」）。

| 項目 | 狀態 |
|---|---|
| 里程碑 1：手錶錄音（16kHz AAC，每 5 分鐘一段）→ 手機收齊接成 audio.m4a | ✅ 模擬器 |
| 手錶等手機「存好」的確認才刪檔（`/wristai/seg/ack`）；沒確認會重送 | ✅ 模擬器 |
| 手錶被打斷 / 收音停住自動恢復、熄屏續錄 | 🟡 已寫，要真機驗證 |
| UI 照 Android：手機四分頁、主頁、錄音清單、詳情（播放器、報告 / 逐字稿）、設定；手錶三鍵正三角＋報告書頁 | ✅ 模擬器 |
| 外觀：深色 / 淺色 / 水墨 / 自動；手錶跟手機的水墨（applicationContext `/wristai/theme`） | ✅ 模擬器 |
| AI 引擎依用途選（`AiCapability`，2026-11-22 使用者決定）：錄音報告 Gemini（分說話者）；Apple 不分說話者 → 當輔助、即時預覽、隱私模式、飲食 | 偵測 ✅（設定頁分「錄音報告 / 輔助 / 飲食」顯示） |
| Apple 引擎（`AppleAI`）：SpeechTranscriber 逐字稿；私有雲 / 裝置端模型整理（長的分段）；飲食用 `@Generable` 結構化營養 | 語音 ✅ Mac 實測（53 秒 1.3 秒）；整理與飲食 ❌ 這台 Mac 與模擬器都沒有 Apple 模型，要真機 |
| 飲食記錄：手錶「飲食」鍵（id `food_`）→ Apple 優先、Gemini 備援（照抄 Android FoodLog.PROMPT）→ 飲食分頁、主頁熱量格、詳情營養卡 | ✅ 模擬器（Gemini：牛肉麵＋燙青菜＋大杯半糖珍奶 → 1150 大卡；手錶飲食鍵整條流程） |
| 即時預覽（Gemini 跑的同時先出 Apple 逐字稿）、Apple 輔助（沒金鑰 / Gemini 失敗改用 Apple） | 🟡 已寫；模擬器沒有語音模型不會觸發，要真機 |
| 隱私模式（設定頁「只在手機上處理」，一律 Apple、不上傳） | ✅ 模擬器（沒有 Apple 語音時清楚報錯，不會上傳） |
| **自架引擎（使用者自己的 Mac）**：`local-ai/`（FunASR 分說話者＋Qwen3-8B on MLX），App `LocalAI.swift`；有設定時錄音報告優先用 Mac，連不到改 Gemini → Apple | ✅ 模擬器：53 秒對話約 45 秒、說話者正確；手錶 → 手機 → Mac → 手錶「報告完成」；Mac 關掉時 1 秒內改用 Gemini |
| **自架模型可切換**：設定頁「語音辨識」FunASR / Breeze-ASR-26（台語）、「報告模型」Qwen3-8B / SARC 台語 12B（GGUF），也可在 Mac 的 config.json 自己加；`/v1/models`、`/v1/config` | ✅ 各組合在模擬器＋curl 都跑過；換模型時 App 顯示載入中，記錄上標示用了哪組模型。台語實錄準確度未測 |
| 里程碑 2：Gemini 報告（提示詞照抄 Android，3.8-flash → 3.5-flash-lite 備援），金鑰存 Keychain | ✅ 模擬器（53 秒兩人對話 → 7 秒出報告，說話者名字正確） |
| 背景處理：上傳與 generateContent 交給系統背景傳輸（`GeminiJobs`），鎖屏 / App 在背景 / 被系統關掉都會完成 | ✅ 模擬器（鎖屏、殺 App 都測過） |
| 完整流程：手錶錄音 → 手機（鎖屏、App 沒開）→ 背景 Gemini → 手錶「✓ 報告完成」 | ✅ 模擬器 25 秒 |
| 真機（2026-11-23）| ✅ iPad Pro 11 吋第 2 代（iPadOS 27.2）：安裝、手機錄音 → 固定 IP → 大D的主機出報告；iPhone 15 Pro Max（iOS 26.7）：安裝、手機錄音 → Gemini 報告。手錶真機未測 |
| 手機 / iPad 錄音、匯入音檔 / 影片 / 文字、從別的 App 分享進來、錄通話（畫面廣播只留聲音）、商用 / 科技介面模式、iPad 全螢幕兩欄版面、來源顯示裝置名字＋型號 | ✅（通話錄音尚未實際通話測試）。**給 Android 的完整總結：`交接/IOS_TO_ANDROID.md`** |

注意事項：
- **模擬器的手錶傳檔是壞的**（Xcode 27.2）：`transferFile`、`transferUserInfo` 會被轉成沒附件的訊息、手機的 wcd 直接丟掉，只有 `sendMessage` 會到。所以模擬器上（`#if targetEnvironment(simulator)`）分段改成切 32KB 用 sendMessage 傳、手機拼回原檔；真機仍用 transferFile。
- **這版 Xcode 沒有獨立的 Simulator App**，Claude 的模擬器面板也只顯示 iPhone；手錶畫面用 `xcrun simctl io <watch> screenshot` 看，或在 Xcode 用 DylanWatch scheme 執行。
- **模擬器沒有語音辨識模型**（`SpeechTranscriber.supportedLocales` 是空的），設定頁顯示「繁中語音辨識：不支援」是正常的；Mac 上實測 zh-TW 支援。
- 測試用開關（只在 Debug）：手錶 `-autoRecordSeconds N` 自動錄 N 秒；手機 `-processId <錄音id>` 啟動就分析；手錶分段長度可用 UserDefaults `segment_seconds` 改短（測完刪掉）。
- 強制關閉 App（在 App 切換器滑掉）時 iOS 會取消背景傳輸，下次開 App 會自動重新分析。
- 測試用開關（只在 Debug）另有：手錶 `-autoRecordFood YES` 讓自動錄音變成飲食記錄；手機 `-localToken <存取碼>` 直接存自架 Mac 的存取碼（模擬器剪貼簿不能用）。
- **大D的主機（自架 Mac）**：安裝 `bash local-ai/setup.sh`，說明見 `local-ai/README.md`；**連線與維護見 `交接/HOST_CONNECT.md`**。服務登入時自動啟動（LaunchAgent `com.dylan.localai`），存取碼在 `~/dylan-local/config.json`（不進 repo）。背景傳輸遇到連不到的主機不會失敗、只會一直等，所以送出前先做 5 秒的 health 檢查；Mac 用自己的背景 session，總時限 1 小時。
- 還沒做：手錶報告書清單 / 明細、手錶今日熱量環（`/wristai/food_today`）、長錄音分段分析（目前太長只會提示逐字稿被截斷）、GPS、搜尋、組合式引擎、處理步驟視覺化、說話者改名。

## Android 跟上 iOS（2026-11-23，依 `交接/IOS_TO_ANDROID.md` 的高優先項目）

- **大D的主機**（`ai/LocalHost.kt`）：跟 iOS `LocalAI.swift` 同一組 API（`/v1/health`、`/v1/models`、`/v1/config`、`/v1/process`）。位址存設定檔，存取碼用 Android Keystore（AES-GCM）加密後才存；`usesCleartextTraffic=true`（主機只有 http）。路由（`ProcessMeetingWorker.tryLocalHost`）：有設定就優先（錄音、飲食都是），先做 5 秒 health；回應格式跟 Gemini 一樣，直接用 `GeminiReportGenerator.parse`。失敗時：有別的引擎（組合式 / Gemini / 錄音機）就把原因寫進 `Meeting.engineNote`（「…。這次改用 X。」）再交給原本的路由；沒有就標失敗。處理中升成前台工作（長錄音可能超過 10 分鐘）。步驟「上傳到大D的主機 → 大D的主機整理」。`aiModelLabel` 認 `local-` 開頭：「大D的主機：FunASR ＋ Qwen3-8B」。`AiConfig.canReport` 有主機就算可以產生報告（被改用錄音機的記錄可以按「產生 AI 報告」再給主機）。
- **設定頁**：最上面「介面模式」商用 / 科技（`ui/UiMode.kt`，存 `ui_mode`）；「大D的主機」卡（`ui/LocalHostSettings.kt`）：位址、存取碼（畫面只顯示「已設定」）、測試連線、清除；科技模式多主機模型選單（選了打 `/v1/config`，每 3 秒查 `/v1/models` 直到載入完）。系統狀態：有主機就是綠燈；手錶那行商用模式只在有連線時顯示。
- **錄音清單改版**（`ui/RecordRow.kt`）：來源圖示（手錶 / 手機 / 通話 / 匯入）、標題＋右上 HH:mm、一行摘要（報告「摘要」段，`summaryOf`）；沒報告時「來源 · 長度」；狀態膠囊只在處理中 / 失敗；依「今天 / 昨天 / 11月20日 週五」分組（不是今年加年份）；長按 → 打開 / 刪除（確認「刪除後無法復原」）；長度「16 秒 / 2 分 / 1 小時 5 分」（`humanDuration`）。科技模式：摘要兩行＋細節行（來源 · 長度 · AI · 耗時）。
- **主頁**：科技模式多一行「AI：目前的引擎」；手錶膠囊商用模式只在有連線時顯示。
- **基本資料**：AI 兩種模式都顯示；耗時、編號、引擎切換原因只在科技模式。還沒有報告時，`engineNote` 兩種模式都顯示在詳情頁。
- 測試：`ListFormatTest`（摘要、長度、日期分組）、`AiLabelTest` 加主機名稱與位址整理。模擬器用假主機（照 API 回固定報告，`adb reverse tcp:8765`）實測：設定 → 測試連線 → 換模型 → 手機錄音 → 主機出報告；主機關掉 → 改用錄音機並寫原因 → 主機開回來按「產生 AI 報告」成功；商用 / 科技切換；長按刪除。**還沒有接真正的大D的主機（Mac mini）測**，需要使用者在手機設定填位址與存取碼。
- IOS_TO_ANDROID.md 的中優先項目（匯入文字檔、分享進來、錄線上通話、裝置名字＋型號、Fold5 展開兩欄、主機模型以外的 meta 欄位）還沒做。

## Android：大D的主機配對（2026-11-23，跟 iOS 的 QR / 密碼配對一樣）

- `ai/LocalHost.kt`：`pair(url, password | code)` 打 `POST /v1/pair`（不帶存取碼，送裝置名「名字（型號）」，`deviceLabel` / `modelName`）→ 存位址、這台自己的存取碼（Keystore 加密）、裝置 id；`parseLink` / `pairLink` 解析 `dylan://pair?url=&lan=&code=`，先試 url，**連不到**（`Unreachable`）才試 lan，主機回錯誤就直接顯示；`devices()`、`revoke(id)`、`setPassword()`、`unpair()`（通知主機作廢這台，連不到也清本機）。手動貼主存取碼時沒有裝置 id。
- 設定頁「大D的主機」卡（`ui/LocalHostSettings.kt`）：還沒連 →「掃描主機上的 QR 碼」（Google Play 服務的掃碼畫面 `play-services-code-scanner`，不用相機權限）或填位址＋配對密碼；科技模式另有「進階：直接貼主存取碼」。已連上 →「已連上大D的主機」、測試連線、取消配對、配對密碼設定 / 更換；科技模式多模型選單與「已配對的裝置」（配對 / 最後使用時間、QR / 密碼，可個別取消，「這台」等於取消配對）。
- `dylan://pair` 連結：MainActivity intent-filter（VIEW、BROWSABLE、scheme dylan、host pair）→ `PendingPair.link` → 切到設定頁自動配對。
- 測試：`PairLinkTest`（連結解析、重複位址、非配對連結、型號）。模擬器用 repo 的**真正** `local-ai/pairing.py` 做假主機實測：QR 連結（對外位址連不到 → 自動改區網位址）配對成功；密碼錯顯示「密碼不對」、對了配對成功；裝置清單、取消別台、取消這台（主機端作廢）；App 內更換密碼後新密碼可配對；用配對拿到的存取碼送錄音、主機出報告。**未測**：真的用相機掃 QR（模擬器沒辦法）、接真正的 Mac mini 主機。

## Android：辨識語言＋錄音音量條（2026-11-24，IOS_TO_ANDROID 第 12、13 項 / 3b、3c 節）

- **辨識語言**（`SpeechLang.kt`）：國語 `zh`（預設，舊資料＝國語）/ 台語・國台語 `nan`，設定 `speech_lang`，Compose 狀態（主頁、錄音頁的選擇器同步）。`Meeting.lang` 在建立錄音時存當下的選擇（手機錄音、手錶錄音、長錄音分段、匯入）。大D的主機送 `X-Lang`（文字輸入不帶）；Gemini 在 `nan` 時提示詞加台語說明（跟 iOS 一字不差，`GeminiReportGenerator(note=)`，短錄音與長錄音分段都加）。
- 畫面（`ui/LangAndLevel.kt` 的 `LangPicker`）：主頁錄音鍵上方、「錄音」分頁手機錄音卡、錄音詳情頁（標題區下方）。詳情頁已有報告時換語言 → 確認「改用「X」重新產生？」（台語提醒較慢）→ 更新 lang 並重新產生；還沒有報告就只改 lang。錄音中的卡片顯示「手機錄音中 · 台語／國語」。
- **音量條**（`AudioLevel.kt`＋`LevelMeter`）：`PhoneRecordingService` 的 AudioRecord 迴圈每段 PCM `AudioLevel.feed`，湊滿約 85 ms 算一次 RMS → 一根柱，留 56 根；高度 `((dB+52)/44)` 夾 0…1 再平方；畫的時候除以最近最大值（至少 0.3）×0.92；左右對稱、越新越亮、太大聲最新 6 根琥珀色。狀態：收音正常 / 聲音太大（峰值 >0.97 後 1.2 秒）/ 幾乎沒有聲音（4 秒都 < -45 dB）；科技模式顯示 dB。Android 沒有 iOS 的「收音中斷」狀態（錄音被打斷時目前會直接停止）。手錶錄音不顯示。
- 順手修：手機錄音中的狀態原本寫「手錶錄音中」「手錶正在錄音」，改成依來源顯示「手機」。
- 測試：`LangLevelTest`（語言代碼、台語提示、dB 刻度、每 85 ms 一根、太大聲）。模擬器＋假主機：主頁選台語 → 錄音卡顯示「台語」與音量條（模擬器麥克風沒聲音，顯示「幾乎沒有聲音」-60 dB）→ 主機收到 `X-Lang: nan`；詳情頁改國語 → 確認 → 重新產生，主機收到 `X-Lang: zh`、meta 存 `zh`。**未測**：真機有聲音時柱子的動態、真正主機的 Breeze-ASR-26。

## Android：設定 →「關於與授權」（2026-11-24）

- `ui/AboutScreen.kt`（設定頁最下面「關於與授權」按鈕）：AI 內容免責、開放原始碼套件（依授權分組、可展開看清單）、Apache 2.0 / MIT 全文、各套件 NOTICE、Google 服務（Play 服務、ML Kit 條款連結）、雲端 AI 服務（Gemini / Anthropic / OpenAI 條款連結；百煉、訊飛、Meta 只列名稱）、大D的主機的模型（連到原始頁面）、商標說明。
- 授權資料在 `app/src/main/assets/licenses/`，由 `python tools/gen_licenses.py` 從 Gradle 實際打包的套件產生（讀 POM 授權、從 jar / aar 取 NOTICE 與 Apache 全文；兩個 MIT 套件沒附全文，腳本照 POM 的著作權人寫標準 MIT 條文）。**加 / 升級套件後要重跑**。目前 161 個套件：Apache 2.0 150、Android SDK License 5（Play 服務）、ML Kit 條款 4、MIT 2（slf4j、standardwebhooks）。build.gradle 的 packaging 會排除 META-INF/LICENSE*、NOTICE*，所以要靠這頁附上。
- 更正：Anthropic Java SDK 是 Apache 2.0（不是 MIT）。
- **還沒確認授權**：FunASR 模型、Breeze-ASR-26（含 doggy8088 的 MLX 轉換）、SARC 台語 12B——頁面寫「依模型頁的授權」並連到原始頁面；商用前要逐一確認（特別是 SARC 是否有底層模型的使用條款與商用限制）。iOS 還沒有這一頁。

## Android：客語＋腔調（2026-11-24，IOS_TO_ANDROID 3b「客語」）

- `SpeechLang` 加 `HAK`（客語）；`HakkaDialect`：四縣 `sixian`（預設）、海陸、大埔、饒平、詔安、南四縣，設定 `hakka_dialect`。`Meeting.dialect` 在建立錄音時存（只有客語才存）。主機送 `X-Lang: hak` ＋ `X-Dialect`；Gemini 提示詞加客語說明（跟 iOS 一字不差，含腔調名）；主機模型名稱認 `hakka` →「客語 Whisper」。
- 畫面：`LangChooser`（主頁、「錄音」分頁）＝三選一＋下面一行說明，選客語時右邊「四縣腔 ▾」選單；錄音卡「手機錄音中 · 客語・海陸」；詳情頁選客語時多一行「辨識語言：客語・X腔」＋腔調選單，已有報告時換語言或腔調都要確認再重新產生（客語提醒比較慢、主機沒裝模型會改用 Gemini）。
- 測試：`LangLevelTest.hakka`。模擬器＋假主機（照目前真主機的行為：客語回「客語模型還沒裝好」）：選客語・海陸 → 錄音 → 主機收到 `hak` / `hailu` → 主機失敗 → 改用其他引擎並寫進 engineNote；meta 存 `lang=hak`、`dialect=hailu`。主機的客語模型授權是 CC BY-NC 4.0（只能非商業使用），還在等 Hugging Face 申請。

## Android：客語改用 Taiwan Tongues（2026-11-24，跟主機同步）

- 大D的主機的客語模型改成數位部 `adi-gov-tw/Taiwan-Tongues-ASR-CE`（Whisper large-v2，本機轉 MLX 8bit、不散布；TRAIL v0.1 授權，可商用但有使用限制，例如要標示 AI 產生內容）。主機的評估與決定見 `HOST_CONNECT.md`、`IOS_TO_ANDROID.md` 3b。台語仍用 Breeze-ASR-26、國語仍用 FunASR，沒有「自動」語言。
- Android 只改顯示：`aiModelLabel` 認 `taiwan-tongues` →「大D的主機：Taiwan Tongues ＋ Qwen3-8B」；客語提示文字改「數位部 Taiwan Tongues 模型」；「關於與授權」的主機模型清單加這個模型（授權 TRAIL v0.1）。X-Dialect 照送（Taiwan Tongues 沒有腔調提示，主機只把腔調放進報告提示詞）。

## 手錶（Wear OS）：辨識語言、音量條、AI 欄位（2026-11-24）

- **辨識語言跟手機同一個設定**：手錶錄音頁中間多一顆「國語 ▾」小膠囊 → 全螢幕清單選 國語 / 台語 / 客語（選客語後下面再選腔調）。手錶改 → `PATH_LANG_SET`（`/wristai/phone_rec/lang`，沿用手機的 message filter）→ 手機 `SpeechLang.set`；手機改或手錶開 App（`CMD_QUERY`）→ 手機推 `PATH_LANG`（`/wristai/lang`）。payload `zh` / `nan` / `hak|hailu`（`shared/SpeechLangs`）。手機不在時手錶記 `speech_lang_dirty`，下次傳錄音前（`PhoneLink.sendPending` 一開始）先補傳，所以手機建立這筆錄音時已經是新語言；手錶有沒傳出的修改時，手機推來的舊值不蓋掉、改把手錶的送過去。手錶錄音照舊在手機建立 Meeting 時存 `lang` / `dialect`。
- **音量條**：`AudioLevel` 從 app 搬到 `shared`（手機、手錶共用同一套刻度）。手錶 `RecordingService` 每段 PCM `feed`；錄音畫面在計時與停止鈕之間畫最近 24 根，下面一行在太大聲 / 幾乎沒聲音時改成提示。手錶遙控手機錄音時不顯示（音量在手機上看）。錄音畫面標題「錄音中 · 客語・海陸」。
- **報告書 / 明細**：手機的清單每筆多 `lang`（國語不帶），手錶清單第二行顯示「· 台語」；明細 JSON 多 `lang`、`ai`（`aiModelLabel`，整理好才有）、`note`（engineNote），手錶明細顯示「語言 …」「AI：大D的主機：…」，改用備援引擎時用琥珀色顯示原因。明細手機回了就不再顯示「正在取得」（原本固定等 8 秒）。
- 測試：`LangLevelTest.watchLangPayload`。Wear OS 模擬器（沒配對手機）：選台語、客語・海陸 → 存檔且標記未同步、重開 App 還在；錄音畫面音量條＋「幾乎沒聲音」；假資料的清單與明細顯示語言與 AI。**未測**：真手錶與手機之間的同步（模擬器沒配對）、真手錶有聲音時的柱子。iOS / watchOS 還沒有這些。

## Android：逐字稿語言標籤（2026-11-24，IOS_TO_ANDROID 3b「逐字稿語言標籤」）

- 逐字稿每一段說話者名字前一個小膠囊：國（灰 `TextMuted`）、台（綠 `Success`）、客（琥珀 `Warning`），淡色底＋細框。無障礙朗讀「國語」「台語」「客語海陸腔」（客語帶腔調）。依 `Meeting.lang`（舊資料＝國）。`SpeechLang.tag` / `tagLabel(dialect)`、`ui/ReportViews.kt` 的 `TranscriptView(langTag, dialect)`、`langTagColor`。
- 匯入的文字記錄（錄音資料夾有 `input.txt`）不標；Android 目前還沒有文字匯入，先照 iOS 的規則留著判斷。
- 標的是使用者選的辨識語言，不是逐句判斷（Breeze、Taiwan Tongues 都沒有台語／客語語言標記）。
- 測試：`LangLevelTest.transcriptTag`；模擬器看過國（灰）、客・海陸（琥珀、朗讀「客語海陸腔」）。

## 大D的主機：多人共用的工作佇列＋管理者（2026-11-24）

- **主機**（`local-ai/jobs.py`、`server.py`、`pairing.py`，說明見 `local-ai/README.md`「多人共用」）：`POST /v1/jobs` 送出就回工作編號，`GET /v1/jobs/<id>` 查排隊 / 結果，`DELETE` 刪。每台裝置輪流（不是先送先做）、每台最多 20 筆、工作存硬碟（重開接著做）、結果 24 小時沒拿就刪、預估等待時間會自己學。舊的 `/v1/process` 照樣能用（也走佇列），iOS 不改也不會壞。
- **管理者**：只有管理者能換模型、改配對密碼、管別台；一般裝置只看得到自己。原本配對的裝置都自動是管理者。
- **Android**（`LocalHost.submit/job/deleteJob`、`ProcessMeetingWorker.hostJob`）：新主機走佇列，舊主機（health 沒有 `jobs`）照舊 `/v1/process`。工作編號存在錄音資料夾 `host_job.txt`，Worker 被停掉再跑時接著查、不重傳；主機找不到這筆就重送一次；連不到超過 15 分鐘才改用別的引擎。處理步驟顯示「排隊中：前面還有 N 筆，約 X 分鐘」，手錶只在筆數變了才通知。做完再多兩步「主機刪除錄音」（主機回 `purged` 確認錄音與轉檔都刪了）、「傳回報告」（拿到後刪掉主機上的報告，顯示「主機上的報告也已刪除」）；舊主機也顯示這兩步。結果存在 `Meeting.hostCleanup`，報告「基本資料」多一行「隱私：大D的主機上的錄音與報告都已刪除」（沒確認到的會寫「24 小時內自動刪除」），兩種介面模式都顯示。「中斷處理」會通知主機丟掉這筆。設定頁：一般使用者看不到模型選單、配對密碼、別台裝置，多一行說明；管理者在裝置清單可「設管理者 / 改一般」。
- **驗證**：`jobs_test.py` 10/10；用 `fake_host.py`（真正的 server.py、假模型）跑 HTTP 測試全過（輪流順序、權限、舊端點）；Android 模擬器以一般使用者配對 → 兩台假裝置塞滿佇列 → 手機錄音顯示「排隊中：前面還有 1 筆，約 2 分鐘」→ 輪到後出報告；中斷處理後主機上那筆確實刪掉。**未做**：部署到真正的 Mac mini（要把 `jobs.py` 一起複製過去）、iOS 改用佇列與管理者介面。

## Android：Google 雲端硬碟備份與取回（2026-11-24）

設定頁「Google 雲端硬碟備份」：連結帳號（`drive.file`，只碰 App 自己建的檔案）→ 每筆錄音一個 zip 放在雲端硬碟「大D 錄音備份」資料夾（報告、逐字稿、meta、可選錄音 m4a）；自動備份（報告完成時，可只用 Wi-Fi）、立即備份（增量）、從雲端取回（只補手機沒有的，錄音解回 wav）。**要先在 Google Cloud 登記 OAuth 用戶端才能用**，步驟、指紋、限制、未驗證項目見 `交接/DRIVE_BACKUP.md`。

## 錄音編號加裝置代碼（2026-11-25）

新錄音編號 = `[food_|imp_|call_]yyyyMMdd_HHmmss_<裝置代碼>`，例 `20261125_001317_gt24`。裝置代碼是每台裝置（手機、手錶各一）第一次用時隨機產生的 4 碼（shared `RecordingIds`，存在 prefs `dylan_ids`）。目的：多台裝置共用 Google 雲端硬碟備份時不會撞號互相覆蓋。舊編號照用；`RecordingIds.timeOf` 新舊都認得（SegmentAssembler 用它推錄音時間）。手錶分段檔名用 `__` 分隔，不受影響。單元測試 `RecordingIdsTest`。iOS 之後也要用同一格式。


## iOS 跟上：佇列、管理者、裝置代碼、飲食語言、「檔案」分頁（2026-11-25，Mac mini）

- **大D的主機已部署**新版（`server.py`、`pairing.py`、`jobs.py`），`jobs_test.py` 10/10；真主機用 `/v1/jobs` 跑一筆真錄音：排隊 → 完成（`purged=true`）→ DELETE。原本配對的兩台都自動是管理者。
- **主機小改**：`GET /v1/jobs/<id>?wait=秒`（最多 1500）會等到做完或逾時才回。iPhone 在背景只能用系統的背景下載任務，沒辦法每幾秒查一次，所以用長等待拿結果；不帶 `wait` 跟以前一樣馬上回（Android 不受影響）。
- **錄音編號**：`RecordingID.make(prefix:)` = `[food_|imp_|call_]yyyyMMdd_HHmmss_<4碼>`，代碼存在各自的 UserDefaults（iPhone、Apple Watch 各一組）。匯入改加 `imp_`；通話錄音（廣播擴充寫的資料夾名還是時間）收進來時改成 `call_…_<代碼>`。`RecordingID.date(of:)` 新舊格式都認得。
- **佇列**（`LocalAI`、`GeminiJobs`）：health 有 `jobs` → 背景上傳到 `/v1/jobs`，工作編號存 `host_job.txt`，用背景下載任務查 `?wait=20`（App 在前景）/ `?wait=1500`（背景）；App 被停掉 / 強制關閉後重開，有 `host_job.txt` 就接著查、不重傳；主機回 404 重送一次；連不到超過 15 分鐘改用 Gemini / Apple；做完 DELETE。分析中的說明顯示「排隊中：前面還有 N 筆，約 X 分鐘」。報告「基本資料」多「隱私」行（`Meeting.hostCleanup`，文字跟 Android `cleanupText` 一樣；舊主機也寫「都已刪除」）。刪除還在排隊的錄音會通知主機丟掉。
- **管理者**：health / devices 回 `admin=false` 時，設定頁不顯示配對密碼、模型選單、裝置清單，多一行說明；管理者在裝置清單（科技模式）可「設管理者／改一般」。
- **飲食語言**：飲食頁「記錄這一餐」上方 國語／台語・國台語／客語＋腔調（跟主頁同一個設定）；飲食詳情頁本來就能換語言重新產生；Gemini 飲食也帶台語／客語說明（同一個 prompt）。台語、客語的飲食不走 Apple 裝置上的模型（聽不懂）。
- **「檔案」分頁**（`FilesView.swift`）：摘要、雲端備份卡（目前寫「準備中」）、全部／錄音／飲食篩選、每筆大小；點一筆：打開、分享錄音檔（m4a）、只刪錄音檔（保留報告）、刪除整筆，刪除前都確認。底部五個分頁平分寬度。
- **驗證**：模擬器配真主機：匯入一段錄音 → `imp_20261125_003533_9pgp` → 主機佇列 → 完成 → 主機上那筆已刪、報告有「隱私」行；「檔案」分頁、只刪錄音檔、飲食頁語言選擇看過。**未做**：Google 雲端硬碟備份（要先在 Google Cloud 建 iOS OAuth 用戶端）；真機上背景長等待；Apple Watch 錄音的新編號只編譯過、沒實測。

## iOS 介面重新設計：主題 A 清晰／C 暖和日常、新分頁（2026-11-25）

設計稿（三個方向的比較，使用者選 A、C）：Claude 的設計畫布「大D 介面重新設計」。**Android 也要跟上**，數值照下面：

- **主題**（設定 → 外觀主題，兩個選項；舊的深色 / 淺色 / 水墨 / 自動先拿掉，舊設定值一律變「清晰」；手錶維持深色）：
  - **清晰**（預設）：底 `#F5F6F8`、卡片白、框線 `#E3E6EB`、字 `#101828` / 次要 `#5D6675`、重點藍 `#1F5BD8`（單色，不用漸層）、錄音紅 `#D0312D`、成功 `#1E7A4C`、警告 `#A35A00`；卡片圓角 16。
  - **暖和日常**：底 `#FBF8F4`、卡片白、框線 `#EDE6DD`、字 `#2B2622` / 次要 `#6E655D`、重點＝錄音 珊瑚紅 `#C4492C`、飲食 / 成功 鼠尾草綠 `#3E7A58`；卡片圓角 22。
  - 新主題的小標題不再用大寫等寬字；語言選擇改成灰軌道＋白色滑塊（iOS 分段控制的樣子）。
- **底部分頁**：今天｜資料庫｜（中間錄音鍵）｜飲食｜設定。中間鍵任何分頁一按就錄、錄音中變停止鍵、長按可選「記錄飲食」。「檔案」分頁移到資料庫右上角的「管理」（同樣的功能，用 sheet 打開）。
- **今天**：日期＋「今天」大標；一張「開始錄音」卡（紅色錄音鍵、辨識語言、飲食／通話／匯入三個小按鈕）；處理中（顯示排隊位置）；今天的記錄清單（時間、國／台／客 或「餐」標籤、標題、摘要；右上今日熱量）。錄音中卡片改成一般卡片底：計時、語言、音量條（重點色）、紅色停止鍵。
- 未做：錄音中的全螢幕畫面（設計稿 A 的第 2 張）、報告頁改成分頁（重點／逐字稿／待辦／資訊）、資料庫把飲食也合進來。模擬器麥克風這次打不開，錄音中卡片沒實際看到。

**Android 已跟上（2026-11-25）**：`ui/theme/Color.kt` 的 `ClearPalette` / `WarmPalette`（同上色碼），`ThemeMode` 只剩 清晰 / 暖和日常（舊值當清晰），`Brand.CardRadius` 16 / 22；手錶一律推 dark。底部分頁 `BottomNav.kt`：今天｜資料庫｜中間錄音鍵（長按「記錄飲食」）｜飲食｜設定；「檔案」分頁改成資料庫右上「管理」（route `files`）。「今天」= `DashboardScreen.kt`（開始錄音卡、處理中含排隊、今天的記錄＋今日熱量）。語言選擇改成灰軌道白滑塊（`Segmented`）；錄音中卡片改一般卡片＋紅色停止鍵。啟動畫面改淺色。未做的項目同 iOS。

**排布與配色分開（2026-11-25，使用者要求，Android 已做，iOS 要跟上）**：設定裡兩個獨立選項，任意組合。
- **排布**（`layout_mode`：`quick` 預設 / `overview`；名字照用途取，**不要叫新版 / 舊版**，之後還會加其他排布）：**快錄**＝今天｜資料庫｜中間錄音鍵｜飲食｜設定（檔案在資料庫「管理」）；**總覽**＝主頁｜錄音｜飲食｜檔案｜設定（主頁是問候、快捷方塊、今日統計、最新報告，錄音頁有手機錄音卡，選中分頁是漸層膠囊）。1.1.87 存的 `new` / `classic` 讀成快錄 / 總覽。Android：`LayoutMode`、`OverviewDashboard.kt`、`BottomNav.kt` 的 `OverviewBottomNav`。
- **配色**（`theme_mode`：`clear` 預設 / `warm` / `dark` / `ink`，兩行兩格選）：深色、水墨用原本的調色盤（深色有漸層，水墨標題用襯線字）；手錶：水墨→水墨，其他→深色。舊值 light / system 當清晰。卡片圓角：清晰 16、暖和 22、深色 / 水墨 20。
- **iOS 已跟上（2026-11-25）**：`LayoutMode`（`layout_mode`：`quick` / `overview`，`RootView.swift`；1.1.87 的 `new` / `classic` 啟動時換成新值）、`OverviewDashboard.swift`（原本的主頁原樣搬回）、`OverviewBottomNav`；設定頁「排布」（快錄／總覽）＋「配色」（兩行兩格）。總覽時資料庫標題是「錄音」、沒有「管理」（有「檔案」分頁）。手錶：水墨→水墨，其他→深色。模擬器看過 快錄＋暖和日常、總覽＋深色。

**設定「介面」整合（2026-11-25，使用者要求）**：原本分開的「介面模式」「排布」「配色」三個區塊合成一個「介面」卡，裡面三列：排布（快錄 / 總覽）、資訊量（商用 / 科技，原介面模式，存值不變 ui_mode）、配色（四格）；三個選擇各自獨立。放在系統狀態卡後面。iOS 照做。
- **iOS 已跟上**：設定頁一張「介面」卡（系統狀態卡後面），三列 排布／資訊量／配色；存值不變。

## 飲食同步到健康（1.1.90）

- 設定 →「健康同步」：一個開關「飲食同步到健康」，預設關。打開時跳系統授權（只要求「寫入營養」，不讀任何健康資料）。
- **Android**：寫 Health Connect（`HealthSync.kt`，`androidx.health.connect:connect-client:1.1.0`）。每筆飲食一筆 `NutritionRecord`：時間＝錄音時間、餐別（早/午/晚/點心）、名稱、熱量、蛋白質、碳水、脂肪；`clientRecordId` ＝ 記錄 id（重複同步是更新不是重複），刪除記錄也從健康刪除。觸發點在 `MeetingStore.update`（飲食變成 DONE 或營養改了）與 `MeetingStore.delete`。打開開關時與「同步過去的記錄」會補寫所有已完成的飲食。
- **Google** ＝ Health Connect 本身；**Samsung Health** 沒開放一般 App 直接寫入，靠 Samsung Health → 設定 → Health Connect 同步（卡片上有「開啟」按鈕帶過去）。
- **Apple 健康**：只在 iOS，用 HealthKit 寫 `dietaryEnergyConsumed`、`dietaryProtein`、`dietaryCarbohydrates`、`dietaryFatTotal`（同一個 HKCorrelation food，metadata 放記錄 id 以便更新 / 刪除）。設定卡片一樣叫「健康同步」，列出 Apple 健康；Android 卡片上 Apple 那列只寫「只在 iPhone 版」。
- 隱私說明頁：`HealthPrivacyActivity`（Health Connect 授權畫面的「privacy policy」連結會開它）。

- **iOS 已跟上**：`HealthSync.swift`（HealthKit，只要求寫入 4 種營養、`read: []`）。每筆一個 `HKCorrelation(.food)`，correlation 與四個樣本都帶 `HKMetadataKeySyncIdentifier`＝記錄 id、`HKMetadataKeySyncVersion`＝現在毫秒，`HKMetadataKeyFoodType`＝標題「：」後面；kcal＝0 不寫（之前寫過就刪）。觸發：`MeetingStore.save`（飲食變 DONE 或營養/標題/時間改了）、`MeetingStore.delete`。設定「健康同步」卡在「介面」卡後面（iOS 沒有飲食記錄區塊），列 Apple 健康 / Google 健康 / Samsung Health（後兩個寫「只在 Android 版（Health Connect）」），「同步過去的記錄」、「上次同步 M/d HH:mm · N 筆」。entitlement `com.apple.developer.healthkit`，`NSHealthUpdateUsageDescription` / `NSHealthShareUsageDescription`（繁中）。模擬器驗過：授權畫面只有寫入、補寫、重複同步是更新不是重複、刪除記錄健康也刪。真機簽章需要 App ID 有 HealthKit（Xcode 自動簽章會加；使用者自己按）。

## 飲食熱量改用食藥署資料（1.1.92）

- App 內建 `app/src/main/assets/nutrition/tfnd.json`（278 KB，2,122 種食物，每 100 克的熱量、蛋白質、脂肪、碳水、纖維、糖、鈉）。來源：衛福部食藥署「食品營養成分資料集」（政府資料開放授權條款－第1版，可商用、要標示來源；「關於與授權」→「內建資料」有寫）。更新：下載 ZIP 後跑 `python -I tools/gen_tfnd.py <zip>`（同一食物不同取樣月份會合併取平均）。
- 提示詞（`FoodLog.PROMPT` / `TEXT_PROMPT`）附上資料庫名稱清單（約 1.3 萬字），AI 把一餐拆成食材，每樣一行 `ITEM: 食物=…; 份量=…; 克=…; 對照=<清單完全相同的名稱或 無>; kcal=…; protein=…; carbs=…; fat=…`，油、糖、醬料另列一行。
- `FoodLog.finish`：對照名稱完全相同、而且跟 AI 自己估的熱量差在 0.4～2.5 倍內，才用資料庫數字（克數 × 每 100 克）；否則用 AI 的。App 自己產生「## 飲食內容」表格（多一欄「來源」：食藥署：名稱 / AI 估算）與「## 合計」，摘要改成「午餐約 X 大卡，蛋白質 Yg」。沒有 ITEM 行（舊格式、大D的主機目前的提示詞）就照舊。
- 大D的主機的飲食提示詞還沒改（Qwen3-8B 多 1.3 萬字清單會慢），之後再評估。
- iOS 要照做：同一份 tfnd.json、同樣的提示詞與 ITEM 格式、同樣的 0.4～2.5 倍檢查與表格。測試：`FoodDbTest`。

- **iOS 已跟上**：`ios/Dylan/Nutrition/tfnd.json`（同一份，複製過來；更新時兩邊一起換）、`FoodDB.swift`（`FoodDB.find` / `promptList`，`FoodLog.parseItems` / `finish`，同樣的完全相同名稱＋0.4～2.5 倍檢查、表格「來源」欄、合計、摘要）、`GeminiClient.foodPrompt`（有資料就用 `foodDBPrompt`，照抄 Android dbPrompt；舊版留 `legacyFoodPrompt`）、`MeetingStore.geminiReplied` 走 `FoodLog.finish`（大D的主機沒 ITEM 行 → 照舊）。Apple 裝置端模型（飲食用 `AppleAI.food`）沒有帶清單（1.3 萬字超過它的上下文），照舊 AI 估。設定最下面「內建資料」卡（iOS 沒有關於頁）。FoodDbTest 的案例用 Swift 跑過全過；真的 Gemini 飲食錄音還沒在 iOS 跑過。

## 拍照記錄飲食（1.1.93）

- 入口：快錄的中間錄音鍵長按 → 選單「說：記錄飲食」「拍：拍照記錄」「拍＋說：拍照再補一句」；飲食分頁「記錄這一餐」下面多一顆「拍照記錄（最多 3 張）」（兩種排布都有）。拍照畫面可以切換「只拍照 / 拍照＋口述」，相機或相簿，一餐最多 3 張。
- 照片：系統相機（FileProvider，不需要相機權限）或系統照片選擇器（不需要權限）。一律轉正、縮成長邊 768 px、JPEG 品質 80（約 60–120 KB），重新編碼順便去掉 EXIF（GPS）。存成記錄資料夾的 `photo_1.jpg`…（`FoodPhotos.kt`），Drive 備份自動包含。
- 只拍照：建一筆 `food_` 記錄，`source = "photo"`、長度 0，詳情頁顯示「拍照 時間」、不顯示辨識語言。拍＋說：照片先放 `FoodPhotos.pendingForVoice`，手機錄音開始時接進那筆飲食記錄。
- 處理：有照片一律走 Gemini（`gemini-3.8-flash` / `3.5-flash-lite`，免費額度就夠；768 以內一張約 258 token），照片用 `inline_data` 夾在請求裡，有錄音照舊上傳。大D的主機與組合式還看不懂圖片，有照片時不走。沒有 Gemini 金鑰 → 失敗訊息「照片辨識飲食需要 Gemini（免費）」。
- 提示詞多兩條：依照片估份量、照片與口述不同以口述為準；看到包裝「營養標示」就照標示算，「對照」填「營養標示」→ 表格來源欄寫「營養標示」，合計寫幾樣依營養標示。其他照樣對照食藥署資料。
- 詳情頁上方顯示照片（點一下全螢幕）。iOS 要照做（PHPicker / 相機需要 NSCameraUsageDescription）。

- **iOS 已跟上**：`FoodPhotos.swift`（縮圖：`UIGraphicsImageRenderer` 轉正、長邊 768、`jpegData(0.8)` 重新編碼不帶 GPS；`FoodPhotoSheet` 只拍照／拍照＋口述、相機＋`PhotosPicker`、最多 3 張；`FoodPhotoStrip` 詳情頁最上面、點一下全螢幕）。入口：快錄中間錄音鍵長按「說：記錄飲食」「拍：拍照記錄」「拍＋說：拍照再補一句」，飲食分頁「拍照記錄（最多 3 張）」。只拍照 source = photo、長度 0、沒有 lang，詳情「拍照 時間」、沒有辨識語言；拍＋說照片放 `FoodPhotos.pendingForVoice`，`PhoneRecorder.begin` 接進記錄（錄不了音就改成只拍照的記錄）。有照片一律 Gemini（只有照片時跳過上傳直接 generate，`generateBody` 夾 inline_data；免費 flash 模型），隱私模式或沒金鑰就失敗並寫原因。提示詞兩條、對照=營養標示 → 來源「營養標示」、合計寫幾樣依營養標示。NSCameraUsageDescription 繁中。模擬器驗過：相簿選 2 張 → 768 px、沒有 GPS；沒金鑰的失敗訊息；詳情照片與全螢幕。真的 Gemini 照片辨識、相機（模擬器沒有）、拍＋說（模擬器麥克風不能錄）還沒驗。
- **iOS 拍攝地點／錄音地點（跟 Android 4e3146d）**：iOS 之前完全沒有地點，這次一起補：`ios/Dylan/Geo.swift`（`GeoFix` 存記錄資料夾 `geo.json`，欄位跟 Android 一樣 lat/lon/accuracy/at/place/source；`GeoStore.save` 用 CLGeocoder 繁中反查，`placeName` 照 Android 去重與中外文接法；`GeoLocator` 用 CLLocationManager 要一次新定位、最多 20 秒，拿不到用 10 分鐘內的最後位置）。權限 NSLocationWhenInUseUsageDescription（繁中），第一次按錄音或打開拍照時問一次（`asked_location`），拒絕照樣錄 / 拍、只是沒有地點。手機錄音（含拍＋說）開始時定位；只拍照：打開拍照畫面就開始定位，送出後最多再等 5 秒存進記錄再交給 AI（取消或選拍＋說就停掉）。AI 提示詞時間後面加「，錄音地點：…」（只拍照是「，拍攝地點：…」）。顯示：詳情頁標題下「地點 …（±N m）›」、報告「基本資料」加「地點」，點了開 Apple 地圖。手錶錄音在 iOS 還沒有地點（Android 由手機代為定位）。模擬器驗過：地名組法 4 例、放一個 geo.json 後詳情與基本資料都顯示地點；真的定位（要使用者允許定位）還沒驗。

## 不到 1 秒的錄音不送辨識（1.1.94）

- 手機錄音（會議、飲食口述）一按就停、不到 1 秒（`PhoneRecordingService.MIN_RECORDING_SEC`）：整筆刪掉、不送辨識，錄音卡顯示「錄音不到 1 秒，沒有送出辨識」，手錶遙控的會收到「錄音不到 1 秒，已取消」。「拍＋說」有照片時口述太短 → 當成只有照片照樣處理。
- 手錶錄音也一樣：`ProcessMeetingWorker` 開頭在分段全部到齊後檢查 audio.wav 長度，不到 1 秒就刪掉。匯入的檔案、通話錄音不受影響。iOS 要照做。

- **iOS 已跟上**：`MeetingStore.assemble` 分段接好後檢查（`minRecordingSec` = 1.0，只看 source phone / watch）：飲食有照片 → 刪錄音、長度 0，當成只有照片處理；否則整筆刪掉，手機錄的 `PhoneRecorder.tooShort()` 顯示「錄音不到 1 秒，沒有送出辨識」（跟「沒有錄到聲音」同一個提示），手錶錄的回報「錄音不到 1 秒，已取消」。模擬器用兩筆 0.5 秒的假分段驗過（手錶那筆刪掉、飲食＋照片那筆變只有照片）。

## 水墨背景畫（1.1.95）

- 水墨配色多一個「背景畫」選擇（設定 → 介面 → 配色選水墨才出現）：孤舟（預設）/ 荷塘 / 魚蝦 / 無，存在 `ink_bg`（boat / lotus / shrimp / none）。
- 畫是原創、程式產生的（`tools/ink_bg.py`，`設計/水墨背景` 有 SVG 原圖與比較圖；一共畫了 5 張，使用者選了孤舟寒江、荷塘清趣、魚蝦戲水）。App 用 1080×2400 WebP（`res/drawable-nodpi/ink_bg_*.webp`，各約 20 KB），鋪滿整個螢幕（含狀態列後面），ContentScale.Crop，捲動時背景不動。
- 有背景畫時：卡片底色 86% 不透明、分頁列 90%，畫隱約透出來，字照樣清楚。畫都放在邊角與下方，日期、標題、卡片位置留白。
- iOS 要照做：同樣三張 WebP（iOS 14+ 支援）、同樣的選擇與透明度。

- **iOS 已跟上**：`ios/Dylan/InkBG/ink_bg_{boat,lotus,shrimp}.webp`（同三張，複製過來）；`InkBg`（`ink_bg`，預設 boat）與 `AppBackdrop`（`ThemeStore.swift`：宣紙底＋畫 aspect-fill 鋪滿含狀態列、捲動不動）；`Brand.painting` / `Brand.cardFill`（卡片 86%）、兩種分頁列 90%。設定「配色」下面水墨才出現「背景畫」分段＋說明。換畫不會整頁重畫（只有切到 / 切離「無」才重畫，因為卡片透明度要變）。模擬器看過三張畫、快錄與總覽。

## 深色改成浮世繪（1.1.96）

- 配色「深色」（dark）拿掉，改成「浮世繪」（`ukiyoe`）。舊的 `theme_mode = dark` 讀成浮世繪。手錶照樣是深色（OLED 省電；只有水墨會推到手錶）。
- 浮世繪是淺色版畫風：和紙底 #F0E4C8、卡片 #F8F2E4、墨字 #2A2522、重點普魯士藍 #1E3A6E、錄音朱紅 #C8402B、成功松綠 #2F5E4C；卡片圓角 12；標題用襯線字（跟水墨一樣）。`UkiyoePalette`、`Brand.isUkiyoe`。
- 背景畫（設定 → 介面 → 配色選浮世繪才出現「背景畫」）：藝伎武士（預設）/ 靈鶴 / 大阪城 / 無，存在 `ukiyoe_bg`（geisha / crane / castle / none）。圖在 `res/drawable-nodpi/ukiyoe_bg_*.webp`（1080×2400，各約 40–50 KB），原創、`tools/ukiyoe_bg.py` 產生（`設計/浮世繪背景` 有 6 張 SVG 與比較圖；使用者選了後三張）。
- 畫的最上面一道普魯士藍漸層（版畫天空），只在狀態列後面 → 有背景畫時狀態列改淺色圖示（導航列維持深色圖示）。`AppTheme.art` 是目前要鋪的畫（水墨或浮世繪），卡片 86%、分頁列 90%。
- iOS 要照做。

- **iOS 已跟上**：`ThemeMode.ukiyoe`（取代 dark；存的 dark 讀成 ukiyoe；`Palette.dark` 只剩手錶用）、`Palette.ukiyoe`（照上面的色碼，平塗、圓角 12、`Brand.serifTitles` 襯線標題、`isNew` 樣式）、`UkiyoeBg`（`ukiyoe_bg`，預設 geisha）、`ios/Dylan/InkBG/ukiyoe_bg_*.webp`；`ThemeStore.art` 是要鋪的畫（水墨或浮世繪）。狀態列：SwiftUI 沒有 API，`StatusBar`（ThemeStore.swift）把根 UIHostingController 的 preferredStatusBarStyle 換成依 `statusOnDark` 回答（有浮世繪畫 → 淺色圖示，其他 → 深色圖示）。模擬器看過：舊的深色開起來是浮世繪＋藝伎武士、狀態列白字；選「無」變回深色字。

## 飲食內容改成清單（1.1.97）

- 報告裡有「熱量」欄的表格（飲食內容）不再一欄一欄排（7 欄擠在一行，「來源」被擠成一字一行），改成一張卡、一樣食物一段：「食物名 …… 366 大卡」／「一碗 · 約 200g · 蛋白質 6g · 碳水 82g · 脂肪 1g」／來源小標籤（食藥署：名稱＝綠、營養標示＝藍、AI 估算＝灰）。欄位用表頭名字找，舊格式（沒有來源欄）也適用。`ReportViews.kt` 的 `FoodTable`。
- 其他表格的欄位改成自動換行（FlowRow），不會擠成直排。iOS 要照做。

- **iOS 已跟上**：`ReportViews.swift` 的 `FoodList`（`ReportView.grouped` 把有「熱量」欄的連續列合成一張卡；欄位用表頭名字找；來源標籤 食藥署＝success、營養標示＝blue、AI 估算＝faint，各 10% 底色）。其他表格 iOS 本來就是一列一張小卡、一欄一行（不會擠），沒改。模擬器看過三種來源。

## 首頁問候（1.1.98）

- 快錄的「今天」頁大標不再寫「今天」，改成有溫度的問候（`ui/Greeting.kt` 的 `greetingFor`）：大標依時段（早安 / 週末早安 / 午安 / 下午好 / 傍晚好 / 晚上好 / 夜深了），下面一句小字依時段、星期與今天記了什麼（中午沒記午餐會問「午餐吃了嗎？」、晚上說今天記了幾筆、吃超過目標會提醒明天清淡一點、星期五打氣、週末放鬆）。同一天同一時段固定同一句，每分鐘檢查時段。總覽排布的主頁也用同一套（取代原本只有早安 / 午安 / 晚安）。底部分頁名字照樣叫「今天」。測試：`GreetingTest`。iOS 要照做。

- **iOS 已跟上**：`ios/Dylan/Greeting.swift`（`Greeting.at` 照抄 greetingFor 的時段、星期與句子，依一年中的第幾天挑；`Greeting.now` 照 Android 呼叫處算今天的記錄數、午／晚餐、熱量）。快錄的「今天」：日期、問候大標（水墨／浮世繪用襯線字）、一句小字；總覽主頁：大標、小字、日期（淡色）。都用 `TimelineView(.everyMinute)` 每分鐘更新。GreetingTest 的案例用 Swift 跑過全過；模擬器看過兩種排布。

## 暖和改成台灣（1.1.99）

- 配色「暖和」（warm）拿掉，改成「台灣」（`taiwan`），以科技為主題的夜藍深色——現在唯一的深色配色。舊的 `theme_mode = warm` 讀成台灣。手錶照樣深色。
- 配色 `TaiwanPalette`：底 #08121D、卡片 #12212F、surfaceHigh #1A2D3E、surfaceHigher #223A4F、框線 #24435A、重點晶片青 #2CC8D8、藍 #4F9BE0、梅花紅 #E0475B（錄音、violet）、成功 #3DD6A0、警告金 #E9B949、錯誤 #FF6B6B、字 #E6F1F5 / #8FA8B6 / #5E7685；主要按鈕底 #178C9C（白字）。卡片圓角 16、單色重點。`Brand.isDark`＝台灣。
- 深色：狀態列、導航列都用淺色圖示（`WristAI_ProjectTheme(onBarsChanged = (statusDark, navDark))`；浮世繪有背景畫時只有狀態列淺色）。
- 背景畫（配色選台灣才出現「背景畫」）：島嶼電路（預設）/ 晶圓 / 夜城 / 無，存在 `taiwan_bg`（island / wafer / city / none）。圖在 `res/drawable-nodpi/taiwan_bg_*.webp`（各約 26–34 KB），原創、`tools/taiwan_bg.py` 產生，不用任何公司名稱或商標；島嶼電路是照實際海岸經緯度畫的點陣台灣、北回歸線、台北—新竹—台中—台南—高雄的連線。
- iOS 要照做。
- **iOS 已跟上**：`ThemeMode.taiwan`（取代 warm；存的 warm 讀成 taiwan）、`Palette.taiwan`（照上面色碼，isDark、accent 單色 #178C9C 給按鈕、cyan #2CC8D8 當重點字色、圓角 16、isNew 樣式）、`TaiwanBg`（`taiwan_bg`，預設 island）、`ios/Dylan/InkBG/taiwan_bg_*.webp`。深色 → `preferredColorScheme(.dark)`（home indicator 淺色），`statusOnDark` 對任何深色配色都成立（狀態列淺色圖示）。模擬器看過三張畫、狀態列白字，換回浮世繪後狀態列照舊。

## 國語夾英文、更多辨識語言（2026-11-26）

- **國語夾英文**：Gemini 共用規則（`GeminiReportGenerator.TYPE_RULES`）加一句：夾在中文裡的英文（術語、縮寫、人名、品牌、產品名）逐字稿和報告都照原文寫英文，不翻譯、不寫成同音字。國語提示文字改「夾雜英文也選這個」。
- **其他語言**（`SpeechLang`）：英語 `en`、日語 `ja`、韓語 `ko`、粵語 `yue`、越南語 `vi`、泰語 `th`、印尼語 `id`；每個有一字標籤（英日韓粵越泰印）、手機內建辨識的 `locale`（en-US…）。`other` = 這七個。
  - 選單：國語 / 台語 / 客語 / 其他 ▾（分段控制第 4 格，點了開選單，選了顯示語言名；`Segmented(reclickable=)`）。主頁、飲食、詳情頁共用 `LangPicker`。
  - Gemini：`geminiNote` 要求逐字稿照原文（粵語寫粵語字），標題、摘要、報告用繁體中文。
  - 大D的主機：health 多 `langs`（陣列）才送；舊主機沒有這欄 = 只有 zh/nan/hak，其他語言會寫「大D的主機還不能辨識英語。這次改用 Gemini。」
  - 舊的手機內建辨識（Claude 路線）用該語言的 locale。三星錄音機（Galaxy AI）的語言在錄音機自己設。
  - 手錶：`SpeechLangs.LANGS` 加同一組代號，手錶語言頁自動列出。
- **待做**：主機端（Mac mini）英語等語言的辨識（例如 Whisper / SenseVoice）與國語夾英文的改善、Qwen 報告提示詞同樣規則；iOS 跟上。
- **iOS 已跟上**：`SpeechLang`（UIMode.swift）加 en/ja/ko/yue/vi/th/id，`label` / `short` / `tag` / `hint` / `locale` / `spoken` / `other` 照 Android；`geminiNote` 一字不差（`GeminiClient.prompt` 改用它）；`typeRules` 加國語夾英文那兩行。`LangPicker`：國語 | 台語 | 客語 | 其他 ▾（第 4 格是 SwiftUI `Menu`，選了顯示語言名、打勾），主頁兩種排布、詳情頁共用。大D的主機：`Health.langs`（沒有這欄 = zh/nan/hak），不支援就不送，engineNote 寫「大D的主機還不能辨識英語。這次改用 Gemini。」。Apple 備援：其他語言用該語言的 locale 辨識（`AppleAI.transcribe(locale:)`）。逐字稿語言標籤顏色：其他語言用重點色。Apple Watch：iOS 手錶沒有語言清單，手錶錄音用 iPhone 上選的語言，新語言自動適用。模擬器看過選單、選粵語後那格與說明。
- **主機（local-ai/server.py）已做一部分**：health 加 `"langs"`（目前 zh/nan/hak，跟以前一樣；要加別的語言時把代號放進 `LANG_ASR`）；Qwen 報告共用規則加國語夾英文那兩行（多一句：辨識把英文聽成同音中文字時依上下文改回英文）。還沒做：多語言語音辨識（Whisper / SenseVoice 要先下載模型、實測），以及 FunASR paraformer-zh 夾英文的實測。主機上執行中的那份要重新部署、重啟才生效。

## 報告語言、介面語言設定（2026-11-26）

- **報告語言**（`ReportLang.kt`，設定 `report_lang`：auto / zh / en / ko；auto = 跟介面語言：中文 → 繁中、韓文 → 한국어、其他 → English）。`ProcessMeetingWorker.doWork` 開頭設 `ReportLang.active`。
  - 提示詞只有中文一份：`ReportLang.localize` 換章節標題（## Summary / ## 요약…）、表頭、說話者代號（Speaker A / 화자 A）、「未知」「無」等固定字，最後加一條「報告語言」規則（覆蓋前面的繁中要求；逐字稿照原文；NUTRITION/ITEM 行維持中文欄位與餐別）。Gemini（`generateContent` 的 systemInstruction）、組合式（`Pipeline`）、Claude（`ReportGenerator`）都套用。
  - 解析三種語言都認得：`Speakers.namesFromReport`、`ReportViews`（說話者表格、摘要「無」、飲食表格欄位）、`LongMeeting` 名單。
  - 飲食報告 App 自己產生的「飲食內容 / 合計 / 來源 / 摘要」跟著報告語言（`FoodWords`）；NUTRITION 的餐別一律中文（健康同步、問候語靠它）。
  - 大D的主機：送 `X-Report-Lang`（主機還沒實作，照舊中文）。
  - 測試：`ReportLangTest`。
- **iOS 已跟上（報告語言）**：`ios/Dylan/ReportLang.swift`（`ReportLang` 照 Android：`localize` 換章節標題、表頭、說話者代號、固定字，規則文字一字不差；`isSpeakerHeading` / `isSummaryHeading` / `isNone` / `isSpeakerColumn` / `isNameColumn` / `isSpeakerCode` / `isUnknownName`；`FoodWords` 三種語言）。Gemini 的 systemInstruction 套 `ReportLang.current.localize`；`FoodLog.finish` 用 `FoodWords`；`Speakers.names`、`fillEmptySummary`、`summarySection`、報告表格（飲食欄位別名、「None / 없음」當空白）、逐字稿說話者（Speaker A / 화자 A）三種都認得；大D的主機送 `X-Report-Lang`。Apple 裝置端整理的報告還是中文標題（Apple 模型那條沒改）。
- **主機（local-ai/server.py）已做**：讀 `X-Report-Lang`（zh/en/ko，沒有 = 繁中），`localize()` 套在 Qwen 的報告、長錄音筆記、飲食提示詞；逐字稿的說話者代號跟著換成 Speaker A / 화자 A。主機上執行中的那份要重新部署、重啟才生效。
- **介面語言**（`AppLocale.kt`）：跟系統 / 繁體中文 / English / 한국어。13+ 用系統的個別 App 語言（`LocaleManager`，`res/xml/locales_config.xml`），12 以下存設定、`MainActivity.attachBaseContext` 套用。設定頁「介面」卡最上面兩個選擇。字串抽到 `strings.xml` 的工作接著做。

## 介面多語言：繁中 / English / 한국어（2026-11-26）

- **做法**：程式照舊寫中文，顯示前查表。`shared/.../I18n.kt`：`"中文".tr`、`"今天記了 {0} 筆".trf(n)`（{0}{1} 可換順序）；查不到 → 原文。對照表 `shared/src/main/assets/i18n/strings.json`（{"中文": {"en", "ko"}}，約 920 句），**iOS 直接用同一份**。
- `I18n.lang` 在 `WristAiApp` / `WearApp` 啟動時依介面語言設好；介面語言換了 → `WristAiApp.restartIfLanguageChanged` 重啟 App（列舉、一開始就算好的字才會換；錄音中不重啟）。日期：`I18n.locale`、`I18n.weekdayShort`。
- 手錶跟手機的介面語言：`PATH_UI_LANG`（跟主題一起推），手錶存成 override，畫面開著就重開。
- **不翻的**：提示詞、存檔用的識別字（步驟名稱、餐別、`hostCleanup` 存中文原文、顯示時 `.tr`）、比對用的字（`== "…"`、`in setOf`、表格欄位名）。
- 工具：`tools/i18n_wrap.py`（把顯示用中文包成 `.tr`/`.trf`，有排除規則）、`tools/i18n_keys.py --missing`（列出沒翻的句子）。新增文字：寫中文加 `.tr`，跑 `--missing`，把英文、韓文補進 strings.json。`I18nTest` 會檢查每句都有翻、參數一致。
- 名稱：大D → Dylan；大D的主機 → Dylan Host / Dylan 호스트。
- 模擬器英文、韓文、繁中逐頁看過（今天、資料庫、飲食、設定、詳情）；App 內切語言會重啟並生效。手錶畫面沒在模擬器看（只有編譯）。
- **iOS 已跟上（介面語言）**：`ios/Dylan/I18n.swift`（`"中文".tr`、`"…{0}…".trf(x)`，查 `ios/Dylan/i18n/strings.json`——共用表的複本，改了共用表要跑 `python3 tools/i18n_ios.py --sync`）。語言設定 `ui_lang`（auto / zh / en / ko；auto 跟系統：中文 → 繁中、韓文 → 한국어、其他 → English），啟動時決定；iOS 不能自己重啟，換了會提示「從 App 切換器把大D 往上滑關掉，再重新開啟」。日期：`Fmt.locale` 啟動時設成 `I18n.locale`。設定「介面」卡最上面「介面語言」「報告語言」兩個分段選擇。
  - 工具 `tools/i18n_ios.py`：`--wrap 檔案…`（把顯示用中文包成 .tr / .trf，`\(x)` 換成 {0}；不包比對、鍵、步驟名稱、原始字串、提示詞）、`--missing`、`--sync`。iOS 獨有的 242 句已補進共用表（英文、韓文）。
  - 不翻的：提示詞（Gemini、Apple、geminiNote）、存檔的步驟名稱（aiStep；顯示時 `.tr`，排隊文字 `LocalAI.isQueueText` 中文與目前語言都認得）、比對用的字。
  - 還沒做：Apple Watch（iOS 手錶 App 還是中文；Android 手錶跟手機介面語言）、系統權限說明文字（相機、定位、健康、麥克風仍是中文，要加 InfoPlist.strings）。模擬器看過英文的主頁、錄音清單、詳情、設定，以及切回繁中；韓文沒逐頁看。

## 即時翻譯（2026-11-26，獨立一頁）

- **頁面** `ui/LiveTranslateScreen.kt`（route `translate`）：說的語言（同錄音的辨識語言）、翻譯成（繁中 / English / 한국어 / 日本語，預設介面語言，跟來源一樣就中↔英）、開始錄音並翻譯 / 開始翻譯（目前的錄音）/ 停止翻譯 / 停止錄音、字幕清單（原文小字、譯文大字，新的在下面自動捲）。入口：主頁錄音卡的「文A 翻譯」、手機錄音中卡片的「即時翻譯」（`TranslatePage.open()`）。
- **引擎** `LiveCaptions.kt`：`PhoneRecordingService` 錄音迴圈 `LiveCaptions.feed` → 佇列 → pipe → 手機內建辨識（`EXTRA_AUDIO_SOURCE` + 分段 + 暫時結果，Android 13+），一段結束 / 出錯自動接下一段，錄音不受影響。一句辨識完 → ML Kit 裝置端翻譯（`com.google.mlkit:translate`，每種語言第一次下載約 30 MB）；翻成中文是簡體 → ICU `Hans-Hant` 轉繁體。錄音停止時字幕一起停。
- 離線語音沒有那個語言：`triggerModelDownload`（系統詢問下載，同一語言這次開 App 只問一次），這次改用線上辨識，畫面寫「聲音會傳到 Google 辨識」。台語、客語、粵語不支援（手機辨識不了）。
- **手錶**：每句譯文 `PATH_CAPTION` 推給手錶，手機錄音中畫面顯示最新一句（最多 3 行）。
- 測試：debug 版可用 `adb shell am start -n com.example.wristai/.MainActivity --es debug_caption_wav <app 內的 wav> --es debug_caption_lang en --es debug_caption_target zh` 餵一個 16 kHz 單聲道 WAV（模擬器沒辦法說話）。模擬器實測：Windows TTS 英文 → 線上辨識 → 繁中譯文正確。手錶顯示沒在模擬器看過。

- **2026-11-26 修正**：三星手機預設的辨識服務可能不接受直接送錄音（Fold5 中→英沒有字幕）。`LiveCaptions.engines`：離線 → Google 線上服務（明確指定元件）→ 其他服務；同一個服務連續出錯 3 次而且一句都沒聽到就換下一個，全部不行才顯示錯誤代碼。翻譯成多了越南語（Tiếng Việt）。
- **大D的主機語言**：不再依 health 的 `langs` 擋掉英語等語言，一律送給主機（`X-Lang`），主機辨識不了會回錯誤、再改用別的引擎。

## iOS：從相簿選影片匯入（2026-11-26，只有 iOS）

- 「匯入」按鈕改成先選來源：「從檔案選（錄音、影片、文字）」或「從相簿選影片」（`ImportSources`，AudioImport.swift；主頁快錄的匯入方塊、錄音頁的「匯入」）。相簿用系統的 `photosPicker`（私密取用，不用照片權限），一次最多 5 段；`VideoFile`（Transferable）把影片複製到暫存（檔名不變，標題就是影片檔名），照一般檔案匯入：`CallInbox.mix` 只取音軌，影像不存，暫存用完就刪。
- 原本「檔案」選檔、從別的 App 分享 / 打開方式送來的影片本來就能匯入（`public.movie`）。
- 選單掛在頁面層的 background（同一個 view 上同時掛 fileImporter / confirmationDialog 會互相吃掉）。
- 模擬器驗過：相簿放一段 7 秒、有國語語音的影片 → 匯入成一筆只有 AAC 聲音的記錄 → 大D的主機產生報告（標題、摘要正確）。Android 沒有這個入口（Android 可以從相簿分享給 App）。

## iOS：即時翻譯、語言都送主機（跟 Android decfa47、9b5d011）

- **即時翻譯頁**（`ios/Dylan/LiveTranslateView.swift`，全螢幕；`TranslatePage.shared.open()`）：說的語言（同錄音的辨識語言）、翻譯成（繁中 / English / 한국어 / 日本語 / Tiếng Việt；預設介面語言，跟來源一樣就中↔英）、開始錄音並翻譯 / 開始翻譯（目前的錄音）/ 停止翻譯 / 停止錄音、字幕清單（原文小字、譯文大字，自動捲到最新）。入口：快錄主頁錄音卡「文A 翻譯」、總覽主頁「文A 即時翻譯」、兩種錄音中卡片的「即時翻譯」。
- **引擎**（`ios/Dylan/LiveCaptions.swift`）：PhoneRecorder 的音訊 tap → `LiveCaptions.feed` → `SFSpeechAudioBufferRecognitionRequest`（先手機上辨識；手機上起不來就改 Apple 線上辨識並寫說明），句尾標點或停頓 1.5 秒算一句，一段結束自動接下一段。翻譯用 Apple「翻譯」框架：`LiveTranslateHost` 掛在 App 最外層，iOS 18 以上 `.translationTask` 拿 session 一句句翻（第一次用某語言系統會問要不要下載）；iOS 17 以下只顯示原文。錄音停止字幕一起停。連續 3 次出錯又沒聽到東西就停字幕並顯示「手機的語音辨識沒辦法邊錄邊辨識（錯誤 N）…」（沒人說話 1110、取消 216/301 不算）。台語、客語、粵語不支援。權限：`NSSpeechRecognitionUsageDescription`（繁中，第一次開始翻譯時問）。
- **手錶**：每句譯文 `pathCaption`（/wristai/caption）推給手錶，手錶錄音頁的狀態文字顯示「文A 最新一句」。
- **語言都送大D的主機**：iOS 拿掉 `Health.langs` 的擋（主機辨識不了會回錯誤，照常改用別的引擎）；詳情頁選其他語言時多一行「逐字稿照X原文寫，報告用報告語言；大D的主機辨識不了時會改用其他引擎。」
- **主機（server.py）**：en/ja/ko/vi/th/id/yue 交給 Breeze（Whisper）並指定 Whisper 語言（粵語用 zh），不再用國語的 FunASR；這些語言的逐字稿不做簡轉繁（粵語照轉）；health 的 langs 列出全部。英文等的準確度沒實測；主機上執行中的要重新部署才生效。
- 測試：debug 版放 `Documents/debug_caption.json`（{"file","lang","target"}）會把那個音檔照實際速度餵進字幕。模擬器的語音辨識起不來（kLSRErrorDomain 300，手機上辨識與線上都一樣），所以字幕與翻譯只在模擬器驗過頁面、失敗時的說明與改線上；真的字幕要在 iPhone 上試。

## 膳食纖維（2026-11-27）

- `Nutrition.fiber`（JSON `fiber`，舊記錄 0）、`Item.fiber`、`FoodDb.Entry.fiber`（tfnd.json 的 `fib`）。提示詞 NUTRITION / ITEM 行多 `fiber=<整數>`，規則「fiber 是膳食纖維（克），算在碳水裡面，不能超過碳水」。
- `fit` 縮小碳水時纖維同比例縮、不超過碳水；表格多「纖維(g)」欄（三語），合計行「碳水 Xg（纖維 Xg）」。AI 自己寫的表格有纖維欄也會讀。
- 顯示：詳情頁頂端第 4 格「纖維」（舊記錄顯示 —）、今日飲食多一行、飲食清單每筆（0 不顯示）。Health Connect 寫入 `dietaryFiber`。
- 營養素不能超過重量：`FoodLog.fit`（蛋白質＋碳水＋脂肪 ≤ 克數，超過依比例縮、熱量照 4/4/9 重算），ITEM 與 AI 自己寫的表格（`fixTable`，克數從份量欄）都套用；舊記錄啟動時修一次（`nutrition_repair_v2`）。

## iOS：飲食營養修正（跟 Android bc57634、d183692）

- `GeminiClient.parseNutrition` 照 Android 放寬：全形＝／：、逗號分隔、中文欄位（餐別／熱量／蛋白質／碳水／脂肪）、粗體 **NUTRITION：**；`stripNutrition` 一樣認得這些寫法。
- `FoodLog`（FoodDB.swift）：`fit`（蛋白質＋碳水＋脂肪超過重量就依比例縮小、熱量照 4／4／9 重算）套在 AI 與營養標示的 ITEM 行；沒有 ITEM 行（大D的主機、舊格式）時 `fixTable` 修 AI 寫的表格（份量欄的克數、拿掉 AI 合計列、換掉合計那一行、摘要重寫），沒改就 `fillFromTable` 用表格加總補 NUTRITION 缺的數字。兩個飲食提示詞加「每樣食物的蛋白質＋碳水＋脂肪（克）加起來不能超過它的重量…」。
- 開 App 時修舊記錄一次（`MeetingStore.repairNutrition`，`nutrition_repair_v2`；存檔時健康同步跟著更新）。
- 主機 server.py 的 FOOD_SYSTEM 加同一條規則（要重新部署才生效）。
- 驗證：照 NutritionParseTest 的 11 個情況用 swiftc 跑過全部通過；模擬器開一次 App，修正跑過、旗標設好，三筆飲食記錄不需要改。

## iOS：膳食纖維（跟 Android c725a7b）

- `AppleAI.Nutrition.fiber`（舊記錄沒有 → 0；自訂解碼，缺欄位不會整筆讀不到）、`FoodDB.Entry.fiber`（tfnd.json 的 fib）、`FoodLog.Item.fiber`；`fit` 之後纖維用 `fiberWithin` 跟碳水同比例縮、不超過碳水；`fixTable` / `fillFromTable` 讀寫纖維欄。
- 提示詞：NUTRITION、ITEM 行加 `; fiber=<整數>`，資料庫版加「fiber 是膳食纖維（克），算在碳水裡面，不能超過碳水。」；舊版表格加纖維(g)、合計「碳水 Xg（纖維 Xg）」。`parseNutrition` 認 fiber / 纖維 / 膳食纖維。App 產生的表格多一欄（纖維(g) / Fiber (g) / 식이섬유(g)），合計三種語言照 Android。
- 畫面：詳情頁第 4 格「纖維」（0 顯示 —）、飲食頁今日多「纖維」、清單與飲食清單卡 > 0 才顯示「纖維 Xg」。順便修：飲食清單卡找欄位的鍵被翻譯（`cell(r, "份量".tr)`），英文 / 韓文介面會找不到中文報告的欄位。
- Apple 健康：另外要 dietaryFiber 權限；之前只給四種的人照常同步（只是沒寫纖維），有權限才寫。刪除也一起刪纖維。
- 主機 FOOD_SYSTEM：NUTRITION 行、表格欄、合計行加纖維，加同一條規則（要重新部署）。
- 驗證：swiftc 跑 16 個情況（含 Android 新增的纖維測試、舊記錄沒有 fiber 的解碼）全過；模擬器看過飲食頁纖維列、舊記錄詳情第 4 格「—」。

## 三大主軸各自的頁面（Android；iOS 還沒跟，等使用者說了才通知 Mac）

三大主軸：錄音報告、飲食記錄、即時翻譯，一樣重要，各有一個分頁。
- 分頁列：快錄是「今天｜錄音｜（錄音鍵）｜飲食｜翻譯」，設定在「今天」右上的齒輪；總覽是「主頁｜錄音｜飲食｜翻譯｜設定」。原本的「檔案」分頁和「資料庫 → 管理」併進「錄音 → 管理」。舊的路由（tab:files、files、translate）會自動轉過去。
- 每個主軸頁上面一排小分頁（`ui/Hub.kt` 的 `HubScaffold`；選到哪個記在 `Hubs`）：
  - 錄音：錄音（清單，右上「匯入」）｜分析｜管理（檔案管理）｜設定。
  - 飲食：今天｜記錄｜分析｜設定。
  - 翻譯：翻譯（即時字幕）｜記錄｜分析｜設定。
- 分析：
  - 錄音：近 7／30 天的段數、總長、平均，每天分鐘長條圖。待辦總覽（`ActionItems` 讀報告的「待辦事項」表格或條列，三種語言；打勾記在本機）。來源、常出現的人、失敗要重試的；科技模式多顯示 AI 模型。
  - 飲食：平均大卡、未超標天數、有記錄的天，每天熱量長條（虛線＝目標）。蛋白質、纖維對目標；熱量來源比例（4/4/9）；餐別。
  - 翻譯：次數、時間、句數，每天分鐘，語言組合、翻譯完成率。
- 設定拆開（`ui/SettingsCards.kt`，都是改了就存；共用設定頁拿掉「儲存設定」按鈕）：
  - 錄音：錄音語言、報告語言、目前 AI（連到全部設定）、通話、Galaxy AI、地點、手錶遙控。
  - 飲食：每日目標（熱量、新的蛋白質 60g、纖維 25g）、健康同步、拍照地點。
  - 翻譯：預設翻成（存起來）、字幕大小、手錶字幕開關、自動存記錄開關、離線翻譯模型（ML Kit 下載／刪除，英文內建）。
  - 共用設定頁多一張「功能設定」卡，連到三個主軸的設定。
- 翻譯記錄（新）：`TranslationStore`，每次開始到停止存成 files/translations/<id>.json（原文、譯文、語言、時間、同時錄的那段錄音）。停止時存一次，3 秒後補存晚到的翻譯。可以查看、分享文字、刪除、打開那段錄音的報告。
- 飲食記錄可以匯出 CSV（含 BOM，Excel 打得開），用系統分享。
- 測試：`ActionItemsTest`；模擬器看過三個主軸的各小分頁，用 zh.wav 跑國語翻英文，翻譯記錄有存到也能打開。
- 還沒做：手錶上開始翻譯的入口（現在手錶只顯示最新一句字幕）；iOS 同樣的頁面。

## 即時翻譯：手機支援的語言都能用（Android；iOS 還沒跟）

- 語言不再寫死：`TranslateLangs` 直接問手機。
  - 翻成：ML Kit 全部（`TranslateLanguage.getAllLanguages()`，59 種）。
  - 說的：Android 13+ 用 `checkRecognitionSupport` 問離線辨識器和 Google 辨識服務，分成離線已裝／可下載離線／線上，而且要 ML Kit 翻得了。模擬器問到 53 種。問不到時用 `DEFAULT_TAGS`。
  - 說的語言存辨識代碼（zh-TW、cmn-Hant-TW、en-GB…，可以選地區口音）；翻成存 ML Kit 代碼。粵語、cmn 都當中文翻，iw→he、in→id、fil→tl、nb→no。
  - 預設口音：最近用過 → 手機地區 → 台灣（中文）→ 常見的（en-US…）。
- 翻譯的「說的語言」跟錄音語言分開（`translate_source`；第一次沿用錄音語言）。
- 兩種聽法（`LiveCaptions.start(..., fromRecording)`）：
  - 只翻譯：辨識器自己開麥克風，一句結束接下一句。不用 Android 13，比較省電。安靜時的 no match／斷線就繼續聽。
  - 錄音中／勾「同時錄音」：照舊把錄音的聲音送進辨識器，錄完照常產生報告。
- 翻譯頁：
  - 上面「說的 ⇄ 翻成」兩個按鈕，標離線／線上。點下去是選語言的底部面板：搜尋、最近使用、全部、地區口音，翻成的語言標「已下載」。
  - ⇄ 對調方向，翻譯中也可以，字幕保留，記錄各自分開存。
  - 工具列：A−／A+（四段字大小）、原文開關、朗讀開關、放大、清除。
  - 每句可以按 🔊 念、長按複製。
  - 放大：全螢幕只顯示最新一句，可以翻轉 180° 給對面的人看，也可以對調、朗讀。
  - 翻譯中螢幕保持亮著。
- 朗讀：`Speaker`（手機 TextToSpeech）。正在念的時候聽到的聲音不當成新的一句。
- 翻譯 → 設定，分成：
  - 語言：說的、翻成。
  - 語音辨識：目前口音的狀態、「下載離線語音」（系統視窗，使用者自己按）、離線／可下載／線上各幾種、允許線上辨識開關。
  - 顯示：字大小、原文、螢幕亮著、手錶字幕。
  - 朗讀：自動朗讀、速度、這個語言能不能念、試聽。
  - 錄音與記錄：同時錄音、自動存記錄。
  - 離線翻譯模型：目前用到的和已下載的，下載／刪除／下載其他語言，只用 Wi-Fi 下載。
- 不再自動跳系統的「下載離線語音」視窗（之前每個語言會跳一次），改成設定裡的按鈕。
- 測試：`TranslateLangsTest`。模擬器跑過：選語言面板（53 種、中文 5 種口音）、cmn-Hant-TW → 英文、放大＋對調、只翻譯（麥克風）模式可以開始和停止。
