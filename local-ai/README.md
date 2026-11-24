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
- `POST /v1/process`：body 是錄音檔；標頭 `X-Kind: meeting|food`、`X-Recorded-At`、`X-Duration`（秒）、`X-Lang: zh|nan|hak`（國語 → FunASR；台語・國台語 → Breeze-ASR-26；客語 → 陽明交大客語 Whisper，加 `X-Dialect: sixian|hailu|dapu|raoping|zhaoan|nansixian`；沒帶就用設定的語音模型）→ `{"text": "<Gemini 格式>", "model": "local-funasr|qwen3-8b"}`，失敗回 `{"error": "..."}`。
  處理中每 20 秒先送一個空白保持連線（6 小時的錄音估計要 30 分鐘以上，App 的連線閒置上限是 30 分鐘），所以狀態碼一律 200，成功或失敗看 JSON。

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


## 客語模型（選用，限非商業）

`formospeech/whisper-large-v3-taiwanese-hakka`（陽明交大，CC BY-NC 4.0，只能非商業使用）是 Hugging Face 上要申請的模型：

1. 用自己的 Hugging Face 帳號到模型頁按申請，等核准。
2. 在主機上登入：`venv/bin/hf auth login`（貼上 read token）。
3. 轉成 MLX（約 3 GB 下載 → 1.6 GB 的 8-bit 模型，轉完刪原檔）：
   `venv/bin/python convert_whisper.py formospeech/whisper-large-v3-taiwanese-hakka models/hakka-mlx-8bit --bits 8 --delete-source`
4. 重啟服務：`launchctl kickstart -k gui/$(id -u)/com.dylan.localai`。之後 `/v1/models` 的 `langs` 會多一個 `hak`。
