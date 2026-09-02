# 简课表

一个把课表装进口袋的本地 Android 应用：导入课表 PDF 或班级课表 Excel，自动识别课程、节次、周次与地点，提供周视图、今日视图与个性化设置。

> **完全离线运行**：不申请联网权限、不收集任何数据，识别全部在手机本地完成。
>
> **兼容性说明**：目前适配正方教务导出的课表 PDF（个人课表）与班级课表 Excel（.xls）。有适配其他教务系统的需求，欢迎发邮件至 **xunguang255@163.com**。

## ✨ 主要功能

- 📄 **PDF 自动识别**：导入课表 PDF（PP-OCR v6 tiny 本地模型），自动解析课程、节次、周次、地点
- 📊 **班级课表 Excel**：直接解析正方教务导出的 .xls（内置 BIFF8 读取器，无需 OCR，秒级完成）
- 📅 **课表周视图**：左右滑动切换周次、今日课程高亮、当前时间线提示、可显示/隐藏周末
- ⏰ **今日页**：正在上课 / 下一节课 / 今日课程时间轴
- 📱 **桌面小组件**：桌面速览今日课程列表（跟随显示开关）
- 🔔 **上课提醒**：课前 5/10/15/20 分钟本地通知，重启后自动恢复
- ➕ **新增课程**：自定义星期、节次与周次（默认按识别到的最长周）
- ✏️ **本地编辑**：点课程修改教师/地点、删除排课
- 🎨 **个性化**：深色模式、动态取色（Android 12+）、作息时间表、学期周数、周末显隐
- 🧪 **实验性**：自定义背景图片 + 磨砂玻璃风格首页/今日页（「我的 → 实验性」开启）
- 📋 **隐私政策**：内置「关于」「隐私政策」页面

## 📝 更新记录

### v1.4（2026-09-02）
- 📊 **班级课表 Excel 导入**：支持正方教务导出的班级课表 .xls（内置 BIFF8 读取器，无需 OCR，秒级导入）；连堂课自动合并（如 6-8 节拆成两行也能拼回一块）
- 🖐 **长按拖拽调课**：课表长按课程块可跨天/跨节次移动，冲突自动避让最近空位
- 🎨 **课程颜色互不相同**：色板扩至 12 组，导入时按课程名去重分配
- 🔗 **分享本周课表**：顶栏分享键一键生成整周课表 PNG 并调起系统分享
- 📱 **2×2 桌面小组件**：新增紧凑版今日课程小组件（原 3×2 保留）
- 🕐 小组件连堂课结束时间修正为最后一节的下课时间
- ✂️ 课表单元格圆角减小

### v1.3
- 📱 桌面小组件、🔔 上课提醒、🧪 磨砂玻璃实验性 UI

## 📦 安装

下载 `dist/` 下的 release APK 安装（Android 8.0+，arm64）。

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
