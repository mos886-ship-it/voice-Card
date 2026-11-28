"""大D 自架 AI 服務（這台 Mac）：錄音 → 語音辨識＋分說話者 → 語言模型寫報告。
模型可以換（App 設定頁）：辨識 FunASR / Breeze-ASR-26（台語），報告 Qwen3-8B / SARC 台語 12B / 自己加的。

回傳格式跟 Gemini 一樣（TITLE / SUMMARY / Markdown / ===TRANSCRIPT=== / [mm:ss] 說話者X：…），
App 的報告解析、說話者名字、飲食 NUTRITION 解析都直接沿用。

端點（都要 Authorization: Bearer <存取碼>，存取碼在 config.json）：
  GET  /v1/health   → {"ok": true, "asr": 名稱, "llm": 名稱, "asr_id", "llm_id", "model", "state": ready|loading|error, "busy",
                        "admin": 這台是不是管理者, "jobs": true（有工作佇列）, "queue": 排隊＋處理中共幾筆,
                        "ahead": 現在送的話前面有幾筆, "wait_sec": 大約要等幾秒才輪到,
                        "langs": 能辨識的語言代號（App 只送這些；其他語言 App 改用 Gemini）}
  GET  /v1/models   → health 的內容 ＋ asr_options / llm_options（[{id, label}]）
  POST /v1/config   → {"asr": id, "llm": id}；存進 config.json，背景換模型（第一次會下載），回 202。只有管理者（403）
  工作佇列（多人共用時用這組，見 jobs.py；每台裝置輪流、主機重開會接著做）：
  POST   /v1/jobs       → 標頭、body 跟 /v1/process 一樣；收完檔馬上回 202 {"id", "state": "queued", "ahead", "wait_sec"}
                          這台排太多筆 / 主機硬碟不夠 → 503 {"error"}
  GET    /v1/jobs/<id>  → {"id", "state": queued|running|done|error, "ahead", "wait_sec"}；加 ?wait=秒（最多 1500）會等到做完或逾時才回；
                          done 多 "text"、"model"（同 /v1/process）；error 多 "error"；做完都有 "purged"（主機上的錄音已刪）。別台的工作 → 404
  DELETE /v1/jobs/<id>  → 排隊中就取消、做完的就刪結果（App 拿到結果後要刪）；正在做的做完直接丟掉
  GET    /v1/jobs       → {"jobs": [這台的工作狀態]}（管理者看全部）
  POST /v1/process  → （舊的，連著等結果；也是排進同一個佇列）body 是錄音檔（m4a / wav…）；標頭 X-Kind: meeting|food、X-Recorded-At、X-Duration（秒）
                       X-Input: text 時 body 是 UTF-8 文字（匯入的會議紀錄 / 逐字稿），跳過語音辨識直接寫報告
                       X-Report-Lang: zh | en | ko（報告語言；沒有 = 繁中）
  遠距對話（talk.py；兩地即時翻譯，主機只轉送文字、不存檔）：
    POST   /v1/talk           配對過的裝置開房 {name, lang} → {room, code, token, member, link}
    POST   /v1/talk/join      對方用邀請碼加入（不用主機存取碼）{room, code, name, lang} → {token, member, members}
    POST   /v1/talk/<room>/msg   X-Talk-Token；{line, text, lang, final}
    GET    /v1/talk/<room>?after=N&wait=25   X-Talk-Token；→ {msgs, members, ended, me, seq}
    DELETE /v1/talk/<room>    離開（開房的人離開 = 結束）
    POST   /v1/talk/<room>/audio  X-Talk-Token；body 是這句的原聲（m4a，最多 400 KB）→ {audio}；msg 帶 audio=… 一起送
    GET    /v1/talk/<room>/audio/<aid>   X-Talk-Token；下載原聲片段
    POST   /v1/talk/presence  X-Contact-Token；{watch:[聯絡人 id], name, lang} → {me, peers:{id:{online,seen,name,lang}}, calls:[來電]}
    POST   /v1/talk/call      X-Contact-Token；{to, name, lang} → 開房並在對方留來電（只能打給通話過的對象）
    POST   /v1/talk/dismiss   X-Contact-Token；{room} 看過的未接來電拿掉
                       X-Food-Country: TW | US | JP | KR …（飲食：這一餐所在的國家，份量依當地；沒有 = TW）
                       X-Domain: auto | off | legal | medical …（領域專家模式，見 report_domains.json；沒有 = auto）
                       X-Lang: zh（國語 → FunASR）| nan（台語・國台語 → Breeze-ASR-26）| hak（客語 → 數位部 Taiwan Tongues；
                       X-Dialect: sixian|hailu|dapu|raoping|zhaoan|nansixian 寫進報告提示，有裝陽明交大客語模型時也當模型提示）；
                       沒帶就用設定頁選的語音模型
                     回 {"text": "<Gemini 格式>", "model": "local-<asr>|<llm>"，例如 local-funasr|qwen3-8b}，失敗回 {"error": "..."}
                     （處理中每 20 秒先送一個空白保持連線，所以狀態碼一律 200，結果看 JSON）
  配對（pairing.py）：
  POST /v1/pair           → {"password": …} 或 {"code": QR 配對碼}，加 "name"（裝置名）；不用存取碼 → {"token", "id"}
  GET  /v1/devices        → {"devices": [{id, name, created, last_seen, via, current, admin}], "password_set", "admin"}
                            一般裝置只看得到自己
  POST /v1/devices/revoke → {"id": …} 取消某台裝置（一般裝置只能取消自己）
  POST /v1/devices/admin  → {"id": …, "admin": true|false} 指定 / 取消管理者（只有管理者）
  POST /v1/password       → {"password": …} 設定 / 更換配對密碼（至少 8 個字；只有管理者）
  GET  /pair              → 配對用的 QR 碼網頁（只給區網 / 本機）
  設定密碼也可以在主機上：venv/bin/python server.py --set-password
  OpenAI 相容（給 VPS / 其他程式用目前載入的報告模型）：
  POST /v1/chat/completions → OpenAI Chat Completions 格式（messages、max_tokens、temperature、stream），
                              存取碼當 API key；跟 App 的工作共用一個佇列（一次一筆）
  GET  /v1/models          → 除了上面的欄位，也有 OpenAI 的 {"object": "list", "data": [...]}

一次只處理一筆（同一顆 GPU），其他請求排隊（jobs.py：每台裝置輪流）。啟動：venv/bin/python server.py（launchd 會在登入時自動啟動）。
"""
import getpass
import ipaddress
import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
import threading
import time
import warnings
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

warnings.filterwarnings("ignore")

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG = os.path.join(HERE, "config.json")
PORT = 8765

# ---------------- 可選的模型（App 設定頁可以切換，選擇存在 config.json） ----------------
# 語音辨識：tag 會放進回傳的 model 代號（local-<asr>|<llm>），App 用它顯示是哪個模型做的。
ASR_OPTIONS = {
    "funasr": {"label": "FunASR（國語，分說話者，最快）", "tag": "funasr"},
    "breeze": {"label": "Breeze-ASR-26（台語＋國語，說話者用 FunASR 分）", "tag": "breeze",
               "repo": "doggy8088/Breeze-ASR-26-MLX-8bit"},   # 8bit：記憶體省一半，準確度差不多
    # 客語（預設）：數位發展部 adi-gov-tw/Taiwan-Tongues-ASR-CE（Whisper large-v2，國／台／客／英，TRAIL 授權、可商用）。
    # 轉成 MLX 放在 models/taiwan-tongues-mlx-8bit（convert_whisper.py；轉過的權重只放本機，不散布）。
    "hakka": {"label": "Taiwan Tongues（數位部，客語；說話者用 FunASR 分）", "tag": "taiwan-tongues",
              "repo": os.path.join(os.path.dirname(os.path.abspath(__file__)), "models", "taiwan-tongues-mlx-8bit")},
    # 客語（選用）：陽明交大 formospeech/whisper-large-v3-taiwanese-hakka（CC BY-NC 4.0，限非商業；Hugging Face 要申請），
    # 用腔調 id 當提示。裝在 models/hakka-mlx-8bit 時，客語錄音改用它。
    "hakka-nycu": {"label": "客語 Whisper（陽明交大，分腔調；限非商業）", "tag": "hakka-nycu", "dialect_prompt": True,
                   "repo": os.path.join(os.path.dirname(os.path.abspath(__file__)), "models", "hakka-mlx-8bit")},
}
# 客語腔調 → 模型訓練時用的提示（模型卡：每個腔調一個 id 當 prompt）
HAKKA_DIALECTS = {"sixian": "htia_sixian", "hailu": "htia_hailu", "dapu": "htia_dapu",
                  "raoping": "htia_raoping", "zhaoan": "htia_zhaoan", "nansixian": "htia_nansixian"}
