# -*- coding: utf-8 -*-
"""验证「表头行列左缘」简化方案：从表头行的文本段提取列边界，无需 rect。
用 pdfplumber 的字符坐标（top 向下，与 PDFBox yDirAdj 一致）模拟。
"""
import re
import pdfplumber

PDF = r"c:\Users\15572\Documents\trae_projects\class\张三(2026-2027-1)课表.pdf"
ROW_GAP = 6.0
SEG_GAP = 15.0
BLOCK_RE = re.compile(r"^(.+?)([★○●◇:])\s*$")


def cluster(vals, th):
    out, cur = [], None
    for v in sorted(vals):
        if cur is None or abs(v - cur[-1]) > th:
            cur = [v]
            out.append(cur)
        else:
            cur.append(v)
    return out


def extract_cells(page):
    chars = [c for c in page.chars if not c["text"].isspace()]
    if not chars:
        return []

    # 1. 全局行聚类（y 向下）
    row_clusters = cluster([c["top"] for c in chars], ROW_GAP)

    # 2. 每行内按 x 分段 -> (y, xLeft, text)
    segments = []
    for row in row_clusters:
        row_y = row[0]
        row_chars = sorted([c for c in chars if abs(c["top"] - row_y) <= ROW_GAP],
                           key=lambda c: (c["x0"], c["top"]))
        seg = []
        for c in row_chars:
            if seg and abs(c["x0"] - seg[-1]["x0"]) > SEG_GAP:
                segments.append((row_y, seg[0]["x0"], "".join(s["text"] for s in seg)))
                seg = []
            seg.append(c)
        if seg:
            segments.append((row_y, seg[0]["x0"], "".join(s["text"] for s in seg)))

    # 3. 表头行 = 含"时间段"的行 -> 列左缘（取该行段中"节次"之后的 7 个段）
    header = next((s for s in segments if "时间段" in s[2]), None)
    if header is None:
        return []
    header_row_y = header[0]
    header_segs = sorted([s for s in segments if abs(s[0] - header_row_y) <= ROW_GAP],
                         key=lambda s: s[1])
    day_mins = [s[1] for s in header_segs if s[2] not in ("时间段", "节次")][:7]

    # 4. 每段归属最近列
    cells = []
    for y, xl, text in segments:
        if not text.strip():
            continue
        idx = min(range(len(day_mins)), key=lambda i: abs(xl - day_mins[i]))
        cells.append((idx, y, text))
    cells.sort(key=lambda t: t[1])

    # 5. 续行合并
    merged = [None] * len(day_mins)
    out = []
    for idx, y, text in cells:
        first = text.split("\n", 1)[0].strip()
        if BLOCK_RE.match(first):
            merged[idx] = len(out)
            out.append((idx, text))
        elif merged[idx] is not None:
            mi = merged[idx]
            out[mi] = (idx, out[mi][1] + "\n" + text)
    return out


def main():
    with pdfplumber.open(PDF) as pdf:
        standard = []
        for page in pdf.pages:
            for table in page.extract_tables():
                for row in table:
                    standard.append(row)

        cells_all = []
        for page in pdf.pages:
            cells_all.extend(extract_cells(page))

    print("=== 表头行方案重建结果 ===")
    for idx, text in cells_all:
        print(f"[列{idx}] {text[:55].replace(chr(10), ' / ')}")

    names_table, names_new = set(), set()
    for row in standard:
        for cell in row:
            if cell:
                for line in cell.split("\n"):
                    m = BLOCK_RE.match(line.strip())
                    if m:
                        names_table.add(m.group(1))
    for _, text in cells_all:
        for line in text.split("\n"):
            m = BLOCK_RE.match(line.strip())
            if m:
                names_new.add(m.group(1))

    print(f"\n标准课程数: {len(names_table)} | 表头方案课程数: {len(names_new)}")
    print("缺失:", names_table - names_new or "无")
    print("多余:", names_new - names_table or "无")


if __name__ == "__main__":
    main()
