"""
水墨配色的背景画（原创、程式产生，SVG 向量；同一个种子每次画出一样的图）。
用法：python -I tools/ink_bg.py  → 设计/水墨背景/*.svg

三张参考：
  mist   遠山煙雨：上方层层远山与云雾，下方留白
  boat   孤舟寒江：淡月、远岸、一叶小舟、几笔水纹与芦苇
  bamboo 竹影清風：左上角几竿竹子与竹叶，右下几片落叶
颜色用宣纸底（#F2EADB）＋墨色（#1A1A1A）不同浓淡，跟水墨配色一样；朱红只用在一个小印章。
画面中间大多留白，放卡片、文字不会被干扰。
"""
import math
import random
from pathlib import Path

W, H = 1080, 2400
PAPER = "#F2EADB"
INK = "#1A1A1A"
SEAL = "#A8322D"
OUT = Path(__file__).resolve().parent.parent / "設計" / "水墨背景"


def defs(extra=""):
    return f"""<defs>
  <filter id="paper" x="0" y="0" width="100%" height="100%">
    <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" seed="7" result="n"/>
    <feColorMatrix in="n" type="matrix" values="0 0 0 0 0.35  0 0 0 0 0.3  0 0 0 0 0.22  0 0 0 0.06 0"/>
  </filter>
  <filter id="bleed" x="-10%" y="-10%" width="120%" height="120%">
    <feTurbulence type="fractalNoise" baseFrequency="0.018" numOctaves="4" seed="3" result="t"/>
    <feDisplacementMap in="SourceGraphic" in2="t" scale="26" xChannelSelector="R" yChannelSelector="G" result="d"/>
    <feGaussianBlur in="d" stdDeviation="2.2"/>
  </filter>
  <filter id="soft" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="14"/></filter>
  <filter id="mist" x="-30%" y="-50%" width="160%" height="200%"><feGaussianBlur stdDeviation="40"/></filter>
  <filter id="brush" x="-10%" y="-10%" width="120%" height="120%">
    <feTurbulence type="fractalNoise" baseFrequency="0.06" numOctaves="2" seed="11" result="t"/>
    <feDisplacementMap in="SourceGraphic" in2="t" scale="5" xChannelSelector="R" yChannelSelector="G"/>
  </filter>
  {extra}
</defs>"""


def frame(body, extra_defs=""):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">'
            f'{defs(extra_defs)}<rect width="{W}" height="{H}" fill="{PAPER}"/>{body}'
            f'<rect width="{W}" height="{H}" filter="url(#paper)"/></svg>')


def ridge(rng, y0, amp, rough, step=12):
    """一条山脊线（几个正弦＋随机起伏），回传点。"""
    phases = [rng.uniform(0, 6.28) for _ in range(4)]
    pts = []
    for x in range(-40, W + 41, step):
        y = y0
        for k, (f, a) in enumerate([(1.3, 1.0), (2.9, 0.45), (6.1, 0.22), (13.0, 0.1)]):
            y -= amp * a * math.sin(x / W * f * math.pi + phases[k])
        y += rng.uniform(-rough, rough)
        pts.append((x, y))
    return pts


def mountain(rng, y0, amp, depth, opacity, gid, rough=6):
    pts = ridge(rng, y0, amp, rough)
    d = f"M{pts[0][0]},{pts[0][1]:.1f} " + " ".join(f"L{x},{y:.1f}" for x, y in pts[1:]) + \
        f" L{W + 40},{y0 + depth} L-40,{y0 + depth} Z"
    top = min(y for _, y in pts)
    grad = (f'<linearGradient id="{gid}" x1="0" y1="{top}" x2="0" y2="{y0 + depth}" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="{INK}" stop-opacity="{opacity}"/>'
            f'<stop offset="0.25" stop-color="{INK}" stop-opacity="{opacity * 0.5}"/>'
            f'<stop offset="0.6" stop-color="{INK}" stop-opacity="0"/>'
            f'<stop offset="1" stop-color="{INK}" stop-opacity="0"/></linearGradient>')
    return f'<path d="{d}" fill="url(#{gid})" filter="url(#bleed)"/>', grad


def seal(x, y, s=56):
    """小小的朱红印（空心方印，不写字）。"""
    return (f'<g opacity="0.75" filter="url(#brush)"><rect x="{x}" y="{y}" width="{s}" height="{s}" rx="4" fill="{SEAL}"/>'
            f'<rect x="{x + 9}" y="{y + 9}" width="{s - 18}" height="{s - 18}" fill="none" stroke="{PAPER}" stroke-width="4"/></g>')