HAKKA_NAMES = {"sixian": "四縣", "hailu": "海陸", "dapu": "大埔", "raoping": "饒平", "zhaoan": "詔安", "nansixian": "南四縣"}
# 報告模型：mlx:<HF repo>（MLX 格式）或 gguf:<HF repo>/<檔名>（llama.cpp，用 GPU）。第一次選到時才下載。
LLM_OPTIONS = {
    "mlx:mlx-community/Qwen3-8B-4bit": {"label": "Qwen3-8B（國語，預設，約 5 GB）", "tag": "qwen3-8b"},
    "gguf:Speech-AI-Research-Center/SARC-Taigi-LLM-12b-GGUF/SARC-Taigi-12b-Q4_K_M.gguf":
        {"label": "SARC 台語 12B（Q4，約 7 GB，較慢）", "tag": "sarc-taigi-12b"},
}
DEFAULT_ASR = "funasr"
DEFAULT_LLM = "mlx:mlx-community/Qwen3-8B-4bit"


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


# 紀錄檔超過 5 MB 就只留最後 1 MB（launchd 會一直往同一個檔寫）
_LOG = os.path.join(HERE, "server.log")
try:
    if os.path.getsize(_LOG) > 5_000_000:
        with open(_LOG, "rb") as f:
            f.seek(-1_000_000, 2)
            tail = f.read()
        with open(_LOG, "wb") as f:
            f.write(tail)
except OSError:
    pass


def load_config():
    if os.path.exists(CONFIG):
        with open(CONFIG, encoding="utf-8") as f:
            return json.load(f)
    cfg = {"token": secrets.token_urlsafe(24), "port": PORT}
    with open(CONFIG, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)
    os.chmod(CONFIG, 0o600)  # 只有自己讀得到
    return cfg


# ---------------- 模型 ----------------

CFG = load_config()

if "--set-password" in sys.argv:   # 在主機上設定配對密碼（不用載入模型）
    from pairing import Pairing

    def _save():
        with open(CONFIG, "w", encoding="utf-8") as f:
            json.dump(CFG, f, indent=2, ensure_ascii=False)
        os.chmod(CONFIG, 0o600)
    pw = getpass.getpass("新的配對密碼（至少 8 個字）：")
    if pw != getpass.getpass("再輸入一次："):
        sys.exit("兩次不一樣，沒有改")
    try:
        Pairing(CFG, _save, print).set_password(pw)
    except ValueError as e:
        sys.exit(str(e))
    print("已設定。服務若正在執行，請重啟：launchctl kickstart -k gui/$(id -u)/com.dylan.localai")
    sys.exit(0)
# config.json 可以再加自己的選項：{"extra_llm": {"mlx:<repo>": {"label": "...", "tag": "..."}}}
LLM_OPTIONS.update(CFG.get("extra_llm", {}))
ASR_OPTIONS.update(CFG.get("extra_asr", {}))

log("loading models…")
import gc  # noqa: E402

import numpy as np  # noqa: E402
import opencc  # noqa: E402
from funasr import AutoModel  # noqa: E402

# FunASR 一直載著：選 Breeze 時也用它的 cam++ 分說話者。CPU 比 GPU 快（實測 3.6s vs 5.9s）
FUNASR = AutoModel(model="paraformer-zh", vad_model="fsmn-vad", punc_model="ct-punc", spk_model="cam++",
                   disable_update=True, device="cpu", log_level="ERROR")
S2TW = opencc.OpenCC("s2twp")
GPU = threading.Lock()   # 一次一筆；換模型也要拿這把鎖，處理到一半不會被換掉
BUSY = {"n": 0}
BUSY_LOCK = threading.Lock()
# 目前用的模型與狀態（state: ready / loading / error）
CUR = {"asr": None, "llm": None, "llm_obj": None, "state": "loading", "error": ""}


def busy(delta):
    with BUSY_LOCK:
        BUSY["n"] += delta


def unload_llm():
    CUR["llm_obj"] = None
    gc.collect()
    try:
        import mlx.core as mx
        mx.metal.clear_cache()
    except Exception:  # noqa: BLE001
        pass


def load_llm(key):
    """mlx:<repo> 或 gguf:<repo>/<檔名> → (種類, 物件)。"""
    kind, _, ref = key.partition(":")
    if kind == "mlx":
        from mlx_lm import load
        model, tok = load(ref)
        return ("mlx", (model, tok))
    if kind == "gguf":
        from huggingface_hub import hf_hub_download
        from llama_cpp import Llama
        repo, _, fname = ref.rpartition("/")
        path = hf_hub_download(repo, fname)
        # n_ctx 8k＋flash attention：16 GB 的 Mac 放 12B 模型時，16k 會配置不到記憶體（llama_decode -3）。
        # 所以 gguf 模型的逐字稿分段比較短（見 chunk_chars）。n_gpu_layers=-1 全放 GPU。
        return ("gguf", Llama(model_path=path, n_gpu_layers=-1, n_ctx=8192, flash_attn=True, verbose=False))
    raise ValueError(f"不認得的模型：{key}")


def apply_models(asr, llm):
    """換成指定的模型（拿著 GPU 鎖呼叫）。失敗就退回原本的。"""
    CUR["state"], CUR["error"] = "loading", ""
    try:
        if asr == "breeze":   # 先下載／載入一次，第一筆才不會等很久
            import mlx_whisper
            mlx_whisper.transcribe(np.zeros(16000, dtype=np.float32), path_or_hf_repo=ASR_OPTIONS["breeze"]["repo"],
                                   language="zh", verbose=None)
        CUR["asr"] = asr
        if llm != CUR["llm"]:
            old = CUR["llm"]
            unload_llm()   # 16 GB 記憶體放不下兩個語言模型，先放掉舊的
            CUR["llm"] = None
            try:
                CUR["llm_obj"] = load_llm(llm)
                CUR["llm"] = llm
            except Exception:
                if old and old != llm:
                    log(f"  load {llm} failed, back to {old}")
                    CUR["llm_obj"] = load_llm(old)
                    CUR["llm"] = old
                raise
        CUR["state"] = "ready"
        log(f"models ready: asr={CUR['asr']} llm={CUR['llm']}")
    except Exception as e:  # noqa: BLE001
        CUR["state"] = "ready" if CUR["llm"] else "error"
        CUR["error"] = f"載入失敗：{e}"
        log(f"  {CUR['error']}")


# 每筆錄音的語言（App 錄音前選）→ 語音模型。國台語夾雜常在同一句裡，整段交給 Breeze，不逐句切。
LANG_ASR = {"zh": "funasr", "nan": "breeze", "hak": "hakka",
            # 其他語言（App 的「其他 ▾」）：Whisper 本身是多語言模型，交給 Breeze（Whisper large-v2 微調）並指定語言，
            # 不要用國語的 FunASR。準確度沒實測過；辨識不了時 App 會改用別的引擎。
            "en": "breeze", "ja": "breeze", "ko": "breeze", "vi": "breeze", "th": "breeze", "id": "breeze", "yue": "breeze"}
# App 的語言代號 → Whisper 的語言（粵語：large-v2 沒有 yue，用 zh）
WHISPER_LANG = {"en": "en", "ja": "ja", "ko": "ko", "vi": "vi", "th": "th", "id": "id", "yue": "zh"}


def asr_ready(key):
    """客語模型要另外轉檔（陽明交大的還要申請）：還沒放好就不列出來（其他選項第一次用到時會自己下載）。"""
    repo = ASR_OPTIONS.get(key, {}).get("repo", "")
    return key not in ("hakka", "hakka-nycu") or os.path.exists(os.path.join(repo, "config.json"))


def lang_asr(lang):
    """App 選的語言 → 語音模型。客語：裝了陽明交大的（分腔調）就用它，否則 Taiwan Tongues。"""
    if lang == "hak" and asr_ready("hakka-nycu"):
        return "hakka-nycu"
    return LANG_ASR.get(lang)


def model_tag(asr=None):
    asr = asr or CUR["asr"]
    a = ASR_OPTIONS.get(asr, {}).get("tag", asr)
    m = LLM_OPTIONS.get(CUR["llm"], {}).get("tag", (CUR["llm"] or "").rsplit("/", 1)[-1].lower())
    return f"local-{a}|{m}"


_asr = CFG.get("asr", DEFAULT_ASR)
_llm = CFG.get("llm", DEFAULT_LLM)
apply_models(_asr if _asr in ASR_OPTIONS else DEFAULT_ASR, _llm if _llm in LLM_OPTIONS else DEFAULT_LLM)

# ---------------- 提示詞（照 Android；逐字稿版） ----------------

