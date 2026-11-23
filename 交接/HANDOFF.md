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
- **自架 Mac**：安裝 `bash local-ai/setup.sh`，說明見 `local-ai/README.md`。服務登入時自動啟動（LaunchAgent `com.dylan.localai`），存取碼在 `~/dylan-local/config.json`（不進 repo）。背景傳輸遇到連不到的主機不會失敗、只會一直等，所以送出前先做 5 秒的 health 檢查；Mac 用自己的背景 session，總時限 1 小時。
- 還沒做：手錶報告書清單 / 明細、手錶今日熱量環（`/wristai/food_today`）、長錄音分段分析（目前太長只會提示逐字稿被截斷）、GPS、搜尋、組合式引擎、處理步驟視覺化、說話者改名。
