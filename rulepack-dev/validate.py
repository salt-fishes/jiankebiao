#!/usr/bin/env python3
"""简课表解析规则包校验器 + 规则模拟器（纯标准库）。

用法：
    python validate.py rulepack.json [--sample sample.txt] [--simulate cells.txt]

- 无 --sample/--simulate：结构 / 安全上限 / 正则可编译 校验；
- --sample：指纹自检（anyOf 命中数 ≥ minHits）与节次/周次正则抽样提示；
- --simulate：用 Python 镜像应用内规则执行器语义，对逐格 OCR 文本试跑
  规则包，打印每格的字段值、命中规则与未入块行——规则包作者据此本地闭环。

cells.txt 格式（与应用 ocr_debug_image.txt 一致）：
    [day 2 3-4节]
    机械工程材料★
    ◎ (3-4节)1-5周,7-11周
    ...
    （空行或 [ 开头行分隔多个单元格；纯文本则整文件视为一个单元格）

注意：本脚本用 Python re 近似执行；应用端是 Java regex，个别方言差异以应用导入为准。
"""
import argparse
import json
import re
import sys

MAX_PATTERN_LEN = 200
MAX_LIST_ITEMS = 24
MAX_WORD_LEN = 24
MAX_SUBJECT_LEN = 4000
SEMANTIC_FIELDS = {
    "campus", "building", "room", "teacher",
    "classNo", "composition", "credit", "sections",
}
TITLE_WORDS = ("高等学校", "教师", "讲师", "教授", "助教", "职称", "无")

errors: list[str] = []
warnings: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


def check_word(value, where: str, required: bool = True) -> None:
    if value is None or value == "":
        if required:
            err(f"{where}: 不能为空")
        return
    if not isinstance(value, str) or len(value) > MAX_WORD_LEN:
        err(f"{where}: 超长（≤{MAX_WORD_LEN} 字符）")


def compile_regex(pattern: str, where: str):
    if len(pattern) > MAX_PATTERN_LEN:
        err(f"{where}: 正则超长（≤{MAX_PATTERN_LEN} 字符）")
        return None
    try:
        return re.compile(pattern)
    except re.error as e:
        err(f"{where}: 正则编译失败: {e}")
        return None


def escape_literal(s: str) -> str:
    out = []
    for c in s:
        if c in "\\^$.|?*+()[]{}-" or ord(c) < 0x20:
            out.append("\\")
        out.append(c)
    return "".join(out)


def cls(s: str) -> str:
    return s.replace("\\", "\\\\").replace("]", "\\]").replace("^", "\\^").replace("-", "\\-")


# ------------------------------------------------------------------
# 规则包规范化：v1 糖 / v2 原生 → 统一规则数据（镜像 Kotlin buildCompiled）
# ------------------------------------------------------------------

