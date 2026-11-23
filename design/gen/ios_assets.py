"""從 logo_final.html（由 logo_final.py 產生）匯出 iOS / watchOS 的 Logo 與 App 圖示。

用法（在 Mac 上，repo 根目錄）：
    python3 design/gen/logo_final.py   # 先改好 logo 再重跑（Windows / Mac 都可以）
    python3 design/gen/ios_assets.py   # 需要 macOS 的 qlmanage 把 SVG 轉成 1024 PNG

輸出：ios/Shared/Assets.xcassets/Logo.imageset/logo.svg、AppIcon.appiconset/icon-1024.png
不要手改輸出檔，改 logo_final.py 重跑。
"""
import os
import re
import shutil
import subprocess
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HTML = os.path.join(ROOT, "design", "logo_final.html")
ASSETS = os.path.join(ROOT, "ios", "Shared", "Assets.xcassets")

html = open(HTML, encoding="utf-8").read()
svg = re.findall(r"<svg.*?</svg>", html, re.S)[0]
inner = re.sub(r"^<svg[^>]*>", "", svg)[: -len("</svg>")]
VIEWBOX = 'viewBox="18 18 72 72" xmlns="http://www.w3.org/2000/svg"'

# 圓形徽章（宣紙圓底），App 內品牌標誌用
logo = f'<svg width="72" height="72" {VIEWBOX}>{inner}</svg>'
logo_dir = os.path.join(ASSETS, "Logo.imageset")
os.makedirs(logo_dir, exist_ok=True)
open(os.path.join(logo_dir, "logo.svg"), "w", encoding="utf-8").write(logo)

# App 圖示：滿版宣紙方底（系統自己切圓角 / 圓形）
paper = re.search(r'<circle cx="54" cy="54" r="36" fill="(#[0-9A-Fa-f]{6})"/>', inner)
body = inner.replace(paper.group(0), "", 1) if paper else inner
color = paper.group(1) if paper else "#F2EADB"
icon = (f'<svg width="1024" height="1024" {VIEWBOX}>'
        f'<rect x="18" y="18" width="72" height="72" fill="{color}"/>{body}</svg>')
icon_dir = os.path.join(ASSETS, "AppIcon.appiconset")
os.makedirs(icon_dir, exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    src = os.path.join(tmp, "icon.svg")
    open(src, "w", encoding="utf-8").write(icon)
    subprocess.run(["qlmanage", "-t", "-s", "1024", "-o", tmp, src], check=True, capture_output=True)
    shutil.copy(src + ".png", os.path.join(icon_dir, "icon-1024.png"))
print("ok:", logo_dir, icon_dir)
