"""
「台灣」配色（取代原本的「暖和」）的背景画：以科技为主题，原创、程式产生（不用任何公司名称或商标）。
用法：python -I tools/taiwan_bg.py  → 設計/台灣背景/*.svg

夜蓝底（这套是深色主题：晚上、会议室用，手表也是深色），芯片青光的线路、晶圆、梅花红点缀（梅花是国花）。
画都放在边角与下方，日期、标题、卡片的位置留白。
  island   島嶼電路：台湾岛的轮廓由电路走线构成，节点发光，几朵梅花
  wafer    晶圓：右下一片晶圆（裸晶格子、反光），细细的走线延伸
  skyline  夜城：夜晚的城市天际线（一座分节的高塔）、远山、资料光流
"""
import math
import random
from pathlib import Path

W, H = 1080, 2400
BG1, BG2 = "#08121D", "#0D1B2A"
CYAN = "#2CC8D8"
CYAN_DIM = "#1D6F7F"
TEAL = "#1E4B5C"
PLUM = "#E0475B"
PLUM_LIGHT = "#F28CA0"
GOLD = "#E9B949"
LINE = "#16324A"
OUT = Path(__file__).resolve().parent.parent / "設計" / "台灣背景"

# 台湾本岛轮廓：海岸上的几个点（经度, 纬度），由北顺时针绕一圈；画的时候依纬度 23.5° 压扁经度
COAST = [(121.53, 25.30), (121.75, 25.15), (122.00, 25.01), (121.85, 24.75), (121.86, 24.58), (121.62, 24.05),
         (121.50, 23.60), (121.38, 23.20), (121.15, 22.75), (120.95, 22.35), (120.85, 21.90), (120.72, 21.94),
         (120.62, 22.38), (120.27, 22.58), (120.10, 23.00), (120.13, 23.45), (120.30, 23.85), (120.42, 24.10),
         (120.52, 24.30), (120.75, 24.60), (120.92, 24.86), (121.10, 25.05), (121.40, 25.18)]


def taiwan(ox, oy, height):
    """把海岸点换成画面座标：北端在 (ox, oy)，总高 [height] 像素。"""
    k = height / (25.30 - 21.90)
    sx = k * math.cos(math.radians(23.5))
    return [(ox + (lon - 121.53) * sx, oy + (25.30 - lat) * k) for lon, lat in COAST]


def taiwan_points(ox, oy, height, lonlat):
    k = height / (25.30 - 21.90)
    sx = k * math.cos(math.radians(23.5))
    return [(ox + (lon - 121.53) * sx, oy + (25.30 - lat) * k) for lon, lat in lonlat]


def frame(body):
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">
<defs>
  <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{BG1}"/><stop offset="1" stop-color="{BG2}"/></linearGradient>
  <filter id="glow" filterUnits="userSpaceOnUse" x="0" y="0" width="{W}" height="{H}"><feGaussianBlur stdDeviation="6" result="b"/>
    <feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
  <filter id="halo" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="40"/></filter>
  <pattern id="grid" width="60" height="60" patternUnits="userSpaceOnUse"><path d="M60,0 L0,0 0,60" fill="none" stroke="{LINE}" stroke-width="1" opacity="0.5"/></pattern>
