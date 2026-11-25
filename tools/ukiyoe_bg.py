"""
浮世繪配色（取代原本的「深色」）的背景画：原创、程式产生，尽量照木刻版画（浮世绘）的画法：
- 和纸米黄底＋纤维纹理；天空上缘一道普鲁士蓝的「一文字ぼかし」（由深到淡的渐层带）
- 平涂色块、墨黑细勾边；普鲁士蓝（ベロ藍）、朱红、桃色、土黄、松绿
- 横向的「すやり霞」（没有框线的桃色 / 淡蓝云带）
- 右上一个题签（短册：白底黑框、直写标题）＋朱红落款印
用法：python -I tools/ukiyoe_bg.py  → 設計/浮世繪背景/*.svg
画都放在边角与下方，日期、标题、卡片的位置留白。
"""
import math
import random
from pathlib import Path

W, H = 1080, 2400
PAPER = "#F0E4C8"          # 和纸
SUMI = "#2A2522"           # 墨线
INDIGO = "#1E3A6E"         # 普鲁士蓝（深）
INDIGO2 = "#3C6AA8"        # 普鲁士蓝（中）
INDIGO3 = "#8FB0D6"        # 淡蓝
VERMILION = "#C8402B"
PEACH = "#E8B98F"          # 桃色霞
PINK = "#EBA9AE"
OCHRE = "#C99A3C"
PINE = "#2F5E4C"
WHITE = "#F8F2E4"
OUT = Path(__file__).resolve().parent.parent / "設計" / "浮世繪背景"


def frame(body, bottom_bokashi=False):
    bottom = (f'<rect x="0" y="{H - 520}" width="{W}" height="520" fill="url(#bokashiB)"/>' if bottom_bokashi else "")
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">
<defs>
  <linearGradient id="bokashi" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="{INDIGO}" stop-opacity="0.95"/><stop offset="0.45" stop-color="{INDIGO2}" stop-opacity="0.55"/>
    <stop offset="1" stop-color="{INDIGO2}" stop-opacity="0"/></linearGradient>
  <linearGradient id="bokashiB" x1="0" y1="1" x2="0" y2="0">
    <stop offset="0" stop-color="{INDIGO}" stop-opacity="0.85"/><stop offset="1" stop-color="{INDIGO2}" stop-opacity="0"/></linearGradient>
  <linearGradient id="waveG" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="{INDIGO2}"/><stop offset="1" stop-color="{INDIGO}"/></linearGradient>
  <filter id="grain" x="0" y="0" width="100%" height="100%">
    <feTurbulence type="fractalNoise" baseFrequency="0.75" numOctaves="3" seed="5" result="n"/>
    <feColorMatrix in="n" type="matrix" values="0 0 0 0 0.45  0 0 0 0 0.38  0 0 0 0 0.25  0 0 0 0.09 0"/>
  </filter>
  <filter id="wood" x="-5%" y="-5%" width="110%" height="110%">
    <feTurbulence type="fractalNoise" baseFrequency="0.04" numOctaves="2" seed="9" result="t"/>
    <feDisplacementMap in="SourceGraphic" in2="t" scale="2.5" xChannelSelector="R" yChannelSelector="G"/>
  </filter>
