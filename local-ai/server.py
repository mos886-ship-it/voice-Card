"""大D 自架 AI 服務（這台 Mac）：錄音 → 語音辨識＋分說話者 → 語言模型寫報告。
模型可以換（App 設定頁）：辨識 FunASR / Breeze-ASR-26（台語），報告 Qwen3-8B / SARC 台語 12B / 自己加的。

回傳格式跟 Gemini 一樣（TITLE / SUMMARY / Markdown / ===TRANSCRIPT=== / [mm:ss] 說話者X：…），
App 的報告解析、說話者名字、飲食 NUTRITION 解析都直接沿用。

端點（都要 Authorization: Bearer <存取碼>，存取碼在 config.json）：
  GET  /v1/health   → {"ok": true, "asr": 名稱, "llm": 名稱, "asr_id", "llm_id", "model", "state": ready|loading|error, "busy"}
  GET  /v1/models   → health 的內容 ＋ asr_options / llm_options（[{id, label}]）
  POST /v1/config   → {"asr": id, "llm": id}；存進 config.json，背景換模型（第一次會下載），回 202
  POST /v1/process  → body 是錄音檔（m4a / wav…）；標頭 X-Kind: meeting|food、X-Recorded-At、X-Duration（秒）
                       X-Input: text 時 body 是 UTF-8 文字（匯入的會議紀錄 / 逐字稿），跳過語音辨識直接寫報告
                     回 {"text": "<Gemini 格式>", "model": "local-<asr>|<llm>"，例如 local-funasr|qwen3-8b}，失敗回 {"error": "..."}
                     （處理中每 20 秒先送一個空白保持連線，所以狀態碼一律 200，結果看 JSON）
  配對（pairing.py）：
  POST /v1/pair           → {"password": …} 或 {"code": QR 配對碼}，加 "name"（裝置名）；不用存取碼 → {"token", "id"}
  GET  /v1/devices        → {"devices": [{id, name, created, last_seen, via, current}], "password_set"}
  POST /v1/devices/revoke → {"id": …} 取消某台裝置
  POST /v1/password       → {"password": …} 設定 / 更換配對密碼（至少 8 個字）
  GET  /pair              → 配對用的 QR 碼網頁（只給區網 / 本機）
  設定密碼也可以在主機上：venv/bin/python server.py --set-password

一次只處理一筆（同一顆 GPU），其他請求排隊。啟動：venv/bin/python server.py（launchd 會在登入時自動啟動）。
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
               "repo": "doggy8088/Breeze-ASR-26-MLX-8bit"}   # 8bit：記憶體省一半，準確度差不多,
}
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


def model_tag():
    a = ASR_OPTIONS.get(CUR["asr"], {}).get("tag", CUR["asr"])
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
一律使用繁體中文（台灣用語）。"""

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
- 依台灣常見的份量與做法估算（便當、小吃、麵店、手搖飲、便利商店等）。
- 使用者明確說出的份量、杯型、甜度、冰塊、加料一定照用，不可以改成別的（例如說「大杯半糖」就是大杯、半糖），只有沒說的部分才用預設。
- 沒說清楚的地方用常見預設，並在「假設與待確認」寫出來。
- 數字一律取整數；熱量單位大卡（kcal），營養素單位公克（g）。
- 餐別依描述與錄音時間判斷：早餐、午餐、晚餐、點心、宵夜、飲料。
- 若內容不是在描述飲食，TITLE 寫「非飲食內容」，NUTRITION 全部填 0。
- 一律使用繁體中文（台灣用語）。這是估算，不要做醫療診斷。

輸出格式（嚴格遵守，不要加其他前言）：
第 1 行：TITLE: <餐別：主要食物，15 字內，例如「午餐：牛肉麵＋燙青菜」>
第 2 行：SUMMARY: <一句話，含總熱量與蛋白質，例如「午餐約 780 大卡，蛋白質 35g」>
第 3 行：NUTRITION: meal=<餐別>; kcal=<整數>; protein=<整數>; carbs=<整數>; fat=<整數>
第 4 行起：Markdown，依序包含：
## 飲食內容
（用表格：食物 | 份量 | 熱量(kcal) | 蛋白質(g) | 碳水(g) | 脂肪(g)；每樣食物一列）
## 合計
（一行：約 X 大卡；蛋白質 Xg、碳水 Xg、脂肪 Xg）
## 營養小提醒
（1～3 點）
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
    return [(s["start"] / 1000, s["end"] / 1000, s.get("spk", 0), s["text"]) for s in res[0].get("sentence_info", [])]