def normalize(pack: dict) -> dict | None:
    schema = pack.get("schemaVersion")
    if schema not in (1, 2):
        err(f"schemaVersion 必须为 1 或 2，得到 {schema}")
        return None
    out = {
        "id": pack.get("id", ""),
        "match": pack.get("match", {}),
        "table": pack.get("table", {}),
        "sections": pack.get("fields", {}).get("sectionsPattern", "\\((\\d+)-(\\d+)节\\)") if schema == 1
        else pack.get("fields", {}).get("sectionsPattern", "\\((\\d+)-(\\d+)节\\)"),
        "weeks": pack.get("fields", {}).get("weeksPattern", ""),
        "parity": (pack.get("fields", {}).get("paritySingle", "单"),
                   pack.get("fields", {}).get("parityDouble", "双")),
        "screenshot": bool(pack.get("screenshot", False)),
    }
    if out["weeks"] == "":
        out["weeks"] = "((?:\\d+\\s*[-–]\\s*\\d+|\\d+)\\s*周(?:\\s*[（(]\\s*[单双]\\s*周?\\s*[）)])?)"
    out["sections_rx"] = None
    if str(out["sections"]).strip():
        out["sections_rx"] = compile_regex(out["sections"], "fields.sectionsPattern")
        if out["sections_rx"] and out["sections_rx"].groups < 2:
            err("fields.sectionsPattern: 需要 ≥2 个捕获组")
    out["weeks_rx"] = compile_regex(out["weeks"] or "", "fields.weeksPattern")
    if out["weeks_rx"] and out["weeks_rx"].groups < 1:
        err("fields.weeksPattern: 需要 ≥1 个捕获组")

    if schema == 2:
        bs = pack.get("blockStart", {}) or {}
        if "typeMarks" in bs or "markAliases" in bs or "trailingZeroFallback" in bs:
            err("schemaVersion 2 不再支持 blockStart.typeMarks/markAliases/trailingZeroFallback，请改写为 courseStart")
        start = bs.get("courseStart") or []
        if not start:
            err("schemaVersion 2 需要 courseStart 规则")
        out["start"] = [
            {
                "re": compile_regex(r.get("pattern", ""), f"courseStart[{i}]"),
                "nameGroup": r.get("nameGroup", 1),
                "type": r.get("type", ""),
                "typeGroup": r.get("typeGroup", 0),
                "typeMap": r.get("typeMap", {}),
                "desc": r.get("name") or (r.get("pattern") or "")[:30],
            }
            for i, r in enumerate(start)
        ]
        fields = pack.get("fields", {}) or {}
        for k in ("positionalTeacher", "positionalRoom"):
            if k in fields:
                err(f"schemaVersion 2 不再支持 fields.{k}，请改写为 fieldRules 行形状规则")
        fr = fields.get("fieldRules") or []
        out["fieldRules"] = [_norm_field_rule(r, i) for i, r in enumerate(fr)]
        out["keyMap"] = fields.get("keyMap", {}) or {}
        out["linePrefixes"] = fields.get("linePrefixes", {}) or {}
        out["separators"] = fields.get("fieldSeparators", ":：·・")
        out["endChars"] = fields.get("fieldEndChars", "/")
    else:
        # v1 糖展开（镜像 expandV1BlockStart / expandV1Fields）
        bs = pack.get("blockStart", {}) or {}
        typeMarks = bs.get("typeMarks", {}) or {}
        markAliases = bs.get("markAliases", {}) or {}
        tzf = bs.get("trailingZeroFallback", True)
        start, seen = [], set()

        def add(pat, typ, desc):
            if pat not in seen:
                seen.add(pat)
                start.append({"re": compile_regex(pat, f"糖规则 {desc}"),
                              "nameGroup": 1, "type": typ, "typeGroup": 0,
                              "typeMap": {}, "desc": desc})

        def type_for(mark):
            if mark in typeMarks:
                return typeMarks[mark]
            return typeMarks.get(markAliases.get(mark, ""), "")

        for mark, t in typeMarks.items():
            add("^(.+)" + escape_literal(mark) + "$", t, f"类型标记 {mark}")
        for wrong, right in markAliases.items():
            pat = ("^(?!.*[：:])(?=.*[\u2e80-\u9fff])(.*[^\d])" + escape_literal(wrong) + "$") \
                if wrong.isdigit() else "^(.+)" + escape_literal(wrong) + "$"
            add(pat, type_for(wrong), f"标记别名 {wrong}")
        if tzf:
            add("^(?!.*[：:])(?=.*[\u2e80-\u9fff])(.*[^\d])0$", type_for("0"), "行尾0兜底")
        out["start"] = start

        fields = pack.get("fields", {}) or {}
        keyMap = fields.get("keyMap", {}) or {}
        linePrefixes = fields.get("linePrefixes", {}) or {}
        seps = fields.get("fieldSeparators", ":：·・")
        ends = fields.get("fieldEndChars", "/")
        rules = []
        for p, f in linePrefixes.items():
            rules.append({"field": f, "linePrefix": p, "re": None, "cap": 1, "line": None,
                          "strip": False, "campus": f == "room", "clean": f == "teacher",
                          "exclude": "", "desc": f"前缀 {p}"})
        keys = sorted(keyMap, key=len, reverse=True)
        for k in keys:
            f = keyMap[k]
            exts = [x.removeprefix(k) for x in keys if x != k and x.startswith(k)]
            guard = "(?!" + "|".join(escape_literal(e) for e in exts) + ")" if exts else ""
            pat = (f"(?:^|[{cls(ends)};；,，])({escape_literal(k)}){guard}"
                   f"\\s*[{cls(seps)}]?\\s*([^ {cls(ends)}]*)")
            rules.append({"field": f, "re": compile_regex(pat, f"键值 {k}"), "cap": 2,
                          "line": None, "linePrefix": None, "strip": False,
                          "campus": f == "room", "clean": f == "teacher",
                          "exclude": "", "desc": f"键值 {k}"})
        if fields.get("positionalTeacher") or "teacher" in linePrefixes.values():
            rules += [
                {"field": "teacher", "re": re.compile("[^一-龥]{0,2}([一-龥]{2,4})[（(](?:高等学校|教师|讲师|教授|助教|无|职称)"),
                 "cap": 1, "line": "first", "linePrefix": None, "strip": False, "campus": False,
                 "clean": True, "exclude": "", "desc": "职称括注行"},
                {"field": "teacher", "re": re.compile("([一-龥]{2,4})(?=【)"),
                 "cap": 1, "line": "first", "linePrefix": None, "strip": False, "campus": False,
                 "clean": True, "exclude": "", "desc": "【周次】同行教师"},
                {"field": "teacher", "re": re.compile("^(?!.*教室$)([一-龥]{2,6})$"),
                 "cap": 1, "line": "last", "linePrefix": None, "strip": False, "campus": False,
                 "clean": True, "exclude": "", "desc": "末行短中文兜底"},
            ]
        if fields.get("positionalRoom") or "room" in linePrefixes.values():
            rules.append({"field": "room",
                          "re": re.compile("^(?=.*\\d)(?=.*[A-Za-z一-龥])([A-Za-z一-龥][A-Za-z0-9\\-一-龥]{0,14}(?: ?[A-Za-z0-9\\-一-龥]{1,10})?)$"),
                          "cap": 1, "line": "first", "linePrefix": None, "strip": True,
                          "campus": True, "clean": False, "exclude": "节周", "desc": "教室行形状"})
        out["fieldRules"] = rules
        out["keyMap"] = {}
        out["linePrefixes"] = {}
        out["separators"] = seps
        out["endChars"] = ends
    return out


