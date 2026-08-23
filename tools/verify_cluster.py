# -*- coding: utf-8 -*-
"""验证修正后的坐标聚类算法（文本段左缘聚类）。"""
import re
import pdfplumber

PDF = r"c:\Users\15572\Documents\trae_projects\class\张三(2026-2027-1)课表.pdf"
ROW_CLUSTER_GAP = 6.0
SEG_GAP = 15.0      # 同一行内相邻字符 gap > 15pt 视为新文本段（中文字符间距 ~6-9pt）
COL_CLUSTER_GAP = 40.0
BLOCK_RE = re.compile(r"^(.+?)([★○●◇:])\s*$")


def cluster(values, threshold):
    vals = sorted(values)
    clusters = []
    for v in vals:
        if not clusters or abs(v - clusters[-1][-1]) > threshold:
            clusters.append([v])
        else:
            clusters[-1].append(v)
    return clusters


def extract_cells(chars):
    """chars: [(text, x, y)] -> 返回按 y 排序的 [(colIdx, text)]，colIdx 0..6。"""
    if not chars:
        return []

    # 1. 视觉行聚类
    row_clusters = cluster([c[2] for c in chars], ROW_CLUSTER_GAP)

    # 2. 每行内按 x 分段 -> 文本段 (y, xLeft, text)
    segments = []
    for row in row_clusters:
        row_y = row[0]
        row_chars = sorted(
            [c for c in chars if abs(c[2] - row_y) <= ROW_CLUSTER_GAP],
            key=lambda c: (c[1], c[2]))
        seg = []
        for c in row_chars:
            if seg and abs(c[1] - seg[-1][1]) > SEG_GAP:
                segments.append((row_y, seg[0][1], "".join(s[0] for s in seg)))
                seg = []
            seg.append(c)
        if seg:
            segments.append((row_y, seg[0][1], "".join(s[0] for s in seg)))

    # 3. 文本段左缘聚类 -> 星期列左缘（取最后 7 簇）
    col_clusters = cluster([s[1] for s in segments], COL_CLUSTER_GAP)
    day_mins = [cl[0] for cl in col_clusters[-7:]]

    # 4. 按列分组，每列内按 (y, x) 排序
    by_col = {i: [] for i in range(7)}
    for y, xl, text in segments:
        idx = min(range(7), key=lambda i: abs(xl - day_mins[i]))
        by_col[idx].append((y, xl, text))

    lines = []
    for i in range(7):
        for y, xl, text in sorted(by_col[i], key=lambda t: (t[0], t[1])):
            lines.append((i, y, text))
    lines.sort(key=lambda t: t[1])

    # 5. 续行合并
    merged = [None] * 7
    cells = []
    for col_idx, y, text in lines:
        first_line = text.split("\n", 1)[0].strip()
        if BLOCK_RE.match(first_line):
            merged[col_idx] = len(cells)
            cells.append((col_idx, text))
        elif merged[col_idx] is not None:
            idx = merged[col_idx]
            cells[idx] = (col_idx, cells[idx][1] + "\n" + text)
    return cells


def main():
    with pdfplumber.open(PDF) as pdf:
        standard = []
        for page in pdf.pages:
            for table in page.extract_tables():
                for row in table:
                    standard.append(row)

        cells_all = []
        for page in pdf.pages:
            chars = [(ch["text"], ch["x0"], ch["top"]) for ch in page.chars
                     if not ch["text"].isspace()]
            cells_all.extend(extract_cells(chars))

    print("=== 聚类重建结果 ===")
    for col_idx, text in cells_all:
        print(f"[列{col_idx}] {text[:60].replace(chr(10), ' / ')}")

    names_table, names_cluster = set(), set()
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
                names_cluster.add(m.group(1))

    print(f"\n标准课程数: {len(names_table)} | 聚类课程数: {len(names_cluster)}")
    print("缺失:", names_table - names_cluster or "无")
    print("多余:", names_cluster - names_table or "无")


if __name__ == "__main__":
    main()
