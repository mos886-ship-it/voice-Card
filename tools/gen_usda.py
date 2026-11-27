"""USDA FoodData Central → app/src/main/assets/nutrition/us.json（App 內建的美國營養資料庫）。

用法：python tools/gen_usda.py <解壓後的 SR Legacy 資料夾> [Foundation Foods 資料夾]
  （fdc.nal.usda.gov/download-datasets 的 CSV zip；授權：公共領域 CC0）

輸出格式跟 tfnd.json 一樣：每 100 克 k（大卡）、p 蛋白質、f 脂肪、c 碳水、fib 膳食纖維，n 名稱、cat 類別。
- 只留一般食材與常見料理（去掉嬰兒食品、品牌速食的重複項目等，見 SKIP_CATEGORIES）。
- 熱量：1008（Energy kcal）；Foundation 沒有就用 2047 / 2048（Atwater）。
- 名稱用 USDA 原文（英文），App 讓 AI 從清單挑完全相同的名稱。名稱太長的截短（逗號後只留前三段）以免提示詞太大；截短後撞名的保留第一筆。
"""
import csv
import json
import os
import sys

NUTRIENTS = {"1008": "k", "2047": "k2047", "2048": "k2048", "1003": "p", "1004": "f", "1005": "c", "1079": "fib"}
SKIP_CATEGORIES = {"Baby Foods", "Restaurant Foods", "American Indian/Alaska Native Foods", "Fast Foods", "Meals, Entrees, and Side Dishes"}
MAX_PARTS = 2


def load(folder):
    def path(name):
        for root, _, files in os.walk(folder):
            if name in files:
                return os.path.join(root, name)
        raise FileNotFoundError(f"{name} not found under {folder}")

    cats = {}
    with open(path("food_category.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            cats[r["id"]] = r["description"]
    foods = {}
    with open(path("food.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["data_type"] not in ("sr_legacy_food", "foundation_food"):
                continue
            cat = cats.get(r.get("food_category_id", ""), "")
            if cat in SKIP_CATEGORIES:
                continue
            foods[r["fdc_id"]] = {"name": r["description"].strip(), "cat": cat, "type": r["data_type"]}
    with open(path("food_nutrient.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            fid = r["fdc_id"]
            key = NUTRIENTS.get(r["nutrient_id"])
            if fid in foods and key and r["amount"]:
                foods[fid][key] = float(r["amount"])
    return foods


COOKED = ("cooked", "roasted", "boiled", "baked", "fried", "braised", "grilled", "broiled", "stewed", "steamed", "pan-fried", "microwaved")


def short(name):
    """前兩段＋生 / 熟 / 乾燥（熱量差很多，不能合併）。"""
    parts = [p.strip() for p in name.split(",")]
    low = name.lower()
    state = ("dried" if any(w in low for w in ("dried", "powder", "dehydrated", "dry mix")) else
             "raw" if " raw" in low or low.endswith("raw") else
             "cooked" if any(w in low for w in COOKED) else "")
    base = ", ".join(parts[:MAX_PARTS])
    return base + (f", {state}" if state and state not in base.lower() else "")


def main():
    folders = sys.argv[1:]
    if not folders:
        sys.exit(__doc__)
    merged = {}
    for folder in folders:
        merged.update(load(folder))
    out, seen = [], set()
    # Foundation（較新的實測資料）優先，再來 SR Legacy
    for fid, d in sorted(merged.items(), key=lambda kv: (kv[1]["type"] != "foundation_food", kv[1]["name"])):
        kcal = d.get("k") or d.get("k2048") or d.get("k2047")
        if kcal is None or "p" not in d:
            continue
        n = short(d["name"])
        if n.lower() in seen:
            continue
        seen.add(n.lower())
        e = {"n": n, "cat": d["cat"] or "Other", "k": round(kcal, 1), "p": round(d.get("p", 0), 1),
             "f": round(d.get("f", 0), 1), "c": round(d.get("c", 0), 1)}
        if d.get("fib"):
            e["fib"] = round(d["fib"], 1)
        out.append(e)
    dest = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "nutrition", "us.json")
    with open(dest, "w", encoding="utf-8") as f:
        json.dump({"source": "U.S. Department of Agriculture, Agricultural Research Service. FoodData Central (SR Legacy, Foundation Foods)",
                   "license": "Public domain (CC0 1.0)", "per": "100g", "foods": out}, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(out)} foods → {dest} ({os.path.getsize(dest) // 1024} KB)")


if __name__ == "__main__":
    main()
