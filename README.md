# 简课表

一个把课表装进口袋的本地 Android 应用：导入课表 PDF 或班级课表 Excel，自动识别课程、节次、周次与地点，提供周视图、今日视图与个性化设置。

> **完全离线运行**：不申请联网权限、不收集任何数据，识别全部在手机本地完成。
>
> **兼容性说明**：目前适配正方 / 强智教务导出的课表 PDF（个人课表）与班级课表 Excel（.xls），另支持课表截图识别导入。有适配其他教务系统的需求，欢迎发邮件至 **xunguang255@163.com**。

## ✨ 主要功能

- 📄 **PDF 自动识别**：导入课表 PDF（PP-OCR v6 tiny 本地模型 + 格线网格重建表格），自动解析课程、节次、周次、地点；支持多页 PDF、连堂与同格多课，正方 / 强智等主流教务导出均可用
- 🧩 **解析规则包**：内置正方、强智等多套解析规则，支持导入自定义 JSON 规则包扩展新教务格式（纯数据、离线校验）
- 📊 **班级课表 Excel**：直接解析正方教务导出的 .xls（内置 BIFF8 读取器，无需 OCR，秒级完成）
- 📅 **课表周视图**：左右滑动切换周次、今日课程高亮、当前时间线提示、可显示/隐藏周末
- ⏰ **今日页**：正在上课 / 下一节课 / 今日课程时间轴
- 📱 **桌面小组件**：桌面速览今日课程列表（跟随显示开关）
- 🔔 **上课提醒**：课前 5/10/15/20 分钟本地通知，重启后自动恢复
- ➕ **新增课程**：自定义星期、节次与周次（默认按识别到的最长周）
- ✏️ **本地编辑**：点课程修改教师/地点、删除排课
- 🗂 **多课表管理**：班级 / 个人 / 同学的课表并存，独立开学日、周数与作息
- 🖼 **系统日历同步**：课程一键写入系统日历「简课表」本地日历，随系统日历提醒，可一键清空撤销
- 📤 **课表分享**：一键生成整周课表图片，调起系统分享
- 🎨 **个性化**：深色模式、动态取色（Android 12+）、磨砂玻璃风格与自定义背景、作息时间表、周末显隐
- 📳 **振动反馈**：底栏切换、调课落位等关键操作的三档触感
- 📋 **隐私透明**：内置「关于」「隐私政策」页面，逐项说明权限用途

## 📝 版本与更新

| 版本线 | 包名 | 定位 | 最新版本 |
| --- | --- | --- | --- |
| **v2.x 主线** | `com.saltfish.simple` | 新功能在此线开发 | v2.2 |
| **v1.7.x LTS** | `com.example.composeapp` | 长期稳定版，仅接收缺陷修复 | v1.7.2 |

- **v2.2（2026-09-07）**：课表解析重构为格线网格分块，修复连堂与同格多课的节次错位；支持多页 PDF；新增可导入的解析规则包
- **v2.1（2026-09-03）**：修复顶部标题竖排堆叠；包名迁移后首个功能版本
- **v1.7.2 LTS（2026-09-03）**：同上顶部布局修复；原 v1.7.1 已并入本版本废弃
- **v1.7 / v1.7.1**：系统日历直同步与一键清空、全新图标、Expressive 风格动效、设置页整合、关于/隐私页重写
- 完整更新记录见 [Releases](https://github.com/salt-fishes/jiankebiao/releases) 或应用内「关于 → 更新记录」

> ⚠️ v2.x 与 v1.x 包名不同，作为**两个独立应用**并存，数据不互通：v2.x 需重新导入课表；桌面小组件需重新添加。

## 📦 安装

前往 [Releases](https://github.com/salt-fishes/jiankebiao/releases) 下载 APK 安装（Android 8.0+，arm64-v8a）。

- **长期使用 / 推荐他人**：下载 **v1.7.x LTS**（包名 com.example.composeapp）
- **跟进新功能**：下载 **v2.x 主线**（包名 com.saltfish.simple）

## 🛠 技术栈

- **语言/UI**：Kotlin 2.1.21 · Jetpack Compose (Material 3, BOM 2024.06)
- **数据**：Room 2.7.1（KSP）
- **OCR**：PaddleOCR PP-OCRv6 tiny（det + rec ONNX）· ONNX Runtime Android
- **图像处理**：OpenCV 4.5.3
- **PDF 渲染**：系统 PdfRenderer

## 🚀 构建

```bash
# 需要：JDK 17、Android SDK (compileSdk 35)、Gradle 8.11
# 项目根需有 local.properties: sdk.dir=...
cd ComposeApp
gradle assembleDebug    # debug
gradle assembleRelease  # release（需 keystore.properties 提供签名）
```

> 签名密钥 `keystore.properties` 不随仓库提供（见 `.gitignore`）。没有它时 release 仍可构建为未签名 APK。**请自行生成并妥善保管签名密钥**，密钥丢失将无法升级应用。

## � 致谢

本应用基于以下开源项目构建，衷心感谢这些项目的开发与维护者：

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR)（PP-OCRv6 tiny 本地模型）：课表文字识别
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)：OCR 模型本地推理
- [OpenCV](https://github.com/opencv/opencv)（4.5.3）：图像预处理与文档矫正
- [Jetpack Compose](https://developer.android.com/jetpack/compose) · [Material 3](https://m3.material.io/)：界面构建与设计系统
- [Room](https://developer.android.com/jetpack/androidx/releases/room)：本地数据持久化
- [Kotlin](https://kotlinlang.org/)：开发语言

## 📄 许可

本项目采用 [MIT License](LICENSE) 开源发布，详见 [LICENSE](LICENSE) 文件。

> 使用的第三方库（PaddleOCR、ONNX Runtime、OpenCV、Jetpack Compose 等）均遵循各自的开源许可。
