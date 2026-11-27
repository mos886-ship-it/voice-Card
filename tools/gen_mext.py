"""文部科學省「日本食品標準成分表（八訂）増補2023年」本表 → app/src/main/assets/nutrition/jp.json（App 內建的日本營養資料庫）。

用法：python tools/gen_mext.py <本表 xlsx>
  （https://www.mext.go.jp/a_menu/syokuhinseibun/ 的「第2章 本表」Excel；2026-03-27 修正版）
授權：公共データ利用規約（第1.0版）相容 CC BY 4.0，可商用；要註明出處：
  「日本食品標準成分表（八訂）増補2023年」（文部科学省）を加工して作成

讀「表全体」工作表第 13 列起：A 食品群、D 食品名、G エネルギー(kcal)、J たんぱく質、M 脂質、S 食物繊維総量、U 炭水化物。
格子裡「(1.2)」是推定值（照用）、「Tr」「(Tr)」是微量（當 0）、「-」「*」是沒有測（當 0）。
食品名把全形空白換成半形，去掉開頭的「＜…＞」分類括號；名稱太長（超過 24 字）的截短。
"""
import json
import os
import re
import sys

import openpyxl

GROUPS = {
    "01": "穀類", "02": "いも及びでん粉類", "03": "砂糖及び甘味類", "04": "豆類", "05": "種実類", "06": "野菜類",
    "07": "果実類", "08": "きのこ類", "09": "藻類", "10": "魚介類", "11": "肉類", "12": "卵類", "13": "乳類",
    "14": "油脂類", "15": "菓子類", "16": "し好飲料類", "17": "調味料及び香辛料類", "18": "調理済み流通食品類",
}
MAX_LEN = 24


def num(v):
    if v is None:
        return 0.0
    if isinstance(v, (int, float)):
        return float(v)
    s = str(v).strip().strip("()（）")
    if s in ("", "-", "*", "Tr", "tr", "0"):
        return 0.0
    try:
        return float(s)
    except ValueError:
        return 0.0


def name_of(raw):
    n = str(raw).replace("　", " ")
    n = re.sub(r"^＜[^＞]*＞\s*", "", n)          # 開頭的分類括號
    n = re.sub(r"\s+", " ", n).strip()
    return n if len(n) <= MAX_LEN else n[:MAX_LEN].rstrip()


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    wb = openpyxl.load_workbook(sys.argv[1], read_only=True, data_only=True)
    ws = wb["表全体"]
    out, seen = [], set()
    for row in ws.iter_rows(min_row=13, max_col=21, values_only=True):
        group, code, _, name = row[0], row[1], row[2], row[3]
        if not code or not name:
            continue
        n = name_of(name)
        if n in seen:
            continue
        seen.add(n)
        e = {"n": n, "cat": GROUPS.get(str(group).zfill(2), "その他"),
             "k": num(row[6]), "p": num(row[9]), "f": num(row[12]), "c": num(row[20])}
        fib = num(row[18])
        if fib:
            e["fib"] = fib
        out.append(e)
    dest = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "nutrition", "jp.json")
    with open(dest, "w", encoding="utf-8") as f:
        json.dump({"source": "「日本食品標準成分表（八訂）増補2023年」（文部科学省）を加工して作成",
                   "license": "公共データ利用規約（第1.0版）／CC BY 4.0 互換", "per": "100g", "foods": out},
                  f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(out)} foods → {dest} ({os.path.getsize(dest) // 1024} KB)")


if __name__ == "__main__":
    main()