def pine(rng, x, y, h, op):
    """远处的小松（一根干＋几层横向针叶团）。"""
    parts = [f'<path d="M{x},{y} q{rng.uniform(-6, 6):.1f},{-h / 2:.1f} 0,{-h:.1f}" stroke="{INK}" stroke-width="3" fill="none" opacity="{op}"/>']
    for i in range(4):
        yy = y - h * (0.35 + i * 0.18)
        w = h * (0.5 - i * 0.09)
        parts.append(f'<ellipse cx="{x + rng.uniform(-4, 4):.1f}" cy="{yy:.1f}" rx="{w:.1f}" ry="{h * 0.06:.1f}" fill="{INK}" opacity="{op * 0.8:.2f}"/>')
    return f'<g filter="url(#bleed)">{"".join(parts)}</g>'


def mist_scene():
    rng = random.Random(20261126)
    body, grads = [], []
    # 由远到近：越近越浓；每层只在山脊下方晕开一小段（山脚自然没入云雾）
    layers = [(520, 150, 420, 0.09), (660, 190, 420, 0.14), (800, 170, 420, 0.21), (940, 120, 380, 0.30)]
    for i, (y0, amp, depth, op) in enumerate(layers):
        p, g = mountain(rng, y0, amp, depth, op, f"m{i}")
        body.append(p)
        grads.append(g)
    for _ in range(6):
        body.append(pine(rng, rng.uniform(640, 1000), rng.uniform(950, 1000), rng.uniform(40, 75), 0.30))
    # 下方：再一层很淡的远山＋一抹水岸（底部不会空荡）
    for i, (y0, amp, op) in enumerate([(1760, 110, 0.07), (1900, 80, 0.10), (2120, 28, 0.08)]):
        p, g = mountain(rng, y0, amp, 380, op, f"low{i}", rough=3)
        body.append(p)
        grads.append(g)
    body.append(seal(920, 180))
    return frame("".join(body), "".join(grads))


def boat_scene():
    rng = random.Random(1126)
    body, grads = [], []
    # 淡月：一圈很淡的墨晕
    # 淡月：不画框线，用月亮四周一圈淡墨烘托（烘云托月）
    body.append(f'<circle cx="820" cy="300" r="170" fill="{INK}" opacity="0.07" filter="url(#mist)"/>')
    body.append(f'<circle cx="820" cy="300" r="88" fill="#F8F3E8" filter="url(#bleed)"/>')
    # 远岸：很低很淡
    p, g = mountain(rng, 1500, 70, 160, 0.16, "far", rough=4)
    body.append(p)
    grads.append(g)
    body.append(f'<ellipse cx="540" cy="1640" rx="900" ry="80" fill="{PAPER}" opacity="0.9" filter="url(#mist)"/>')
    # 水纹：几道细细的横笔
    for _ in range(9):
        y = rng.uniform(1780, 2240)
        x = rng.uniform(60, 700)
        w = rng.uniform(80, 260)
        body.append(f'<path d="M{x:.0f},{y:.0f} q{w / 2:.0f},{rng.uniform(-4, 4):.1f} {w:.0f},0" stroke="{INK}" stroke-width="{rng.uniform(1.5, 3):.1f}" '
                    f'fill="none" opacity="{rng.uniform(0.08, 0.18):.2f}" stroke-linecap="round" filter="url(#brush)"/>')
    # 小舟＋蓑笠翁
    bx, by = 300, 1960
    body.append(f'<g filter="url(#brush)" opacity="0.7">'
                f'<path d="M{bx - 110},{by} q110,46 230,-6 q-110,18 -230,6 Z" fill="{INK}"/>'
                f'<path d="M{bx + 10},{by - 4} l-8,-50 l18,0 Z" fill="{INK}" opacity="0.9"/>'
                f'<path d="M{bx - 18},{by - 52} q20,-22 44,0 Z" fill="{INK}"/>'
                f'<path d="M{bx + 24},{by - 30} L{bx + 150},{by - 120}" stroke="{INK}" stroke-width="2.5"/>'
                f'<path d="M{bx + 150},{by - 120} q6,60 -4,120" stroke="{INK}" stroke-width="1" fill="none" opacity="0.6"/>'
                f'</g>')
    body.append(f'<ellipse cx="{bx}" cy="{by + 18}" rx="160" ry="10" fill="{INK}" opacity="0.07" filter="url(#soft)"/>')
    # 右下芦苇
    for _ in range(16):
        x = rng.uniform(820, 1060)
        h = rng.uniform(160, 380)
        lean = rng.uniform(-60, -10)
        body.append(f'<path d="M{x:.0f},2400 q{lean / 3:.0f},{-h / 2:.0f} {lean:.0f},{-h:.0f}" stroke="{INK}" stroke-width="{rng.uniform(2, 4):.1f}" '
                    f'fill="none" opacity="{rng.uniform(0.18, 0.35):.2f}" stroke-linecap="round" filter="url(#brush)"/>')
        body.append(f'<ellipse cx="{x + lean:.0f}" cy="{2400 - h:.0f}" rx="7" ry="22" transform="rotate({lean / 3:.0f} {x + lean:.0f} {2400 - h:.0f})" '
                    f'fill="{INK}" opacity="0.18" filter="url(#bleed)"/>')
    body.append(seal(120, 1520))
    return frame("".join(body), "".join(grads))


