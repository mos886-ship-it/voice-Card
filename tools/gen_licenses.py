"""產生 App 內「關於與授權」頁用的授權檔（app/src/main/assets/licenses/）。

依賴有變（加 / 升級套件）時重跑：
    python tools/gen_licenses.py

做法：用 Gradle 列出 release 版實際打包的套件 → 到 Gradle 快取找各套件的 jar / aar →
讀 POM 的授權名稱，取出 jar 裡的 NOTICE（Apache 2.0 規定要附上）→ 寫出：
  libraries.tsv   套件清單（群組、名稱、版本、授權）
  notices.txt     各套件的 NOTICE 內容
  apache-2.0.txt  Apache License 2.0 全文（從套件裡附的 LICENSE 取）
  mit.txt         MIT 授權全文（從 slf4j 附的 LICENSE 取，會註明著作權人）
"""
import io
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/licenses"
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"


def gradle_deps():
    gw = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    r = subprocess.run([str(gw), ":app:dependencies", "--configuration", "releaseRuntimeClasspath", "-q"],
                       cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", shell=os.name == "nt")
    deps = set()
    for line in r.stdout.splitlines():
        m = re.search(r"([\w.\-]+):([\w.\-]+):([\w.\-]+)(?: -> ([\w.\-]+))?", line)
        if m and "project :" not in line:
            deps.add((m.group(1), m.group(2), m.group(4) or m.group(3)))
    return sorted(deps)


def pom_license(group, artifact, version, depth=0):
    d = CACHE / group / artifact / version
    poms = list(d.rglob("*.pom")) if d.exists() else []
    if not poms or depth > 4:
        return None
    text = poms[0].read_text(encoding="utf-8", errors="replace")
    m = re.search(r"<license>.*?<name>\s*([^<]+?)\s*</name>", text, re.S)
    if m:
        return m.group(1)
    p = re.search(r"<parent>.*?<groupId>([^<]+)</groupId>.*?<artifactId>([^<]+)</artifactId>.*?<version>([^<]+)</version>", text, re.S)
    return pom_license(p.group(1).strip(), p.group(2).strip(), p.group(3).strip(), depth + 1) if p else None


def normalize(name):
    if not name:
        return "（見原始專案）"
    n = name.lower()
    if "apache" in n:
        return "Apache License 2.0"
    if "mit" in n:
        return "MIT License"
    if "android software development kit" in n:
        return "Android SDK License"
    if "ml kit" in n:
        return "ML Kit Terms of Service"
    if "bsd" in n:
        return "BSD License"
    return name


def archives(group, artifact, version):
    d = CACHE / group / artifact / version
    return [p for p in d.rglob("*") if p.suffix in (".jar", ".aar") and not p.name.endswith("-sources.jar")] if d.exists() else []


def entries(path):
    """jar / aar（含 aar 裡的 classes.jar）裡的檔案：(名稱, bytes)。"""
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if path.suffix == ".aar" and n.endswith(".jar"):
                with zipfile.ZipFile(io.BytesIO(z.read(n))) as inner:
                    for m in inner.namelist():
                        yield m, lambda m=m, inner_bytes=z.read(n): zipfile.ZipFile(io.BytesIO(inner_bytes)).read(m)
            else:
                yield n, lambda n=n: z.read(n)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    deps = gradle_deps()
    if not deps:
        sys.exit("Gradle 沒有列出任何套件")
    rows, notices, seen = [], [], set()
    apache = mit = None
    for g, a, v in deps:
        if a.endswith("-bom") or a.endswith("-parent"):
            continue
        lic = normalize(pom_license(g, a, v))
        rows.append(f"{g}\t{a}\t{v}\t{lic}")
        for p in archives(g, a, v):
            for name, read in entries(p):
                base = name.rsplit("/", 1)[-1].upper()
                if "NOTICE" in base and not name.endswith("/"):
                    t = read().decode("utf-8", "replace").strip()
                    if t and t not in seen:
                        seen.add(t)
                        notices.append(f"===== {g}:{a}:{v} — {name} =====\n{t}\n")
                elif base.startswith("LICENSE") and not name.endswith("/"):
                    t = read().decode("utf-8", "replace")
                    if apache is None and "Apache License" in t and "Version 2.0, January 2004" in t and "TERMS AND CONDITIONS" in t:
                        apache = t.strip()
                    if mit is None and g == "org.slf4j" and "Permission is hereby granted, free of charge" in t:
                        mit = t.strip()
    (OUT / "libraries.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    (OUT / "notices.txt").write_text("\n".join(notices) or "（沒有 NOTICE 檔）\n", encoding="utf-8")
    if apache:
        (OUT / "apache-2.0.txt").write_text(apache + "\n", encoding="utf-8")
    # MIT 的兩個套件（slf4j、standardwebhooks）jar 裡都沒附授權全文：照 POM 的著作權人寫標準 MIT 條文
    if mit is None:
        mit = "\n".join([
            "SLF4J API — Copyright (c) QOS.ch Sarl (Switzerland)  https://www.slf4j.org",
            "Standard Webhooks (Java) — Copyright (c) Standard Webhooks  https://github.com/standard-webhooks/standard-webhooks",
            "",
            "The MIT License",
            "",
            "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated "
            "documentation files (the \"Software\"), to deal in the Software without restriction, including without limitation "
            "the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to "
            "permit persons to whom the Software is furnished to do so, subject to the following conditions:",
            "",
            "The above copyright notice and this permission notice shall be included in all copies or substantial portions of "
            "the Software.",
            "",
            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO "
            "THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE "
            "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, "
            "TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE "
            "SOFTWARE.",
        ])
    (OUT / "mit.txt").write_text(mit + "\n", encoding="utf-8")
    print(f"{len(rows)} 個套件、{len(notices)} 份 NOTICE、Apache 全文 {'有' if apache else '沒有'}、MIT 全文 {'有' if mit else '沒有'}")


if __name__ == "__main__":
    main()