TYPE_RULES = """錄音可能是會議、課堂、訪談、電話通話、演講、討論、或個人口述的備忘 / 想法，請先判斷是哪一種，再依類型整理重點：
- 會議 / 討論：討論內容、決定事項、誰負責什麼。
- 課堂 / 演講：主要概念、重要定義與例子、要複習的地方。
- 訪談 / 通話：問了什麼、對方怎麼回答、約定事項。
- 個人備忘 / 想法：把零散的想法整理成清楚的條列與下一步。
不要捏造錄音裡沒有的內容；不確定的標註「（待確認）」。
逐字稿是語音辨識結果，可能有同音錯字（例如地名、數字），請依上下文改成正確的字。
一律使用繁體中文（台灣用語）。
夾雜在中文裡的英文（單字、術語、縮寫、人名、公司、品牌與產品名，例如 KPI、deadline、iPhone、Python）照原文寫英文，
逐字稿和報告都一樣：不要翻成中文，也不要寫成同音的中文字；語音辨識把英文聽成同音中文字時，依上下文改回英文。"""

REPORT_SYSTEM = f"""你是專業的語音記錄助理。使用者會給你一份語音辨識產生的逐字稿（可能有同音錯字、標點不完整），請依上下文理解後整理。
{TYPE_RULES}

輸出格式（嚴格遵守，不要加其他前言）：
第 1 行：TITLE: <15 字內的標題，能看出內容與類型，例如「Q3 預算週會」「統計學第 5 講」「王經理電話訪談」>
第 2 行：SUMMARY: <2～3 句、80 字內的摘要，會顯示在手錶上>
第 3 行起：Markdown 報告，依序包含以下章節（沒有內容的章節寫「無」）：
## 摘要
（3～5 句完整摘要。這一節一定要寫，不可以寫「無」）
## 說話者
（用表格：說話者 | 名字 | 角色 | 主要觀點；每位說話者一列。名字從自我介紹、別人怎麼稱呼他推斷，只有職稱或稱謂也可以直接當名字（例如「王經理」）；完全沒有線索才寫「未知」）
## 重點
（有多位說話者時，每點註明是誰說的，例如「說話者B 提出…」）
## 結論 / 決定
## 待辦事項
（用表格：事項 | 負責人 | 期限；負責人寫真正要做事的人，用說話者代號，若是錄音裡提到的其他人就寫他的名字；沒提到就寫「未指定」）
## 待確認 / 後續問題

說話者規則：
- 一律用「說話者A」「說話者B」這種寫法（不要寫成語言者、發言者）。
- 逐字稿裡的代號是語音辨識依聲音分出來的，報告裡請沿用同樣的代號；換人的地方偶爾會切錯一兩個字，請依上下文判斷。
- 只輸出 TITLE、SUMMARY 與 Markdown 報告，不要再輸出逐字稿。"""

NOTES_SYSTEM = f"""你是語音記錄助理。使用者會給你一段長錄音逐字稿中的一部分，請用繁體中文條列這段的重點（註明說話者代號）、
決定、待辦事項（負責人、期限，沒提到寫「未指定」）與待確認事項。只輸出條列，不要前言。
{TYPE_RULES}"""

FOOD_SYSTEM = """你是營養記錄助理。使用者會給你一段口述的逐字稿（語音辨識，可能有同音錯字），描述剛吃（或喝）了什麼，請整理成飲食記錄，並估算熱量與三大營養素。
- %PORTION%
- 使用者明確說出的份量、杯型、甜度、冰塊、加料一定照用，不可以改成別的（例如說「大杯半糖」就是大杯、半糖），只有沒說的部分才用預設。
- 沒說清楚的地方用常見預設，並在「假設與待確認」寫出來。
- 數字一律取整數；熱量單位大卡（kcal），營養素單位公克（g）。
- 每樣食物的蛋白質＋碳水＋脂肪（克）加起來不能超過它的重量；熱量約等於 蛋白質×4＋碳水×4＋脂肪×9。
- fiber 是膳食纖維（克），算在碳水裡面，不能超過碳水。
- 餐別依描述與錄音時間判斷：早餐、午餐、晚餐、點心、宵夜、飲料。
- 若內容不是在描述飲食，TITLE 寫「非飲食內容」，NUTRITION 全部填 0。
- 一律使用繁體中文（台灣用語）。這是估算，不要做醫療診斷。

輸出格式（嚴格遵守，不要加其他前言）：
第 1 行：TITLE: <餐別：主要食物，15 字內，例如「午餐：牛肉麵＋燙青菜」>
第 2 行：SUMMARY: <一句話，含總熱量與蛋白質，例如「午餐約 780 大卡，蛋白質 35g」>
第 3 行：NUTRITION: meal=<餐別>; kcal=<整數>; protein=<整數>; carbs=<整數>; fat=<整數>; fiber=<整數>
第 4 行起：Markdown，依序包含：
## 飲食內容
（用表格：食物 | 份量 | 熱量(kcal) | 蛋白質(g) | 碳水(g) | 脂肪(g) | 纖維(g)；每樣食物一列）
## 合計
（一行：約 X 大卡；蛋白質 Xg、碳水 Xg（纖維 Xg）、脂肪 Xg）
## 營養小提醒
（1～3 點；%ADVICE%）
## 假設與待確認"""

TRANSCRIPT_MARKER = "===TRANSCRIPT==="
CHUNK_CHARS = 9000   # 一次送給 MLX 8B 模型的逐字稿長度（16GB 記憶體下的安全值）
GGUF_CHUNK_CHARS = 4000   # gguf（llama.cpp，n_ctx 8k）用的長度


def chunk_chars():
    return GGUF_CHUNK_CHARS if CUR["llm_obj"] and CUR["llm_obj"][0] == "gguf" else CHUNK_CHARS


# ---------------- 處理 ----------------

def to_wav(src):
    """任何格式 → 16kHz 單聲道 wav（用 macOS 內建 afconvert，不用裝 ffmpeg）。"""
    dst = src + ".wav"
    subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", src, dst],
                   check=True, capture_output=True)
    return dst


LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"


def spk_name(spk):
    return "說話者" + (LETTERS[spk] if spk < len(LETTERS) else str(spk))


def funasr_segments(wav):
    """FunASR → [(開始秒, 結束秒, 說話者編號, 文字)]。"""
    res = FUNASR.generate(input=wav, batch_size_s=300)
    if not res:   # 整段沒聽到國語（或全是靜音）時 FunASR 回空清單
        return []
    return [(s["start"] / 1000, s["end"] / 1000, s.get("spk", 0), s["text"]) for s in res[0].get("sentence_info", [])]


def word_speakers(audio, words, win=1.5, hop=0.75, threshold=0.45):
    """Whisper 系的說話者：在 Whisper 聽到聲音的地方每 1.5 秒（每 0.75 秒移一次）取 cam++ 聲紋，分群後每個字照它蓋到的
    聲紋窗投票。不再借國語 FunASR 的句子：台語、客語、外語國語模型聽不懂，標點亂斷甚至整段回空，結果全部變成一位說話者。
    words: [(開始秒, 結束秒)] → 每個字的說話者編號（照第一次出現的順序 0, 1, 2…）。"""
    import torch
    from sklearn.cluster import AgglomerativeClustering
    if not words:
        return []
    regions = []   # 連續說話的區間（字之間停頓 0.5 秒以內算同一段）
    for a, b in words:
        if regions and a - regions[-1][1] <= 0.5:
            regions[-1][1] = max(regions[-1][1], b)
        else:
            regions.append([a, b])
    spans = []
    for a, b in regions:
        if b - a <= win:
            c = (a + b) / 2
            spans.append((max(0.0, c - win / 2), c + win / 2))
            continue
        t = a
        while t + win < b:
            spans.append((t, t + win))
            t += hop
        spans.append((b - win, b))
    if len(spans) < 2:
        return [0] * len(words)
    chunks = [audio[int(a * 16000):int(b * 16000)] for a, b in spans]
    rs = FUNASR.inference(chunks, input_len=None, model=FUNASR.spk_model, kwargs=FUNASR.spk_kwargs,
                          fs=16000, disable_pbar=True)
    emb = torch.cat([r["spk_embedding"] for r in rs], dim=0).cpu().numpy()
    emb = emb / np.linalg.norm(emb, axis=1, keepdims=True)
    if len(spans) >= 20:   # 夠多就用 FunASR 自己的分群（長錄音也撐得住）
        lab = np.asarray(FUNASR.cb_model(torch.from_numpy(emb), oracle_num=None))
    else:                  # FunASR 的分群少於 20 個窗會直接全部算同一人，短錄音自己分
        lab = AgglomerativeClustering(n_clusters=None, metric="cosine", linkage="average",
                                      distance_threshold=threshold).fit_predict(emb)
    # 只出現一兩下的群（咳嗽、雜音）併到聲紋最像的說話者
    big = [k for k in set(lab.tolist()) if (lab == k).sum() >= max(3, 0.05 * len(lab))]
    if not big:
        big = [int(np.bincount(lab).argmax())]
    cent = {k: emb[lab == k].mean(0) for k in big}
    lab = [k if k in cent else max(cent, key=lambda c: float(emb[i] @ cent[c])) for i, k in enumerate(lab.tolist())]
    out = []
    for a, b in words:
        votes = {}
        for (s0, s1), k in zip(spans, lab):
            o = min(b, s1) - max(a, s0)
            if o > 0:
                votes[k] = votes.get(k, 0) + o
        if not votes:
            m = (a + b) / 2
            votes = {lab[min(range(len(spans)), key=lambda i: abs((spans[i][0] + spans[i][1]) / 2 - m))]: 1}
        out.append(max(votes, key=votes.get))
    order = {k: n for n, k in enumerate(dict.fromkeys(out))}
    return [order[k] for k in out]