def leaf(x, y, length, angle, op):
    w = length * 0.13
    return (f'<path d="M0,0 C{length * 0.25:.0f},{-w:.0f} {length * 0.7:.0f},{-w * 0.8:.0f} {length:.0f},0 '
            f'C{length * 0.7:.0f},{w * 0.5:.0f} {length * 0.25:.0f},{w * 0.7:.0f} 0,0 Z" '
            f'transform="translate({x:.0f},{y:.0f}) rotate({angle:.0f})" fill="{INK}" opacity="{op:.2f}"/>')


def bamboo_scene():
    rng = random.Random(611)
    body = []
    # 三竿竹子（从左边斜进来），一节一节，节和节之间留白
    for (x0, lean, width, op) in [(70, 120, 26, 0.30), (170, 200, 20, 0.22), (-10, 60, 30, 0.38)]:
        y = -40
        x = x0
        while y < 1150:
            seg = rng.uniform(150, 210)
            nx = x + lean * seg / 1200
            body.append(f'<path d="M{x:.0f},{y + 6:.0f} L{nx:.0f},{y + seg - 6:.0f}" stroke="{INK}" stroke-width="{width}" '
                        f'opacity="{op}" stroke-linecap="butt" filter="url(#bleed)"/>')
            body.append(f'<path d="M{nx - width * 0.8:.0f},{y + seg:.0f} q{width * 0.8:.0f},-8 {width * 1.6:.0f},0" stroke="{INK}" stroke-width="5" '
                        f'fill="none" opacity="{op + 0.15:.2f}" filter="url(#brush)"/>')
            x, y = nx, y + seg
    # 竹叶：几簇，每簇 3–5 片「个」「介」字形
    clusters = [(300, 260), (420, 520), (240, 760), (520, 330), (380, 980)]
    for cx, cy in clusters:
        base = rng.uniform(10, 60)
        for _ in range(rng.randint(3, 5)):
            body.append(leaf(cx + rng.uniform(-30, 30), cy + rng.uniform(-20, 20), rng.uniform(120, 200),
                             base + rng.uniform(-45, 45), rng.uniform(0.22, 0.42)))
        body.append(f'<path d="M{cx - 60:.0f},{cy + 40:.0f} q40,-30 70,-40" stroke="{INK}" stroke-width="3" fill="none" opacity="0.3" filter="url(#brush)"/>')
    # 竹子放右上（标题、日期在左边，不要压到字）：整组左右翻转
    body_g = f'<g transform="translate({W},0) scale(-1,1)"><g filter="url(#brush)">{"".join(body)}</g></g>'
    # 左下：风吹落的几片叶子＋一块淡淡的石头（底部分页列会挡住最下面约 200px，往上放）
    fall = []
    for _ in range(5):
        fall.append(leaf(rng.uniform(120, 460), rng.uniform(1780, 2060), rng.uniform(70, 110), rng.uniform(-160, 160), rng.uniform(0.12, 0.22)))
    stone = (f'<path d="M0,2240 q60,-170 190,-170 q120,0 170,120 q20,60 0,90 L0,2400 Z" fill="{INK}" opacity="0.10" filter="url(#bleed)"/>'
             f'<path d="M60,2150 q50,-50 120,-55" stroke="{INK}" stroke-width="4" fill="none" opacity="0.18" filter="url(#brush)"/>')
    return frame(body_g + "".join(fall) + stone + seal(940, 2040))


