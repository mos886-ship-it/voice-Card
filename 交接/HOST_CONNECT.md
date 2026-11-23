# 大D的主機：連線與維護指南

更新：2026-11-23

「大D的主機」是使用者自己的電腦（目前是一台 Mac mini），跑語音辨識＋分說話者＋語言模型，App 把錄音（或文字）送過去、拿回報告。免費、沒有額度、錄音不出使用者的設備。

這份文件是**唯一的連線說明**：主機怎麼設定、開機怎麼自動起來、路由器怎麼設、App 怎麼填、怎麼測、出問題怎麼查。服務本身的模型與 API 細節在 `local-ai/README.md`，App 端（iOS / Android）怎麼實作在 `IOS_TO_ANDROID.md` 第 3 節。

> **不寫進 repo 的東西**：固定 IP（看路由器的「WAN / 網際網路」狀態頁）、存取碼（主機上的 `~/dylan-local/config.json`）。這兩個只在本機和使用者手上。

---

## 1. 連線路徑

```
在家（同一個 Wi-Fi / 區網）
  App ──http──▶ Dylan-Mac-mini.local:8765  或  192.168.0.23:8765 ──▶ 服務

出門（行動網路 / 別人的 Wi-Fi）
  App ──http──▶ <固定 IP>:48765 ──▶ 路由器轉埠 ──▶ 192.168.0.23:8765 ──▶ 服務
```

在家也可以用固定 IP（路由器支援「回送」，已實測）；但這時主機看到的來源 IP 是固定 IP 本身，在家打錯存取碼 10 次會把自己家封鎖一小時（見第 7 節）。

---

## 2. 主機現況

| 項目 | 值 |
|---|---|
| 機器 | Mac mini，Apple M4，16 GB，macOS 27.2 |
| 電腦名稱 / 區網名稱 | Dylan Mac mini / `Dylan-Mac-mini.local` |
| 網路 | 有線（en0），區網 IP **192.168.0.23**（路由器 DHCP 保留），閘道 192.168.0.1 |
| 服務埠 | **8765**（聽所有網路介面） |
| 對外埠 | **48765**（路由器轉到 192.168.0.23:8765） |
| 服務程式 | `~/dylan-local/server.py`（repo 裡的副本：`local-ai/server.py`，改了要兩邊一致） |
| Python 環境 | `~/dylan-local/venv` |
| 設定 | `~/dylan-local/config.json`：`token`（存取碼）、`asr`、`llm`（目前選的模型）；權限 600 |
| 紀錄 | `~/dylan-local/server.log`（每次請求、來源 IP、處理耗時、錯誤） |
| 預設模型 | 語音辨識 FunASR、報告 Qwen3-8B（MLX 4bit） |

---

## 3. 開機自動啟動（已設定，不用動）

服務是 macOS 的 LaunchAgent：`~/Library/LaunchAgents/com.dylan.localai.plist`。

| 設定 | 目前 | 效果 |
|---|---|---|
| `RunAtLoad` | true | 使用者 **mac** 登入時就啟動服務 |
| `KeepAlive` | true | 服務當掉 / 被關掉，系統立刻重開 |
| 自動登入 | 開（使用者 mac） | 開機後自己登入 → 服務跟著起來，不用有人在鍵盤前 |
| FileVault | 關 | （打開 FileVault 會讓自動登入失效：開機後要有人輸入密碼，服務才會起來） |
| 停電後自動開機 | 開（`pmset autorestart 1`） | 來電後自己開機 → 登入 → 服務 |
| 睡眠 | 永不（`sleep 0`；只有螢幕 10 分鐘後關） | 隨時連得到 |
| 網路喚醒 | 開（`womp 1`） | — |

**開機後約 40 秒**服務才把模型載入完成；這段時間 `/v1/health` 回 `state: loading`，App 會顯示「主機正在載入模型」或改用 Gemini。

會讓服務沒起來的情況：
- 關掉自動登入、打開 FileVault → 要有人登入 Mac。
- 有人登出使用者 mac。
- macOS 更新後重開機：會自己回來，等 1 分鐘。

常用指令（在主機的終端機）：

```bash
launchctl kickstart -k gui/$(id -u)/com.dylan.localai
```
重啟服務（也會清掉「錯 10 次封鎖」的名單）。

```bash
launchctl print gui/$(id -u)/com.dylan.localai | grep "state ="
```
看服務是不是在跑（應該是 `state = running`）。

```bash
tail -50 ~/dylan-local/server.log
```
看最近的請求與錯誤。

---

## 4. 路由器設定（已設定，換路由器時照做）

1. **DHCP 保留**：讓主機的有線網路固定拿 `192.168.0.23`（用主機的有線網卡 MAC 位址綁定，名稱「Dylan-Mac-mini」）。
2. **轉埠（Port Forwarding / 虛擬伺服器）**：
   - 名稱：`dylan-ai`
   - 協定：TCP
   - 外部埠：48765
   - 內部 IP：192.168.0.23，內部埠：8765
   - 遠端 IP：任何（Any）
   - 規則：啟用
3. **固定 IP**：向 ISP 申請、綁在路由器上；IP 看路由器的 WAN 狀態頁。改完路由器設定要重開路由器。

---

## 5. App 端怎麼填

