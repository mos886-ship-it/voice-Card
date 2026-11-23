# 大D iOS：真機測試準備與清單

模擬器測不到的東西（真的麥克風、手錶 → 手機的檔案傳輸、背景處理、行動網路連自架 Mac）都要真機。
這份是第一次裝到 iPhone ＋ Apple Watch 的步驟，以及要逐項打勾的測試。

## 0. 專案已經準備好的部分

- Bundle ID 統一在專案設定 `DYLAN_BUNDLE_ID`（目前 `tw.dylan.wristai`，手錶自動是 `tw.dylan.wristai.watchkitapp`，配對設定 `WKCompanionAppBundleIdentifier` 也跟著改）。
  Xcode 說這個 ID 不能用（被別人註冊走）時，只要改這一個值：Project「Dylan」→ Build Settings → 搜尋 `DYLAN_BUNDLE_ID`。
- 手機與手錶 App 都已用真機架構編譯過（未簽章）。手錶 App 包在手機 App 裡，裝手機時會一起裝到配對的手錶。
- 權限說明：手錶麥克風、手機區網（連 Mac）都有；App 允許 http（連固定 IP 的自架 Mac 需要）。
- 模擬器專用的 sendMessage 分段傳檔只在模擬器編譯，真機走正式的 `transferFile`。

## 1. 你要自己做的（需要 Apple ID 與實體裝置）

1. Xcode → Settings → Accounts → 加入你的 Apple ID（免費帳號也可以）。
2. 打開 `ios/Dylan.xcodeproj`，兩個 target（Dylan、DylanWatch）各自：Signing & Capabilities → Team 選你的帳號，保持「Automatically manage signing」。
3. iPhone 用線接 Mac → 手機上「信任這台電腦」。
4. 開「開發者模式」：iPhone 設定 → 隱私權與安全性 → 開發者模式 → 開啟並重新開機；手錶：手錶的設定 → 隱私權與安全性 → 開發者模式（手機先接上 Xcode 才會出現）。
5. 手錶要和這支 iPhone 配對、開著、在充電座上或手腕上解鎖，跟 Mac 同一個 Wi-Fi（第一次 Xcode 會花幾分鐘準備手錶）。
6. Xcode 上方選 scheme「Dylan」、裝置選你的 iPhone → Run。第一次會要求在 iPhone 設定 → 一般 → VPN 與裝置管理 信任你的開發者憑證。
7. 手錶：用手機的「Watch」App → 我的手錶 → 下面「可安裝的 App」裝「大D」（如果沒有自動安裝）。或在 Xcode 選 scheme「DylanWatch」＋你的手錶 → Run。

可以裝的手機：iOS 17 以上（Xcode 27 不能再裝到 iOS 16 的手機，例如 iPhone X；App 本身最低支援 iOS 16）。
已實測：iPhone 15 Pro Max（iOS 26.7.1）安裝、手機錄音、Gemini 報告 OK；連自架 Mac 尚未在 iPhone 上確認（Mac 記錄裡沒有收到手機的請求，下次先檢查 App 的位址與存取碼有沒有按「儲存」）。
iPad Pro 11 吋第 2 代（iPadOS 27.2，iPhone 相容模式）：安裝、手機錄音 → 固定 IP → 自架 Mac（FunASR＋Qwen3-8B）OK：35 秒錄音，辨識 4 秒、報告 23 秒。

免費帳號的限制：裝的 App 7 天後失效（重新 Run 一次即可）；同時最多 3 個 App ID。

## 2. 自架 Mac 的連線設定（在手機 App 裡）

- 設定 → 這台 Mac（自架）
  - 在家（同一個 Wi-Fi）：位址 `http://Dylan-Mac-mini.local:8765`（第一次會問「允許尋找區域網路上的裝置」，要按允許）。
  - 出門（行動網路）：位址 `http://<固定 IP>:48765`（固定 IP 見路由器設定，不寫在 repo）。
  - 存取碼：在 Mac 終端機執行下面這行，會直接複製到剪貼簿（同一個 Apple ID 的通用剪貼簿會帶到 iPhone），到 App 按「更換」貼上：
    `python3 -c "import json;print(json.load(open('/Users/mac/dylan-local/config.json'))['token'],end='')" | pbcopy`
