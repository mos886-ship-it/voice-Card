"""
产生 App 内建的食藥署营养资料（app/src/main/assets/nutrition/tfnd.json）。

来源：衛生福利部食品藥物管理署「食品營養成分資料集」（政府資料開放授權條款－第1版）
下载：https://data.fda.gov.tw/opendata/exportDataList.do?method=ExportData&InfoId=20&logType=3
用法：python -I tools/gen_tfnd.py <下载的 zip 或解开的 20_3.json>

只留 7 项（每 100 克）：热量、蛋白质、脂肪、碳水、膳食纤维、糖、钠。
同一种食物不同取样月份 / 年份（「全脂鮮乳(6月取樣)」…）合并成一笔取平均。
"""
import collections
import json
import re
import sys
import zipfile
from pathlib import Path

KEEP = {"熱量": "k", "粗蛋白": "p", "粗脂肪": "f", "總碳水化合物": "c", "膳食纖維": "fib", "糖質總量": "sug", "鈉": "na"}
SAMPLED = re.compile(r"\((?:\d{4}年)?\d{1,2}月(?:份)?取樣\)|\(\d{4}年取樣\)")


def load(src: Path):
    if src.suffix == ".zip":
        with zipfile.ZipFile(src) as z:
            name = next(n for n in z.namelist() if n.endswith(".json"))
            return json.loads(z.read(name).decode("utf-8"))
    return json.loads(src.read_text(encoding="utf-8"))


def state(desc: str) -> str:
    m = re.search(r"樣品狀態[:：]\s*([^;；]+)", desc or "")
    s = (m.group(1).strip() if m else "")
    return s if s in ("生", "熟") else ""


def main():
    src = Path(sys.argv[1])
    rows = [{k: v for x in r for k, v in x.items()} for r in load(src)]
    foods, values = {}, collections.defaultdict(dict)
    for r in rows:
        fid = r["整合編號"]
        foods.setdefault(fid, r)
        key = KEEP.get(r.get("分析項"))
        if key:
            try:
                values[fid][key] = float((r.get("每100克含量") or "").strip())
            except ValueError:
                pass

    groups = collections.defaultdict(list)
    for fid, f in foods.items():
        if "k" not in values[fid]:
            continue
        name = SAMPLED.sub("", f["樣品名稱"]).strip()
        groups[(name, state(f.get("內容物描述")))].append(fid)

    out = []
    for (name, st), ids in sorted(groups.items()):
        f = foods[ids[0]]
        e = {"n": name, "cat": f["食品分類"]}
        if st:
            e["s"] = st
        alias = (f.get("俗名") or "").strip()
        if alias and alias != name:
            e["a"] = alias
        for key in KEEP.values():
            nums = [values[i][key] for i in ids if key in values[i]]
            if nums:
                e[key] = round(sum(nums) / len(nums), 1)
        out.append(e)

    # 名字重复（同名不同状态）时，名字加上状态，让 AI 选得到
    count = collections.Counter(e["n"] for e in out)
    for e in out:
        if count[e["n"]] > 1 and e.get("s"):
            e["n"] = f"{e['n']}({e['s']})"

    dst = Path(__file__).resolve().parent.parent / "app/src/main/assets/nutrition/tfnd.json"
    dst.parent.mkdir(parents=True, exist_ok=True)
    meta = {"source": "衛生福利部食品藥物管理署 食品營養成分資料集", "license": "政府資料開放授權條款－第1版",
            "per": "100g", "foods": out}
    dst.write_text(json.dumps(meta, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"{len(out)} foods -> {dst} ({dst.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
