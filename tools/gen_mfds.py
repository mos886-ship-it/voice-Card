"""식품의약품안전처「전국통합식품영양성분정보(음식)표준데이터」→ app/src/main/assets/nutrition/kr.json（App 內建的韓國營養資料庫）。

用法：python tools/gen_mfds.py <下載的 CSV 或 XLSX>
  （https://www.data.go.kr/data/15100070/standard.do 的「CSV／XLS 다운로드」，2026-10-06 版）
出處：식품의약품안전처 식품영양성분 데이터베이스（Korean Food Composition Database, K-FCDB）。
授權：公共資料入口網站的標準資料；食藥處著作權政策允許標示出處使用。商用前要向食藥處確認（見 交接/HANDOFF.md）。

讀欄位：식품명、식품대분류명、영양성분함량기준량、에너지(kcal)、단백질(g)、지방(g)、탄수화물(g)、식이섬유(g)。
只留基準量是 100g／100ml 的列（其他基準量不換算，避免算錯）；名稱重複的留第一筆。
"""
import csv
import io
import json
import os
import sys

COLS = {"name": "식품명", "cat": "식품대분류명", "base": "영양성분함량기준량", "k": "에너지(kcal)",
        "p": "단백질(g)", "f": "지방(g)", "c": "탄수화물(g)", "fib": "식이섬유(g)"}


def rows(path):
    if path.lower().endswith((".xlsx", ".xls")):
        import openpyxl
        wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
        ws = wb[wb.sheetnames[0]]
        it = ws.iter_rows(values_only=True)
        header = [str(h).strip() if h is not None else "" for h in next(it)]
        for r in it:
            yield dict(zip(header, r))
    else:
        raw = open(path, "rb").read()
        for enc in ("utf-8-sig", "cp949", "euc-kr"):
            try:
                text = raw.decode(enc)
                break
            except UnicodeDecodeError:
                continue
        yield from csv.DictReader(io.StringIO(text))


def num(v):
    try:
        return round(float(str(v).replace(",", "").strip()), 1)
    except (TypeError, ValueError):
        return 0.0


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    out, seen = [], set()
    for r in rows(sys.argv[1]):
        r = {str(k).strip(): v for k, v in r.items()}
        base = str(r.get(COLS["base"], "")).replace(" ", "").lower()
        if base and base not in ("100g", "100ml"):
            continue
        n = str(r.get(COLS["name"], "") or "").strip()
        if not n or n in seen:
            continue
        seen.add(n)
        e = {"n": n, "cat": str(r.get(COLS["cat"], "") or "기타").strip(), "k": num(r.get(COLS["k"])),
             "p": num(r.get(COLS["p"])), "f": num(r.get(COLS["f"])), "c": num(r.get(COLS["c"]))}
        fib = num(r.get(COLS["fib"]))
        if fib:
            e["fib"] = fib
        if e["k"] > 0:
            out.append(e)
    dest = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "nutrition", "kr.json")
    with open(dest, "w", encoding="utf-8") as f:
        json.dump({"source": "식품의약품안전처 식품영양성분 데이터베이스 (전국통합식품영양성분정보 음식 표준데이터)",
                   "license": "공공데이터포털 표준데이터 · 출처 표시", "per": "100g", "foods": out},
                  f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(out)} foods → {dest} ({os.path.getsize(dest) // 1024} KB)")


if __name__ == "__main__":
    main()
