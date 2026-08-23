# -*- coding: utf-8 -*-
"""PC 端复现：渲染 PDF 页 -> PP-OCRv6 det 推理 -> 简单后处理，验证检测是否正常。"""
import glob

import fitz  # PyMuPDF
import numpy as np
import onnxruntime as ort

PDF = r"c:\Users\15572\Documents\trae_projects\class\张三(2026-2027-1)课表.pdf"
DET = glob.glob(r"C:\Users\15572\AppData\Local\Temp\m6_det\**\inference.onnx", recursive=True)[0]

# 1. 渲染 page 0（模拟 Android PdfRenderer：2x 缩放）
doc = fitz.open(PDF)
page = doc[0]
scale = 2.0
mat = fitz.Matrix(scale, scale)
pix = page.get_pixmap(matrix=mat)
img = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width, pix.n)
if pix.n == 4:
    img = img[:, :, :3]
print("rendered:", img.shape)

# 2. 预处理（对齐 SDK DetPreprocessor：BGR，归一化 ImageNet，resize 到 32 倍数）
def round_half_to_even(v):
    import math
    return math.floor(v / 32.0 + 0.5) * 32 if (v / 32.0) % 1 != 0.5 else (round(v / 32.0) * 32 if round(v / 32.0) % 2 == 0 else (math.floor(v / 32.0) * 32))

h, w = img.shape[:2]
new_h = max(round_half_to_even(h), 32)
new_w = max(round_half_to_even(w), 32)
print("resize to:", new_w, "x", new_h)

import cv2
resized = cv2.resize(img, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
bgr = resized[:, :, ::-1].copy().astype(np.float32) / 255.0
mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
std = np.array([0.229, 0.224, 0.225], dtype=np.float32)
# CHW, C 顺序对应 RGB -> SDK 里 imgMode=BGR 时不做 cvtColor，输入保持 BGR 通道序
# 但 SDK 直接对 BGR mat 归一化（mean/std 是 RGB 的）并 C 顺序为 BGR
x = ((bgr - mean) / std).transpose(2, 0, 1).astype(np.float32)[None]

# 3. det 推理
sess = ort.InferenceSession(DET, providers=["CPUExecutionProvider"])
inp = sess.get_inputs()[0].name
out = sess.get_outputs()[0].name
pred = sess.run([out], {inp: x})[0]
print("det out:", pred.shape, "min:", pred.min(), "max:", pred.max(), "mean:", pred.mean())

# 4. 简单后处理：阈值 0.3 的像素分布
pm = pred[0, 0]
thresh = 0.3
mask = pm > thresh
print("mask pixels:", int(mask.sum()), "/", mask.size)
if mask.sum() > 0:
    ys, xs = np.where(mask)
    print("mask bbox: x", xs.min(), "-", xs.max(), " y", ys.min(), "-", ys.max())