def _norm_field_rule(r: dict, i: int) -> dict:
    fr = {
        "field": r.get("field", ""),
        "name": r.get("name", ""),
        "cap": r.get("captureGroup", 1),
        "line": r.get("line"),
        "linePrefix": r.get("linePrefix"),
        "strip": bool(r.get("stripTrailingParen")),
        "campus": bool(r.get("splitCampus")),
        "clean": bool(r.get("cleanTeacher")),
        "exclude": r.get("excludeChars", ""),
    }
    fr["desc"] = fr["name"] or fr["field"]
    if fr["field"] not in SEMANTIC_FIELDS:
        err(f"fieldRules[{i}] 语义字段 {fr['field']} 不在白名单")
    if fr["linePrefix"] is None:
        fr["re"] = compile_regex(r.get("pattern", ""), f"fieldRules[{i}]") if r.get("pattern") else None
        if fr["re"] is None and not fr["linePrefix"]:
            err(f"fieldRules[{i}]: 缺 pattern 或 linePrefix")
    else:
        fr["re"] = None
    if fr["line"] not in (None, "first", "last"):
        err(f"fieldRules[{i}] line 取值非法")
    if fr["linePrefix"] is not None and fr["line"] is not None:
        err(f"fieldRules[{i}] linePrefix 与 line 不可同时使用")
    return fr


