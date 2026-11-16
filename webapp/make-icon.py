#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成桌面图标的图标资源（.icns）。

画一张 1024 的源图，再导出整套 iconset，最后交给 iconutil 打成 .icns。
只有做桌面启动图标时才需要跑，平时改页面不用管它。

用法：python3 make-icon.py
"""

import pathlib
import shutil
import subprocess

from PIL import Image, ImageDraw

S = 1024                     # 源图尺寸，缩小时失真最小
HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "icon"

# 和页面里同一套配色
GREEN_TOP = (58, 127, 92)
GREEN_BOTTOM = (36, 92, 64)
PAPER = (255, 255, 255)
LINE = (214, 208, 196)
ACCENT = (176, 106, 62)


def rounded_gradient(size, radius, top, bottom):
    """圆角矩形 + 竖向渐变：先画一张渐变，再用圆角遮罩抠出来。"""
    grad = Image.new("RGB", (1, size), top)
    px = grad.load()
    for y in range(size):
        t = y / max(1, size - 1)
        px[0, y] = (
            round(top[0] + (bottom[0] - top[0]) * t),
            round(top[1] + (bottom[1] - top[1]) * t),
            round(top[2] + (bottom[2] - top[2]) * t),
        )
    grad = grad.resize((size, size))

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)

    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(grad, (0, 0), mask)
    return out


def build():
    img = rounded_gradient(S, 224, GREEN_TOP, GREEN_BOTTOM)
    d = ImageDraw.Draw(img)

    # 一张作业纸：比底稍偏上，给底下的勾留位置
    paper_box = (232, 168, 792, 744)
    d.rounded_rectangle(paper_box, radius=44, fill=PAPER)

    # 纸上三条作业横线，宽度递减，看着像一列条目而不是表格
    lines = [(300, 300, 700), (300, 400, 636), (300, 500, 560)]
    for x1, y, x2 in lines:
        d.rounded_rectangle([x1, y, x2, y + 26], radius=13, fill=LINE)

    # 右下角一枚对勾徽章：绿底白勾，小尺寸下也能看出「已完成」
    cx, cy, r = 700, 700, 148
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(47, 111, 79), outline=PAPER, width=26)
    d.line(
        [(cx - 74, cy + 4), (cx - 20, cy + 60), (cx + 82, cy - 58)],
        fill=PAPER, width=34, joint="curve",
    )

    # 一小段橙色书签，避免整张图只有一个色相
    d.rounded_rectangle([300, 596, 392, 620], radius=12, fill=ACCENT)

    OUT.mkdir(exist_ok=True)
    src = OUT / "icon-1024.png"
    img.save(src)

    # iconutil 只认固定命名，1x/2x 成对给
    iconset = OUT / "作业板.iconset"
    if iconset.exists():
        shutil.rmtree(iconset)
    iconset.mkdir()
    for base in (16, 32, 128, 256, 512):
        for scale, suffix in ((1, ""), (2, "@2x")):
            px = base * scale
            name = f"icon_{base}x{base}{suffix}.png"
            img.resize((px, px), Image.LANCZOS).save(iconset / name)

    icns = OUT / "作业板.icns"
    subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(icns)], check=True)
    print(f"图标已生成：{icns}")


if __name__ == "__main__":
    build()
