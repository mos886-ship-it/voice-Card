# 大D的主機：自架 AI 服務（使用者自己的電腦）

> 連線、開機自動啟動、路由器、App 怎麼填、出問題怎麼查：**`交接/HOST_CONNECT.md`**。這份只講服務本身（模型、安裝、API）。

錄音 → **語音辨識＋分說話者** → **語言模型**寫報告。模型可以在 App「設定 → 大D的主機」切換（實測時比較用）：

| | 選項 | 說明 |
|---|---|---|
| 語音辨識 | `funasr`（預設） | FunASR paraformer-zh＋cam++，CPU。國語最快（53 秒對話約 4–9 秒） |
| | `breeze` | Breeze-ASR-26（聯發科／陽明交大，2026，Apache-2.0，台語＋國語，MLX 8bit `doggy8088/Breeze-ASR-26-MLX-8bit`）。輸出國語漢字；說話者用 FunASR cam++ 依時間對到每個字 |
| 報告模型 | `mlx:mlx-community/Qwen3-8B-4bit`（預設） | MLX，約 5 GB，53 秒對話報告約 27–57 秒 |
| | `gguf:Speech-AI-Research-Center/SARC-Taigi-LLM-12b-GGUF/SARC-Taigi-12b-Q4_K_M.gguf` | SARC 台語 12B（2026，Gemma 授權），llama.cpp＋Metal，7.3 GB，context 8k。寫法偏台語漢字（佮、矣），會自己補沒說過的內容，要實測再決定 |

- 選擇存在 `~/dylan-local/config.json` 的 `asr`、`llm`；換模型時先放掉舊的語言模型（16 GB 放不下兩個），第一次選到時自動下載。
- 自己加模型：在 `config.json` 加 `"extra_llm": {"mlx:<HF repo>": {"label": "顯示名稱", "tag": "短代號"}}`（或 `gguf:<repo>/<檔名>`），重啟服務後 App 選單就會出現。
- 回傳的 `model` 代號是 `local-<辨識>|<報告>`（例如 `local-breeze|qwen3-8b`），每筆記錄都看得到是哪組模型做的。
回傳跟 Gemini 一樣的格式，App 直接沿用 Gemini 的解析。App 端程式在 `ios/Dylan/LocalAI.swift`。

## 安裝

```bash
bash local-ai/setup.sh
```

- 建立 `~/dylan-local`：Python 環境（約 850 MB）、模型快取（FunASR 2.1 GB 在 `~/.cache/modelscope`、Qwen 4.3 GB 在 `~/.cache/huggingface`）。
- 存取碼：`~/dylan-local/config.json` 的 `token`（第一次啟動自動產生，權限 600）。**不要放進 repo。** App 不用手動貼：用 QR 碼或配對密碼配對（`pairing.py`，見 `交接/HOST_CONNECT.md`）。
- 登入時自動啟動：`~/Library/LaunchAgents/com.dylan.localai.plist`（KeepAlive：當掉會自動重啟）。紀錄在 `~/dylan-local/server.log`。

## 端點（埠 8765，都要 `Authorization: Bearer <token>`）

- `GET /v1/health` → `{"ok": true, "asr": 名稱, "llm": 名稱, "asr_id", "llm_id", "model", "state": "ready|loading|error", "error", "busy"}`
- `GET /v1/models` → health 的內容＋`asr_options`、`llm_options`（`[{id, label}]`）
- `POST /v1/config` `{"asr": id, "llm": id}` → 存進 config.json，等正在處理的那筆做完才在背景換（第一次會下載），回 202；用 health 的 `state` 看何時好
- 配對（不用存取碼）：`POST /v1/pair`；已連上的裝置：`GET /v1/devices`、`POST /v1/devices/revoke`、`POST /v1/password`；配對頁 `GET /pair`（只給區網 / 本機）。詳見 `pairing.py` 開頭的說明。
- OpenAI 相容：`POST /v1/chat/completions`（messages、max_tokens、temperature、stream），`GET /v1/models` 也回 OpenAI 格式的 `data`。用法見 `交接/HOST_CONNECT.md` 5b。
- `POST /v1/process`：body 是錄音檔；標頭 `X-Kind: meeting|food`、`X-Recorded-At`、`X-Duration`（秒）、`X-Lang: zh|nan|hak`（國語 → FunASR；台語・國台語 → Breeze-ASR-26；客語 → 數位部 Taiwan Tongues，加 `X-Dialect: sixian|hailu|dapu|raoping|zhaoan|nansixian`；沒帶就用設定的語音模型）→ `{"text": "<Gemini 格式>", "model": "local-funasr|qwen3-8b"}`，失敗回 `{"error": "..."}`。
  處理中每 20 秒先送一個空白保持連線（6 小時的錄音估計要 30 分鐘以上，App 的連線閒置上限是 30 分鐘），所以狀態碼一律 200，成功或失敗看 JSON。
  （舊的方式，現在也是排進下面的佇列再等結果。）

### 多人共用：工作佇列與管理者（2026-11-24，`jobs.py`）