# ------------------------------------------------------------------
# 模拟器：镜像 ScheduleParser 规则执行器
# ------------------------------------------------------------------

def strip_trailing_paren(t: str) -> str:
    prev = None
    while prev != t:
        prev = t
        m = re.search(r"[（(][^（()）]*[)）]$", t)
        if m:
            t = t[: m.start()].strip()
    return t


def clean_teacher(v: str) -> str:
    t = v.strip()
    m = re.search(r"[（(][^（()）]*(?:高等学校|教师|讲师|教授|助教|职称|无)", t)
    if m:
        return t[: m.start()].strip()
    return t


def apply_location(value: str, out: dict) -> None:
    v = strip_trailing_paren(value.strip().replace("\u3000", " "))
    sp = v.find(" ")
    head = v[:sp] if sp > 0 else ""
    looks_campus = bool(head) and len(head) <= 5 and all("\u2e80" <= ch <= "\u9fff" for ch in head)
    if looks_campus and 0 < sp < len(v) - 1 and not out.get("campus"):
        out["campus"] = head
        out["room"] = v[sp + 1:].strip()
    else:
        out["room"] = v


def parse_weeks(text: str, parity: tuple) -> list[int]:
    weeks = set()
    for raw in re.split(r"[;；,，.、]", text.replace("周", " ")):
        p0 = raw.strip()
        parity_n = 1 if parity[0] and parity[0] in p0 else (0 if parity[1] and parity[1] in p0 else -1)
        part = p0.replace(parity[0], "").replace(parity[1], "")
        part = re.sub(r"[()（）]", "", part).strip()
        m = re.fullmatch(r"(\d+)\s*[-–—~至]\s*(\d+)", part)
        rng = range(int(m.group(1)), int(m.group(2)) + 1) if m else (
            [int(part)] if re.fullmatch(r"\d+", part) else [])
        for w in rng:
            if 1 <= w <= 30 and (parity_n < 0 or w % 2 == parity_n):
                weeks.add(w)
    return sorted(weeks)


def match_start(rules: list[dict], line: str):
    t = line[:MAX_SUBJECT_LEN]
    for r in rules:
        rx = r["re"]
        if rx is None:
            continue
        m = rx.search(t)
        if not m:
            continue
        try:
            name = m.group(r["nameGroup"]).strip()
        except Exception:
            name = ""
        if not name:
            continue
        typ = ""
        if r["typeGroup"] > 0 and r["typeMap"]:
            try:
                typ = r["typeMap"].get(m.group(r["typeGroup"]) or "", "")
            except Exception:
                typ = ""
        else:
            typ = r["type"]
        rest = t[m.end():].strip() if m.end() < len(t) else ""
        return {"rule": r, "name": name, "type": typ, "rest": rest}
    return None