def breeze_segments(wav, asr="breeze", dialect=None, lang=None):
    """Whisper 系（Breeze-ASR-26 台語、客語，MLX）辨識文字；說話者用 word_speakers（cam++ 聲紋直接分群，不靠國語辨識）。"""
    import mlx_whisper
    import torchaudio
    repo = ASR_OPTIONS[asr]["repo"]
    prompt = None
    if not asr_ready(asr):
        raise RuntimeError("客語模型還沒裝好（在主機上用 convert_whisper.py 轉 adi-gov-tw/Taiwan-Tongues-ASR-CE-pretrained-v1.0）")
    if ASR_OPTIONS[asr].get("dialect_prompt"):
        prompt = HAKKA_DIALECTS.get(dialect or "sixian", "htia_sixian")
    audio, _ = torchaudio.load(wav)   # afconvert 的 wav 是 extensible 格式，內建 wave 讀不了
    audio = audio.mean(dim=0).numpy().astype(np.float32)
    r = mlx_whisper.transcribe(audio,
                               path_or_hf_repo=repo, language=WHISPER_LANG.get(lang, "zh"), initial_prompt=prompt,
                               condition_on_previous_text=False, word_timestamps=True,
                               hallucination_silence_threshold=2.0, verbose=None)   # 靜音處不要亂生字
    words = [w for seg in r["segments"]
             for w in (seg.get("words") or [{"start": seg["start"], "end": seg["end"], "word": seg["text"]}])
             if w["word"].strip()]
    spks = word_speakers(audio, [(w["start"], w["end"]) for w in words])

    out = []   # (開始, 結束, 說話者, 文字)
    for w, spk in zip(words, spks):
        word = w["word"]
        # Whisper 用空白分句：中文之間的空白改成逗號
        word = re.sub(r"(?<=[\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])", "，", word)
        if out and out[-1][2] == spk and w["start"] - out[-1][1] < 2:
            a, _, k, t = out[-1]
            lead = "，" if word[:1].isspace() and t[-1:] not in "，。！？" else ""
            out[-1] = (a, w["end"], k, t + lead + word.strip())
        else:
            out.append((w["start"], w["end"], spk, word.strip()))
    # Whisper 偶爾在結尾一直重複同一個字或詞（「走走走走…」），重複 4 次以上就只留一次
    return [(a, b, k, re.sub(r"(.{1,4}?)\1{3,}", r"\1", t)) for a, b, k, t in out]


WHISPER_ASR = ("breeze", "hakka", "hakka-nycu")


def transcribe(wav, asr=None, dialect=None, lang=None):
    """→ [mm:ss] 說話者X：內容（同一人連續說的話合併成一行，最長約 30 秒一段）。"""
    asr = asr or CUR["asr"]
    segs = breeze_segments(wav, asr, dialect, lang) if asr in WHISPER_ASR else funasr_segments(wav)
    # 日文、韓文、英文…不要做簡轉繁（會改到日文漢字）；粵語照樣轉成台灣繁體
    keep = lang in WHISPER_LANG and lang != "yue"
    lines, cur = [], None
    for start, _, spk, text in segs:
        name = spk_name(spk)
        text = text if keep else tw(text)
        if cur and cur["name"] == name and start - cur["start"] < 30:
            sep = "" if re.search(r"[，。！？、,.!?]$", cur["text"]) else "，"
            cur["text"] += (sep if asr in WHISPER_ASR else "") + text
        else:
            cur = {"start": start, "name": name, "text": text}
            lines.append(cur)
    out = []
    for c in lines:
        sec = int(c["start"])
        stamp = f"{sec // 3600}:{sec // 60 % 60:02d}:{sec % 60:02d}" if sec >= 3600 else f"{sec // 60:02d}:{sec % 60:02d}"
        out.append(f"[{stamp}] {c['name']}：{c['text']}")
    return "\n".join(out)


def tw(text):
    """簡體 → 台灣繁體；「臺」統一成「台」。"""
    return S2TW.convert(text).replace("臺", "台")


def chat(system, user, max_tokens=2500):
    messages = [{"role": "system", "content": system}, {"role": "user", "content": user}]
    kind, obj = CUR["llm_obj"]
    if kind == "gguf":
        r = obj.create_chat_completion(messages=messages, temperature=0.2, max_tokens=max_tokens)
        out = r["choices"][0]["message"]["content"] or ""
    else:
        from mlx_lm import generate
        from mlx_lm.sample_utils import make_sampler
        model, tok = obj
        try:   # Qwen3：關掉「思考」模式，直接輸出
            prompt = tok.apply_chat_template(messages, add_generation_prompt=True, enable_thinking=False, tokenize=False)
        except TypeError:
            prompt = tok.apply_chat_template(messages, add_generation_prompt=True, tokenize=False)
        out = generate(model, tok, prompt=prompt, max_tokens=max_tokens, sampler=make_sampler(temp=0.2))
    out = re.sub(r"<think>.*?</think>", "", out, flags=re.S).strip()
    # 有的模型寫成「TITLE : …」「**TITLE:** …」，統一成 App 認得的「TITLE: …」
    out = re.sub(r"(?m)^[*#\s]*(TITLE|SUMMARY|NUTRITION)[*\s]*[:：][*\s]*", r"\1: ", out)
    # 小模型偶爾把「說話者」改寫成別的詞，改回來（App 靠這個找說話者表格）
    return tw(out).replace("語言者", "說話者").replace("發言者", "說話者")


# ---------------- OpenAI 相容 ----------------
OA_MAX_TOKENS = 8192


def llm_tag():
    return LLM_OPTIONS.get(CUR["llm"], {}).get("tag", (CUR["llm"] or "").rsplit("/", 1)[-1].lower())


def oa_messages(raw):
    """OpenAI 的 messages → 模型的對話。content 可以是字串或 [{type: text, text}]；tool 等其他角色當成 user。"""
    out = []
    for m in raw if isinstance(raw, list) else []:
        if not isinstance(m, dict):
            continue
        c = m.get("content", "")
        if isinstance(c, list):
            c = "".join(p.get("text", "") for p in c if isinstance(p, dict) and p.get("type", "text") == "text")
        role = m.get("role", "user")
        out.append({"role": role if role in ("system", "user", "assistant") else "user", "content": str(c or "")})
    return out


def oa_generate(messages, max_tokens, temperature, think, on_text=None):
    """用目前載入的報告模型回答。on_text(片段) 有給就邊產生邊回呼（串流）。回傳 (全文, prompt tokens, 產生 tokens, finish)。"""
    kind, obj = CUR["llm_obj"]
    parts, n_prompt, n_gen, finish = [], 0, 0, "stop"
    if kind == "gguf":
        stream = obj.create_chat_completion(messages=messages, temperature=temperature, max_tokens=max_tokens, stream=True)
        for ch in stream:
            choice = ch["choices"][0]
            piece = choice.get("delta", {}).get("content") or ""
            if piece:
                parts.append(piece)
                if on_text:
                    on_text(piece)
            if choice.get("finish_reason"):
                finish = choice["finish_reason"]
        text = "".join(parts)
        try:
            n_gen = len(obj.tokenize(text.encode("utf-8"), add_bos=False))
            n_prompt = len(obj.tokenize("".join(m["content"] for m in messages).encode("utf-8")))
        except Exception:  # noqa: BLE001
            pass
        return text, n_prompt, n_gen, finish
    from mlx_lm import stream_generate
    from mlx_lm.sample_utils import make_sampler
    model, tok = obj
    try:   # Qwen3：預設關掉「思考」，要的話 enable_thinking: true
        prompt = tok.apply_chat_template(messages, add_generation_prompt=True, enable_thinking=think, tokenize=False)
    except TypeError:
        prompt = tok.apply_chat_template(messages, add_generation_prompt=True, tokenize=False)
    for r in stream_generate(model, tok, prompt=prompt, max_tokens=max_tokens, sampler=make_sampler(temp=temperature)):
        if r.text:
            parts.append(r.text)
            if on_text:
                on_text(r.text)
        n_prompt, n_gen = r.prompt_tokens, r.generation_tokens
        if r.finish_reason:
            finish = "length" if r.finish_reason == "length" else "stop"
    text = "".join(parts)
    if not think:
        text = re.sub(r"<think>.*?</think>\s*", "", text, flags=re.S)
    return text, n_prompt, n_gen, finish