def lotus_leaf(rng, cx, cy, rx, ry, op, tilt=0):
    """荷叶：一大片淡墨（略透视的椭圆、边缘缺一角），中心深一点，放射状叶脉。"""
    notch = rng.uniform(0, 360)
    parts = [f'<ellipse cx="0" cy="0" rx="{rx}" ry="{ry}" fill="{INK}" opacity="{op:.2f}"/>',
             f'<path d="M0,0 L{rx * 1.1 * math.cos(math.radians(notch)):.0f},{ry * 1.1 * math.sin(math.radians(notch)):.0f} '
             f'L{rx * 1.1 * math.cos(math.radians(notch + 14)):.0f},{ry * 1.1 * math.sin(math.radians(notch + 14)):.0f} Z" fill="{PAPER}"/>',
             f'<ellipse cx="0" cy="0" rx="{rx * 0.55:.0f}" ry="{ry * 0.55:.0f}" fill="{INK}" opacity="{op * 0.5:.2f}"/>']
    for k in range(11):
        a = math.radians(notch + 25 + k * 30)
        if k * 30 > 320:
            break
        parts.append(f'<path d="M0,0 L{rx * 0.92 * math.cos(a):.0f},{ry * 0.92 * math.sin(a):.0f}" stroke="{INK}" stroke-width="2" opacity="{op * 1.3:.2f}"/>')
    parts.append(f'<circle r="{rx * 0.06:.0f}" fill="{INK}" opacity="{min(op * 2.5, 0.6):.2f}"/>')
    return (f'<g transform="translate({cx},{cy}) rotate({tilt})" filter="url(#bleed)">{"".join(parts)}</g>')


def stem(x0, y0, x1, y1, bend, op, w=4):
    """荷梗：一根弯弯的线，加几个小点（梗上的刺）。"""
    return (f'<path d="M{x0},{y0} Q{(x0 + x1) / 2 + bend:.0f},{(y0 + y1) / 2:.0f} {x1},{y1}" stroke="{INK}" stroke-width="{w}" '
            f'fill="none" opacity="{op:.2f}" stroke-linecap="round" filter="url(#brush)"/>')


def lotus_flower(cx, cy, s, op):
    """荷花：几瓣淡淡的花瓣（细墨线勾边＋很淡的胭脂色），一个花苞色的芯。"""
    petals = []
    for ang, scale in ((-38, 0.9), (38, 0.9), (-14, 1.0), (14, 1.0), (0, 1.08)):
        petals.append(f'<path d="M0,0 C{-s * 0.32 * scale:.0f},{-s * 0.4 * scale:.0f} {-s * 0.22 * scale:.0f},{-s * 0.95 * scale:.0f} 0,{-s * scale:.0f} '
                      f'C{s * 0.22 * scale:.0f},{-s * 0.95 * scale:.0f} {s * 0.32 * scale:.0f},{-s * 0.4 * scale:.0f} 0,0 Z" '
                      f'transform="rotate({ang})" fill="#E9C9C3" fill-opacity="0.45" stroke="{INK}" stroke-width="2" stroke-opacity="{op:.2f}"/>')
    return f'<g transform="translate({cx},{cy})" filter="url(#brush)">{"".join(petals)}</g>'


def fish(x, y, size, angle, op):
    """小鱼：淡墨鱼身、深一点的背、分叉的尾巴、一个眼点。"""
    L = size
    return (f'<g transform="translate({x:.0f},{y:.0f}) rotate({angle:.0f})" filter="url(#brush)">'
            f'<path d="M0,0 C{L * 0.25:.0f},{-L * 0.2:.0f} {L * 0.65:.0f},{-L * 0.16:.0f} {L * 0.8:.0f},0 C{L * 0.65:.0f},{L * 0.14:.0f} {L * 0.25:.0f},{L * 0.18:.0f} 0,0 Z" '
            f'fill="{INK}" opacity="{op * 0.55:.2f}"/>'
            f'<path d="M{L * 0.08:.0f},{-L * 0.05:.0f} C{L * 0.3:.0f},{-L * 0.17:.0f} {L * 0.55:.0f},{-L * 0.14:.0f} {L * 0.72:.0f},{-L * 0.03:.0f}" '
            f'stroke="{INK}" stroke-width="{max(2, L * 0.04):.1f}" fill="none" opacity="{op:.2f}"/>'
            f'<path d="M{L * 0.78:.0f},0 L{L:.0f},{-L * 0.13:.0f} L{L * 0.93:.0f},0 L{L:.0f},{L * 0.13:.0f} Z" fill="{INK}" opacity="{op * 0.8:.2f}"/>'
            f'<circle cx="{L * 0.12:.0f}" cy="{-L * 0.03:.0f}" r="{max(2, L * 0.025):.1f}" fill="{INK}" opacity="{min(op * 1.6, 0.8):.2f}"/></g>')