def simulate_cell(text: str, pack: dict) -> None:
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    blocks: list[list[str]] = []
    bidx: list[list[int]] = []
    unmatched: list[str] = []
    unmatched_idx: list[int] = []

    def try_join(joined: str):
        js = match_start(pack["start"], joined)
        if js and len(js["name"]) <= 30 and not any(c in js["name"] for c in "【:：/；;，"):
            return js
        return None

    for i, line in enumerate(lines):
        start = match_start(pack["start"], line)
        if start is None and blocks:
            last_block = blocks[-1]
            if len(last_block) > 1:
                joined = last_block[-1] + line
                js = try_join(joined)
                if js:
                    idx = len(last_block) - 1
                    nb, ni = [joined], [bidx[-1][-1], i]
                    if js["rest"]:
                        nb.append(js["rest"])
                        ni.append(i)
                    blocks[-1] = last_block[:idx]
                    bidx[-1] = bidx[-1][:idx]
                    blocks.append(nb)
                    bidx.append(ni)
                    continue
        if start is None and not blocks and unmatched:
            joined = unmatched[-1] + line
            js = try_join(joined)
            if js:
                unmatched.pop()
                base = unmatched_idx.pop()
                nb, ni = [joined], [base, i]
                if js["rest"]:
                    nb.append(js["rest"])
                    ni.append(i)
                blocks.append(nb)
                bidx.append(ni)
                continue
        if start is not None:
            blocks.append([line])
            bidx.append([i])
            if start["rest"]:
                blocks[-1].append(start["rest"])
                bidx[-1].append(i)
        elif blocks:
            blocks[-1].append(line)
            bidx[-1].append(i)
        else:
            unmatched.append(line)
            unmatched_idx.append(i)

    print(f"  未入块 {len(unmatched)}: {unmatched if unmatched else ''}")
    for block in blocks:
        title = block[0]
        st = match_start(pack["start"], title)
        name, typ = (st["name"], st["type"]) if st else ("", "")
        out: dict[str, str] = {}
        fills: list[str] = []
        sections: list[int] = []
        weeks: list[int] = []
        rest = [ln.strip() for ln in block[1:] if ln.strip()]

        prefix_rules = [(ri, r) for ri, r in enumerate(pack["fieldRules"]) if r["linePrefix"]]
        claimed: dict[int, tuple[int, str]] = {}
        for li, line in enumerate(rest):
            for ri, r in prefix_rules:
                if line.startswith(r["linePrefix"]):
                    claimed[li] = (ri, line.removeprefix(r["linePrefix"]).strip())
                    break
        body = "".join(ln for li, ln in enumerate(rest) if li not in claimed)
        body = body.replace(" ", "").replace("\u3000", "").replace("（", "(").replace("）", "")[:MAX_SUBJECT_LEN]

        for ri, rule in enumerate(pack["fieldRules"]):
            if rule["field"] != "sections" and rule["field"] in out:
                continue
            if rule["linePrefix"] is not None:
                hits = sorted((li, v) for li, (rj, v) in claimed.items() if rj == ri)
                if rule["field"] == "sections":
                    for _, value in hits:
                        if pack["sections_rx"]:
                            m = pack["sections_rx"].search(value)
                            if m:
                                sections = [int(m.group(1)), int(m.group(2))]
                        wr = ",".join(m.group(1).replace(" ", "") for m in pack["weeks_rx"].finditer(value))
                        if wr:
                            weeks = parse_weeks(wr, pack["parity"])
                    continue
                if not hits:
                    continue
                value = hits[-1][1]
            elif rule["line"] in ("first", "last"):
                matches = []
                for line in rest:
                    t = strip_trailing_paren(line) if rule["strip"] else line
                    if any(c in t for c in rule["exclude"]):
                        continue
                    if rule["re"]:
                        m = rule["re"].search(t)
                        if m:
                            matches.append(m.group(rule["cap"]).strip())
                            if rule["line"] == "first":
                                break
                if not matches:
                    continue
                value = matches[-1]
            else:
                if not rule["re"]:
                    continue
                m = rule["re"].search(body)
                if not m:
                    continue
                value = m.group(rule["cap"])
            v = clean_teacher(value.strip()) if rule["clean"] else value.strip()
            if rule["campus"]:
                before = out.get("room")
                apply_location(v, out)
                fills.append(f"room={out.get('room')} campus={out.get('campus', '')} ← {rule['desc']}")
                _ = before
            else:
                out[rule["field"]] = v
                fills.append(f"{rule['field']}={v} ← {rule['desc']}")

        if not sections and pack["sections_rx"]:
            m = pack["sections_rx"].search(body)
            if m:
                sections = [int(m.group(1)), int(m.group(2))]
        if not weeks:
            wr = ",".join(m.group(1).replace(" ", "") for m in pack["weeks_rx"].finditer(body))
            if wr:
                weeks = parse_weeks(wr, pack["parity"])

        fields = {k: out.get(k, "") for k in ("campus", "building", "room", "teacher", "classNo", "composition", "credit")}
        seg = f"{sections[0]}-{sections[1]}节" if len(sections) == 2 else "节次?(网格回填)"
        wk = f"{min(weeks)}-{max(weeks)}({len(weeks)}周)" if weeks else "[]"
        print(f"  块「{name}」({typ or '-'}) {seg} {wk}")
        print(f"    {fields}")
        if fills:
            for f in fills:
                print(f"    · {f}")


