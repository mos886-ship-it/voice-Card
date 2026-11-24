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
import mlx.nn as nn
from huggingface_hub import snapshot_download
from mlx.utils import tree_flatten, tree_unflatten
from safetensors.numpy import load_file

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
    weights = {}
    files = sorted(f for f in os.listdir(path) if f.endswith(".safetensors"))
    for name in files:
        for k, v in load_file(os.path.join(path, name)).items():
            nk = rename(k)
            if nk is None:
                continue
            a = mx.array(v).astype(getattr(mx, dtype))
            if nk.endswith(("conv1.weight", "conv2.weight")):
                a = a.swapaxes(1, 2)   # PyTorch (out, in, k) → MLX (out, k, in)
            weights[nk] = a

    from mlx_whisper import whisper
    model = whisper.Whisper(whisper.ModelDimensions(**config), getattr(mx, dtype))
    expected = {k for k, _ in tree_flatten(model.parameters())}
    missing, extra = expected - set(weights) - {"alignment_heads"}, set(weights) - expected
    if missing or extra:
        raise SystemExit(f"權重對不上：少了 {sorted(missing)[:5]}… 多了 {sorted(extra)[:5]}…")
    model.update(tree_unflatten(list(weights.items())))
    if bits:
        nn.quantize(model, group_size=64, bits=bits)
        config["quantization"] = {"group_size": 64, "bits": bits}
    config["model_type"] = "whisper"

    os.makedirs(out, exist_ok=True)
    mx.save_safetensors(os.path.join(out, "weights.safetensors"), dict(tree_flatten(model.parameters())))
    with open(os.path.join(out, "config.json"), "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2)
    for name in TOKENIZER_FILES:
        if os.path.exists(os.path.join(path, name)):
            shutil.copy(os.path.join(path, name), out)
    print(f"done → {out}（{config['n_vocab']} 詞、{config['n_mels']} mel、{'%d-bit' % bits if bits else dtype}）")
    if delete_source and not os.path.isdir(src):
        cache = os.path.dirname(os.path.dirname(path))   # models--org--name
        shutil.rmtree(cache, ignore_errors=True)
        print(f"deleted download cache {cache}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("src", help="Hugging Face repo 或本機資料夾")
    ap.add_argument("out")
    ap.add_argument("--bits", type=int, choices=[4, 8])
    ap.add_argument("--delete-source", action="store_true", help="轉完刪掉下載的原始檔（省磁碟）")
    a = ap.parse_args()
    convert(a.src, a.out, a.bits, delete_source=a.delete_source)
