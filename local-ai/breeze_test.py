"""Breeze-ASR-26（台語，MLX）測試：venv/bin/python breeze_test.py <音檔> [模型 repo]"""
import sys, time, warnings
warnings.filterwarnings("ignore")
import wave
import numpy as np
import mlx_whisper, opencc


def load_wav(path):
    """16kHz 單聲道 16-bit wav → float32 陣列（mlx-whisper 讀檔要 ffmpeg，這台 Mac 沒有）。"""
    import torchaudio
    audio, sr = torchaudio.load(path)          # afconvert 產生的 wav 是 extensible 格式，內建 wave 讀不了
    return audio.mean(dim=0).numpy().astype(np.float32)
repo = sys.argv[2] if len(sys.argv) > 2 else "doggy8088/Breeze-ASR-26-MLX-8bit"
conv = opencc.OpenCC("s2twp")
t = time.time()
r = mlx_whisper.transcribe(load_wav(sys.argv[1]), path_or_hf_repo=repo, language="zh", condition_on_previous_text=False, verbose=None)
for s in r["segments"]:
    print(f"[{int(s['start'])//60:02d}:{int(s['start'])%60:02d}] {conv.convert(s['text']).replace('臺','台')}")
print(f"--- {time.time()-t:.1f}s ({repo})")