def split_cells(text: str) -> list[tuple[str, str]]:
    """按 [day ...] 头或空行切分单元格。"""
    cells: list[tuple[str, str]] = []
    cur: list[str] = []
    header = ""
    for ln in text.splitlines():
        s = ln.strip()
        if s.startswith("[") or (not s and cur):
            if cur:
                cells.append((header, "\n".join(cur)))
                cur = []
            header = s if s.startswith("[") else ""
        elif s:
            cur.append(s)
    if cur:
        cells.append((header, "\n".join(cur)))
    return cells


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("rulepack")
    ap.add_argument("--sample", help="课表全文样本文本（指纹自检；请用占位数据）")
    ap.add_argument("--simulate", help="逐格 OCR 文本（[day N] 头分隔），镜像应用执行器试跑")
    args = ap.parse_args()

    try:
        with open(args.rulepack, encoding="utf-8") as f:
            raw = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        print(f"[错误] 无法读取规则包: {e}")
        return 1

    # ---- 结构校验（沿用 v1 检查 + v2 扩展） ----
    rid = raw.get("id", "")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,40}", rid):
        err("id 只能含字母数字与 -_.（≤40 字符）")
    if rid in {"zfsoft", "icon-grid", "app-cards", "qz", "generic"}:
        err("id 与内置包重名")
    check_word(raw.get("name"), "name")
    match = raw.get("match") or {}
    any_of = match.get("anyOf") or []
    if not any_of:
        err("match.anyOf 不能为空")
    if len(any_of) > MAX_LIST_ITEMS:
        err("match.anyOf 条目过多")
    for i, w in enumerate(any_of):
        check_word(w, f"match.anyOf[{i}]")
    min_hits = match.get("minHits", 1)
    table = raw.get("table") or {}
    check_word(table.get("headerAnchor", ""), "table.headerAnchor", required=False)
    check_word(table.get("dayPattern", "星期"), "table.dayPattern", required=False)
    if not 1 <= table.get("dayCount", 7) <= 7:
        err("table.dayCount 必须在 1..7")
    for key in ("labelWords", "legendWords"):
        words = table.get(key) or []
        if len(words) > MAX_LIST_ITEMS:
            err(f"table.{key} 条目过多")
        for i, w in enumerate(words):
            check_word(w, f"table.{key}[{i}]", required=False)

    pack = normalize(raw)

    # ---- 指纹自检 ----
    if args.sample:
        try:
            with open(args.sample, encoding="utf-8", errors="replace") as f:
                text = f.read()
        except OSError as e:
            print(f"[错误] 无法读取样本: {e}")
            return 1
        hits = [w for w in any_of if w in text]
        print(f"[指纹] 命中 {len(hits)}/{len(any_of)}: {hits}（minHits={min_hits}）")
        if len(hits) < min_hits:
            err("指纹命中数不足——该包不会在应用内被选中")

    # ---- 模拟器 ----
    if args.simulate:
        try:
            with open(args.simulate, encoding="utf-8", errors="replace") as f:
                text = f.read()
        except OSError as e:
            print(f"[错误] 无法读取逐格文本: {e}")
            return 1
        if pack:
            print(f"[模拟] 规则包 {pack['id']}（screenshot={pack['screenshot']}）")
            for header, cell in split_cells(text):
                print(f"[{header or 'cell'}]" if header else "[cell]")
                simulate_cell(cell, pack)
        print("[模拟] 提示：Python re 与 Java regex 存在方言差异，真值以应用内导入为准")

    for w in warnings:
        print(f"[警告] {w}")
    if errors:
        for e in errors:
            print(f"[错误] {e}")
        print("校验失败")
        return 1
    print("校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