def shrimp(x, y, size, angle, op, rng):
    """小虾：透明感的淡墨身体（一节一节变小、尾扇），深一点的头，长长的触须与一对细钳。"""
    s = size
    parts = [f'<path d="M0,0 C{s * 0.12:.0f},{-s * 0.16:.0f} {s * 0.34:.0f},{-s * 0.14:.0f} {s * 0.38:.0f},0 C{s * 0.3:.0f},{s * 0.1:.0f} {s * 0.1:.0f},{s * 0.1:.0f} 0,0 Z" '
             f'fill="{INK}" opacity="{op * 0.7:.2f}"/>',
             f'<circle cx="{s * 0.06:.0f}" cy="{-s * 0.06:.0f}" r="{s * 0.035:.0f}" fill="{INK}" opacity="{min(op * 1.8, 0.85):.2f}"/>']
    # 身体：沿着一条弯弧，一节一节
    cx, cy, ang, w = s * 0.38, 0.0, 10.0, s * 0.11
    for i in range(6):
        seg = s * (0.11 - i * 0.008)
        nx = cx + seg * math.cos(math.radians(ang))
        ny = cy + seg * math.sin(math.radians(ang))
        parts.append(f'<path d="M{cx:.0f},{cy:.0f} L{nx:.0f},{ny:.0f}" stroke="{INK}" stroke-width="{w:.0f}" opacity="{op * 0.55:.2f}" stroke-linecap="round"/>')
        cx, cy, ang, w = nx, ny, ang + 16, w * 0.9
    tip = math.radians(ang)
    for spread in (-28, 0, 28):
        a = tip + math.radians(spread)
        parts.append(f'<path d="M{cx:.0f},{cy:.0f} l{s * 0.12 * math.cos(a):.0f},{s * 0.12 * math.sin(a):.0f}" stroke="{INK}" stroke-width="{s * 0.04:.0f}" '
                     f'opacity="{op * 0.6:.2f}" stroke-linecap="round"/>')
    # 触须：两三根长长的细线往前
    for k in range(3):
        parts.append(f'<path d="M0,{-s * 0.02:.0f} q{-s * rng.uniform(0.4, 0.7):.0f},{-s * rng.uniform(0.1, 0.5):.0f} {-s * rng.uniform(0.9, 1.5):.0f},{s * rng.uniform(-0.2, 0.4):.0f}" '
                     f'stroke="{INK}" stroke-width="1.4" fill="none" opacity="{op * 0.8:.2f}"/>')
    # 一对细长的钳
    for sign in (-1, 1):
        parts.append(f'<path d="M{s * 0.05:.0f},{s * 0.04:.0f} l{-s * 0.3:.0f},{sign * s * 0.1:.0f} l{-s * 0.25:.0f},{sign * s * 0.02:.0f}" stroke="{INK}" '
                     f'stroke-width="2.4" fill="none" opacity="{op * 0.8:.2f}" stroke-linecap="round"/>'
                     f'<path d="M{-s * 0.5:.0f},{sign * s * 0.12:.0f} l{-s * 0.1:.0f},{-sign * s * 0.03:.0f} m{s * 0.1:.0f},{sign * s * 0.03:.0f} l{-s * 0.09:.0f},{sign * s * 0.04:.0f}" '
                     f'stroke="{INK}" stroke-width="2" fill="none" opacity="{op * 0.8:.2f}"/>')
    # 小脚
    for k in range(5):
        parts.append(f'<path d="M{s * (0.3 + k * 0.06):.0f},{s * 0.05:.0f} l{-s * 0.03:.0f},{s * 0.07:.0f}" stroke="{INK}" stroke-width="1.2" opacity="{op * 0.6:.2f}"/>')
    return f'<g transform="translate({x:.0f},{y:.0f}) rotate({angle:.0f})" filter="url(#brush)">{"".join(parts)}</g>'


