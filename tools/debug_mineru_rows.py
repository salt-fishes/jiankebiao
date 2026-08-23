# -*- coding: utf-8 -*-
"""打印 MinerU HTML 表格中每个 <tr> 的原始 td 序列（含属性），重点看第 2 页区域。"""
import re

MINERU = r"C:\Users\15572\Documents\trae_projects\class\_device_out\mineru_full.txt"
html = open(MINERU, encoding="utf-8").read()

TR_RE = re.compile(r"<tr[^>]*>(.*?)</tr>", re.S)
TD_RE = re.compile(r"<td([^>]*)>(.*?)</td>", re.S)

rows = TR_RE.findall(html)
print(f"总行数: {len(rows)}")
for ri, rh in enumerate(rows):
    cells = TD_RE.findall(rh)
    print(f"--- row{ri}: {len(cells)} 个 td")
    for ci, (attrs, text) in enumerate(cells):
        rs = re.search(r'rowspan="(\d+)"', attrs)
        col = re.search(r'colspan="(\d+)"', attrs)
        t = text[:60].replace("\n", "\\n")
        print(f"   [{ci}] rs={rs.group(1) if rs else 1} cs={col.group(1) if col else 1} {t}")