App「設定 → 大D的主機」：

| 欄位 | 在家 | 出門 |
|---|---|---|
| 位址 | `http://Dylan-Mac-mini.local:8765`（或 `http://192.168.0.23:8765`） | `http://<固定 IP>:48765` |
| 存取碼 | 見下面 | 同一個 |

- 位址沒打 `http://` 會自動補上。一個位址同時適合在家和出門的話，就填固定 IP 那個（在家靠路由器回送也通）。
- 第一次連區網時 iPhone / iPad 會問「允許尋找區域網路上的裝置」，要按**允許**（拒絕過就到 iPhone 設定 → 大D → 區域網路 打開）。
- 填好按 **測試連線**，應該顯示「連線成功：FunASR…、Qwen3-8B…」。

### 存取碼怎麼拿到手機上
存取碼是 43 個字元，**不要用聊天軟體傳**。在主機的終端機執行：

```bash
python3 -c "import json;print(json.load(open('/Users/mac/dylan-local/config.json'))['token'],end='')" | pbcopy
```

- 手機 / iPad 跟主機用**同一個 Apple ID**（藍牙、Wi-Fi、接力都開）：直接在 App 的存取碼欄位「貼上」→ 按 **儲存**（沒按儲存不會存，iPhone 第一次就是這樣失敗的）。
- 不同 Apple ID：在主機上把存取碼轉成 QR code 顯示在螢幕，用手機相機掃、拷貝、貼上：

```bash
brew install qrencode && python3 -c "import json;print(json.load(open('/Users/mac/dylan-local/config.json'))['token'],end='')" | qrencode -s 12 -o /tmp/code.png && open /tmp/code.png
```
用完刪掉 `/tmp/code.png`。

- Android：App 存取碼存在 Keystore / EncryptedSharedPreferences；要允許 http 明文（`network_security_config`）。

---

## 6. 測試

在主機上（把存取碼放進變數、不印出來）：

```bash
T=$(python3 -c "import json;print(json.load(open('/Users/mac/dylan-local/config.json'))['token'],end='')"); curl -s -m 8 -o /dev/null -w "區網 %{http_code}\n" http://192.168.0.23:8765/v1/health -H "Authorization: Bearer $T"
```

- `200`：服務正常。
- 換成 `http://<固定 IP>:48765/v1/health` 再測一次，`200` 表示轉埠正常。
- 從外面（手機關 Wi-Fi 用行動網路）在 App 按測試連線，主機的 `server.log` 會出現一行 `http <來源 IP> "GET /v1/health" 200`。

---

## 7. 出問題時

| 狀況 | 原因 / 處理 |
|---|---|
| App：「存取碼不對」（401） | 存取碼貼錯或沒按儲存。重新複製、貼上、**儲存** |
| App 連不上，`server.log` 有 `blocked <IP>`（429） | 同一個來源 IP 錯 10 次被封 1 小時。主機執行重啟服務指令即解除。在家用固定 IP 連時被封的是自己家 |
| App：「連不到大D的主機」，`server.log` 完全沒有那支手機的紀錄 | 請求沒到主機：位址打錯（外面要用 **48765**、家裡用 **8765**）、手機沒網路、拒絕了「區域網路」權限、路由器轉埠或 DHCP 保留跑掉了（主機 IP 不是 192.168.0.23）|
| App：「主機正在載入模型」很久 | 剛開機或剛換模型；第一次選新模型要下載（幾 GB）。看 `server.log` |
| 報告很慢 | 主機只有 16 GB，一次處理一筆；換大模型時更慢。科技模式的清單看得到每筆「耗時」 |
| 主機關機或沒網路 | App 會在 5 秒內改用 Gemini（有金鑰時），記錄上寫「大D的主機連不到，改用 Gemini」 |

---

## 8. 安全

- 對外開的是 **http（不加密）**：錄音、報告、存取碼在網路上是明文。可以接受的前提是存取碼夠長、有錯誤封鎖。要加密就改用 Tailscale（手機和主機都裝，位址填主機的 Tailscale IP、埠 8765，不用開路由器的埠），或在前面加 HTTPS。
- 服務的保護：存取碼固定時間比對；同一個來源 IP 錯 10 次封 1 小時（存在記憶體，重啟服務就清掉）；請求逾時 120 秒（處理中的連線另計）；設定請求限 10 KB；每筆請求記錄來源 IP。
- 存取碼外洩或懷疑被猜：改 `config.json` 的 `token`（例如 `python3 -c "import secrets;print(secrets.token_urlsafe(32))"` 產生新的），重啟服務，再把新的貼到每一台裝置。
- 不用時可以在路由器把 `dylan-ai` 轉埠規則關掉，只留區網。

---

## 9. 維護

- **換模型**：App 科技模式「設定 → 大D的主機」的兩個選單；或改 `config.json` 的 `asr` / `llm` 後重啟服務。
- **更新服務程式**：改 repo 的 `local-ai/server.py` → 複製到 `~/dylan-local/server.py` → 重啟服務。
- **重新安裝**（新主機）：`bash local-ai/setup.sh`（建 Python 環境、下載模型、裝 LaunchAgent、產生存取碼），再照第 3、4 節設定自動登入、不睡眠、路由器。