def chunks(text, size):
    out, cur = [], ""
    for line in text.split("\n"):
        if cur and len(cur) + len(line) + 1 > size:
            out.append(cur)
            cur = ""
        cur += ("\n" if cur else "") + line
    if cur:
        out.append(cur)
    return out


# ---------------- 報告語言（照 Android / iOS ReportLang）----------------
# App 送 X-Report-Lang：zh / en / ko。提示詞只有中文一份，換語言時把章節標題、表頭、說話者代號、固定字換掉，最後加一條規則。

REPORT_LANGS = {"zh": ("說話者", "繁體中文（台灣用語）"), "en": ("Speaker ", "English"), "ko": ("화자 ", "한국어")}
_HEADINGS = [
    ("摘要", "Summary", "요약"), ("說話者", "Speakers", "화자"), ("重點", "Key points", "핵심 내용"),
    ("結論 / 決定", "Conclusions / Decisions", "결론 / 결정"), ("待辦事項", "Action items", "할 일"),
    ("待確認 / 後續問題", "Open questions / Follow-ups", "확인 필요 / 후속 질문"), ("討論重點", "Discussion", "논의 내용"),
    ("決議事項", "Decisions", "결정 사항"), ("營養小提醒", "Nutrition tips", "영양 팁"),
    ("假設與待確認", "Assumptions to check", "가정 및 확인 필요"), ("專業重點", "Expert notes", "전문가 관점"),
    ("心智圖大綱", "Mind map outline", "마인드맵 개요"), ("名詞解釋", "Glossary", "용어 설명"), ("複習題", "Review questions", "복습 문제"),
]
_PHRASES = [
    ("說話者 | 名字 | 角色 | 主要觀點", "Speaker | Name | Role | Main points", "화자 | 이름 | 역할 | 주요 의견"),
    ("事項 | 負責人 | 期限", "Item | Owner | Due", "항목 | 담당자 | 기한"),
    ("「未知」", "「Unknown」", "「미상」"), ("「未指定」", "「Unassigned」", "「미지정」"), ("「無」", "「None」", "「없음」"),
    ("「本人」", "「Me」", "「본인」"), ("「（聽不清）」", "「(inaudible)」", "「(잘 안 들림)」"),
    ("「（待確認）」", "「(to confirm)」", "「(확인 필요)」"),
    # 領域專家模式
    ("「## 專業重點」", "「## Expert notes」", "「## 전문가 관점」"), ("「領域：○○」", "「Domain: …」", "「분야: …」"),
    ("「（建議諮詢專業人員）」", "「(consult a professional)」", "「(전문가 상담 권장)」"),
    # 課堂講座
    ("「## 心智圖大綱」", "「## Mind map outline」", "「## 마인드맵 개요」"), ("「## 名詞解釋」", "「## Glossary」", "「## 용어 설명」"),
    ("「## 複習題」", "「## Review questions」", "「## 복습 문제」"), ("「名詞：一句白話解釋」", "「Term: one-line plain explanation」", "「용어: 쉬운 한 줄 설명」"),
    ("「答：」", "「A:」", "「답:」"),
]


# ---------------- 領域專家模式（法律、醫療…）----------------
# 清單跟 App 共用：shared/src/main/assets/report_domains.json（setup.sh 會複製到服務目錄）。
# App 送 X-Domain：auto（自動判斷，一般對話不加）/ off / 領域 id。跟 App 的 ReportDomain 一樣。
def _load_domains():
    here = os.path.dirname(os.path.abspath(__file__))
    for path in (os.path.join(here, "report_domains.json"),
                 os.path.join(here, "..", "shared", "src", "main", "assets", "report_domains.json")):
        try:
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            continue
    return None


DOMAINS = _load_domains()


def domain_rules(setting):
    t = DOMAINS
    if not t or setting == "off":
        return ""
    ds = t["domains"]
    if setting in ("", None, "auto"):
        focus = "\n".join(f"- {d['name']}：{d['focus']}" + (f"（報告最後加一行：「{d['disclaimer']}」）" if d.get("disclaimer") else "")
                          for d in ds)
        return t["auto"].replace("{names}", "、".join(d["name"] for d in ds)).replace("{focus}", focus)
    d = next((d for d in ds if d["id"] == setting), None)
    if not d:
        return ""
    out = t["common"].replace("【領域專家模式】", f"【領域專家模式：{d['name']}】") + f"\n這個領域要注意的事項：{d['focus']}。"
    if d.get("extra"):
        out += "\n" + d["extra"]
    if d.get("disclaimer"):
        out += f"\n報告最後加一行：「{d['disclaimer']}」"
    return out


# 飲食：這一餐在哪個國家（App 送 X-Food-Country：TW / US / JP / KR …；沒有 = TW），份量依當地習慣估算。跟 App 的 FoodRegion.portionRule 一樣。
FOOD_PORTIONS = {
    "TW": "依台灣常見的份量與做法估算（便當、小吃、麵店、手搖飲、便利商店等）。",
    "US": "這一餐在美國：依美國常見的份量與做法估算（餐廳份量通常較大；漢堡、三明治、沙拉、披薩、咖啡店飲料的 tall/grande/venti、汽水杯型等），單位換算成克（1 cup 約 240 ml、1 oz 約 28 g）。",
    "JP": "這一餐在日本：依日本常見的份量與做法估算（定食、丼飯、拉麵、便利商店便當與飯糰、居酒屋小菜等），食物名稱可以用日文原名並附中文。",
    "KR": "這一餐在韓國：依韓國常見的份量與做法估算（白飯一碗約 210 克、湯鍋、拌飯、韓式烤肉、小菜（반찬）、便利商店餐點等），食物名稱可以用韓文原名並附中文。",
}


# 健康目標（App 送 X-Food-Goal：balanced / lose / muscle / sugar）：營養小提醒照目標講重點。跟 App 的 FoodGoals.adviceRule 一樣。
FOOD_ADVICE = {
    "lose": "使用者的目標是減脂控卡：營養小提醒以熱量、份量、油脂與含糖飲料為重點，提出下一餐可以怎麼少一點熱量但吃得飽。",
    "muscle": "使用者的目標是增肌高蛋白：營養小提醒以這餐蛋白質夠不夠為重點，建議下一餐補什麼蛋白質來源（豆漿、蛋、雞胸、魚、豆腐等）。",
    "sugar": "使用者的目標是控糖穩血糖（適合銀髮族與三高）：營養小提醒特別標出精緻澱粉、含糖飲料與甜點的份量，建議穩定血糖的吃法（先吃菜和蛋白質、換成全穀、無糖）。",
}


def food_system(country, goal=None):
    cc = (country or "TW").upper()[:2]
    rule = FOOD_PORTIONS.get(cc, f"這一餐在國家代碼 {cc} 的地方：依當地常見的份量與做法估算。")
    advice = FOOD_ADVICE.get(goal or "", "給均衡飲食的簡短建議")
    return FOOD_SYSTEM.replace("%PORTION%", rule).replace("%ADVICE%", advice)


def vocab_rule(b64):
    """App 送 X-Vocab（base64、UTF-8、一行一個）：使用者的專有名詞與常見人名。跟 App 的 Vocab.rule 一樣。"""
    if not b64:
        return ""
    try:
        import base64
        words = [w.strip() for w in base64.b64decode(b64).decode("utf-8").split("\n") if w.strip()][:200]
    except Exception:  # noqa: BLE001
        return ""
    if not words:
        return ""
    return "【使用者的專有名詞與常見人名】辨識和整理時，聽到同音或相近的詞，優先用這些寫法：" + "、".join(words) + "。沒出現的不要硬套。"


def with_vocab(prompt, b64):
    rule = vocab_rule(b64)
    if not rule or "## 待辦事項" not in prompt:
        return prompt
    return prompt.rstrip() + "\n\n" + rule


def with_domain(prompt, setting):
    """報告提示詞（有「待辦事項」章節）才加；飲食、分段筆記不加。"""
    rules = domain_rules(setting)
    if not rules or "## 待辦事項" not in prompt or "NUTRITION" in prompt:
        return prompt
    return prompt.rstrip() + "\n\n" + rules


def report_lang(code):
    return code if code in REPORT_LANGS else "zh"


def speaker_code(lang, letter):
    return REPORT_LANGS[report_lang(lang)][0] + letter


