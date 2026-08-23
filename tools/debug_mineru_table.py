# -*- coding: utf-8 -*-
"""调试：打印 MinerU HTML 表格解析的原始网格。"""
import re

MINERU = r"C:\Users\15572\Documents\trae_projects\class\_device_out\mineru_full.txt"

html = open(MINERU, encoding="utf-8").read()

TD_RE = re.compile(r"<td([^>]*)>(.*?)</td>", re.S)
TR_RE = re.compile(r"<tr[^>]*>(.*?)</tr>", re.S)
TABLE_RE = re.compile(r"<table>(.*?)</table>", re.S)

tables = TABLE_RE.findall(html)
print(f"表格数量: {len(tables)}")

for ti, thtml in enumerate(tables):
    print(f"\n===== 表格 {ti} =====")
    rows_html = TR_RE.findall(thtml)
    print(f"行数: {len(rows_html)}")
    for ri, rh in enumerate(rows_html):
        cells = []
        for m in TD_RE.finditer(rh):
            attrs = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
            text = m.group(2)[:40].replace("\n", " ")
            cells.append(f"[rs={attrs.get('rowspan','1')}]{text}")
        print(f"row{ri}: " + " | ".join(cells))
