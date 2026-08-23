# -*- coding: utf-8 -*-
import pdfplumber

pdf = pdfplumber.open(r"c:\Users\15572\Documents\trae_projects\class\张三(2026-2027-1)课表.pdf")
page = pdf.pages[0]
chars = [c for c in page.chars if not c["text"].isspace()]


def cluster(vals, th):
    out, cur = [], None
    for v in sorted(vals):
        if cur is None or abs(v - cur[-1]) > th:
            cur = [v]
            out.append(cur)
        else:
            cur.append(v)
    return out


rows = cluster([c["top"] for c in chars], 6.0)
for row in rows:
    ry = row[0]
    if not (200 <= ry <= 320):
        continue
    rc = sorted([c for c in chars if abs(c["top"] - ry) <= 6], key=lambda c: (c["x0"], c["top"]))
    seg, segs = [], []
    for c in rc:
        if seg and abs(c["x0"] - seg[-1]["x0"]) > 15:
            segs.append((round(seg[0]["x0"]), "".join(s["text"] for s in seg)))
            seg = []
        seg.append(c)
    if seg:
        segs.append((round(seg[0]["x0"]), "".join(s["text"] for s in seg)))
    print("y=%.1f n=%d" % (ry, len(segs)), [(xl, t[:14]) for xl, t in segs])
