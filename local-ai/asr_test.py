"""FunASR 測試：繁中辨識＋分說話者（大D 自架引擎第 1 步）。

用法：venv/bin/python asr_test.py <16kHz 單聲道 wav> [cpu|mps]
輸出跟 Gemini 逐字稿同格式：[mm:ss] 說話者A：內容（簡體轉台灣繁體）
"""
import sys
import time
import warnings

warnings.filterwarnings("ignore")

import opencc
from funasr import AutoModel

t0 = time.time()
model = AutoModel(
    model="paraformer-zh",   # 中文辨識
    vad_model="fsmn-vad",    # 切句
    punc_model="ct-punc",    # 標點
    spk_model="cam++",       # 分說話者
    disable_update=True,
    device=sys.argv[2] if len(sys.argv) > 2 else "cpu",   # mps = Apple 晶片 GPU
    log_level="ERROR",
)
t1 = time.time()
res = model.generate(input=sys.argv[1], batch_size_s=300)
t2 = time.time()

conv = opencc.OpenCC("s2twp")   # 簡體 → 台灣繁體（含用詞）
letters = "ABCDEFGHIJ"
for s in res[0].get("sentence_info", []):
    sec = int(s["start"] / 1000)
    spk = s.get("spk", 0)
    name = "說話者" + (letters[spk] if spk < len(letters) else str(spk))
    print(f"[{sec // 60:02d}:{sec % 60:02d}] {name}：{conv.convert(s['text'])}")
print(f"--- load {t1 - t0:.1f}s, recognize {t2 - t1:.1f}s")
