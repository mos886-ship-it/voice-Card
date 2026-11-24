# Google 雲端硬碟備份與取回（Android，2026-11-24）

## 使用者看到的

設定 →「Google 雲端硬碟備份」：

- **連結 Google 雲端硬碟**：Google 自己的畫面選帳號、按允許。App 只拿 `drive.file` 權限：只看得到、改得到 **App 自己建立的檔案**，看不到雲端硬碟裡其他東西。
- 雲端硬碟會多一個資料夾「**大D 錄音備份**」，每筆錄音一個 zip：`<編號> <標題>.zip`，裡面是 meta.json（標題、說話者名字、營養…）、report.md、transcript.txt、geo.json 等，加上錄音 audio.m4a（16 kHz / 48 kbps，一小時約 22 MB）。
- 選項：**備份錄音檔**（關掉只備份報告和逐字稿）、**報告完成後自動備份**、**只用 Wi-Fi 自動備份**（預設都開）。
- **立即備份**：只傳新的或有改過的（zip 的 appProperties 記內容指紋）。
- **從雲端取回**：只補回手機上沒有的錄音，不會蓋掉手機上的；錄音解回 audio.wav，可以播放、重新產生報告。
- 手機上刪掉錄音**不會**刪雲端硬碟的備份。**取消連結**會撤銷 App 的權限，雲端硬碟裡的檔案留著。

程式：`app/.../backup/DriveBackup.kt`（授權、Drive REST、打包 / 解開、備份 / 取回）、`backup/BackupWorker.kt`（WorkManager，App 關掉也會做完）、`ui/BackupSettings.kt`。報告完成時 `MeetingStore.update` 自動排一次備份。單元測試 `DriveBackupTest`。

## 一次性設定：在 Google Cloud 登記這個 App（要用你自己的 Google 帳號做）

沒登記之前，按「連結」會顯示「這個 App 還沒在 Google Cloud 登記」。程式裡**不用放任何金鑰**：Android 是用「套件名稱＋簽名指紋」認 App。

1. 打開 https://console.cloud.google.com → 上方選單「建立專案」（名稱例如「大D」）。
2. 「API 和服務」→「程式庫」→ 搜尋 **Google Drive API** → 啟用。
3. 「Google Auth Platform」（舊名「OAuth 同意畫面」）→ 開始設定：
   - 應用程式名稱「大D」、支援電子郵件填你自己的；目標對象選 **外部**。
   - 「目標對象」→ **測試使用者** → 加入你要用來備份的 Gmail（測試階段只有名單裡的人能連結）。
   - 「資料存取」→ 新增範圍 → 勾 `.../auth/drive.file`（「查看、編輯、建立及刪除您使用這個應用程式所使用的 Google 雲端硬碟檔案」）。
4. 「用戶端」→ 建立用戶端 → 類型 **Android**：
   - 套件名稱：`com.example.wristai`
   - SHA-1 憑證指紋：在專案資料夾執行 `.\gradlew :app:signingReport`，抄 `Variant: debug` 下面的 `SHA1`。
     （這台 Windows 的 debug 金鑰：`96:6A:87:8B:B0:E8:6E:6A:F5:88:88:D1:04:5F:35:B3:3F:18:48:B8`。換電腦編譯、或之後用正式簽名金鑰，要再加一個用戶端。）
5. 存好就可以用，不用改程式、不用重裝 App（Google 那邊生效可能要幾分鐘）。

注意：
- 專案在「測試」狀態時，Google 給的授權 **7 天後會失效**，到時 App 會顯示「要重新授權」，按一次「連結」就好。要一直有效，把專案「發布」成正式版；`drive.file` 是非敏感權限，通常不需要 Google 審查。
- 上架前改 applicationId（`com.example.*` 不能上架）時，要用新的套件名稱再建一個 Android 用戶端。

## 還沒做 / 未驗證

- **還沒用真的 Google 帳號實測**（模擬器沒有登入 Google 帳號，需要上面的一次性設定）。已驗證：編譯、單元測試（打包 / 解開、防止 zip 寫到資料夾外、增量指紋、檔名、Drive 清單解析）、設定頁顯示、按「連結」會正確打開 Google 的帳號畫面。
- iOS 還沒有這個功能。
- 備份的檔案沒有另外加密（存在使用者自己的雲端硬碟，靠 Google 帳號保護）。
