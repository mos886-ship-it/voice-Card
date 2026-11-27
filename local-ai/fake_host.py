"""假的大D的主機：跑真正的 server.py（HTTP、配對、管理者、工作佇列），只把模型換成假的，哪台電腦都能跑。
給測 App 用（Android 模擬器：adb reverse tcp:8765 tcp:8765，App 位址填 http://127.0.0.1:8765）。

    python fake_host.py [--port 8765] [--seconds 8] [--password testpass123]

- 每筆「處理」等 --seconds 秒，回固定格式的報告（標題寫第幾筆、哪台裝置送的）。
- server.py / pairing.py / jobs.py 複製到暫存資料夾再載入：config.json、jobs/ 都寫在那裡，不會弄髒 repo。
- 啟動時印出主存取碼；有給 --password 就設好配對密碼。
"""
import argparse
import os
import shutil
import sys
import tempfile
import threading
import time
import types

HERE = os.path.dirname(os.path.abspath(__file__))
ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=8765)
ap.add_argument("--seconds", type=float, default=8)
ap.add_argument("--password", default="")
ap.add_argument("--dir", default="", help="用這個資料夾（重開時保留佇列，測重開機接著做）")
args = ap.parse_args()

work = args.dir or tempfile.mkdtemp(prefix="dylan-fake-")
os.makedirs(work, exist_ok=True)
shutil.copy(os.path.join(HERE, "..", "shared", "src", "main", "assets", "report_domains.json"), work)   # 領域專家模式清單
for f in ("server.py", "pairing.py", "jobs.py"):
    shutil.copy(os.path.join(HERE, f), work)
cfg = os.path.join(work, "config.json")
if not os.path.exists(cfg):
    with open(cfg, "w", encoding="utf-8") as f:
        f.write('{"token": "fake-main-token-' + os.urandom(6).hex() + '", "port": %d}' % args.port)

# 模型相關的套件換成空殼（server.py 載入時會用到）
for name in ("numpy", "opencc"):
    sys.modules[name] = types.ModuleType(name)
sys.modules["opencc"].OpenCC = lambda *_: types.SimpleNamespace(convert=lambda t: t)
funasr = types.ModuleType("funasr")
funasr.AutoModel = lambda **_: None
sys.modules["funasr"] = funasr
sys.argv = [sys.argv[0]]
sys.path.insert(0, work)
import server as S  # noqa: E402

S.CUR.update({"asr": "funasr", "llm": S.DEFAULT_LLM, "llm_obj": ("fake", None), "state": "ready", "error": ""})
COUNT = {"n": 0}
LOCK = threading.Lock()


def fake_process(path, kind, recorded_at, duration, text_input=False, asr=None, dialect=None, rlang="zh", lang=None,
                 domain="auto", country=None, *_rest, **_kw):
    with LOCK:
        COUNT["n"] += 1
        n = COUNT["n"]
    time.sleep(args.seconds)
    size = os.path.getsize(path)
    if text_input:   # 匯入的文字 / 翻譯記錄：回報收到什麼、領域專家模式有沒有載入
        with open(path, encoding="utf-8", errors="replace") as f:
            first = f.read().strip().splitlines()[0][:40]
        rules = S.with_domain(S.REPORT_SYSTEM, domain)[len(S.REPORT_SYSTEM):].strip()
        return (f"TITLE: 假主機文字第 {n} 筆\nSUMMARY: 收到文字記錄（領域設定 {domain}）。\n## 摘要\n開頭：{first}\n"
                f"## 重點\n- 領域規則：{rules[:60] or '（無）'}\n## 專業重點\n領域：測試\n")
    if kind == "food":
        return (f"TITLE: 午餐：假餐點 {n}\nSUMMARY: 午餐約 600 大卡，蛋白質 30g\n"
                f"NUTRITION: meal=午餐; kcal=600; protein=30; carbs=70; fat=20\n## 飲食內容\n假資料\n\n"
                f"{S.TRANSCRIPT_MARKER}\n[00:00] 說話者A：我吃了假餐點")
    return (f"TITLE: 假主機第 {n} 筆\nSUMMARY: 假主機處理的第 {n} 筆（{kind}，{duration:.0f} 秒，{size // 1024} KB，"
            f"{asr or 'funasr'}）。\n## 摘要\n這是假的大D的主機產生的報告，第 {n} 筆，錄音時間 {recorded_at}。\n"
            f"## 說話者\n| 說話者 | 名字 | 角色 | 主要觀點 |\n|---|---|---|---|\n| 說話者A | 測試員 | 測試 | 排隊測試 |\n"
            f"## 重點\n- 排隊第 {n} 筆\n\n{S.TRANSCRIPT_MARKER}\n[00:00] 說話者A：這是第 {n} 筆測試錄音。")


S.process = fake_process
if args.password:
    S.PAIR.set_password(args.password)
print("work dir:", work, flush=True)
print("main token:", S.CFG["token"], flush=True)
S.JOBS.start()
srv = S.ThreadingHTTPServer(("0.0.0.0", args.port), S.Handler)
print(f"fake host on :{args.port}, {args.seconds}s per job", flush=True)
srv.serve_forever()
