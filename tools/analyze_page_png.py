# -*- coding: utf-8 -*-
"""分析拉取到的页面渲染图：尺寸、亮度、暗像素行分布，判断渲染是否正常。"""
import sys
from PIL import Image
import numpy as np

for path in sys.argv[1:]:
    img = Image.open(path).convert("L")
    a = np.array(img)
    h, w = a.shape
    dark = a < 128
    rows_dark = (dark.sum(axis=1) > 0)
    cols_dark = (dark.sum(axis=0) > 0)
    print(f"=== {path} ===")
    print(f"size: {w}x{h}  mean: {a.mean():.1f}  dark_px: {dark.mean()*100:.2f}%")
    print(f"rows with dark px: {rows_dark.sum()} / {h}   cols with dark px: {cols_dark.sum()} / {w}")
    # 首末各找 10 个暗行位置
    idx = np.where(rows_dark)[0]
    print("first dark rows:", idx[:10].tolist(), "last:", idx[-10:].tolist() if len(idx) else [])
