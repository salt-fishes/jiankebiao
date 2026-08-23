# -*- coding: utf-8 -*-
"""把微信图片生成为 Android launcher 图标（legacy PNG，各密度）。

尺寸：mdpi 48 / hdpi 72 / xhdpi 96 / xxhdpi 144 / xxxhdpi 192（像素，正方形）。
输出到 ComposeApp/app/src/main/res/mipmap-*/ic_launcher.png
"""
import os
from PIL import Image

SRC = r"C:\Users\15572\Documents\trae_projects\class\微信图片_20260823122624_201_106.jpg"
RES = r"C:\Users\15572\Documents\trae_projects\class\ComposeApp\app\src\main\res"

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

img = Image.open(SRC).convert("RGB")
# 中心裁剪为正方形（若原图非方）后缩放
w, h = img.size
side = min(w, h)
left = (w - side) // 2
top = (h - side) // 2
img = img.crop((left, top, left + side, top + side))

for folder, size in DENSITIES.items():
    out_dir = os.path.join(RES, folder)
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "ic_launcher.png")
    img.resize((size, size), Image.LANCZOS).save(out, "PNG")
    print(f"{folder}/ic_launcher.png  {size}x{size}  OK")