</defs>
<rect width="{W}" height="{H}" fill="url(#bg)"/>
<rect width="{W}" height="{H}" fill="url(#grid)" opacity="0.5"/>
{body}
</svg>"""


def trace(points, color=CYAN_DIM, w=3, op=0.8):
    d = "M" + " L".join(f"{x:.0f},{y:.0f}" for x, y in points)
    return f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{w}" stroke-linejoin="round" opacity="{op}"/>'


def node(x, y, r=7, color=CYAN):
    return (f'<circle cx="{x:.0f}" cy="{y:.0f}" r="{r * 2.2:.0f}" fill="{color}" opacity="0.18"/>'
            f'<circle cx="{x:.0f}" cy="{y:.0f}" r="{r}" fill="{BG1}" stroke="{color}" stroke-width="3" filter="url(#glow)"/>')


def plum(cx, cy, r, op=0.9):
    """梅花：五瓣圆、花心金点（简洁的几何花）。"""
    petals = "".join(f'<circle cx="{cx + r * 0.6 * math.cos(math.radians(k * 72 - 90)):.1f}" cy="{cy + r * 0.6 * math.sin(math.radians(k * 72 - 90)):.1f}" r="{r * 0.48:.1f}"/>'
                     for k in range(5))
    return (f'<g fill="{PLUM}" opacity="{op}" filter="url(#glow)">{petals}</g>'
            f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r * 0.22:.1f}" fill="{GOLD}"/>')


def manhattan(rng, x0, y0, x1, y1):
    """电路走线：只走水平、垂直、45 度（像 PCB）。"""
    pts = [(x0, y0)]
    mx = x0 + (x1 - x0) * rng.uniform(0.3, 0.7)
    pts.append((mx, y0))
    dy = y1 - y0
    pts.append((mx + math.copysign(min(abs(dy), abs(x1 - mx)) * 0.5, x1 - mx), y0 + dy * 0.5))
    pts.append((pts[-1][0], y1))
    pts.append((x1, y1))
    return pts


def inside(pt, poly):
    x, y = pt
    c = False
    for i in range(len(poly)):
        (x1, y1), (x2, y2) = poly[i], poly[i - 1]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            c = not c
    return c


def plum_line(cx, cy, r, op=0.85):
    """线描的梅花：五个小圆瓣只勾边，花心一点金。"""
    petals = "".join(f'<circle cx="{cx + r * 0.6 * math.cos(math.radians(k * 72 - 90)):.1f}" cy="{cy + r * 0.6 * math.sin(math.radians(k * 72 - 90)):.1f}" r="{r * 0.45:.1f}"/>'
                     for k in range(5))
    return (f'<g fill="{PLUM}" fill-opacity="0.18" stroke="{PLUM}" stroke-width="2" opacity="{op}">{petals}</g>'
            f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r * 0.16:.1f}" fill="{GOLD}"/>')


def island_scene():
    """島嶼電路：点阵组成的台湾（小、细致），北回归线、几个城市节点用细弧线连起来。"""
    rng = random.Random(23)
    ox, oy, hgt = 820, 1480, 640           # 北端位置、整座岛高 640 像素
    poly = taiwan(ox, oy, hgt)
    xs = [p[0] for p in poly]
    ys = [p[1] for p in poly]
    body = [f'<circle cx="{(min(xs) + max(xs)) / 2:.0f}" cy="{(min(ys) + max(ys)) / 2:.0f}" r="330" fill="{CYAN}" opacity="0.05" filter="url(#halo)"/>']
    # 点阵：网格上落在岛内的点，越往中央山脉越亮
    step = 13
    spine_x = lambda y: ox - (y - oy) * 0.28
    dots = []
    yy = min(ys)
    while yy <= max(ys):
        xx = min(xs)
        while xx <= max(xs):
            if inside((xx, yy), poly):
                d = abs(xx - spine_x(yy))
                op = max(0.25, 0.95 - d / 160)
                dots.append(f'<circle cx="{xx:.0f}" cy="{yy:.0f}" r="2.6" fill="{CYAN}" opacity="{op:.2f}"/>')
            xx += step
        yy += step
    body.append("".join(dots))
    # 北回归线（23.5°N）：一条淡淡的虚线横过去，右边小字
    k = hgt / (25.30 - 21.90)
    ty = oy + (25.30 - 23.44) * k
    body.append(f'<path d="M{min(xs) - 160:.0f},{ty:.0f} H{W - 40}" stroke="{GOLD}" stroke-width="1.5" stroke-dasharray="6 8" opacity="0.45"/>')
    body.append(f'<text x="{W - 44}" y="{ty - 10:.0f}" text-anchor="end" font-size="22" font-family="Consolas, monospace" fill="{GOLD}" opacity="0.6">23.5°N</text>')
    # 城市节点＋细弧线
    cities = taiwan_points(ox, oy, hgt, ((121.52, 25.04), (120.97, 24.80), (120.68, 24.15), (120.21, 22.99), (120.30, 22.63)))
    for (x1, y1), (x2, y2) in zip(cities, cities[1:]):
        mx, my = (x1 + x2) / 2 - 22, (y1 + y2) / 2
        body.append(f'<path d="M{x1:.0f},{y1:.0f} Q{mx:.0f},{my:.0f} {x2:.0f},{y2:.0f}" stroke="{CYAN}" stroke-width="1.5" fill="none" opacity="0.7"/>')
    for (x, y) in cities:
        body.append(f'<circle cx="{x:.0f}" cy="{y:.0f}" r="12" fill="{CYAN}" opacity="0.15"/>'
                    f'<circle cx="{x:.0f}" cy="{y:.0f}" r="4.5" fill="#E6F1F5"/>')
    # 两朵线描梅花
    body.append(plum_line(230, 1640, 22))
    body.append(plum_line(275, 1690, 13))
    return frame("".join(body))


def wafer_scene():
    rng = random.Random(300)
    cx, cy, r = 860, 2000, 520
    body = [f'<circle cx="{cx}" cy="{cy}" r="{r + 30}" fill="{CYAN}" opacity="0.06" filter="url(#halo)"/>',
            f'<defs><clipPath id="wf"><circle cx="{cx}" cy="{cy}" r="{r}"/></clipPath>'
            f'<linearGradient id="sheen" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#2A5A78"/>'
            f'<stop offset="0.45" stop-color="#173B55"/><stop offset="0.55" stop-color="#3A7FA0"/><stop offset="1" stop-color="#132E44"/></linearGradient></defs>',
            f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="url(#sheen)" stroke="{CYAN}" stroke-width="3" opacity="0.9"/>']
    # 裸晶格子
    die = 64
    cells = []
    for gx in range(int(cx - r), int(cx + r), die):
        for gy in range(int(cy - r), int(cy + r), die):
            if math.hypot(gx + die / 2 - cx, gy + die / 2 - cy) < r - die * 0.7:
                op = rng.uniform(0.05, 0.18)
                cells.append(f'<rect x="{gx + 3}" y="{gy + 3}" width="{die - 6}" height="{die - 6}" rx="3" fill="{CYAN}" opacity="{op:.2f}"/>')
    body.append(f'<g clip-path="url(#wf)">{"".join(cells)}</g>')
    body.append(f'<path d="M{cx - 40},{cy - r + 2} h80" stroke="{BG1}" stroke-width="10"/>')   # 定位缺口
    # 从晶圆往上、往左的走线
    for _ in range(9):
        sx = rng.uniform(cx - r * 0.7, cx + r * 0.2)
        sy = cy - math.sqrt(max(0, r * r - (sx - cx) ** 2))
        ex, ey = rng.uniform(60, 700), rng.uniform(1250, 1500)
        body.append(trace(manhattan(rng, sx, sy, ex, ey), CYAN_DIM, 2.5))
        body.append(node(ex, ey, 6, CYAN if rng.random() < 0.5 else CYAN_DIM))
    # 左上角一小束线路＋梅花
    for k in range(4):
        y = 120 + k * 28
        body.append(trace([(0, y), (160 + k * 30, y), (220 + k * 30, y + 60)], LINE, 2))
    body.append(plum(150, 1700, 24))
    body.append(plum(205, 1745, 15))
    return frame("".join(body))


def skyline_scene():
    rng = random.Random(101)
    body = [f'<circle cx="820" cy="260" r="70" fill="#E8EEF2" opacity="0.85" filter="url(#glow)"/>',
            f'<circle cx="820" cy="260" r="160" fill="#E8EEF2" opacity="0.06" filter="url(#halo)"/>']
    # 远山（中央山脉剪影）
    body.append(f'<path d="M0,1780 L120,1690 L240,1740 L380,1620 L520,1720 L660,1640 L800,1730 L960,1660 L1080,1720 L1080,2400 L0,2400 Z" fill="#0F2638"/>')
    # 城市：一排楼，中间一座分节高塔（八节＋塔尖，原创造型）
    base = 2200
    x = 0
    while x < W:
        bw = rng.uniform(60, 130)
        bh = rng.uniform(180, 420)
        if 430 < x < 650:
            x += bw
            continue
        body.append(f'<rect x="{x:.0f}" y="{base - bh:.0f}" width="{bw - 6:.0f}" height="{bh:.0f}" fill="#13304A" stroke="{LINE}" stroke-width="2"/>')
        for wy in range(int(base - bh + 20), base - 20, 34):
            for wx in range(int(x + 12), int(x + bw - 18), 22):
                if rng.random() < 0.35:
                    body.append(f'<rect x="{wx}" y="{wy}" width="8" height="12" fill="{GOLD if rng.random() < 0.3 else CYAN}" opacity="{rng.uniform(0.4, 0.85):.2f}"/>')
        x += bw
    tx, tw = 540, 120
    y = base
    seg_h = 95
    body.append(f'<rect x="{tx - tw * 0.7:.0f}" y="{y - 160}" width="{tw * 1.4:.0f}" height="160" fill="#16385A" stroke="{CYAN_DIM}" stroke-width="2"/>')
    y -= 160
    for k in range(8):
        top_w, bot_w = tw * 1.05, tw * 0.8
        body.append(f'<path d="M{tx - bot_w / 2:.0f},{y:.0f} L{tx - top_w / 2:.0f},{y - seg_h:.0f} L{tx + top_w / 2:.0f},{y - seg_h:.0f} L{tx + bot_w / 2:.0f},{y:.0f} Z" '
                    f'fill="#16385A" stroke="{CYAN}" stroke-width="2" opacity="0.95"/>')
        body.append(f'<path d="M{tx - top_w / 2 + 8:.0f},{y - seg_h + 14:.0f} h{top_w - 16:.0f}" stroke="{CYAN}" stroke-width="2" opacity="0.6" filter="url(#glow)"/>')
        y -= seg_h
    body.append(f'<rect x="{tx - 22}" y="{y - 70:.0f}" width="44" height="70" fill="#16385A" stroke="{CYAN}" stroke-width="2"/>')
    body.append(f'<path d="M{tx},{y - 70:.0f} L{tx},{y - 190:.0f}" stroke="{CYAN}" stroke-width="4" filter="url(#glow)"/>')
    body.append(f'<circle cx="{tx}" cy="{y - 194:.0f}" r="7" fill="{PLUM}" filter="url(#glow)"/>')
    # 资料光流：几条横向的光带往右跑
    for _ in range(7):
        yy = rng.uniform(1080, 1500)
        x0 = rng.uniform(-100, 600)
        body.append(f'<path d="M{x0:.0f},{yy:.0f} h{rng.uniform(200, 500):.0f}" stroke="{CYAN}" stroke-width="2.5" opacity="{rng.uniform(0.2, 0.5):.2f}" '
                    f'stroke-linecap="round" filter="url(#glow)"/>')
    # 河面倒影
    body.append(f'<rect x="0" y="{base}" width="{W}" height="{H - base}" fill="#091827"/>')
    for k in range(7):
        yy = base + 24 + k * 26
        body.append(f'<path d="M{rng.uniform(380, 480):.0f},{yy} h{rng.uniform(80, 200):.0f}" stroke="{CYAN}" stroke-width="3" opacity="{0.4 - k * 0.04:.2f}"/>')
    body.append(plum(120, 1250, 22))
    body.append(plum(170, 1290, 14))
    return frame("".join(body))


SCENES = (("1_島嶼電路", island_scene), ("2_晶圓", wafer_scene), ("3_夜城", skyline_scene))

if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for name, fn in SCENES:
        (OUT / f"{name}.svg").write_text(fn(), encoding="utf-8")
        print(OUT / f"{name}.svg")
