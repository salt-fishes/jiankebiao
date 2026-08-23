# -*- coding: utf-8 -*-
import pdfplumber

pdf = pdfplumber.open(r"c:\Users\15572\Documents\trae_projects\class\张三(2026-2027-1)课表.pdf")
page = pdf.pages[0]
chars = [c for c in page.chars if not c["text"].isspace()]

# 打印含 "大学物理" 的字符
for c in chars:
    if c["text"] in "大学物理A2":
        print("char=%s x0=%.1f top=%.1f" % (c["text"], c["x0"], c["top"]))
