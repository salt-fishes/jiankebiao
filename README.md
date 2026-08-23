# 简课表

一个把课表装进口袋的本地 Android 应用：导入课表 PDF，自动识别课程、节次、周次与地点，提供周视图、今日视图与个性化设置。

> **完全离线运行**：不申请联网权限、不收集任何数据，OCR 识别全部在手机本地完成。

## ✨ 主要功能

- 📄 **PDF 自动识别**：导入课表 PDF（PP-OCR v6 tiny 本地模型），自动解析课程、节次、周次、地点
- 📅 **课表周视图**：左右滑动切换周次、今日课程高亮、当前时间线提示、可显示/隐藏周末
- ⏰ **今日页**：正在上课 / 下一节课 / 今日课程时间轴
- ➕ **新增课程**：自定义星期、节次与周次（默认按识别到的最长周）
- ✏️ **本地编辑**：点课程修改教师/地点、删除排课
- 🎨 **个性化**：深色模式、动态取色（Android 12+）、作息时间表、学期周数、周末显隐
- 📋 **隐私政策**：内置「关于」「隐私政策」页面

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

## 📄 许可

MIT License
