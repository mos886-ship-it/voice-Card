"""大D 自架 AI 服務（這台 Mac）：錄音 → FunASR 逐字稿＋分說話者 → Qwen（MLX，用 GPU）寫報告。

回傳格式跟 Gemini 一樣（TITLE / SUMMARY / Markdown / ===TRANSCRIPT=== / [mm:ss] 說話者X：…），
App 的報告解析、說話者名字、飲食 NUTRITION 解析都直接沿用。

端點（都要 Authorization: Bearer <存取碼>，存取碼在 config.json）：
  GET  /v1/health   → {"ok": true, "asr": ..., "llm": ..., "busy": false}
  POST /v1/process  → body 是錄音檔（m4a / wav…）；標頭 X-Kind: meeting|food、X-Recorded-At、X-Duration（秒）
                     回 {"text": "<Gemini 格式>", "model": "local-funasr|qwen3-8b"}，失敗回 {"error": "..."}
                     （處理中每 20 秒先送一個空白保持連線，所以狀態碼一律 200，結果看 JSON）

一次只處理一筆（同一顆 GPU），其他請求排隊。啟動：venv/bin/python server.py（launchd 會在登入時自動啟動）。
"""
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
LLM_NAME = "mlx-community/Qwen3-8B-4bit"
MODEL_TAG = "local-funasr|qwen3-8b"


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


# ---------------- 模型（啟動時載入一次） ----------------

log("loading models…")
import opencc  # noqa: E402
from funasr import AutoModel  # noqa: E402
from mlx_lm import generate, load  # noqa: E402
from mlx_lm.sample_utils import make_sampler  # noqa: E402

ASR = AutoModel(model="paraformer-zh", vad_model="fsmn-vad", punc_model="ct-punc", spk_model="cam++",
                disable_update=True, device="cpu", log_level="ERROR")   # CPU 比 GPU 快（實測 3.6s vs 5.9s）
LLM, TOK = load(LLM_NAME)
S2TW = opencc.OpenCC("s2twp")
GPU = threading.Lock()
BUSY = {"n": 0}
BUSY_LOCK = threading.Lock()


def busy(delta):
    with BUSY_LOCK:
        BUSY["n"] += delta
log("models ready")

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
CHUNK_CHARS = 9000   # 一次送給 8B 模型的逐字稿長度（16GB 記憶體下的安全值）


# ---------------- 處理 ----------------

def to_wav(src):
    """任何格式 → 16kHz 單聲道 wav（用 macOS 內建 afconvert，不用裝 ffmpeg）。"""
    dst = src + ".wav"
    subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", src, dst],
                   check=True, capture_output=True)
    return dst


def transcribe(wav):
    """FunASR → [mm:ss] 說話者X：內容（同一人連續說的話合併成一行，最長約 30 秒一段）。"""
    res = ASR.generate(input=wav, batch_size_s=300)
    letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    lines, cur = [], None
    for s in res[0].get("sentence_info", []):
        spk = s.get("spk", 0)
        name = "說話者" + (letters[spk] if spk < len(letters) else str(spk))
        text = tw(s["text"])
        start = s["start"] / 1000
        if cur and cur["name"] == name and start - cur["start"] < 30:
            cur["text"] += text
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
    try:
        prompt = TOK.apply_chat_template(messages, add_generation_prompt=True, enable_thinking=False, tokenize=False)
    except TypeError:
        prompt = TOK.apply_chat_template(messages, add_generation_prompt=True, tokenize=False)
    out = generate(LLM, TOK, prompt=prompt, max_tokens=max_tokens, sampler=make_sampler(temp=0.2))
    out = re.sub(r"<think>.*?</think>", "", out, flags=re.S).strip()
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


def process(audio_path, kind, recorded_at, duration):
    t0 = time.time()
    wav = to_wav(audio_path)
    transcript = transcribe(wav)
    t1 = time.time()
    header = f"錄音時間：{recorded_at}，長度約 {duration / 60:.1f} 分鐘。"
    if not transcript.strip():
        report = "TITLE: 沒有聽到內容\nSUMMARY: 這段錄音沒有辨識到說話內容。\n## 摘要\n這段錄音沒有辨識到說話內容。"
    elif kind == "food":
        report = chat(FOOD_SYSTEM, f"{header}\n\n逐字稿：\n{transcript}", max_tokens=1500)
    elif len(transcript) <= CHUNK_CHARS:
        report = chat(REPORT_SYSTEM, f"{header}\n\n<transcript>\n{transcript}\n</transcript>")
    else:
        # 長錄音：分段做筆記再合併（照 Android LongMeeting 的做法）
        parts = chunks(transcript, CHUNK_CHARS)
        notes = []
        for i, p in enumerate(parts):
            log(f"  notes {i + 1}/{len(parts)}")
            notes.append(f"【第 {i + 1} 部分】\n" + chat(NOTES_SYSTEM, p, max_tokens=1200))
        report = chat(REPORT_SYSTEM, f"{header}\n以下是長錄音各部分整理好的筆記（不是逐字稿），請整合成一份報告：\n\n" + "\n\n".join(notes))
    t2 = time.time()
    log(f"  asr {t1 - t0:.1f}s, llm {t2 - t1:.1f}s, transcript {len(transcript)} chars")
    return f"{report}\n\n{TRANSCRIPT_MARKER}\n{transcript}"


# ---------------- HTTP ----------------

CFG = load_config()


class Handler(BaseHTTPRequestHandler):
    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        if self.headers.get("Authorization", "") == f"Bearer {CFG['token']}":
            return True
        self._json(401, {"error": "存取碼不對"})
        return False

    def do_GET(self):
        if self.path != "/v1/health":
            return self._json(404, {"error": "not found"})
        if not self._authorized():
            return
        with BUSY_LOCK:
            n = BUSY["n"]
        self._json(200, {"ok": True, "asr": "FunASR paraformer-zh + cam++", "llm": LLM_NAME, "busy": n > 0})

    def do_POST(self):
        if self.path != "/v1/process":
            return self._json(404, {"error": "not found"})
        if not self._authorized():
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 1_000_000_000:
            return self._json(400, {"error": "沒有錄音檔或檔案太大"})
        kind = self.headers.get("X-Kind", "meeting")
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
            log(f"job {kind} {length // 1024} KB, {duration:.0f}s")
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
                    result = {"text": process(path, kind, recorded_at, duration), "model": MODEL_TAG}
            except Exception as e:  # noqa: BLE001
                log(f"  failed: {e}")
                result = {"error": f"這台 Mac 處理失敗：{e}"}
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
        log("http " + fmt % args)


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("0.0.0.0", CFG.get("port", PORT)), Handler)
    log(f"listening on :{CFG.get('port', PORT)}")
    srv.serve_forever()