def breeze_segments(wav):
    """Breeze-ASR-26（Whisper，MLX）辨識文字；每個字的說話者照 FunASR cam++ 的時間段（字的中點落在誰說話的時段）。"""
    import mlx_whisper
    import torchaudio
    audio, _ = torchaudio.load(wav)   # afconvert 的 wav 是 extensible 格式，內建 wave 讀不了
    r = mlx_whisper.transcribe(audio.mean(dim=0).numpy().astype(np.float32),
                               path_or_hf_repo=ASR_OPTIONS["breeze"]["repo"], language="zh",
                               condition_on_previous_text=False, word_timestamps=True,
                               hallucination_silence_threshold=2.0, verbose=None)   # 靜音處不要亂生字
    spans = funasr_segments(wav)

    def who(t, prev):
        best, spk = None, prev
        for s0, s1, k, _ in spans:
            d = 0 if s0 <= t <= s1 else min(abs(t - s0), abs(t - s1))
            if best is None or d < best:
                best, spk = d, k
        return spk

    out = []   # (開始, 結束, 說話者, 文字)
    for seg in r["segments"]:
        for w in seg.get("words") or [{"start": seg["start"], "end": seg["end"], "word": seg["text"]}]:
            word = w["word"]
            if not word.strip():
                continue
            spk = who((w["start"] + w["end"]) / 2, out[-1][2] if out else 0)
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


def transcribe(wav):
    """→ [mm:ss] 說話者X：內容（同一人連續說的話合併成一行，最長約 30 秒一段）。"""
    segs = breeze_segments(wav) if CUR["asr"] == "breeze" else funasr_segments(wav)
    lines, cur = [], None
    for start, _, spk, text in segs:
        name = spk_name(spk)
        text = tw(text)
        if cur and cur["name"] == name and start - cur["start"] < 30:
            sep = "" if re.search(r"[，。！？、,.!?]$", cur["text"]) else "，"
            cur["text"] += (sep if CUR["asr"] == "breeze" else "") + text
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


def process(audio_path, kind, recorded_at, duration, text_input=False):
    t0 = time.time()
    if text_input:
        with open(audio_path, encoding="utf-8", errors="replace") as f:
            transcript = f.read().strip()
        header = f"這是一份匯入的文字記錄（會議紀錄、逐字稿或筆記，不是錄音），時間：{recorded_at}。"
    else:
        wav = to_wav(audio_path)
        transcript = transcribe(wav)
        header = f"錄音時間：{recorded_at}，長度約 {duration / 60:.1f} 分鐘。"
    t1 = time.time()
    if not transcript.strip():
        report = "TITLE: 沒有聽到內容\nSUMMARY: 這段錄音沒有辨識到說話內容。\n## 摘要\n這段錄音沒有辨識到說話內容。"
    elif kind == "food":
        report = chat(FOOD_SYSTEM, f"{header}\n\n逐字稿：\n{transcript}", max_tokens=1500)
    elif len(transcript) <= chunk_chars():
        report = chat(REPORT_SYSTEM, f"{header}\n\n<transcript>\n{transcript}\n</transcript>")
    else:
        # 長錄音：分段做筆記再合併（照 Android LongMeeting 的做法）
        parts = chunks(transcript, chunk_chars())
        notes = []
        for i, p in enumerate(parts):
            log(f"  notes {i + 1}/{len(parts)}")
            notes.append(f"【第 {i + 1} 部分】\n" + chat(NOTES_SYSTEM, p, max_tokens=1200))
        report = chat(REPORT_SYSTEM, f"{header}\n以下是長錄音各部分整理好的筆記（不是逐字稿），請整合成一份報告：\n\n" + "\n\n".join(notes))
    t2 = time.time()
    log(f"  asr {t1 - t0:.1f}s, llm {t2 - t1:.1f}s, transcript {len(transcript)} chars")
    return f"{report}\n\n{TRANSCRIPT_MARKER}\n{transcript}"


# ---------------- HTTP ----------------


def save_config():
    with open(CONFIG, "w", encoding="utf-8") as f:
        json.dump(CFG, f, indent=2, ensure_ascii=False)
    os.chmod(CONFIG, 0o600)


def status():
    with BUSY_LOCK:
        n = BUSY["n"]
    return {"ok": True, "asr": ASR_OPTIONS.get(CUR["asr"], {}).get("label", CUR["asr"]),
            "llm": LLM_OPTIONS.get(CUR["llm"], {}).get("label", CUR["llm"]),
            "asr_id": CUR["asr"], "llm_id": CUR["llm"], "model": model_tag(),
            "state": CUR["state"], "error": CUR["error"], "busy": n > 0}


sys.path.insert(0, HERE)
from pairing import Pairing  # noqa: E402

PAIR = Pairing(CFG, save_config, log)


def lan_url():
    try:
        name = subprocess.run(["scutil", "--get", "LocalHostName"], capture_output=True, text=True).stdout.strip()
    except OSError:
        name = ""
    return f"http://{name or 'localhost'}.local:{PORT}"