</defs>
<rect width="{W}" height="{H}" fill="{PAPER}"/>
<rect width="{W}" height="330" fill="url(#bokashi)"/>
{bottom}
<g filter="url(#wood)">{body}</g>
<rect width="{W}" height="{H}" filter="url(#grain)"/>
</svg>"""


def kasumi(x, y, w, h, color, op=0.85):
    """すやり霞：没有框线的横向云带（两端圆），上缘有一个小台阶。"""
    r = h / 2
    return (f'<g fill="{color}" opacity="{op}"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r:.0f}"/>'
            f'<rect x="{x + w * 0.18:.0f}" y="{y - h * 0.55:.0f}" width="{w * 0.45:.0f}" height="{h}" rx="{r:.0f}"/></g>')


def sun(cx, cy, r):
    return f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{VERMILION}" stroke="{SUMI}" stroke-width="2.5"/>'


def full_moon(cx, cy, r):
    return f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{WHITE}" stroke="{SUMI}" stroke-width="2.5"/>'


def cartouche(x, y, title, w=74):
    """题签（短册）：白底、墨黑双框、直写标题；下面一个朱红落款印。"""
    h = 40 + len(title) * 56
    chars = "".join(f'<text x="{x + w / 2:.0f}" y="{y + 62 + i * 56}" font-size="46" text-anchor="middle" '
                    f'font-family="Noto Serif TC, Noto Serif CJK TC, PMingLiU, serif" font-weight="700" fill="{SUMI}">{c}</text>'
                    for i, c in enumerate(title))
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{WHITE}" stroke="{SUMI}" stroke-width="3"/>'
            f'<rect x="{x + 6}" y="{y + 6}" width="{w - 12}" height="{h - 12}" fill="none" stroke="{SUMI}" stroke-width="1.5"/>'
            + chars + seal(x - 60, y + h - 48, 48))


def seal(x, y, s=50):
    return (f'<rect x="{x:.0f}" y="{y:.0f}" width="{s}" height="{s}" rx="4" fill="{VERMILION}"/>'
            f'<rect x="{x + 8:.0f}" y="{y + 8:.0f}" width="{s - 16}" height="{s - 16}" fill="none" stroke="{PAPER}" stroke-width="3.5"/>')


def birds(rng, x, y, n, spread):
    out = []
    for _ in range(n):
        bx, by, s = x + rng.uniform(-spread, spread), y + rng.uniform(-spread / 2, spread / 2), rng.uniform(12, 20)
        out.append(f'<path d="M{bx - s:.0f},{by:.0f} q{s / 2:.0f},{-s / 2:.0f} {s:.0f},0 q{s / 2:.0f},{-s / 2:.0f} {s:.0f},0" '
                   f'stroke="{SUMI}" stroke-width="3" fill="none" stroke-linecap="round"/>')
    return "".join(out)


# ---------------- 1 浪 ----------------

def wave_row(rng, y, amp, period, fill, foam=True):
    """一排卷浪：浪头往前卷，前缘一排白色「浪爪」（小弧线）与浪花点；浪身里有几道平行的流线。"""
    d = [f"M-80,{H}", f"L-80,{y}"]
    deco = []
    x = -80
    while x < W + 80:
        p = period * rng.uniform(0.9, 1.1)
        a = amp * rng.uniform(0.85, 1.15)
        d.append(f"C{x + p * 0.2:.0f},{y - a * 0.9:.0f} {x + p * 0.62:.0f},{y - a * 1.15:.0f} {x + p * 0.72:.0f},{y - a * 0.62:.0f}")
        d.append(f"C{x + p * 0.66:.0f},{y - a * 0.4:.0f} {x + p * 0.86:.0f},{y - a * 0.1:.0f} {x + p:.0f},{y:.0f}")
        if foam:
            # 浪爪：浪头下缘一串往下弯的小白爪
            for k in range(7):
                t = k / 7
                fx = x + p * (0.45 + 0.3 * t)
                fy = y - a * (1.0 - 0.5 * t)
                deco.append(f'<path d="M{fx:.0f},{fy:.0f} q{a * 0.06:.0f},{a * 0.1:.0f} {a * 0.13:.0f},{a * 0.06:.0f}" stroke="{WHITE}" stroke-width="4" fill="none" stroke-linecap="round"/>')
                deco.append(f'<circle cx="{fx + a * 0.02:.0f}" cy="{fy - a * 0.07:.0f}" r="{a * 0.035:.1f}" fill="{WHITE}"/>')
        # 浪身流线
        for j in range(3):
            o = a * (0.25 + j * 0.2)
            deco.append(f'<path d="M{x + p * 0.12:.0f},{y - a * 0.15 + o * 0.4:.0f} C{x + p * 0.3:.0f},{y - a * 0.85 + o:.0f} {x + p * 0.55:.0f},{y - a * 0.9 + o:.0f} {x + p * 0.66:.0f},{y - a * 0.55 + o * 0.6:.0f}" '
                        f'stroke="{INDIGO3}" stroke-width="2.5" fill="none" opacity="0.7"/>')
        x += p
    d.append(f"L{W + 80},{H} Z")
    return f'<path d="{" ".join(d)}" fill="{fill}" stroke="{SUMI}" stroke-width="3"/>' + "".join(deco)


def waves_scene():
    rng = random.Random(1831)
    body = [sun(640, 250, 85), birds(rng, 330, 520, 4, 70)]
    body.append(kasumi(-60, 600, 560, 50, PEACH))
    body.append(kasumi(560, 1250, 600, 46, PEACH, 0.7))
    for (y, amp, period, fill) in ((1760, 120, 320, INDIGO3), (1930, 140, 360, INDIGO2), (2110, 150, 380, "url(#waveG)"), (2290, 130, 340, INDIGO)):
        body.append(wave_row(rng, y, amp, period, fill))
    body.append(cartouche(950, 70, "浪"))
    return frame("".join(body))


# ---------------- 2 山 ----------------

def mountain_scene():
    rng = random.Random(36)
    body = [sun(560, 240, 80)]
    body.append(kasumi(-60, 560, 520, 50, PEACH))
    body.append(kasumi(520, 1240, 640, 52, PEACH, 0.8))
    peak_y, cx = 1500, 600
    left, right = cx - 860, cx + 860
    # 山身：上浅下深的普鲁士蓝（版画的ぼかし）
    body.append(f'<defs><linearGradient id="mt" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{INDIGO2}"/>'
                f'<stop offset="1" stop-color="{INDIGO}"/></linearGradient></defs>')
    body.append(f'<path d="M{left},2400 Q{cx - 340},{peak_y + 400} {cx - 80},{peak_y} L{cx + 80},{peak_y} '
                f'Q{cx + 340},{peak_y + 400} {right},2400 Z" fill="url(#mt)" stroke="{SUMI}" stroke-width="3"/>')
    snow = [f"M{cx - 80},{peak_y}", f"L{cx + 80},{peak_y}", f"Q{cx + 160},{peak_y + 130} {cx + 250},{peak_y + 250}"]
    x = cx + 250
    while x > cx - 250:
        nx = x - rng.uniform(40, 70)
        snow.append(f"L{(x + nx) / 2:.0f},{peak_y + rng.uniform(150, 330):.0f} L{nx:.0f},{peak_y + rng.uniform(200, 260):.0f}")
        x = nx
    snow.append(f"Q{cx - 160},{peak_y + 130} {cx - 80},{peak_y} Z")
    body.append(f'<path d="{" ".join(snow)}" fill="{WHITE}" stroke="{SUMI}" stroke-width="2.5"/>')
    # 雪线下几道流纹
    for k in range(5):
        xx = cx - 200 + k * 100
        body.append(f'<path d="M{xx},{peak_y + 300} q{rng.uniform(-30, 30):.0f},150 {rng.uniform(-60, 60):.0f},320" stroke="{INDIGO3}" stroke-width="3" fill="none" opacity="0.6"/>')
    # 山脚松林＋前景霞
    for i in range(15):
        tx = 30 + i * 72 + rng.uniform(-12, 12)
        th = rng.uniform(100, 160)
        for lvl in range(3):
            ly = 2400 - th * (0.25 + lvl * 0.25)
            lw = th * (0.32 - lvl * 0.08)
            body.append(f'<ellipse cx="{tx:.0f}" cy="{ly:.0f}" rx="{lw:.0f}" ry="{th * 0.07:.0f}" fill="{PINE}" stroke="{SUMI}" stroke-width="2"/>')
        body.append(f'<path d="M{tx:.0f},2400 L{tx:.0f},{2400 - th * 0.8:.0f}" stroke="{SUMI}" stroke-width="3"/>')
    body.append(kasumi(-80, 2120, 700, 56, PEACH, 0.9))
    body.append(birds(rng, 840, 900, 3, 60))
    body.append(cartouche(950, 70, "峰"))
    return frame("".join(body))


# ---------------- 3 櫻 ----------------

def blossom(cx, cy, r, color):
    petals = []
    for k in range(5):
        a = math.radians(k * 72 - 90)
        px, py = cx + r * 0.55 * math.cos(a), cy + r * 0.55 * math.sin(a)
        petals.append(f'<circle cx="{px:.1f}" cy="{py:.1f}" r="{r * 0.5:.1f}"/>')
    return (f'<g fill="{color}" stroke="{SUMI}" stroke-width="1.5">{"".join(petals)}</g>'
            f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r * 0.22:.1f}" fill="{VERMILION}"/>')


def branch(x0, y0, x1, y1, bend, w):
    return (f'<path d="M{x0},{y0} Q{(x0 + x1) / 2 + bend:.0f},{(y0 + y1) / 2 - bend / 2:.0f} {x1},{y1}" stroke="#4A3328" '
            f'stroke-width="{w}" fill="none" stroke-linecap="round"/>')


def sakura_scene():
    rng = random.Random(4)
    body = [kasumi(-60, 560, 520, 48, PINK, 0.75), kasumi(520, 1220, 640, 50, PEACH, 0.8)]
    main = [(1120, 380, 760, 720, 60, 26), (900, 560, 960, 860, -40, 14), (840, 660, 640, 800, 30, 12),
            (760, 720, 600, 1020, -50, 10), (680, 880, 820, 1100, 40, 8)]
    for x0, y0, x1, y1, b, w in main:
        body.append(branch(x0, y0, x1, y1, b, w))
    for (sx, sy) in ((760, 720), (960, 860), (640, 800), (600, 1020), (820, 1100), (880, 600), (700, 900), (1010, 470), (930, 720)):
        for _ in range(rng.randint(2, 4)):
            body.append(blossom(sx + rng.uniform(-45, 45), sy + rng.uniform(-35, 35), rng.uniform(16, 26), rng.choice((PINK, WHITE))))
    for _ in range(18):
        px, py = rng.uniform(80, 1040), rng.uniform(1300, 2150)
        body.append(f'<ellipse cx="{px:.0f}" cy="{py:.0f}" rx="9" ry="5" transform="rotate({rng.uniform(0, 180):.0f} {px:.0f} {py:.0f})" '
                    f'fill="{PINK}" stroke="{SUMI}" stroke-width="1"/>')
    # 下方：远山＋水面流纹
    body.append(f'<path d="M-40,2400 L-40,2060 Q200,1940 420,2030 Q640,2110 860,2000 Q1000,1940 1120,2010 L1120,2400 Z" fill="{INDIGO2}" stroke="{SUMI}" stroke-width="3"/>')
    for k in range(5):
        y = 2140 + k * 50
        body.append(f'<path d="M{rng.uniform(40, 300):.0f},{y} q120,-14 240,0 t240,0" stroke="{INDIGO3}" stroke-width="3" fill="none"/>')
    body.append(cartouche(70, 1500, "櫻"))
    return frame("".join(body))


# ---------------- 4 艺伎武士 ----------------

def geisha(x, y, k):
    return (
        f'<path d="M{x - 0.02 * k:.0f},{y - 0.95 * k:.0f} L{x - 0.02 * k:.0f},{y - 0.5 * k:.0f}" stroke="{SUMI}" stroke-width="5"/>'
        f'<path d="M{x - 0.34 * k:.0f},{y - 0.95 * k:.0f} Q{x - 0.02 * k:.0f},{y - 1.25 * k:.0f} {x + 0.3 * k:.0f},{y - 0.95 * k:.0f} Z" '
        f'fill="{VERMILION}" stroke="{SUMI}" stroke-width="3"/>'
        + "".join(f'<path d="M{x - 0.02 * k:.0f},{y - 1.1 * k:.0f} L{x + (f - 0.5) * 0.62 * k:.0f},{y - 0.95 * k:.0f}" stroke="{SUMI}" stroke-width="1.5"/>'
                  for f in (0.1, 0.3, 0.5, 0.7, 0.9)) +
        f'<path d="M{x - 0.1 * k:.0f},{y - 0.74 * k:.0f} Q{x - 0.16 * k:.0f},{y - 0.4 * k:.0f} {x - 0.2 * k:.0f},{y} '
        f'Q{x - 0.02 * k:.0f},{y + 0.02 * k:.0f} {x + 0.17 * k:.0f},{y - 0.01 * k:.0f} Q{x + 0.12 * k:.0f},{y - 0.4 * k:.0f} {x + 0.1 * k:.0f},{y - 0.74 * k:.0f} Z" '
        f'fill="{INDIGO2}" stroke="{SUMI}" stroke-width="3"/>'
        + "".join(f'<g transform="translate({x + dx * k:.0f},{y - dy * k:.0f})">{blossom(0, 0, 0.03 * k, WHITE)}</g>'
                  for dx, dy in ((-0.08, 0.2), (0.05, 0.12), (-0.02, 0.32), (0.08, 0.28), (-0.11, 0.07), (0.1, 0.05))) +
        f'<rect x="{x - 0.12 * k:.0f}" y="{y - 0.56 * k:.0f}" width="{0.24 * k:.0f}" height="{0.08 * k:.0f}" fill="{OCHRE}" stroke="{SUMI}" stroke-width="2.5"/>'
        f'<path d="M{x - 0.12 * k:.0f},{y - 0.54 * k:.0f} l{-0.1 * k:.0f},{0.06 * k:.0f} l{0.02 * k:.0f},{0.12 * k:.0f} l{0.08 * k:.0f},{-0.1 * k:.0f} Z" fill="{OCHRE}" stroke="{SUMI}" stroke-width="2"/>'
        f'<path d="M{x - 0.04 * k:.0f},{y - 0.74 * k:.0f} L{x + 0.02 * k:.0f},{y - 0.64 * k:.0f} L{x + 0.06 * k:.0f},{y - 0.74 * k:.0f}" stroke="{VERMILION}" stroke-width="4" fill="none"/>'
        f'<ellipse cx="{x + 0.02 * k:.0f}" cy="{y - 0.81 * k:.0f}" rx="{0.045 * k:.0f}" ry="{0.055 * k:.0f}" fill="{WHITE}" stroke="{SUMI}" stroke-width="2"/>'
        f'<path d="M{x - 0.05 * k:.0f},{y - 0.82 * k:.0f} Q{x - 0.07 * k:.0f},{y - 0.94 * k:.0f} {x + 0.02 * k:.0f},{y - 0.92 * k:.0f} '
        f'Q{x + 0.08 * k:.0f},{y - 0.9 * k:.0f} {x + 0.06 * k:.0f},{y - 0.84 * k:.0f} Q{x + 0.02 * k:.0f},{y - 0.87 * k:.0f} {x - 0.05 * k:.0f},{y - 0.82 * k:.0f} Z" fill="{SUMI}"/>'
        f'<ellipse cx="{x - 0.02 * k:.0f}" cy="{y - 0.94 * k:.0f}" rx="{0.05 * k:.0f}" ry="{0.03 * k:.0f}" fill="{SUMI}"/>'
        f'<path d="M{x - 0.08 * k:.0f},{y - 0.95 * k:.0f} l{0.13 * k:.0f},{-0.03 * k:.0f}" stroke="{OCHRE}" stroke-width="3"/>'
        f'<circle cx="{x + 0.06 * k:.0f}" cy="{y - 0.98 * k:.0f}" r="{0.012 * k:.1f}" fill="{VERMILION}"/>'
        f'<circle cx="{x + 0.035 * k:.0f}" cy="{y - 0.79 * k:.0f}" r="{0.006 * k:.1f}" fill="{VERMILION}"/>'
    )


def samurai(x, y, k):
    stripes = "".join(f'<path d="M{x + o * k:.0f},{y - 0.48 * k:.0f} L{x + o * 1.9 * k:.0f},{y}" stroke="{INDIGO3}" stroke-width="3" opacity="0.8"/>'
                      for o in (-0.08, -0.04, 0.04, 0.08))
    return (
        f'<path d="M{x - 0.1 * k:.0f},{y - 0.5 * k:.0f} L{x - 0.2 * k:.0f},{y} L{x + 0.2 * k:.0f},{y} L{x + 0.1 * k:.0f},{y - 0.5 * k:.0f} Z" fill="{INDIGO}" stroke="{SUMI}" stroke-width="3"/>'
        + stripes +
        f'<path d="M{x},{y - 0.45 * k:.0f} L{x},{y}" stroke="{SUMI}" stroke-width="2.5"/>'
        f'<path d="M{x - 0.09 * k:.0f},{y - 0.5 * k:.0f} L{x - 0.1 * k:.0f},{y - 0.72 * k:.0f} L{x + 0.1 * k:.0f},{y - 0.72 * k:.0f} L{x + 0.09 * k:.0f},{y - 0.5 * k:.0f} Z" fill="{WHITE}" stroke="{SUMI}" stroke-width="3"/>'
        f'<path d="M{x - 0.22 * k:.0f},{y - 0.76 * k:.0f} L{x + 0.22 * k:.0f},{y - 0.76 * k:.0f} L{x + 0.12 * k:.0f},{y - 0.66 * k:.0f} L{x - 0.12 * k:.0f},{y - 0.66 * k:.0f} Z" fill="{PINE}" stroke="{SUMI}" stroke-width="3"/>'
        f'<circle cx="{x - 0.12 * k:.0f}" cy="{y - 0.71 * k:.0f}" r="{0.018 * k:.1f}" fill="{WHITE}" stroke="{SUMI}" stroke-width="1.5"/>'
        f'<circle cx="{x + 0.12 * k:.0f}" cy="{y - 0.71 * k:.0f}" r="{0.018 * k:.1f}" fill="{WHITE}" stroke="{SUMI}" stroke-width="1.5"/>'
        f'<path d="M{x - 0.05 * k:.0f},{y - 0.72 * k:.0f} L{x},{y - 0.58 * k:.0f} L{x + 0.05 * k:.0f},{y - 0.72 * k:.0f}" fill="none" stroke="{SUMI}" stroke-width="3"/>'
        f'<path d="M{x - 0.02 * k:.0f},{y - 0.52 * k:.0f} L{x + 0.42 * k:.0f},{y - 0.42 * k:.0f}" stroke="{SUMI}" stroke-width="{0.022 * k:.0f}" stroke-linecap="round"/>'
        f'<path d="M{x - 0.15 * k:.0f},{y - 0.55 * k:.0f} L{x - 0.02 * k:.0f},{y - 0.52 * k:.0f}" stroke="{OCHRE}" stroke-width="{0.024 * k:.0f}" stroke-linecap="round"/>'
        f'<path d="M{x - 0.02 * k:.0f},{y - 0.5 * k:.0f} L{x + 0.3 * k:.0f},{y - 0.47 * k:.0f}" stroke="{SUMI}" stroke-width="{0.018 * k:.0f}" stroke-linecap="round"/>'
        f'<path d="M{x - 0.11 * k:.0f},{y - 0.51 * k:.0f} L{x - 0.02 * k:.0f},{y - 0.5 * k:.0f}" stroke="{VERMILION}" stroke-width="{0.02 * k:.0f}" stroke-linecap="round"/>'
        f'<ellipse cx="{x - 0.01 * k:.0f}" cy="{y - 0.83 * k:.0f}" rx="{0.05 * k:.0f}" ry="{0.06 * k:.0f}" fill="#F2DCC0" stroke="{SUMI}" stroke-width="2"/>'
        f'<path d="M{x - 0.05 * k:.0f},{y - 0.86 * k:.0f} Q{x},{y - 0.92 * k:.0f} {x + 0.05 * k:.0f},{y - 0.85 * k:.0f} L{x + 0.05 * k:.0f},{y - 0.8 * k:.0f} '
        f'Q{x + 0.02 * k:.0f},{y - 0.86 * k:.0f} {x - 0.05 * k:.0f},{y - 0.86 * k:.0f} Z" fill="{SUMI}"/>'
        f'<rect x="{x - 0.015 * k:.0f}" y="{y - 0.95 * k:.0f}" width="{0.03 * k:.0f}" height="{0.05 * k:.0f}" rx="3" fill="{SUMI}"/>'
    )


def figures_scene():
    rng = random.Random(1603)
    body = [kasumi(-60, 560, 480, 48, PEACH), kasumi(600, 1240, 540, 46, PINK, 0.7)]
    # 柳枝从右上垂下
    for k in range(6):
        x0 = 760 + k * 55
        body.append(f'<path d="M{x0},{330} q{rng.uniform(-30, 10):.0f},120 {rng.uniform(-40, 0):.0f},{rng.uniform(200, 300):.0f}" stroke="{PINE}" stroke-width="3" fill="none"/>')
    # 朱红拱桥
    body.append(f'<path d="M-40,2400 L-40,2150 Q540,1990 1120,2150 L1120,2400 Z" fill="{INDIGO2}" stroke="{SUMI}" stroke-width="3"/>')
    for k in range(5):
        y = 2230 + k * 40
        body.append(f'<path d="M{rng.uniform(40, 500):.0f},{y} q100,-12 200,0 t200,0" stroke="{INDIGO3}" stroke-width="3" fill="none"/>')
    body.append(f'<path d="M-40,2070 Q540,1910 1120,2070" stroke="{VERMILION}" stroke-width="12" fill="none"/>')
    for i in range(9):
        px = 40 + i * 125
        top = 2070 - 160 * (1 - ((px - 540) / 580) ** 2)
        body.append(f'<path d="M{px},{top + 2:.0f} L{px},{top + 82:.0f}" stroke="{VERMILION}" stroke-width="9"/>'
                    f'<circle cx="{px}" cy="{top - 4:.0f}" r="8" fill="{OCHRE}" stroke="{SUMI}" stroke-width="1.5"/>')
    body.append(f'<path d="M-40,2150 Q540,1990 1120,2150" stroke="{VERMILION}" stroke-width="10" fill="none"/>')
    body.append(geisha(360, 2020, 520))
    body.append(samurai(730, 2020, 520))
    body.append(cartouche(950, 70, "橋上"))
    return frame("".join(body))


# ---------------- 5 灵鹤 ----------------

def crane(x, y, s, flip=False):
    sx = -1 if flip else 1
    wing_up = (f'<path d="M{-0.1 * s:.0f},{-0.05 * s:.0f} Q{-0.05 * s:.0f},{-0.6 * s:.0f} {0.35 * s:.0f},{-0.95 * s:.0f} '
               f'Q{0.4 * s:.0f},{-0.5 * s:.0f} {0.3 * s:.0f},{-0.05 * s:.0f} Z" fill="{WHITE}" stroke="{SUMI}" stroke-width="3"/>'
               + "".join(f'<path d="M{0.0 * s:.0f},{-0.05 * s:.0f} Q{0.1 * s:.0f},{-(0.3 + j * 0.15) * s:.0f} {0.32 * s:.0f},{-(0.45 + j * 0.15) * s:.0f}" stroke="{SUMI}" stroke-width="1.2" fill="none" opacity="0.5"/>' for j in range(3)) +
               f'<path d="M{0.35 * s:.0f},{-0.95 * s:.0f} Q{0.42 * s:.0f},{-0.6 * s:.0f} {0.38 * s:.0f},{-0.3 * s:.0f} L{0.27 * s:.0f},{-0.38 * s:.0f} '
               f'Q{0.3 * s:.0f},{-0.7 * s:.0f} {0.35 * s:.0f},{-0.95 * s:.0f} Z" fill="{SUMI}"/>')
    wing_down = (f'<path d="M{-0.05 * s:.0f},{0.04 * s:.0f} Q{0.1 * s:.0f},{0.5 * s:.0f} {0.45 * s:.0f},{0.62 * s:.0f} '
                 f'Q{0.4 * s:.0f},{0.3 * s:.0f} {0.28 * s:.0f},{0.04 * s:.0f} Z" fill="#E4DAC6" stroke="{SUMI}" stroke-width="3"/>'
                 f'<path d="M{0.45 * s:.0f},{0.62 * s:.0f} Q{0.38 * s:.0f},{0.4 * s:.0f} {0.36 * s:.0f},{0.22 * s:.0f} L{0.3 * s:.0f},{0.3 * s:.0f} Z" fill="{SUMI}"/>')
    return (f'<g transform="translate({x},{y}) scale({sx},1)">' + wing_down +
            f'<path d="M{0.3 * s:.0f},{0.03 * s:.0f} L{1.05 * s:.0f},{0.1 * s:.0f}" stroke="{SUMI}" stroke-width="{0.025 * s:.1f}"/>'
            f'<path d="M{0.3 * s:.0f},{0.05 * s:.0f} L{1.0 * s:.0f},{0.15 * s:.0f}" stroke="{SUMI}" stroke-width="{0.025 * s:.1f}"/>'
            f'<ellipse cx="{0.1 * s:.0f}" cy="0" rx="{0.3 * s:.0f}" ry="{0.1 * s:.0f}" fill="{WHITE}" stroke="{SUMI}" stroke-width="3"/>'
            f'<path d="M{0.34 * s:.0f},{-0.02 * s:.0f} q{0.1 * s:.0f},{0.02 * s:.0f} {0.12 * s:.0f},{0.08 * s:.0f} q{-0.08 * s:.0f},{0.02 * s:.0f} {-0.14 * s:.0f},{-0.02 * s:.0f} Z" fill="{SUMI}"/>'
            f'<path d="M{-0.18 * s:.0f},{-0.02 * s:.0f} Q{-0.5 * s:.0f},{-0.1 * s:.0f} {-0.72 * s:.0f},{-0.08 * s:.0f}" stroke="{SUMI}" stroke-width="{0.05 * s:.1f}" fill="none" stroke-linecap="round"/>'
            f'<circle cx="{-0.76 * s:.0f}" cy="{-0.08 * s:.0f}" r="{0.04 * s:.1f}" fill="{WHITE}" stroke="{SUMI}" stroke-width="2"/>'
            f'<circle cx="{-0.77 * s:.0f}" cy="{-0.11 * s:.0f}" r="{0.02 * s:.1f}" fill="{VERMILION}"/>'
            f'<path d="M{-0.8 * s:.0f},{-0.07 * s:.0f} L{-0.95 * s:.0f},{-0.05 * s:.0f}" stroke="{OCHRE}" stroke-width="{0.016 * s:.1f}" stroke-linecap="round"/>'
            + wing_up + '</g>')


def pine_branch(x, y, length, rng):
    out = [f'<path d="M{x},{y} Q{x + length * 0.5:.0f},{y - 70} {x + length},{y - 20}" stroke="#4A3328" stroke-width="24" fill="none" stroke-linecap="round"/>']
    for k in range(5):
        cx = x + length * (0.15 + k * 0.18)
        cy = y - 60 + rng.uniform(-30, 10)
        out.append(f'<ellipse cx="{cx:.0f}" cy="{cy:.0f}" rx="85" ry="34" fill="{PINE}" stroke="{SUMI}" stroke-width="2.5"/>')
        for j in range(9):
            a = math.radians(-170 + j * 20)
            out.append(f'<path d="M{cx:.0f},{cy + 10:.0f} l{70 * math.cos(a):.0f},{30 * math.sin(a):.0f}" stroke="#1E3F33" stroke-width="2"/>')
    return "".join(out)


def cranes_scene():
    rng = random.Random(1000)
    body = [sun(560, 240, 85), kasumi(-60, 560, 520, 48, PEACH), kasumi(560, 1240, 600, 46, PEACH, 0.75)]
    body.append(crane(470, 1500, 230))
    body.append(crane(780, 1700, 170))
    body.append(crane(300, 1980, 190, flip=True))
    body.append(kasumi(520, 2060, 640, 52, INDIGO3, 0.6))
    body.append(pine_branch(-40, 2340, 520, rng))
    body.append(cartouche(950, 70, "鶴"))
    return frame("".join(body))


# ---------------- 6 大阪城 ----------------

def castle_tier(cx, base, w, h, roof_h, gable):
    wall = f'<rect x="{cx - w / 2:.0f}" y="{base - h:.0f}" width="{w:.0f}" height="{h:.0f}" fill="{WHITE}" stroke="{SUMI}" stroke-width="3"/>'
    wins = "".join(f'<rect x="{cx - w / 2 + w * f - 9:.0f}" y="{base - h * 0.62:.0f}" width="18" height="{h * 0.32:.0f}" fill="{SUMI}"/>'
                   for f in (0.18, 0.39, 0.61, 0.82))
    rw = w * 1.3
    roof = (f'<path d="M{cx - rw / 2:.0f},{base - h - roof_h * 0.1:.0f} Q{cx - rw * 0.3:.0f},{base - h:.0f} {cx - w * 0.42:.0f},{base - h - roof_h:.0f} '
            f'L{cx + w * 0.42:.0f},{base - h - roof_h:.0f} Q{cx + rw * 0.3:.0f},{base - h:.0f} {cx + rw / 2:.0f},{base - h - roof_h * 0.1:.0f} '
            f'L{cx + rw / 2 - 10:.0f},{base - h + 8:.0f} L{cx - rw / 2 + 10:.0f},{base - h + 8:.0f} Z" fill="#3E7F6E" stroke="{SUMI}" stroke-width="3"/>')
    g = ""
    if gable:
        g = (f'<path d="M{cx - w * 0.2:.0f},{base - h - roof_h * 0.15:.0f} L{cx},{base - h - roof_h * 1.25:.0f} L{cx + w * 0.2:.0f},{base - h - roof_h * 0.15:.0f} Z" '
             f'fill="#3E7F6E" stroke="{SUMI}" stroke-width="3"/>'
             f'<path d="M{cx - w * 0.12:.0f},{base - h - roof_h * 0.3:.0f} L{cx},{base - h - roof_h * 0.95:.0f} L{cx + w * 0.12:.0f},{base - h - roof_h * 0.3:.0f}" '
             f'fill="none" stroke="{OCHRE}" stroke-width="4"/>')
    return wall + wins + roof + g


def castle_scene():
    rng = random.Random(1583)
    body = [sun(560, 240, 80), kasumi(-60, 560, 520, 48, PINK, 0.8), kasumi(560, 1200, 620, 46, PEACH, 0.8)]
    cx = 640
    body.append(f'<path d="M{cx - 400},2160 Q{cx - 330},2000 {cx - 290},1900 L{cx + 290},1900 Q{cx + 330},2000 {cx + 400},2160 Z" fill="#9A9383" stroke="{SUMI}" stroke-width="3"/>')
    for r in range(5):
        y = 1925 + r * 48
        body.append(f'<path d="M{cx - 300 - r * 22},{y} L{cx + 300 + r * 22},{y}" stroke="{SUMI}" stroke-width="2" opacity="0.6"/>')
        for s in range(8):
            xx = cx - 280 - r * 20 + s * (560 + r * 40) / 8 + (r % 2) * 30
            body.append(f'<path d="M{xx:.0f},{y} l0,48" stroke="{SUMI}" stroke-width="1.5" opacity="0.45"/>')
    base, w = 1900, 500
    for (h, rh, gable) in ((120, 60, False), (105, 58, True), (95, 54, False), (85, 50, True), (80, 48, False)):
        body.append(castle_tier(cx, base, w, h, rh, gable))
        base -= h + rh * 0.85
        w *= 0.8
    for sxn in (-1, 1):
        body.append(f'<path d="M{cx + sxn * w * 0.5:.0f},{base + 6:.0f} q{sxn * 8:.0f},-30 {sxn * 22:.0f},-34 q{-sxn * 2:.0f},14 {-sxn * 10:.0f},24 Z" fill="{OCHRE}" stroke="{SUMI}" stroke-width="2"/>')
    body.append(f'<rect x="-20" y="2160" width="1120" height="260" fill="{INDIGO2}" stroke="{SUMI}" stroke-width="3"/>')
    for k in range(5):
        y = 2210 + k * 40
        body.append(f'<path d="M{rng.uniform(40, 300):.0f},{y} q120,-14 240,0 t240,0" stroke="{INDIGO3}" stroke-width="3" fill="none"/>')
    for (sx0, sy0) in ((-20, 1500), (1100, 1640)):
        dirx = 1 if sx0 < 0 else -1
        body.append(branch(sx0, sy0, sx0 + dirx * 260, sy0 + 120, 40, 14))
        for _ in range(6):
            body.append(blossom(sx0 + dirx * rng.uniform(40, 260), sy0 + rng.uniform(40, 140), rng.uniform(14, 22), rng.choice((PINK, WHITE))))
    for _ in range(14):
        px, py = rng.uniform(80, 1040), rng.uniform(1300, 2100)
        body.append(f'<ellipse cx="{px:.0f}" cy="{py:.0f}" rx="8" ry="4.5" transform="rotate({rng.uniform(0, 180):.0f} {px:.0f} {py:.0f})" fill="{PINK}" stroke="{SUMI}" stroke-width="1"/>')
    body.append(cartouche(950, 70, "大阪城"))
    return frame("".join(body))


SCENES = (("1_浪", waves_scene), ("2_峰", mountain_scene), ("3_櫻", sakura_scene),
          ("4_藝伎武士", figures_scene), ("5_靈鶴", cranes_scene), ("6_大阪城", castle_scene))

if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.svg"):
        old.unlink()
    for old in OUT.glob("*.png"):
        old.unlink()
    for name, fn in SCENES:
        (OUT / f"{name}.svg").write_text(fn(), encoding="utf-8")
        print(OUT / f"{name}.svg")
