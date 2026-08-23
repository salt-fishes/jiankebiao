# -*- coding: utf-8 -*-
"""用 subprocess 无损拉取设备上的渲染图并分析内容。"""
import subprocess

ADB = r"C:\Users\15572\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV = "REMOVED-DEVICE-SERIAL"
OUT = r"c:\Users\15572\Documents\trae_projects\class\_ref\p0_device.png"

data = subprocess.check_output(
    [ADB, "-s", DEV, "exec-out", "run-as", "com.example.composeapp", "cat", "files/page_0.png"])
with open(OUT, "wb") as f:
    f.write(data)
print("saved:", len(data), "bytes")

from PIL import Image
import numpy as np
img = np.array(Image.open(OUT).convert("L"))
print("size:", img.shape, "mean:", round(float(img.mean()), 1))
print("dark pct:", round(float((img < 128).mean()) * 100, 2), "%")
print("rows with dark:", int((img.min(axis=1) < 128).sum()))
print("full-width dark rows:", int(((img < 128).sum(axis=1) > img.shape[1] * 0.5).sum()))
# 检查是否是反色（浅色少）
print("white pct:", round(float((img > 200).mean()) * 100, 2), "%")