# 防猜存取碼：同一個來源 IP 錯 10 次就封 1 小時（服務透過固定 IP 對外開放，誰都連得到）
MAX_FAILS = 10
BLOCK_SECONDS = 3600
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

    def _local_only(self):
        """配對頁只給區網 / 本機：從外面（經路由器轉埠）看不到 QR 碼。"""
        try:
            ip = ipaddress.ip_address(self.client_address[0])
        except ValueError:
            return False
        return ip.is_private or ip.is_loopback

    def do_GET(self):
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
            return self._json(200, {"devices": PAIR.devices(getattr(self, "device_id", None)),
                                    "password_set": PAIR.has_password})
        if self.path not in ("/v1/health", "/v1/models"):
            return self._json(404, {"error": "not found"})
        if not self._authorized():
            return
        if self.path == "/v1/health":
            return self._json(200, status())
        opts = lambda d: [{"id": k, "label": v["label"]} for k, v in d.items()]  # noqa: E731
        self._json(200, {**status(), "asr_options": opts(ASR_OPTIONS), "llm_options": opts(LLM_OPTIONS)})

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
        if asr not in ASR_OPTIONS or llm not in LLM_OPTIONS:
            return self._json(400, {"error": "沒有這個模型"})
        CFG["asr"], CFG["llm"] = asr, llm
        save_config()
        if asr == CUR["asr"] and llm == CUR["llm"]:
            return self._json(200, status())
        CUR["state"] = "loading"

        def work():
            with GPU:   # 正在處理的那筆做完才換
                apply_models(asr, llm)
        threading.Thread(target=work, daemon=True).start()
        self._json(202, status())

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

    def do_POST(self):
        if self.path == "/v1/pair":
            return self._pair()
        if self.path in ("/v1/password", "/v1/devices/revoke"):
            if not self._authorized():
                return
            try:
                body = self._body()
            except ValueError:
                return self._json(400, {"error": "格式不對"})
            if self.path == "/v1/password":
                try:
                    PAIR.set_password(str(body.get("password", "")))
                except ValueError as e:
                    return self._json(400, {"error": str(e)})
                return self._json(200, {"ok": True})
            if not PAIR.revoke(str(body.get("id", ""))):
                return self._json(404, {"error": "沒有這台裝置"})
            return self._json(200, {"ok": True})
        if self.path == "/v1/config":
            return self._config() if self._authorized() else None
        if self.path != "/v1/process":
            return self._json(404, {"error": "not found"})
        if not self._authorized():
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 1_000_000_000:
            return self._json(400, {"error": "沒有錄音檔或檔案太大"})
        kind = self.headers.get("X-Kind", "meeting")
        text_input = self.headers.get("X-Input", "") == "text"
        recorded_at = self.headers.get("X-Recorded-At", "")
        duration = float(self.headers.get("X-Duration", "0") or 0)
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "audio")
            with open(path, "wb") as f:
                left = length
                while left > 0:
                    buf = self.rfile.read(min(1 << 20, left))
                    if not buf:
                        break
                    f.write(buf)
                    left -= len(buf)
            log(f"job {kind}{' text' if text_input else ''} {length // 1024} KB, {duration:.0f}s")
            # 長錄音在這台 Mac 上可能要半小時以上：先回 200，處理期間每 20 秒送一個空白（JSON 前面的空白是合法的），
            # App 的連線才不會因為太久沒資料而逾時；結果（或錯誤）最後用 JSON 送出。
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Connection", "close")
            self.end_headers()
            done = threading.Event()

            def heartbeat():
                while not done.wait(20):
                    try:
                        self.wfile.write(b" ")
                        self.wfile.flush()
                    except OSError:
                        return   # App 斷線了；處理照常做完

            hb = threading.Thread(target=heartbeat, daemon=True)
            hb.start()
            busy(1)
            try:
                with GPU:   # 一次一筆
                    if not CUR["llm_obj"]:
                        raise RuntimeError(CUR["error"] or "模型還沒載入")
                    tag = model_tag()
                    if text_input:   # 沒用到語音辨識：代號的辨識部分寫 text
                        tag = "local-text|" + tag.split("|", 1)[-1]
                    result = {"text": process(path, kind, recorded_at, duration, text_input), "model": tag}
            except Exception as e:  # noqa: BLE001
                log(f"  failed: {e}")
                result = {"error": f"大D的主機處理失敗：{e}"}
            finally:
                busy(-1)
                done.set()
                hb.join()
            try:
                self.wfile.write(json.dumps(result, ensure_ascii=False).encode("utf-8"))
                self.wfile.flush()
            except OSError:
                log("  client gone before result was sent")

    def log_message(self, fmt, *args):
        log(f"http {self.client_address[0]} " + fmt % args)


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("0.0.0.0", CFG.get("port", PORT)), Handler)
    log(f"listening on :{CFG.get('port', PORT)}")
    srv.serve_forever()
