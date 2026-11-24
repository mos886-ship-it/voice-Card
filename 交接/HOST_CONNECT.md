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
| 設定 | `~/dylan-local/config.json`：`token`（主存取碼）、`password`（配對密碼的雜湊）、`devices`（配對過的裝置，存取碼只存雜湊）、`public_url`（對外位址，放進配對 QR 碼）、`asr`、`llm`；權限 600 |
| 配對 | `~/dylan-local/pairing.py`（repo：`local-ai/pairing.py`） |
| 紀錄 | `~/dylan-local/server.log`（每次請求、來源 IP、處理耗時、錯誤） |
| 預設模型 | 語音辨識 FunASR、報告 Qwen3-8B（MLX 4bit） |
| 依錄音語言（`X-Lang`） | 國語 `zh` → FunASR；台語 `nan` → Breeze-ASR-26（MLX 8bit，HF 快取）；客語 `hak` → 數位部 Taiwan Tongues（`~/dylan-local/models/taiwan-tongues-mlx-8bit`，1.5 GB，本機轉檔、不散布；TRAIL 授權）。說話者一律 FunASR 分 |
| 磁碟 | 很緊（2026-11-24 約剩 6 GB）。轉模型時會吃大量記憶體 → swap 變大佔磁碟；`convert_whisper.py` 已改成逐層量化（峰值約 3.8 GB）。下載新模型前先看 `df -h ~` |

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
| 連線方式 | 配對（見下面：掃 QR 碼或配對密碼） | 同一台配對一次就好 |

- 位址沒打 `http://` 會自動補上。一個位址同時適合在家和出門的話，就填固定 IP 那個（在家靠路由器回送也通）。
- 第一次連區網時 iPhone / iPad 會問「允許尋找區域網路上的裝置」，要按**允許**（拒絕過就到 iPhone 設定 → 大D → 區域網路 打開）。
- 填好按 **測試連線**，應該顯示「連線成功：FunASR…、Qwen3-8B…」。

### 怎麼連上（配對，不用手動貼存取碼）

兩種方法，任選：

**A. 掃 QR 碼（第一台裝置用這個）**
1. 在家裡的 Wi-Fi，用瀏覽器打開 `http://Dylan-Mac-mini.local:8765/pair`（或在主機上打開 `http://localhost:8765/pair`）。這頁**只有家裡的網路和主機本身打得開**，從外面會被拒絕。
2. 用 iPhone / iPad 的**相機**掃畫面上的 QR 碼，點「在大D中打開」；或在 App「設定 → 大D的主機 → 掃描主機上的 QR 碼」。
   用這台的 Safari 打開配對頁時沒辦法掃自己，點頁面上的「在這台 iPhone / iPad 上直接配對」。
3. App 自動填好位址、拿到這台裝置自己的存取碼，顯示「已連上大D的主機」。
   - QR 碼 10 分鐘內有效、只能用一次；重新整理頁面會換新的。
   - QR 碼裡帶兩個位址：對外的（固定 IP:48765，主機 `config.json` 的 `public_url`）和區網的；App 先試對外的，連不到再試區網的。

**B. 配對密碼（之後的裝置、人在外面也可以）**
1. 先設定密碼（一次就好）：在已經連上的裝置，App「設定 → 大D的主機 → 配對密碼 → 設定」（至少 8 個字）；或在主機上執行：
   ```bash
   ~/dylan-local/venv/bin/python ~/dylan-local/server.py --set-password
   ```
2. 新裝置：App「設定 → 大D的主機」填**位址**、輸入**配對密碼** → 按「配對」。

**原理**：每台裝置配對時拿到自己的長存取碼（43 字元，App 存在鑰匙圈；主機只存雜湊），密碼只在配對那一次傳。科技模式的「已配對的裝置」可以看每台的配對時間、最後使用時間，個別「取消」；這台自己按「取消配對」。手機遺失就從別台取消它。

**防猜**：配對失敗同一個 IP 5 次封 1 小時；所有來源 1 小時內失敗 30 次，配對暫停 1 小時（重啟服務可解除）。

**進階（舊方法）**：科技模式下還能直接貼主機 `config.json` 的主存取碼。複製主存取碼的指令：
```bash
python3 -c "import json;print(json.load(open('/Users/mac/dylan-local/config.json'))['token'],end='')" | pbcopy
```

- Android：App 存取碼存在 Keystore / EncryptedSharedPreferences；要允許 http 明文（`network_security_config`）。

---

## 5b. 從 VPS / 其他程式呼叫（OpenAI 相容）

大D的主機也是一個 **OpenAI 相容的 API**：任何支援 OpenAI 的程式（OpenAI SDK、LangChain、各種工具）把 base URL 指到主機、API key 填這台的存取碼，就能用主機目前載入的報告模型（預設 Qwen3-8B）。

1. **給這台 VPS 自己的存取碼**（配對一次；先設好配對密碼）：
   ```bash
   curl -s -X POST http://<固定IP>:48765/v1/pair -H "Content-Type: application/json" -d '{"password":"你的配對密碼","name":"VPS"}'
   ```
   回應裡的 `token` 存在 VPS（例如環境變數 `DYLAN_TOKEN`）。它會出現在 App 科技模式的「已配對的裝置」，可以隨時取消。
