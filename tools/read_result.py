# -*- coding: utf-8 -*-
"""读取设备上的 result.json 并分析。"""
import subprocess
import json

ADB = r"C:\Users\15572\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV = "REMOVED-DEVICE-SERIAL"

data = subprocess.check_output(
    [ADB, "-s", DEV, "exec-out", "run-as", "com.example.composeapp", "cat", "files/result.json"],
    stderr=subprocess.DEVNULL)
try:
    d = json.loads(data.decode("utf-8"))
    print("courses:", len(d["courses"]), "entries:", len(d["entries"]))
    print("names:", [c["name"] for c in d["courses"]])
    for e in d["entries"]:
        print("  ", e["dayOfWeek"], e["course"], e["startSection"], e["weeks"])
except Exception as ex:
    print("ERROR:", ex)
    print("raw head:", data[:200])
