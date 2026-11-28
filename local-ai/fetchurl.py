"""大D的主機：從連結抓音訊（YouTube、Podcast 頁面等，用 yt-dlp），以及收翻譯錯誤回報。

POST /v1/fetch_url  {"url": "..."}  → 200 音訊檔（Content-Type 依格式；X-Title = 標題 base64）
                                      501 主機沒裝 yt-dlp；400 網址不對；422 抓不到
POST /v1/feedback   {"kind": "translation", ...} → 存到 feedback.jsonl（只在這台主機）

只抓音訊、不轉檔（不需要 ffmpeg）：YouTube 通常是 m4a；手機端用 MediaExtractor 解碼。
請只匯入你有權使用的內容（自己的影片、公開授權、課堂錄影等）。
"""
import base64
import glob
import json
import os
import shutil
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlparse

MAX_BYTES = 300 * 1024 * 1024       # 300 MB
MAX_SECONDS = 4 * 3600              # 4 小時
TIMEOUT = 15 * 60                   # 抓檔最多 15 分鐘
FEEDBACK_MAX = 5_000                # 一筆回報最多字數

_lock = threading.Lock()


def ytdlp_cmd():
    """venv 裡的 yt-dlp（setup.sh 裝的）或系統的。沒有回 None。"""
    here = os.path.dirname(os.path.abspath(__file__))
    for p in (os.path.join(here, "venv", "bin", "yt-dlp"), shutil.which("yt-dlp")):
        if p and os.path.exists(p):
            return p
    return None


def valid_url(url):
    try:
        u = urlparse((url or "").strip())
    except ValueError:
        return False
    return u.scheme in ("http", "https") and bool(u.netloc) and len(url) < 2000


def fetch(url, runner=subprocess.run):
    """回傳 (路徑, 標題, mime, 暫存資料夾)。呼叫端送完要刪暫存資料夾。失敗丟 ValueError / RuntimeError / FileNotFoundError。"""
    if not valid_url(url):
        raise ValueError("網址不對")
    exe = ytdlp_cmd()
    if not exe:
        raise FileNotFoundError("大D的主機沒有安裝 yt-dlp（重新執行 setup.sh）")
    tmp = tempfile.mkdtemp(prefix="dylan_fetch_")
    cmd = [exe, "--no-playlist", "--no-progress", "-f", "bestaudio[ext=m4a]/bestaudio/best",
           "--max-filesize", str(MAX_BYTES), "--match-filter", f"duration < {MAX_SECONDS}",
           "--print-to-file", "%(title)s", os.path.join(tmp, "title.txt"),
           "-o", os.path.join(tmp, "audio.%(ext)s"), url.strip()]
    try:
        r = runner(cmd, capture_output=True, text=True, timeout=TIMEOUT)
    except subprocess.TimeoutExpired:
        shutil.rmtree(tmp, ignore_errors=True)
        raise RuntimeError("抓檔太久（超過 15 分鐘）")
    files = [f for f in glob.glob(os.path.join(tmp, "audio.*")) if not f.endswith(".part")]
    if r.returncode != 0 or not files:
        shutil.rmtree(tmp, ignore_errors=True)
        msg = (r.stderr or r.stdout or "").strip().splitlines()
        raise RuntimeError("抓不到這個連結的音訊" + (f"：{msg[-1][:200]}" if msg else ""))
    path = files[0]
    title = ""
    try:
        with open(os.path.join(tmp, "title.txt"), encoding="utf-8") as f:
            title = f.read().strip().splitlines()[0][:200]
    except (OSError, IndexError):
        pass
    ext = os.path.splitext(path)[1].lower()
    mime = {".m4a": "audio/mp4", ".mp4": "video/mp4", ".webm": "audio/webm", ".mp3": "audio/mpeg",
            ".ogg": "audio/ogg", ".opus": "audio/ogg", ".wav": "audio/wav"}.get(ext, "application/octet-stream")
    return path, title, mime, tmp


def title_header(title):
    return base64.b64encode((title or "").encode("utf-8")).decode("ascii")


def save_feedback(path, body, device=None, now=None):
    """一行一筆存起來（JSON Lines）。只留需要的欄位。"""
    if not isinstance(body, dict):
        raise ValueError("格式不對")
    rec = {"at": int(now if now is not None else time.time()), "device": device or ""}
    for k in ("kind", "source", "target", "original", "translated", "corrected", "note", "app"):
        v = body.get(k)
        if v is not None:
            rec[k] = str(v)[:FEEDBACK_MAX]
    if not rec.get("original") and not rec.get("note"):
        raise ValueError("回報是空的")
    with _lock, open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    return rec
