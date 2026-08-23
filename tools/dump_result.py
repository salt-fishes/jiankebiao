# -*- coding: utf-8 -*-
"""打印设备结果的完整周次列表，与 MinerU 基准人工核对。"""
import json
import sys

d = json.load(open(sys.argv[1], encoding="utf-8"))
print("--- 设备周次全量 ---")
for e in d["entries"]:
    ws = ",".join(map(str, e["weeks"]))
    print(f"{e['course']:<12} day{e['dayOfWeek']} {e['startSection']}-{e['endSection']}节 weeks=[{ws}]")

print("\n--- 课程字段 ---")
for c in d["courses"]:
    print(f"{c['name']:<12} {c['type']} 学分:{c['credit'] or '-'} 教师:{c['teacher'] or '-'} "
          f"{c['campus']}/{c['building']}/{c['room']} 教学班:{c['classNo'] or '-'}")