def localize(prompt, lang):
    """中文提示詞 → 這個報告語言的格式（zh 原樣回傳）。跟 App 的 ReportLang.localize 一樣。"""
    lang = report_lang(lang)
    if lang == "zh":
        return prompt
    i = 1 if lang == "en" else 2
    p = prompt
    for h in _HEADINGS:
        p = re.sub(r"(?m)^(\s*)## " + re.escape(h[0]) + r"\s*$", lambda m, t=h[i]: m.group(1) + "## " + t, p)
    for ph in _PHRASES:
        p = p.replace(ph[0], ph[i])
    p = re.sub(r"說話者([A-Z?])", lambda m: REPORT_LANGS[lang][0] + m.group(1), p)
    p = p.replace("一律使用繁體中文（台灣用語）。", "")
    name = REPORT_LANGS[lang][1]
    return (p.rstrip() + "\n\n【報告語言（最優先，覆蓋前面所有「繁體中文」的要求）】TITLE、SUMMARY、Markdown 報告（章節標題照上面給的寫法）"
            f"一律用 {name} 撰寫；說話者代號寫成「{speaker_code(lang, 'A')}、{speaker_code(lang, 'B')}…」。逐字稿照錄音原本的語言寫，不要翻譯。"
            f"飲食的 NUTRITION 與 ITEM 行維持原本的欄位格式（欄位名稱與餐別用中文），只有標題、摘要與報告文字用 {name}。")


def process(audio_path, kind, recorded_at, duration, text_input=False, asr=None, dialect=None, rlang="zh", lang=None, domain="auto",
            country=None, goal=None, vocab=None):
    t0 = time.time()
    if text_input:
        with open(audio_path, encoding="utf-8", errors="replace") as f:
            transcript = f.read().strip()
        header = f"這是一份匯入的文字記錄（會議紀錄、逐字稿或筆記，不是錄音），時間：{recorded_at}。"
    else:
        wav = to_wav(audio_path)
        transcript = transcribe(wav, asr, dialect, lang)
        if report_lang(rlang) != "zh":   # 逐字稿的說話者代號跟報告語言一樣（Speaker A / 화자 A）
            transcript = re.sub(r"說話者([A-Z?0-9]+)", lambda m: speaker_code(rlang, m.group(1)), transcript)
        header = f"錄音時間：{recorded_at}，長度約 {duration / 60:.1f} 分鐘。"
        if (asr or CUR["asr"]) in ("hakka", "hakka-nycu"):
            header += (f"這段錄音是客語（{HAKKA_NAMES.get(dialect or 'sixian', '四縣')}腔，可能夾雜國語），逐字稿可能有用字不一的客語漢字或同音字；"
                       "報告請用台灣繁體華語寫，依上下文理解原意，不要自己補沒說過的內容。")
        elif lang in WHISPER_LANG:
            header += (f"這段錄音主要是 {lang} 語言（App 語言代號），逐字稿照原文；報告照報告語言的規定寫，"
                       "依上下文理解原意，不要自己補沒說過的內容。")
        elif (asr or CUR["asr"]) == "breeze":
            header += ("這段錄音有台語（可能國台語夾雜），逐字稿已用華語字寫出，台語詞可能被寫成意思相近或同音的華語字；"
                       "請依上下文理解原意來整理，不要逐字照抄可疑的字，也不要自己補沒說過的內容。")
    t1 = time.time()
    if not transcript.strip():
        report = "TITLE: 沒有聽到內容\nSUMMARY: 這段錄音沒有辨識到說話內容。\n## 摘要\n這段錄音沒有辨識到說話內容。"
    elif kind == "food":
        report = chat(localize(food_system(country, goal), rlang), f"{header}\n\n逐字稿：\n{transcript}", max_tokens=1500)
    elif len(transcript) <= chunk_chars():
        report = chat(localize(with_vocab(with_domain(REPORT_SYSTEM, domain), vocab), rlang), f"{header}\n\n<transcript>\n{transcript}\n</transcript>")
    else:
        # 長錄音：分段做筆記再合併（照 Android LongMeeting 的做法）
        parts = chunks(transcript, chunk_chars())
        notes = []
        for i, p in enumerate(parts):
            log(f"  notes {i + 1}/{len(parts)}")
            notes.append(f"【第 {i + 1} 部分】\n" + chat(localize(NOTES_SYSTEM, rlang), p, max_tokens=1200))
        report = chat(localize(with_vocab(with_domain(REPORT_SYSTEM, domain), vocab), rlang), f"{header}\n以下是長錄音各部分整理好的筆記（不是逐字稿），請整合成一份報告：\n\n" + "\n\n".join(notes))
    t2 = time.time()
    log(f"  asr {t1 - t0:.1f}s, llm {t2 - t1:.1f}s, transcript {len(transcript)} chars")
    return f"{report}\n\n{TRANSCRIPT_MARKER}\n{transcript}"


# ---------------- HTTP ----------------


def save_config():
    with open(CONFIG, "w", encoding="utf-8") as f:
        json.dump(CFG, f, indent=2, ensure_ascii=False)
    os.chmod(CONFIG, 0o600)


def status(dev=None):
    """[dev] = 發請求的裝置（"main" = 主存取碼）：加上它是不是管理者、現在送的話要排多久。"""
    with BUSY_LOCK:
        n = BUSY["n"]
    q = JOBS.preview(dev or "main")
    return {"ok": True, "asr": ASR_OPTIONS.get(CUR["asr"], {}).get("label", CUR["asr"]),
            "llm": LLM_OPTIONS.get(CUR["llm"], {}).get("label", CUR["llm"]),
            "asr_id": CUR["asr"], "llm_id": CUR["llm"], "model": model_tag(),
            "state": CUR["state"], "error": CUR["error"], "busy": n > 0 or q["queue"] > 0,
            "admin": PAIR.is_admin(dev), "jobs": True, "langs": sorted(LANG_ASR), **q}


sys.path.insert(0, HERE)
from pairing import Pairing  # noqa: E402

PAIR = Pairing(CFG, save_config, log)

from jobs import Jobs, QueueFull  # noqa: E402


def run_job(j, path):
    """佇列裡的一筆：拿 GPU 鎖處理（換模型、OpenAI 相容的請求也用這把鎖，輪流用）。"""
    asr = j.get("asr")
    if asr and asr not in ASR_OPTIONS:
        asr = None
    busy(1)
    try:
        with GPU:
            if not CUR["llm_obj"]:
                raise RuntimeError(CUR["error"] or "模型還沒載入")
            tag = model_tag(asr)
            if j.get("text_input"):   # 沒用到語音辨識：代號的辨識部分寫 text
                tag = "local-text|" + tag.split("|", 1)[-1]
            log(f"job {j['id']} {j['kind']}{' text' if j.get('text_input') else ''} {j.get('size', 0) // 1024} KB, "
                f"{float(j.get('duration') or 0):.0f}s, asr={asr or CUR['asr']}")
            text = process(path, j["kind"], j.get("recorded_at", ""), float(j.get("duration") or 0),
                           bool(j.get("text_input")), asr, j.get("dialect"), j.get("report_lang") or "zh", j.get("lang"),
                           j.get("domain") or "auto", j.get("country"), j.get("goal"), j.get("vocab"))
        return {"text": text, "model": tag}
    except Exception as e:  # noqa: BLE001
        log(f"  failed: {e}")
        raise RuntimeError(f"大D的主機處理失敗：{e}") from e
    finally:
        busy(-1)


JOBS = Jobs(os.path.join(HERE, "jobs"), run_job, log)


def lan_url():
    try:
        name = subprocess.run(["scutil", "--get", "LocalHostName"], capture_output=True, text=True).stdout.strip()
    except OSError:
        name = ""
    return f"http://{name or 'localhost'}.local:{PORT}"


# 防猜存取碼：同一個來源 IP 錯 10 次就封 1 小時（服務透過固定 IP 對外開放，誰都連得到）
MAX_FAILS = 10
BLOCK_SECONDS = 3600
import talk  # noqa: E402  遠距對話（兩地即時翻譯）的中繼

TALK = talk.Rooms(contacts_path=os.path.join(HERE, "talk_contacts.json"))   # 通話對象（只有名字、語言、雜湊）

