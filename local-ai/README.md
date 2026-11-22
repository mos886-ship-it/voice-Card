# 大D 自架 AI 服務（使用者自己的 Mac）

錄音 → **FunASR**（繁中辨識＋分說話者，CPU）→ **Qwen3-8B 4-bit**（MLX，Apple 晶片 GPU）寫報告。
回傳跟 Gemini 一樣的格式，App 直接沿用 Gemini 的解析。App 端程式在 `ios/Dylan/LocalAI.swift`。

## 安裝

```bash
bash local-ai/setup.sh
```

- 建立 `~/dylan-local`：Python 環境（約 850 MB）、模型快取（FunASR 2.1 GB 在 `~/.cache/modelscope`、Qwen 4.3 GB 在 `~/.cache/huggingface`）。
- 存取碼：`~/dylan-local/config.json` 的 `token`（第一次啟動自動產生，權限 600）。貼到 App「設定 → 這台 Mac（自架）」。**不要放進 repo。**
- 登入時自動啟動：`~/Library/LaunchAgents/com.dylan.localai.plist`（KeepAlive：當掉會自動重啟）。紀錄在 `~/dylan-local/server.log`。

## 端點（埠 8765，都要 `Authorization: Bearer <token>`）

- `GET /v1/health` → `{"ok": true, "asr": ..., "llm": ..., "busy": false}`
- `POST /v1/process`：body 是錄音檔；標頭 `X-Kind: meeting|food`、`X-Recorded-At`、`X-Duration`（秒）→ `{"text": "<Gemini 格式>", "model": "local-funasr|qwen3-8b"}`

## 手機怎麼連到

- 同一個 Wi-Fi：App 位址填 `http://<Mac 名稱>.local:8765`（系統設定 → 一般 → 共享 可以看到名稱）。第一次 macOS 可能會問是否允許 Python 接受連入連線，要按允許。
- 出門在外：Mac 和 iPhone 都裝 Tailscale，位址填 Mac 的 Tailscale IP（`http://100.x.x.x:8765`）。**不要在路由器開對外的埠。**
- 模擬器：預設 `http://localhost:8765`。
- Mac 要保持開機、不睡眠（系統設定 → 能源 → 防止自動進入睡眠，需要自己設定）。

## 實測（M4 / 16 GB，2026-11-22）

- 53 秒兩人對話：辨識 3.6–7.8 秒、報告 34 秒，App 端全程約 45 秒；說話者分得正確。
- FunASR 用 GPU（mps）反而比較慢（5.9 秒 vs 3.6 秒），所以用 CPU；Qwen 用 MLX 跑在 GPU。
- 16 GB 記憶體下 8B 是實際上限。小模型不太會修同音錯字（例如「縣義區」沒改成「信義區」）。

## 停用 / 移除

```bash
launchctl bootout gui/$(id -u)/com.dylan.localai
rm ~/Library/LaunchAgents/com.dylan.localai.plist
rm -rf ~/dylan-local ~/.cache/modelscope ~/.cache/huggingface
```
