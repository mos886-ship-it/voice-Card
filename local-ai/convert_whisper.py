"""把 Hugging Face（transformers）格式的 Whisper 轉成 mlx-whisper 用的格式（config.json + weights.safetensors）。

用在客語模型 formospeech/whisper-large-v3-taiwanese-hakka（要先在 Hugging Face 申請、`hf auth login`）：

    venv/bin/python convert_whisper.py formospeech/whisper-large-v3-taiwanese-hakka models/hakka-mlx-8bit --bits 8

轉好後 server.py 的「客語」選項就會出現（重啟服務）。原始下載可以用 --delete-source 轉完就刪（省磁碟）。
"""
import argparse
import json
import os
import re
import shutil

import mlx.core as mx
from huggingface_hub import snapshot_download
from mlx.utils import tree_flatten

TOKENIZER_FILES = ["added_tokens.json", "merges.txt", "normalizer.json", "preprocessor_config.json",
                   "special_tokens_map.json", "tokenizer.json", "tokenizer_config.json", "vocab.json",
                   "generation_config.json", "README.md"]

RENAME = [   # (HF 名稱片段, mlx-whisper 名稱片段)，依序套用
    (r"^model\.", ""),
    (r"^proj_out\.weight$", None),                      # 跟 token embedding 共用，不用存
    (r"^encoder\.embed_positions\.weight$", None),      # mlx 用 sinusoids 算
    (r"\.layers\.", ".blocks."),
    (r"\.self_attn\.q_proj\.", ".attn.query."),
    (r"\.self_attn\.k_proj\.", ".attn.key."),
    (r"\.self_attn\.v_proj\.", ".attn.value."),
    (r"\.self_attn\.out_proj\.", ".attn.out."),
    (r"\.self_attn_layer_norm\.", ".attn_ln."),
    (r"\.encoder_attn\.q_proj\.", ".cross_attn.query."),
    (r"\.encoder_attn\.k_proj\.", ".cross_attn.key."),
    (r"\.encoder_attn\.v_proj\.", ".cross_attn.value."),
    (r"\.encoder_attn\.out_proj\.", ".cross_attn.out."),
    (r"\.encoder_attn_layer_norm\.", ".cross_attn_ln."),
    (r"\.final_layer_norm\.", ".mlp_ln."),
    (r"\.fc1\.", ".mlp1."),
    (r"\.fc2\.", ".mlp2."),
    (r"^encoder\.layer_norm\.", "encoder.ln_post."),
    (r"^decoder\.layer_norm\.", "decoder.ln."),
    (r"^decoder\.embed_tokens\.", "decoder.token_embedding."),
    (r"^decoder\.embed_positions\.weight$", "decoder.positional_embedding"),
]


def rename(key):
    for pat, rep in RENAME:
        if re.search(pat, key):
            if rep is None:
                return None
            key = re.sub(pat, rep, key)
    return key


def convert(src, out, bits=None, dtype="float16", delete_source=False):
    path = src if os.path.isdir(src) else snapshot_download(src, allow_patterns=["*.json", "*.safetensors", "*.txt", "*.md"])
    with open(os.path.join(path, "config.json"), encoding="utf-8") as f:
        hf = json.load(f)
    config = {
        "n_mels": hf["num_mel_bins"], "n_audio_ctx": hf["max_source_positions"], "n_audio_state": hf["d_model"],
        "n_audio_head": hf["encoder_attention_heads"], "n_audio_layer": hf["encoder_layers"],
        "n_vocab": hf["vocab_size"], "n_text_ctx": hf["max_target_positions"], "n_text_state": hf["d_model"],
        "n_text_head": hf["decoder_attention_heads"], "n_text_layer": hf["decoder_layers"],
    }
    # 一個一個權重處理（每個量化完就 eval，放掉原本的 fp16），記憶體只要約「輸出大小＋一層」，
    # 不會像整個模型一起載入那樣把 16 GB 的 Mac 擠到大量 swap、吃光磁碟。
    from mlx_whisper import whisper
    small = whisper.ModelDimensions(**{**config, "n_audio_state": 64, "n_text_state": 64, "n_audio_head": 1,
                                       "n_text_head": 1, "n_vocab": 64, "n_audio_ctx": 8, "n_text_ctx": 8})
    expected = {k for k, _ in tree_flatten(whisper.Whisper(small).parameters())} - {"alignment_heads"}
    quant = re.compile(r"(\.(attn|cross_attn)\.(query|key|value|out)|\.mlp[12])\.weight$|^decoder\.token_embedding\.weight$")
    weights, seen = {}, set()
    for name in sorted(f for f in os.listdir(path) if f.endswith(".safetensors")):
        for k, a in mx.load(os.path.join(path, name)).items():
            nk = rename(k)
            if nk is None:
                continue
            seen.add(nk)
            a = a.astype(getattr(mx, dtype))
            if nk.endswith(("conv1.weight", "conv2.weight")):
                a = a.swapaxes(1, 2)   # PyTorch (out, in, k) → MLX (out, k, in)
            if bits and quant.search(nk) and a.shape[-1] % 64 == 0:
                w, sc, bi = mx.quantize(a, group_size=64, bits=bits)
                base = nk[: -len(".weight")]
                weights.update({nk: w, base + ".scales": sc, base + ".biases": bi})
                mx.eval(w, sc, bi)
            else:
                weights[nk] = a
                mx.eval(a)
    missing, extra = expected - seen, seen - expected
    if missing or extra:
        raise SystemExit(f"權重對不上：少了 {sorted(missing)[:5]}… 多了 {sorted(extra)[:5]}…")
    if bits:
        config["quantization"] = {"group_size": 64, "bits": bits}
    config["model_type"] = "whisper"

    os.makedirs(out, exist_ok=True)
    for name in TOKENIZER_FILES:
        if os.path.exists(os.path.join(path, name)):
            shutil.copy(os.path.join(path, name), out)
    if delete_source and not os.path.isdir(src):   # 先刪原檔再寫：磁碟緊的時候才放得下
        cache = os.path.dirname(os.path.dirname(path))   # models--org--name
        shutil.rmtree(cache, ignore_errors=True)
        print(f"deleted download cache {cache}")
    mx.save_safetensors(os.path.join(out, "weights.safetensors"), weights)
    with open(os.path.join(out, "config.json"), "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2)
    print(f"done → {out}（{config['n_vocab']} 詞、{config['n_mels']} mel、{'%d-bit' % bits if bits else dtype}）")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("src", help="Hugging Face repo 或本機資料夾")
    ap.add_argument("out")
    ap.add_argument("--bits", type=int, choices=[4, 8])
    ap.add_argument("--delete-source", action="store_true", help="轉完刪掉下載的原始檔（省磁碟）")
    a = ap.parse_args()
    convert(a.src, a.out, a.bits, delete_source=a.delete_source)
