# Android 开发环境记录

> 更新日期：2026-08-18
> 系统：Windows（Asia/Shanghai 时区）

## 一、核心组件

| 组件 | 版本 | 位置 |
|------|------|------|
| Android CLI | 1.0.15985488 | WinGet 安装：`C:\Users\15572\AppData\Local\Microsoft\WinGet\Packages\Google.AndroidCLI_Microsoft.Winget.Source_8wekyb3d8bbwe\android.exe` |
| JDK (Temurin) | 17.0.20+8 | `C:\Users\15572\java\jdk-17.0.20+8` |
| Android SDK | - | `C:\Users\15572\AppData\Local\Android\Sdk` |
| Gradle | 8.11.1 | `C:\Users\15572\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat` |

## 二、Android SDK 组件

| 组件 | 版本 | 说明 |
|------|------|------|
| platform-tools (adb) | 最新 | adb 守护进程正常 |
| build-tools | 34.0.0 | 含 aapt2、d8、zipalign 等 |
| platforms | android-34 | 编译目标 API 34 |
| licenses | 已接受 | android-sdk-license |

**注意**：未安装 emulator 模拟器组件、无 AVD。调试使用真机。

## 三、项目构建配置

### HelloWorld（经典 View + Kotlin）

| 配置项 | 版本 | 说明 |
|--------|------|------|
| Android Gradle Plugin (AGP) | 8.7.3 | 默认 build-tools 34.0.0 |
| Kotlin | 2.1.21 | kotlin-gradle-plugin |
| compileSdk / targetSdk | 34 | |
| minSdk | 24 | |
| JDK 目标 | 17 | |

### ComposeApp（Compose + Material3 + Room + Widget）

| 配置项 | 版本 | 说明 |
|--------|------|------|
| AGP | 8.7.3 | |
| Kotlin | 2.1.21 | 含 `org.jetbrains.kotlin.plugin.compose` |
| KSP | 2.1.21-2.0.1 | 用于 Room 编译期处理 |
| Compose BOM | **2024.06.00** | Material3 1.2.1 / UI 1.6.8（BOM 管理） |
| activity-compose | 1.9.0 | |
| lifecycle-runtime-ktx | 2.8.4 | |
| Room | 2.7.1 | runtime + ktx + compiler(KSP)；v2：items/courses/schedule_entries，开发期 fallbackToDestructiveMigration(dropAllTables=true) |
| compileSdk | **35** | （2026-08-22 起，build.gradle.kts 已升级） |
| targetSdk | 34 | minSdk 26 |
| ABI | **仅 arm64-v8a** | `defaultConfig.ndk.abiFilters` |

> **⚠️ Compose 版本约束**：BOM ≥ 2025.05.01（Compose 1.8.x）、activity-compose ≥ 1.10、
> lifecycle ≥ 2.9 均强制要求 **compileSdk 35**。本机已装 android-35 后可升级；
> 如遇沙箱拦截 SDK 写入需先放行。

### 课表应用架构（2026-08-22 阶段 1~3 完成）

- 数据层：`data/ScheduleEntities.kt`（CourseEntity/ScheduleEntryEntity/EntryWithCourse，
  地点·教师随条目存储——同课程不同天可能不同教室）、`ScheduleDao.kt`、`ScheduleRepository.kt`
- 设置：`data/ScheduleSettings.kt`（SharedPreferences 存储；开学日期默认 2026-08-31、
  显示周末[默认关]、显示非本周[默认开]、动态取色[默认关]、深色模式、12 节默认作息表）+
  `WeekCalculator`/`TimeUtils`
- 解析入库：`ScheduleParseService` 解析成功后自动写 Room（result.json 流程保留兼容）；
  启动时若库空而 result.json 存在会一次性迁移
- UI：`ui/AppShell.kt`（NavigationBar 三页壳 + 解析状态 + Snackbar + 深色模式解析）、
  `ui/timetable/TimetableScreen.kt`（周视图网格：HorizontalPager 切周、隐藏周末、
  冲突分槽、非本周淡化、时间红线、周选择 Sheet）、`CourseDetailSheet.kt`（学分/教师在详情）、
  `ui/today/TodayScreen.kt`、`ui/mine/MineScreen.kt`
- 主题：seed `#333464` TonalSpot（material-color-utilities 计算），见 `ui/theme/Color.kt`
- 设计方案文档：根目录 `UI_UX_DESIGN.md`

## 四、环境变量

| 变量 | 值 | 作用域 |
|------|-----|--------|
| `JAVA_HOME` | `C:\Users\15572\java\jdk-17.0.20+8` | 用户级 |
| `Path` | 追加 `C:\Users\15572\java\jdk-17.0.20+8\bin` | 用户级 |
| `ANDROID_HOME` | （未设置） | 由 `local.properties` 中 `sdk.dir` 提供 |

## 五、已知问题与注意事项

1. **沙箱限制**：`android sdk list`、`android create --list` 等需写入
   `C:\Users\15572\AppData\Local\Android\Sdk\.sdk\` 的命令会被 TRAE 沙箱拦截
   （`TRAE Sandbox Error: hit restricted`）。规避方式：
   - 构建 APK 走本地 Gradle（已绕过，不依赖 android CLI 模板）
   - 如需放行，在 设置 → 权限与批准 → 自定义配置 中允许该目录写入
2. **AGP 版本约束**：AGP 8.9.1 强制要求 build-tools 35.0.0，本机仅有 34.0.0，
   故固定使用 AGP 8.7.3（默认 build-tools 34.0.0）。
3. **Java 不在 PATH**：新终端已通过用户级环境变量注入；若终端未生效，
   需重启终端或手动执行：
   ```powershell
   $env:JAVA_HOME = "C:\Users\15572\java\jdk-17.0.20+8"
   $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
   ```
4. **本地 Gradle 替代 wrapper**：本机未安装 gradle 到 PATH，直接使用
   `~/.gradle/wrapper/dists` 下已缓存的 Gradle 8.11.1 发行版。

## 六、常用命令

```powershell
# 查看已连接设备
adb devices -l

# 构建 debug APK（2026-08-21 更新：沙箱阻止 ~/.gradle 原生库与 Kotlin daemon，
# 需把 GRADLE_USER_HOME 指到工作区、Gradle 发行版复制到 _build_tools，Kotlin 进程内编译）
$env:JAVA_HOME = "C:\Users\15572\java\jdk-17.0.20+8"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
$env:GRADLE_USER_HOME = "C:\Users\15572\Documents\trae_projects\class\_build_tools\.gradle_home"
& "C:\Users\15572\Documents\trae_projects\class\_build_tools\gradle-8.11.1\bin\gradle.bat" assembleDebug --console=plain

# 安装到设备（-s 指定序列号）
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk

# 端到端解析测试（推送 PDF→触发解析→轮询→二进制拉取 result/日志/页面PNG）
python tools/run_device_test.py --bands 4 --overlap 40 --tag run1
# --bands 1 为整页识别（对照分区模式）；产物在 _device_out/

# 结果对比（与桌面 pdfplumber 基准打分）
python tools/parse_schedule_pdf.py "张三(2026-2027-1)课表.pdf" _device_out/ground_truth.json
python tools/compare_with_ground_truth.py _device_out/result.json _device_out/ground_truth.json
```

## 七、真机信息

| 设备 | 序列号 | 说明 |
|------|--------|------|
| 华为 Android-Device | REMOVED-DEVICE-SERIAL | USB 调试已授权 |