def lotus_scene():
    rng = random.Random(4)
    body = []
    # 右上：一片大荷叶探进来（只露一部分）＋一朵花
    body.append(stem(1080, 900, 960, 300, 50, 0.25))
    body.append(lotus_leaf(rng, 960, 300, 260, 120, 0.16, tilt=-8))
    body.append(stem(1000, 1000, 820, 720, -30, 0.22, w=3))
    body.append(lotus_flower(820, 720, 120, 0.35))
    # 左下：两片荷叶、一个花苞、梗从底部伸上来
    body.append(stem(220, 2400, 260, 1830, -40, 0.28))
    body.append(lotus_leaf(rng, 250, 1830, 230, 95, 0.18, tilt=6))
    body.append(stem(60, 2400, 40, 2030, 30, 0.25))
    body.append(lotus_leaf(rng, 30, 2030, 190, 80, 0.22, tilt=-4))
    body.append(stem(420, 2400, 470, 1660, 50, 0.24, w=3))
    body.append(lotus_flower(470, 1660, 70, 0.32))
    # 鱼：中下方三尾，往右上游
    for (x, y, sz, a) in ((620, 1900, 110, -20), (720, 1980, 90, -28), (560, 2040, 80, -12)):
        body.append(fish(x, y, sz, a + 180, 0.45))
    # 水纹
    for _ in range(6):
        y = rng.uniform(1550, 2150)
        x = rng.uniform(560, 960)
        body.append(f'<path d="M{x:.0f},{y:.0f} q40,{rng.uniform(-4, 4):.1f} 90,0" stroke="{INK}" stroke-width="2" fill="none" opacity="0.10" filter="url(#brush)"/>')
    body.append(seal(940, 1560))
    return frame("".join(body))


def shrimp_scene():
    rng = random.Random(55)
    body = []
    # 右上：荷叶边角（只露一角）＋垂下来的水草
    body.append(lotus_leaf(rng, 1060, 140, 300, 130, 0.14, tilt=12))
    for _ in range(5):
        x = rng.uniform(820, 1060)
        body.append(f'<path d="M{x:.0f},260 q{rng.uniform(-40, 40):.0f},{rng.uniform(120, 200):.0f} {rng.uniform(-30, 30):.0f},{rng.uniform(300, 480):.0f}" '
                    f'stroke="{INK}" stroke-width="2" fill="none" opacity="0.14" filter="url(#brush)"/>')
    # 下方：几只虾往左游，一前一后
    for (x, y, sz, a, op) in ((640, 1780, 230, 8, 0.5), (820, 1960, 200, -6, 0.45), (560, 2080, 170, 14, 0.4)):
        body.append(shrimp(x, y, sz, a, op, rng))
    # 两尾小鱼在中间偏右
    body.append(fish(860, 1500, 90, 200, 0.35))
    body.append(fish(940, 1560, 70, 192, 0.3))
    # 左下：水草从底部长上来
    for _ in range(7):
        x = rng.uniform(30, 260)
        h = rng.uniform(260, 520)
        body.append(f'<path d="M{x:.0f},2400 q{rng.uniform(-50, 50):.0f},{-h / 2:.0f} {rng.uniform(-40, 40):.0f},{-h:.0f}" stroke="{INK}" '
                    f'stroke-width="{rng.uniform(2, 4):.1f}" fill="none" opacity="{rng.uniform(0.12, 0.24):.2f}" stroke-linecap="round" filter="url(#brush)"/>')
    # 气泡
    for _ in range(5):
        body.append(f'<circle cx="{rng.uniform(380, 560):.0f}" cy="{rng.uniform(1560, 1740):.0f}" r="{rng.uniform(5, 11):.0f}" fill="none" stroke="{INK}" '
                    f'stroke-width="1.5" opacity="0.18"/>')
    body.append(seal(110, 1500))
    return frame("".join(body))


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for name, fn in (("1_遠山煙雨", mist_scene), ("2_孤舟寒江", boat_scene), ("3_竹影清風", bamboo_scene),
                     ("4_荷塘清趣", lotus_scene), ("5_魚蝦戲水", shrimp_scene)):
        (OUT / f"{name}.svg").write_text(fn(), encoding="utf-8")
        print(OUT / f"{name}.svg")
