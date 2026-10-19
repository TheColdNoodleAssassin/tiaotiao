"""
悬浮球外观预览生成器（不参与 APK 构建，只用于设计评审）。

用纯 Python 复刻 FloatingBallView 的绘制几何，输出三张背景下的实际比例效果图，
方便在没有真机/模拟器时判断透明度与图标比例是否合适。

FloatingBallView 里的几何常量一旦调整，改这里的同名常量即可对齐。
"""
import struct
import zlib
import math

# ---- 与 FloatingBallView 对齐的常量 ----
BALL_DP = 56
DENSITY = 3.0                      # xxhdpi，56dp -> 168px
FILL_ALPHA = 0x4D / 255.0          # 圆形底：黑色 30%
RING_ALPHA = 0x4D / 255.0          # 描边：白色 30%
RING_STROKE_DP = 1.2
GLYPH_HEIGHT_RATIO = 0.78
TRI_WIDTH_RATIO = 0.42
BAR_WIDTH_RATIO = 0.11
GAP_RATIO = 0.13
CORNER_RATIO = 0.10
STROKE_RATIO = 0.045

S = 3                              # 超采样倍数
TILE_W, TILE_H = 360, 380

BALL = BALL_DP * DENSITY
R = BALL / 2 - 1.0 * DENSITY
CX = CY = BALL / 2
RING_W = RING_STROKE_DP * DENSITY

glyph_h = R * GLYPH_HEIGHT_RATIO
tri_w = R * TRI_WIDTH_RATIO
bar_w = R * BAR_WIDTH_RATIO
gap = R * GAP_RATIO
total_w = tri_w + gap + bar_w
GLEFT = CX - total_w / 2
GTOP = CY - glyph_h / 2
GBOT = CY + glyph_h / 2
BAR_X0 = GLEFT + tri_w + gap
BAR_X1 = GLEFT + total_w
CORNER = min(glyph_h * CORNER_RATIO, bar_w / 2)
HALO = (R * STROKE_RATIO) / 2

P0 = (GLEFT, GTOP)
P1 = (GLEFT + tri_w, CY)
P2 = (GLEFT, GBOT)


def seg_dist(px, py, a, b):
    ax, ay = a
    bx, by = b
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    d = vx * vx + vy * vy
    t = 0.0 if d == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / d))
    return math.hypot(px - (ax + t * vx), py - (ay + t * vy))


def _cross(ax, ay, bx, by, px, py):
    return (bx - ax) * (py - ay) - (by - ay) * (px - ax)


def in_tri(px, py):
    d1 = _cross(*P0, *P1, px, py)
    d2 = _cross(*P1, *P2, px, py)
    d3 = _cross(*P2, *P0, px, py)
    return (d1 > 0 and d2 > 0 and d3 > 0) or (d1 < 0 and d2 < 0 and d3 < 0)


def tri_white(px, py):
    if in_tri(px, py):
        return True
    return min(seg_dist(px, py, P0, P1), seg_dist(px, py, P1, P2),
               seg_dist(px, py, P2, P0)) <= HALO


def rrect_sd(px, py):
    """圆角矩形有符号距离，<=0 表示在内部"""
    hx = (BAR_X1 - BAR_X0) / 2.0
    hy = (GBOT - GTOP) / 2.0
    ccx = (BAR_X0 + BAR_X1) / 2.0
    ccy = (GTOP + GBOT) / 2.0
    r = CORNER
    qx = abs(px - ccx) - hx + r
    qy = abs(py - ccy) - hy + r
    return math.hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - r


def glyph_white(px, py):
    return tri_white(px, py) or rrect_sd(px, py) <= HALO


def bg_light(x, y):
    return 242, 244, 248


def bg_dark(x, y):
    return 18, 22, 31


def bg_photo(x, y):
    t = (x / TILE_W + y / TILE_H) / 2
    return int(40 + 120 * t), int(70 + 90 * t), int(150 + 80 * t)


def render(bg_fn):
    rows = []
    ox = (TILE_W - BALL) / 2
    oy = (TILE_H - BALL) / 2
    for Y in range(TILE_H):
        row = []
        for X in range(TILE_W):
            acc = [0.0, 0.0, 0.0]
            for sy in range(S):
                for sx in range(S):
                    x = X + (sx + 0.5) / S
                    y = Y + (sy + 0.5) / S
                    r, g, b = bg_fn(x, y)
                    px, py = x - ox, y - oy
                    d = math.hypot(px - CX, py - CY)
                    if d <= R:
                        r *= (1 - FILL_ALPHA)
                        g *= (1 - FILL_ALPHA)
                        b *= (1 - FILL_ALPHA)
                    if R - RING_W <= d <= R:
                        r = r * (1 - RING_ALPHA) + 255 * RING_ALPHA
                        g = g * (1 - RING_ALPHA) + 255 * RING_ALPHA
                        b = b * (1 - RING_ALPHA) + 255 * RING_ALPHA
                    if d <= R and glyph_white(px, py):
                        r = g = b = 255.0
                    acc[0] += r
                    acc[1] += g
                    acc[2] += b
            n = S * S
            row.append((int(acc[0] / n), int(acc[1] / n), int(acc[2] / n)))
        rows.append(row)
    return rows


def save(path, panels):
    width = TILE_W * len(panels)
    height = TILE_H
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for panel in panels:
            for r, g, b in panel[y]:
                raw += bytes((r, g, b))

    def chunk(tag, data):
        body = struct.pack('>I', len(data)) + tag + data
        return body + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)

    header = struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(chunk(b'IHDR', header))
        f.write(chunk(b'IDAT', zlib.compress(bytes(raw), 9)))
        f.write(chunk(b'IEND', b''))
    print("saved", path, width, "x", height)


if __name__ == '__main__':
    import os
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       '..', 'docs', 'ball-preview.png')
    save(os.path.normpath(out), [render(bg_light), render(bg_dark), render(bg_photo)])
