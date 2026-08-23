# -*- coding: utf-8 -*-
"""检查课表 PDF 的字体信息：是否嵌入、字体名、编码。"""
import pdfplumber
import sys

PDF = r"C:\Users\15572\Documents\trae_projects\class\张三(2026-2027-1)课表.pdf"

with pdfplumber.open(PDF) as pdf:
    for i, page in enumerate(pdf.pages):
        print(f"=== page {i} fonts ===")
        fonts = {}
        for c in page.chars:
            key = c.get("fontname")
            fonts.setdefault(key, []).append(c["text"])
        for name, chars in fonts.items():
            print(f"  {name!r}: {len(chars)} chars, sample={''.join(chars[:12])!r}")
        # pdf 对象层字体描述
        if "objects" in page:
            for k, v in page.objects.items():
                if k == "fonts":
                    for fname, finfo in v.items():
                        print(f"  obj font {fname}: { {kk: vv for kk, vv in finfo.items() if kk in ('FontDescriptor','Type','Subtype','Encoding','DescendantFonts')} }")
                if k == "font" or str(k).startswith("F"):
                    pass
