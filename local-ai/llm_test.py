"""MLX 語言模型測試：逐字稿 → 報告（大D 自架引擎第 2 步）。

用法：venv/bin/python llm_test.py <逐字稿.txt> [模型]
提示詞照 Android `GeminiReportGenerator.TEXT_REPORT_PROMPT`（逐字稿版），輸出 TITLE / SUMMARY / Markdown，
跟 App 現有的報告解析與畫面相容。
"""
import sys
import time

from mlx_lm import generate, load
from mlx_lm.sample_utils import make_sampler

MODEL = sys.argv[2] if len(sys.argv) > 2 else "mlx-community/Qwen3-8B-4bit"

TYPE_RULES = """錄音可能是會議、課堂、訪談、電話通話、演講、討論、或個人口述的備忘 / 想法，請先判斷是哪一種，再依類型整理重點：
- 會議 / 討論：討論內容、決定事項、誰負責什麼。
- 課堂 / 演講：主要概念、重要定義與例子、要複習的地方。
- 訪談 / 通話：問了什麼、對方怎麼回答、約定事項。
- 個人備忘 / 想法：把零散的想法整理成清楚的條列與下一步。
不要捏造錄音裡沒有的內容；聽不清楚的地方標註「（聽不清）」，不確定的標註「（待確認）」。
一律使用繁體中文（台灣用語）。"""

SYSTEM = f"""你是專業的語音記錄助理。使用者會給你一份語音辨識產生的逐字稿（可能有同音錯字、標點不完整），請依上下文理解後整理。
{TYPE_RULES}

輸出格式（嚴格遵守，不要加其他前言）：
第 1 行：TITLE: <15 字內的標題，能看出內容與類型，例如「Q3 預算週會」「統計學第 5 講」「王經理電話訪談」>
第 2 行：SUMMARY: <2～3 句、80 字內的摘要，會顯示在手錶上>
第 3 行起：Markdown 報告，依序包含以下章節（沒有內容的章節寫「無」）：
## 摘要
（3～5 句完整摘要：這段錄音在講什麼、誰說了什麼重要的事、結論是什麼。這一節一定要寫，不可以寫「無」）
## 說話者
（用表格：說話者 | 名字 | 角色 | 主要觀點；每位說話者一列。名字從自我介紹、別人怎麼稱呼他推斷，只有職稱或稱謂也可以直接當名字（例如「王經理」）；完全沒有線索才寫「未知」）
## 重點
（依錄音類型整理；有多位說話者時，每點註明是誰說的，例如「說話者B 提出…」）
## 結論 / 決定
## 待辦事項
（用表格：事項 | 負責人 | 期限；負責人用說話者代號；沒提到就寫「未指定」）
## 待確認 / 後續問題

說話者規則：
- 逐字稿裡的「說話者A、說話者B…」是語音辨識依聲音分出來的代號，報告裡請沿用同樣的代號。
- 逐字稿是語音辨識結果，換人的地方偶爾會切錯一兩個字，請依上下文判斷。
- 只輸出 TITLE、SUMMARY 與 Markdown 報告，不要再輸出逐字稿。"""

transcript = open(sys.argv[1], encoding="utf-8").read()
user = f"錄音時間：2026/11/22 09:30，長度約 0.9 分鐘。\n\n<transcript>\n{transcript}\n</transcript>"

t0 = time.time()
model, tokenizer = load(MODEL)
t1 = time.time()
messages = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}]
# Qwen3：關掉「思考」模式，直接輸出報告（比較快）
try:
    prompt = tokenizer.apply_chat_template(messages, add_generation_prompt=True, enable_thinking=False, tokenize=False)
except TypeError:
    prompt = tokenizer.apply_chat_template(messages, add_generation_prompt=True, tokenize=False)
n_in = len(tokenizer.encode(prompt))
out = generate(model, tokenizer, prompt=prompt, max_tokens=2000, sampler=make_sampler(temp=0.2))
t2 = time.time()
n_out = len(tokenizer.encode(out))
print(out.strip())
print(f"--- load {t1 - t0:.1f}s, generate {t2 - t1:.1f}s, in {n_in} tok, out {n_out} tok ({n_out / (t2 - t1):.1f} tok/s)")