FAILS = {}   # ip → [錯幾次, 封到何時]
FAILS_LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    timeout = 120   # 連上來卻不送資料的連線 2 分鐘後斷掉，不會一直佔著

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        ip = self.client_address[0]
        now = time.time()
        with FAILS_LOCK:
            rec = FAILS.get(ip)
            if rec and rec[1] > now:
                blocked = True
            else:
                blocked = False
                if rec and rec[1] and rec[1] <= now:
                    FAILS.pop(ip, None)   # 封鎖時間過了，重新計算
        if blocked:
            self._json(429, {"error": "存取碼錯太多次，請一小時後再試"})
            return False
        # 主存取碼或配對過的裝置存取碼（固定時間比對：不能從回應快慢猜出存取碼）
        ok, dev = PAIR.check(self.headers.get("Authorization", ""))
        if ok:
            self.device_id = dev
            with FAILS_LOCK:
                FAILS.pop(ip, None)
            return True
        with FAILS_LOCK:
            rec = FAILS.setdefault(ip, [0, 0])
            rec[0] += 1
            if rec[0] >= MAX_FAILS:
                rec[1] = now + BLOCK_SECONDS
                log(f"blocked {ip} for {BLOCK_SECONDS // 60} min after {rec[0]} wrong codes")
        self._json(401, {"error": "存取碼不對"})
        return False

    def _body(self):
        n = int(self.headers.get("Content-Length", "0") or 0)
        if n > 10_000:
            raise ValueError("too big")
        return json.loads(self.rfile.read(n) or b"{}")

    def _dev(self):
        return getattr(self, "device_id", None)

    def _admin_only(self):
        """只有管理者能做的事（換模型、改配對密碼、管別台裝置）。不是 → 回 403。"""
        if PAIR.is_admin(self._dev()):
            return True
        self._json(403, {"error": "只有大D的主機的管理者可以做這件事"})
        return False

    def _upload(self):
        """/v1/jobs、/v1/process 共用：讀標頭 → (參數, 錯誤訊息)。body 還沒讀。"""
        try:
            length = int(self.headers.get("Content-Length", "0") or 0)
        except ValueError:
            length = 0
        if length <= 0 or length > 1_000_000_000:
            return None, "沒有錄音檔或檔案太大"
        asr = lang_asr(self.headers.get("X-Lang", ""))   # None → 設定頁選的
        if asr and asr not in ASR_OPTIONS:
            asr = None
        dialect = self.headers.get("X-Dialect", "")   # 客語腔調：sixian / hailu / dapu / raoping / zhaoan / nansixian
        try:
            duration = float(self.headers.get("X-Duration", "0") or 0)
        except ValueError:
            duration = 0.0
        kind = self.headers.get("X-Kind", "meeting")
        return {"size": length, "kind": kind if kind in ("meeting", "food") else "meeting",
                "text_input": self.headers.get("X-Input", "") == "text",
                "recorded_at": self.headers.get("X-Recorded-At", "")[:40], "duration": duration,
                "asr": asr, "dialect": dialect if dialect in HAKKA_DIALECTS else None,
                "report_lang": report_lang(self.headers.get("X-Report-Lang", "")),
                "lang": (self.headers.get("X-Lang", "") or "")[:8] or None,
                "domain": (self.headers.get("X-Domain", "") or "auto")[:24],
                "country": (self.headers.get("X-Food-Country", "") or "")[:2].upper() or None,
                "goal": (self.headers.get("X-Food-Goal", "") or "")[:12] or None,
                "vocab": (self.headers.get("X-Vocab", "") or "")[:6000] or None}, None

    def _receive(self, params):
        """把上傳的內容寫進佇列（JOBS.submit）。"""
        def write(path):
            left = params["size"]
            with open(path, "wb") as f:
                while left > 0:
                    buf = self.rfile.read(min(1 << 20, left))
                    if not buf:
                        raise ConnectionError("上傳中斷")
                    f.write(buf)
                    left -= len(buf)
        return JOBS.submit(self._dev() or "main", params, write)

    def _jobs_post(self):
        params, err = self._upload()
        if err:
            return self._json(400, {"error": err})
        try:
            j = self._receive(params)
        except QueueFull as e:
            return self._json(503, {"error": str(e)})
        except (ConnectionError, OSError) as e:
            log(f"  upload failed: {e}")
            return
        self._json(202, JOBS.view(j))

    def _job_id(self):
        return self.path.split("?")[0][len("/v1/jobs/"):]

    def _local_only(self):
        """配對頁只給區網 / 本機：從外面（經路由器轉埠）看不到 QR 碼。"""
        try:
            ip = ipaddress.ip_address(self.client_address[0])
        except ValueError:
            return False
        return ip.is_private or ip.is_loopback

    # ---------- 遠距對話（talk.py）：房間存取碼放在 X-Talk-Token，不是主機的存取碼 ----------
    def _talk_room(self):
        parts = self.path.split("?")[0].strip("/").split("/")   # v1 talk <room> [msg]
        return parts[2] if len(parts) >= 3 else ""

    def _talk(self, fn):
        try:
            return self._json(200, fn())
        except talk.TalkError as e:
            return self._json(e.code, {"error": str(e)})
        except (ValueError, TypeError):
            return self._json(400, {"error": "格式不對"})

    def _talk_link(self, res):
        import urllib.parse
        res["link"] = "dylan://talk?" + urllib.parse.urlencode({
            "url": CFG.get("public_url") or lan_url(), "lan": lan_url(), "room": res["room"], "code": res["code"]})
        return res

    def do_GET(self):
        if self.path.startswith("/v1/talk/") and "/audio/" in self.path:
            parts = self.path.split("?")[0].strip("/").split("/")   # v1 talk <room> audio <aid>
            try:
                data, mime = TALK.get_audio(parts[2], self.headers.get("X-Talk-Token", ""), parts[4] if len(parts) > 4 else "")
            except talk.TalkError as e:
                return self._json(e.code, {"error": str(e)})
            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)
            return
        if self.path.startswith("/v1/talk/"):
            import urllib.parse
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            return self._talk(lambda: TALK.poll(self._talk_room(), self.headers.get("X-Talk-Token", ""),
                                                int((q.get("after") or ["0"])[0]), float((q.get("wait") or ["0"])[0])))
        if self.path.split("?")[0] == "/pair":
            if not self._local_only():
                return self._json(403, {"error": "配對頁只能在家裡的網路打開"})
            body = PAIR.page(CFG.get("public_url") or lan_url(), lan_url()).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/v1/devices":
            if not self._authorized():
                return
            return self._json(200, {"devices": PAIR.devices(self._dev()),
                                    "password_set": PAIR.has_password, "admin": PAIR.is_admin(self._dev())})
        if self.path.split("?")[0] == "/v1/jobs" or self.path.startswith("/v1/jobs/"):
            if not self._authorized():
                return
            admin = PAIR.is_admin(self._dev())
            if self.path.startswith("/v1/jobs/"):
                j = JOBS.get(self._job_id(), self._dev(), admin)
                # ?wait=秒（最多 1500）：還沒做完就先等著，做完或逾時才回（iPhone 在背景只能靠這種長等待拿結果）
                m = re.search(r"[?&]wait=(\d+)", self.path)
                if j and m and j["state"] not in ("done", "error"):
                    JOBS.wait(j["id"], min(int(m.group(1)), 1500))
                    j = JOBS.get(self._job_id(), self._dev(), admin)
                return self._json(200, JOBS.view(j)) if j else self._json(404, {"error": "找不到這筆工作（可能已經過期被刪掉）"})
            with JOBS.cond:
                mine = [j for j in JOBS.jobs.values() if admin or j["dev"] == self._dev()]
            return self._json(200, {"jobs": [{k: v for k, v in JOBS.view(j).items() if k != "text"} for j in mine]})
        if self.path not in ("/v1/health", "/v1/models"):
            return self._json(404, {"error": "not found"})
        if not self._authorized():
            return
        if self.path == "/v1/health":
            return self._json(200, status(self._dev()))
        opts = lambda d: [{"id": k, "label": v["label"]} for k, v in d.items() if asr_ready(k)]  # noqa: E731
        oa = [{"id": llm_tag(), "object": "model", "created": 0, "owned_by": "dylan"}]
        self._json(200, {**status(self._dev()), "asr_options": opts(ASR_OPTIONS), "llm_options": opts(LLM_OPTIONS),
                         "langs": [k for k in LANG_ASR if asr_ready(lang_asr(k))],
                         "object": "list", "data": oa})

    def _config(self):
        """POST /v1/config {"asr": id, "llm": id} → 存起來，背景換模型（可能要下載，先回 202，App 用 /v1/health 看進度）。"""
        try:
            n = int(self.headers.get("Content-Length", "0") or 0)
            if n > 10_000:
                return self._json(400, {"error": "格式不對"})
            body = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return self._json(400, {"error": "格式不對"})
        asr, llm = body.get("asr", CUR["asr"]), body.get("llm", CUR["llm"])
        if asr not in ASR_OPTIONS or llm not in LLM_OPTIONS or not asr_ready(asr):
            return self._json(400, {"error": "沒有這個模型"})
        CFG["asr"], CFG["llm"] = asr, llm
        save_config()
        if asr == CUR["asr"] and llm == CUR["llm"]:
            return self._json(200, status(self._dev()))
        CUR["state"] = "loading"

        def work():
            with GPU:   # 正在處理的那筆做完才換
                apply_models(asr, llm)
        threading.Thread(target=work, daemon=True).start()
        self._json(202, status(self._dev()))

    def _pair(self):
        ip = self.client_address[0]
        try:
            body = self._body()
        except ValueError:
            return self._json(400, {"error": "格式不對"})
        try:
            token, dev = PAIR.pair(ip, password=body.get("password"), code=body.get("code"), name=body.get("name", ""))
        except PermissionError as e:
            return self._json(429 if "一小時" in str(e) else 401, {"error": str(e)})
        self._json(200, {"token": token, "id": dev})

    def _chat(self):
        """POST /v1/chat/completions（OpenAI 相容）。"""
        def err(code, msg, kind="invalid_request_error"):
            self._json(code, {"error": {"message": msg, "type": kind}})
        try:
            n = int(self.headers.get("Content-Length", "0") or 0)
            if n <= 0 or n > 400_000:
                return err(400, "請求太大或是空的（上限 400 KB）")
            body = json.loads(self.rfile.read(n))
        except ValueError:
            return err(400, "格式不對（要 JSON）")
        messages = oa_messages(body.get("messages"))
        if not messages:
            return err(400, "messages 是空的")
        max_tokens = int(body.get("max_tokens") or body.get("max_completion_tokens") or 1024)
        max_tokens = max(1, min(max_tokens, OA_MAX_TOKENS))
        temperature = float(body.get("temperature", 0.7) if body.get("temperature") is not None else 0.7)
        think = bool(body.get("enable_thinking", False))
        stream = bool(body.get("stream", False))
        cid = "chatcmpl-" + secrets.token_hex(12)
        created = int(time.time())
        t0 = time.time()
        busy(1)
        try:
            with GPU:   # 跟 App 的錄音報告排同一個隊
                if not CUR["llm_obj"]:
                    return err(503, CUR["error"] or "模型還沒載入，請稍後再試", "server_error")
                tag = llm_tag()
                if not stream:
                    text, n_p, n_g, finish = oa_generate(messages, max_tokens, temperature, think)
                    self._json(200, {"id": cid, "object": "chat.completion", "created": created, "model": tag,
                                     "choices": [{"index": 0, "message": {"role": "assistant", "content": text},
                                                  "finish_reason": finish}],
                                     "usage": {"prompt_tokens": n_p, "completion_tokens": n_g, "total_tokens": n_p + n_g}})
                else:
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                    self.send_header("Cache-Control", "no-cache")
                    self.send_header("Connection", "close")
                    self.end_headers()

                    def send(delta, finish=None):
                        chunk = {"id": cid, "object": "chat.completion.chunk", "created": created, "model": tag,
                                 "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
                        self.wfile.write(b"data: " + json.dumps(chunk, ensure_ascii=False).encode("utf-8") + b"\n\n")
                        self.wfile.flush()
                    send({"role": "assistant", "content": ""})
                    _, n_p, n_g, finish = oa_generate(messages, max_tokens, temperature, think,
                                                      on_text=lambda t: send({"content": t}))
                    send({}, finish)
                    self.wfile.write(b"data: [DONE]\n\n")
                    self.wfile.flush()
                log(f"  chat {len(messages)} msgs, {n_p}+{n_g} tokens, {time.time() - t0:.1f}s{' stream' if stream else ''}")
        except (BrokenPipeError, ConnectionResetError):
            log("  chat client gone")
        except Exception as e:  # noqa: BLE001
            log(f"  chat failed: {e}")
            try:
                err(500, f"大D的主機處理失敗：{e}", "server_error")
            except OSError:
                pass
        finally:
            busy(-1)

    def do_POST(self):
        if self.path == "/v1/talk":
            if not self._authorized():
                return
            return self._talk(lambda: self._talk_link(self._talk_create()))
        if self.path == "/v1/talk/join":
            return self._talk(lambda: (lambda b: TALK.join(b.get("room"), b.get("code"), b.get("name"), b.get("lang"),
                                                            contact=b.get("contact")))(self._body()))
        if self.path in ("/v1/talk/presence", "/v1/talk/call", "/v1/talk/dismiss"):
            ct = self.headers.get("X-Contact-Token", "")
            if self.path == "/v1/talk/presence":
                return self._talk(lambda: (lambda b: TALK.presence(ct, b.get("watch") or [], b.get("name"), b.get("lang")))(self._body()))
            if self.path == "/v1/talk/call":
                return self._talk(lambda: (lambda b: TALK.call(ct, b.get("to"), b.get("name"), b.get("lang")))(self._body()))
            return self._talk(lambda: (lambda b: TALK.dismiss(ct, b.get("room")))(self._body()))
        if self.path.startswith("/v1/talk/") and self.path.endswith("/audio"):
            n = int(self.headers.get("Content-Length", "0") or 0)
            if n <= 0 or n > talk.MAX_AUDIO:
                return self._json(413, {"error": "原聲片段太大"})
            data = self.rfile.read(n)
            return self._talk(lambda: TALK.put_audio(self._talk_room(), self.headers.get("X-Talk-Token", ""), data,
                                                     self.headers.get("Content-Type", "audio/mp4")))
        if self.path.startswith("/v1/talk/") and self.path.endswith("/msg"):
            return self._talk(lambda: (lambda b: TALK.post(self._talk_room(), self.headers.get("X-Talk-Token", ""),
                                                            b.get("line"), b.get("text"), b.get("lang"), b.get("final"),
                                                            audio=b.get("audio")))(self._body()))
        if self.path == "/v1/chat/completions":
            return self._chat() if self._authorized() else None
        if self.path == "/v1/pair":
            return self._pair()
        if self.path in ("/v1/password", "/v1/devices/revoke", "/v1/devices/admin"):
            if not self._authorized():
                return
            try:
                body = self._body()
            except ValueError:
                return self._json(400, {"error": "格式不對"})
            target = str(body.get("id", ""))
            # 一般裝置只能取消自己；其他都要管理者
            if not (self.path == "/v1/devices/revoke" and target == self._dev()) and not self._admin_only():
                return
            if self.path == "/v1/devices/admin":
                try:
                    ok = PAIR.set_admin(target, bool(body.get("admin")))
                except ValueError as e:
                    return self._json(400, {"error": str(e)})
                return self._json(200, {"ok": True}) if ok else self._json(404, {"error": "沒有這台裝置"})
            if self.path == "/v1/password":
                try:
                    PAIR.set_password(str(body.get("password", "")))
                except ValueError as e:
                    return self._json(400, {"error": str(e)})
                return self._json(200, {"ok": True})
            if not PAIR.revoke(target):
                return self._json(404, {"error": "沒有這台裝置"})
            return self._json(200, {"ok": True})
        if self.path == "/v1/config":
            return self._config() if self._authorized() and self._admin_only() else None
        if self.path == "/v1/jobs":
            return self._jobs_post() if self._authorized() else None
        if self.path != "/v1/process":
            return self._json(404, {"error": "not found"})
        if not self._authorized():
            return
        params, err = self._upload()
        if err:
            return self._json(400, {"error": err})
        try:
            j = self._receive(params)
        except QueueFull as e:
            return self._json(503, {"error": str(e)})
        except (ConnectionError, OSError) as e:
            log(f"  upload failed: {e}")
            return
        # 長錄音在這台 Mac 上可能要半小時以上（還要排隊）：先回 200，等待期間每 20 秒送一個空白（JSON 前面的空白是合法的），
        # App 的連線才不會因為太久沒資料而逾時；結果（或錯誤）最後用 JSON 送出。
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Connection", "close")
        self.end_headers()
        while True:
            done = JOBS.wait(j["id"], 20)
            if done is not None:
                break
            try:
                self.wfile.write(b" ")
                self.wfile.flush()
            except OSError:
                # App 斷線了：這筆照樣做完，結果留著（24 小時後刪）
                return log(f"  client gone while waiting for {j['id']}")
        v = JOBS.view(done)
        result = {"text": v["text"], "model": v["model"]} if v["state"] == "done" else {"error": v.get("error") or "大D的主機處理失敗"}
        try:
            self.wfile.write(json.dumps(result, ensure_ascii=False).encode("utf-8"))
            self.wfile.flush()
            JOBS.cancel(j["id"], None, admin=True)   # 結果已經送到，不用留
        except OSError:
            log("  client gone before result was sent")

    def _talk_create(self):
        b = self._body()
        return TALK.create(self._dev(), b.get("name"), b.get("lang"), contact=b.get("contact"))

    def do_DELETE(self):
        if self.path.startswith("/v1/talk/"):
            return self._talk(lambda: TALK.leave(self._talk_room(), self.headers.get("X-Talk-Token", "")))
        if not self.path.startswith("/v1/jobs/"):
            return self._json(404, {"error": "not found"})
        if not self._authorized():
            return
        if not JOBS.cancel(self._job_id(), self._dev(), PAIR.is_admin(self._dev())):
            return self._json(404, {"error": "找不到這筆工作"})
        self._json(200, {"ok": True})

    def log_message(self, fmt, *args):
        log(f"http {self.client_address[0]} " + fmt % args)


if __name__ == "__main__":
    JOBS.start()   # 一個執行緒依序處理佇列（重開前沒做完的會接著做）
    srv = ThreadingHTTPServer(("0.0.0.0", CFG.get("port", PORT)), Handler)
    log(f"listening on :{CFG.get('port', PORT)}")
    srv.serve_forever()