- 按「測試連線」→ 應該顯示「連線成功：FunASR…、Qwen3-8B…」，下面出現兩個模型選單。
- 打錯存取碼 10 次會封鎖那個 IP 一小時：Mac 上重啟服務就解除 `launchctl kickstart -k gui/$(id -u)/com.dylan.localai`。

## 3. 測試清單

| # | 測試 | 怎麼做 | 預期 |
|---|---|---|---|
| 1 | 安裝 | 手機 Run，手錶出現「大D」 | 兩邊都能打開；手機主頁「手錶已連線」 |
| 1b | 手機錄音 | 主頁按「開始錄音」（或「記錄飲食」），錄 1 分鐘再按「停止」 | 第一次問麥克風權限；錄音中可鎖螢幕；停止後「錄音」頁出現新記錄 → 完成；來源顯示「手機」 |
| 2 | 手錶錄音權限 | 手錶按「開始錄音」 | 第一次問麥克風權限；允許後開始計時 |
| 3 | 短錄音（1 分鐘，兩個人講話） | 錄完按停止 | 幾秒內手機「錄音」頁出現新記錄 → 處理中 → 完成；報告有分說話者；手錶顯示「報告完成」 |
| 4 | 自架 Mac（家裡 Wi-Fi） | 設定好 Mac 位址再錄一段 | 記錄的「基本資料」顯示「這台 Mac：FunASR ＋ Qwen3-8B」 |
| 5 | 自架 Mac（行動網路） | 手機關 Wi-Fi、位址用固定 IP，再錄一段 | 一樣完成；Mac 的 `~/dylan-local/server.log` 看得到來源 IP |
| 6 | Mac 連不到時的備援 | Mac 服務停掉或位址打錯，錄一段 | 改用 Gemini（有金鑰時）或 Apple；不會卡住 |
| 7 | 背景處理 | 錄完立刻鎖手機 / 切到別的 App 5 分鐘 | 回來時報告已完成（或繼續處理），不需要重來 |
| 8 | 長錄音（≥30 分鐘） | 手錶錄 30 分鐘（螢幕暗掉也要繼續錄） | 分段傳到手機、合併成一筆；手錶上的錄音檔在手機確認收到後才刪 |
| 9 | 錄音中被打斷 | 錄音時打電話給手錶 / 開 Siri | 結束後錄音繼續或妥善停止，已錄的不會不見 |
| 10 | 飲食 | 手錶按「記錄飲食」說「午餐吃了一碗牛肉麵和燙青菜」 | 飲食頁出現，有熱量與蛋白質 |
| 11 | 換模型 | 設定頁把語音辨識換成 Breeze-ASR-26 再錄一段 | 載入中提示 → 完成；記錄顯示「Breeze-ASR-26 ＋ Qwen3-8B」 |
| 12 | 台語 | 用台語說一段（或放台語影片給手錶錄），分別用 FunASR 和 Breeze | 比較逐字稿準確度，決定預設 |
| 13 | Apple AI（iOS 26 以上的手機） | 設定頁看「Apple Intelligence」「繁中語音辨識」狀態；開「不上傳到雲端」錄一段 | 模型下載完後能在手機上產生逐字稿與重點 |
| 14 | 主題 | 設定換深色 / 淺色 / 水墨 | 手機與手錶一起換 |
| 15 | 耗電 | 錄 1 小時後看手錶電量 | 記下掉了多少，供之後調整 |

## 4. 出問題時要給的資料

- 手機：Xcode → Window → Devices and Simulators → 你的 iPhone → Open Console，篩選「Dylan」。
- 手錶傳檔：手機 App 主頁的連線狀態，以及手錶上的待傳數量。
- 自架 Mac：`tail -50 ~/dylan-local/server.log`。
