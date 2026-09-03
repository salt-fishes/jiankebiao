# Android 开发环境记录

> 更新日期：2026-09-03（随 v2.1 / v1.7.2 发布更新）
> 系统：Windows（Asia/Shanghai 时区）

## 一、核心组件

| 组件 | 版本 | 位置 |
|------|------|------|
| JDK (Temurin) | 17.0.20+8 | `%USERPROFILE%\java\jdk-17.0.20+8` |
| Android SDK | - | `%LOCALAPPDATA%\Android\Sdk` |
| Gradle | 8.11.1（wrapper） | 仓库自带 `ComposeApp/gradlew`，缓存于 `~/.gradle/wrapper/dists` |
| adb | platform-tools 最新 | 已在 PATH |

## 二、Android SDK 组件

| 组件 | 版本 | 说明 |
|------|------|------|
| build-tools | 34.0.0 | 含 aapt2、d8、zipalign；aapt 可用于权限核查（`aapt d permissions *.apk`） |
| platforms | android-34 / android-35 | compileSdk 35 |
| licenses | 已接受 | android-sdk-license |

**注意**：无 emulator/AVD，调试使用真机。

## 三、项目构建配置（双版本线）

| 配置项 | v2.x 主线（master） | v1.7.x LTS（lts/1.7 分支） |
|--------|--------------------|---------------------------|
| namespace / applicationId | `com.saltfish.simple` | `com.example.composeapp` |
| 最新版本 | v2.1（versionCode 11） | v1.7.2（versionCode 10） |
| AGP | 8.7.3 | 8.7.3 |
| Kotlin | 2.1.21（含 compose 插件） | 2.1.21 |
| KSP | 2.1.21-2.0.1（Room 编译期处理） | 同左 |
| Compose BOM | 2024.06.00 | 2024.06.00 |
| material3 | **1.4.0**（Expressive 基线，显式依赖压过 BOM 的 1.2.1） | 1.4.0 |
| Room | 2.7.1（runtime/ktx/compiler；schema 按路径导出 `app/schemas`） | 同左 |
| compileSdk / targetSdk / minSdk | 35 / 34 / 26 | 35 / 34 / 26 |
| ABI | 仅 arm64-v8a | 仅 arm64-v8a |
| 签名 | 根目录 `keystore.properties` + `keystore/jiankebiao.jks`（不入库；worktree 构建需手动复制这两项） | 同左 |
| LFS | `res/**/*.png` 与 OCR onnx 模型经 Git LFS 入库 | 同左 |

> **⚠️ material3 版本约束**：compose-bom 2024.06.00 映射 material3 1.2.1，
> 由显式依赖 `material3:1.4.0` 压制。Expressive 公开 API（MotionScheme/
> MaterialExpressiveTheme 等）在 1.4.0 为 internal，需等 1.5 线（现为 alpha）；
> 当前以自建 `ui/theme/AppMotion`（Expressive 风格弹簧）替代，升 1.5 后单文件切换。

## 四、模块地图（v2.1，包 com.saltfish.simple）

- `data/`：Room（CourseEntity/ScheduleEntryEntity/AppDatabase）+ `ScheduleRepository` +
  `ScheduleSettings`（SharedPreferences，SettingsRepository 提供 Flow）+
  `CalendarExport`（.ics）+ `CalendarSync`（写系统日历「简课表」本地日历 / 清空撤销）+
  `WeekCalculator`/`TimeUtils`
- `schedule/`：`SchedulePdfParser`（PdfRenderer 2x 渲染 → PaddleOCR PP-OCRv6 tiny
  band 分区识别 → 列边界聚类重建表格）、`ScheduleXlsParser`（内置 BIFF8 直读 .xls）
- `ui/`：`AppShell`（自绘底栏：56dp 悬浮药丸、拖拽吸附、Haptics；OverlayPage 二级页转场
  与触摸拦截）、`timetable/`（周视图、课表管理、小组件绑定、课程编辑）、
  `today/`、`mine/`（我的整合页 + About/Privacy/SectionTime 覆盖页）
- `ui/theme/`：Color（seed #333464 + 12 组课程色板）、Theme（根部提供 LocalContentColor，
  修复透明容器下暗色正文变黑）、Glass（磨砂玻璃层）、AppMotion（动效中枢）、
  Haptics（三档振动）
- `reminder/`：课前提醒（精确闹钟 + 开机重排）；`widget/`：3×2 / 2×2 小组件
- `ppocr-sdk/`：PaddleOCR ONNX 封装（包名 com.paddle.ocr，未随主线改包）

## 五、环境变量

| 变量 | 值 | 作用域 |
|------|-----|--------|
| `JAVA_HOME` | `%USERPROFILE%\java\jdk-17.0.20+8` | 用户级 |
| `Path` | 追加 `%JAVA_HOME%\bin` | 用户级 |
| `ANDROID_HOME` | （未设置） | 由 `local.properties` 中 `sdk.dir` 提供 |

## 六、已知问题与注意事项

1. **AGP 版本约束**：AGP 8.9.1 强制 build-tools 35.0.0，本机仅有 34.0.0，故固定 AGP 8.7.3。
2. **git 代理**：本机 git 配置了 `http.proxy=127.0.0.1:7890`；代理未开时推送失败，
   用 `git -c http.proxy= -c https.proxy= push ...` 直连绕过（GitHub 直连可用）。
3. **worktree 构建 LTS**：`lts/1.7` 在独立 worktree（`../jiankebiao-lts`）开发构建；
   签名文件不入库，worktree 需手动复制 `keystore.properties` 与 `keystore/`，否则产出
   unsigned APK。
4. **敏感文件**：`MinerU_*.md`（真实课表 OCR 导出，含个人信息）禁止入库，已在
   .gitignore 声明；历史上曾误传，已通过历史重写清除。

## 七、常用命令

```powershell
# 查看/安装
adb devices -l
adb install -r app\build\outputs\apk\release\app-release.apk

# 构建（仓库根的 ComposeApp/ 下）
.\gradlew :app:assembleRelease --console=plain
.\gradlew :app:testReleaseUnitTest --console=plain

# 权限核查（发布前自检：不应出现 INTERNET / 剪贴板）
aapt d permissions app\build\outputs\apk\release\app-release.apk

# 系统日历核查（shell 需先 pm grant 日历权限）
adb shell content query --uri content://com.android.calendar/calendars
```

## 八、真机信息

设备型号与序列号等设备标识**不入库**（唯一标识类信息），调试前本地执行 `adb devices -l` 查看。

> 测试真机为国产 ROM（Android 16）：系统日历无 .ics 导入入口——这是应用内置
> 「同步到系统日历」直写能力的原因。