2. **呼叫**：
   ```python
   from openai import OpenAI
   c = OpenAI(base_url="http://<固定IP>:48765/v1", api_key=os.environ["DYLAN_TOKEN"])
   r = c.chat.completions.create(model="qwen3-8b", messages=[{"role": "user", "content": "…"}], max_tokens=500)
   ```
   ```bash
   curl -s http://<固定IP>:48765/v1/chat/completions -H "Authorization: Bearer $DYLAN_TOKEN" -H "Content-Type: application/json" -d '{"model":"qwen3-8b","messages":[{"role":"user","content":"你好"}]}'
   ```

支援：`messages`（system / user / assistant；content 可以是字串或 text 片段）、`max_tokens`（上限 8192，預設 1024）、`temperature`（預設 0.7）、`stream: true`（SSE）、`usage`。`GET /v1/models` 回 OpenAI 格式的模型清單。額外參數 `enable_thinking: true` 打開 Qwen3 的「思考」模式（預設關，回得快）。

注意：
- `model` 填什麼都會用**主機目前載入的那個**（換模型在 App 科技模式或 `/v1/config`）；回應的 `model` 欄位會寫實際用的。
- 跟 App 的錄音報告**排同一個隊**（16 GB 一次只能跑一個模型）：App 正在處理長錄音時，呼叫會等到它做完。客戶端逾時請設長一點。
- 輸出是模型原文（不會自動轉繁體）；要繁體就在 system 寫「用繁體中文回答」。
- 不支援：工具呼叫（function calling）、圖片、embeddings、`n > 1`。
- 實測（M4 16GB，Qwen3-8B）：一句話問答約 1.6 秒；兩點條列 115 tokens 約 5 秒。
- **加密**：經固定 IP 是 http 明文。VPS 長期用建議改走 Tailscale（VPS 和主機都裝，位址用主機的 Tailscale IP、埠 8765），或從主機開反向 SSH 通道到 VPS（`ssh -N -R 8765:localhost:8765 user@vps`，VPS 呼叫 `http://localhost:8765/v1`）。

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
| App：「存取碼不對」（401） | 這台的配對被取消了（或主存取碼換了）。按「取消配對」後重新配對 |
| 配對：「密碼不對」/「QR 碼過期或已經用過」 | 重新輸入；QR 碼重新整理配對頁再掃 |
| 配對：「主機還沒設定配對密碼」 | 先用 QR 碼配對，再到設定裡設密碼 |
| 配對頁打不開（403） | 配對頁只給家裡的網路：手機連家裡 Wi-Fi，用 `Dylan-Mac-mini.local` 或 192.168.0.23，不要用固定 IP |
| App 連不上，`server.log` 有 `blocked <IP>`（429） | 同一個來源 IP 錯 10 次被封 1 小時。主機執行重啟服務指令即解除。在家用固定 IP 連時被封的是自己家 |
| App：「連不到大D的主機」，`server.log` 完全沒有那支手機的紀錄 | 請求沒到主機：位址打錯（外面要用 **48765**、家裡用 **8765**）、手機沒網路、拒絕了「區域網路」權限、路由器轉埠或 DHCP 保留跑掉了（主機 IP 不是 192.168.0.23）|
| App：「主機正在載入模型」很久 | 剛開機或剛換模型；第一次選新模型要下載（幾 GB）。看 `server.log` |
| 報告很慢 | 主機只有 16 GB，一次處理一筆；換大模型時更慢。科技模式的清單看得到每筆「耗時」 |
| 主機關機或沒網路 | App 會在 5 秒內改用 Gemini（有金鑰時），記錄上寫「大D的主機連不到，改用 Gemini」 |

---

## 8. 安全

- 對外開的是 **http（不加密）**：錄音、報告、存取碼在網路上是明文。可以接受的前提是存取碼夠長、有錯誤封鎖。要加密就改用 Tailscale（手機和主機都裝，位址填主機的 Tailscale IP、埠 8765，不用開路由器的埠），或在前面加 HTTPS。
- 服務的保護：存取碼固定時間比對；同一個來源 IP 錯 10 次封 1 小時（存在記憶體，重啟服務就清掉）；請求逾時 120 秒（處理中的連線另計）；設定請求限 10 KB；每筆請求記錄來源 IP。
- 某台裝置遺失：在別台的「已配對的裝置」取消它。配對密碼外洩：在 App 或主機上換密碼（已配對的裝置不受影響）。主存取碼外洩：改 `config.json` 的 `token`（`python3 -c "import secrets;print(secrets.token_urlsafe(32))"`）後重啟服務。
- 不用時可以在路由器把 `dylan-ai` 轉埠規則關掉，只留區網。

---

## 9. 維護

- **換模型**：App 科技模式「設定 → 大D的主機」的兩個選單；或改 `config.json` 的 `asr` / `llm` 後重啟服務。
- **更新服務程式**：改 repo 的 `local-ai/server.py` → 複製到 `~/dylan-local/server.py` → 重啟服務。
- **重新安裝**（新主機）：`bash local-ai/setup.sh`（建 Python 環境、下載模型、裝 LaunchAgent、產生存取碼），再照第 3、4 節設定自動登入、不睡眠、路由器。