- 主機一次只能處理一筆。`POST /v1/jobs`（標頭、body 同 `/v1/process`）收完檔就回 202 `{"id", "state": "queued", "ahead", "wait_sec"}`；
  `GET /v1/jobs/<id>` 查 `queued|running|done|error`，做完多 `text`、`model`；拿到後 `DELETE /v1/jobs/<id>`。`GET /v1/jobs` 列出自己的。
- **每台裝置輪流**：最久沒輪到的裝置先，一個人一次送 10 筆不會卡住別人。每台最多排 20 筆；硬碟剩不到 1 GB 不收（503）。
- 工作存在 `~/dylan-local/jobs/<id>/`（權限 700），錄音做完就刪、結果沒人拿 24 小時後刪；**服務重開會接著做**沒做完的。
- 預估等待：照錄音長度 × 實測速度（`jobs/stats.json` 會自己學）。health 也回 `queue`、`ahead`、`wait_sec`，App 送出前就知道要排多久。
- **管理者**：主存取碼、第一台配對的裝置、加這功能前已配對的裝置。只有管理者能換模型（`/v1/config`）、改配對密碼、看全部裝置、
  取消別台、`POST /v1/devices/admin {"id", "admin"}` 指定別台當管理者。一般裝置只看得到、只能取消自己。health 回 `admin`。
- 測試：`python jobs_test.py`（佇列，不用模型）；`python fake_host.py --seconds 8 --password testpass123`：跑真正的 server.py、模型換成假的，任何電腦都能測 App（Android 模擬器 `adb reverse tcp:8765 tcp:8765`）。

## 手機怎麼連到

見 `交接/HOST_CONNECT.md`（在家 `http://Dylan-Mac-mini.local:8765`；出門 `http://<固定 IP>:48765`；存取碼怎麼拿到手機上；安全注意事項）。模擬器預設 `http://localhost:8765`。

## 實測（M4 / 16 GB，2026-11-22）

- 53 秒兩人對話：辨識 3.6–7.8 秒、報告 34 秒，App 端全程約 45 秒；說話者分得正確。
- FunASR 用 GPU（mps）反而比較慢（5.9 秒 vs 3.6 秒），所以用 CPU；Qwen 用 MLX 跑在 GPU。
- 16 GB 記憶體下 8B 是實際上限。小模型不太會修同音錯字（例如「縣義區」沒改成「信義區」）。
- Breeze-ASR-26：同一段國語對話分說話者正確，辨識約 30 秒（Mac 記憶體吃緊、在用 swap 時）；Whisper 偶爾在結尾重複同一個字，服務會把連續重複 4 次以上的壓成一次。
- SARC 台語 12B：n_ctx 16k 時 Metal 配置不到記憶體（`llama_decode returned -3`），所以用 8k＋flash attention，逐字稿每段 4000 字。報告約 46 秒。
- 台語錄音的準確度還沒測（需要真的台語錄音）。單獨測辨識：`venv/bin/python breeze_test.py <音檔> [repo]`。

## 已知限制

- Python 環境是用 Xcode 附的 Python 3.9 建的（這台 Mac 沒有 Homebrew）；Xcode 大改版後如果服務起不來，重跑 `bash local-ai/setup.sh`。
- 服務聽所有網路介面（區網才連得到），靠存取碼保護；在公共 Wi-Fi 上可以先停用服務。
- 8B 小模型偶爾會寫錯數字或不修同音錯字；報告前面的「基本資料」會標示是這台 Mac 產生的。

## 停用 / 移除

```bash
launchctl bootout gui/$(id -u)/com.dylan.localai
rm ~/Library/LaunchAgents/com.dylan.localai.plist
rm -rf ~/dylan-local ~/.cache/modelscope ~/.cache/huggingface
```


## 客語模型

預設用數位發展部 **Taiwan Tongues**（`adi-gov-tw/Taiwan-Tongues-ASR-CE-pretrained-v1.0`，Whisper large-v2，TRAIL v0.1 授權、可商用、不用申請）：

```
venv/bin/python convert_whisper.py adi-gov-tw/Taiwan-Tongues-ASR-CE-pretrained-v1.0 models/taiwan-tongues-mlx-8bit --bits 8 --delete-source
launchctl kickstart -k gui/$(id -u)/com.dylan.localai
```

下載 3.2 GB → 轉成 1.5 GB 的 8-bit（峰值記憶體約 3.8 GB），轉完刪原檔。轉過的權重算 TRAIL 的「衍生作品」：只在自己主機用沒關係，**不要上傳或開放給別人用**（那算散布，要依 TRAIL 釋出）。

選用：陽明交大 `formospeech/whisper-large-v3-taiwanese-hakka`（分腔調；CC BY-NC 4.0，只能非商業；Hugging Face 要申請後 `venv/bin/hf auth login`）。轉到 `models/hakka-mlx-8bit` 後，客語錄音會改用它：

```
venv/bin/python convert_whisper.py formospeech/whisper-large-v3-taiwanese-hakka models/hakka-mlx-8bit --bits 8 --delete-source
```
