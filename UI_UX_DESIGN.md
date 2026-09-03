# 课表 App · UI/UX 设计方案（MD3）

> 版本：v2.0（实施现状版）· 2026-09-03（随 v2.1 / v1.7.2 更新）
> 依据：Material Design 3 规范 + 实际落地实现
> 现状约束：material3 **1.4.0**（Expressive 基线）、compose-bom 2024.06.00、compileSdk 35、AGP 8.7.3、Kotlin 2.1.21
> 双版本线：v2.x 主线包名 `com.saltfish.simple`；v1.7.x LTS 包名 `com.example.composeapp`。两线 UI 一致。

## 一、设计基调

- **Material You 三原则**：Personal（动态取色）/ Adaptive / Expressive（形变圆角 + 弹簧动效）
- **种子色 `#333464`**（深蓝紫，品牌色）：TonalSpot 生成亮/暗两套 scheme，写入
  `ui/theme/Color.kt / Theme.kt`；「我的 → 外观 → 动态取色」默认关，开启后 API 31+ 跟随壁纸
- **深度表达**：色调表面（surface-container 五级容器）替代阴影；玻璃模式下用
  磨砂玻璃层（`GlassSurface`：tint 0.46 + 1px 定向描边 + 135° 高光）表达层级
- **动效中枢**：`ui/theme/AppMotion` 提供 Expressive 风格四规格
  （spatial 中等回弹弹簧 / spatialFast 硬弹簧 / effects / effectsFast 短时值），
  全部自定义动画统一走它；material3 升 1.5 后可整体替换为官方 MotionScheme
- **触感**：`ui/theme/Haptics` 三档（tick / click / heavy），关键操作全部接线

## 二、信息架构与导航（实际实现）

3 个一级目的地，Scaffold + **自绘迷你底栏**（非 NavigationBar 组件）：

```
┌ 课表(周视图,首页) ┐
│ 今日               │ ← 底栏三项：课表 / 今日 / 我的
└ 我的(设置)        ┘
```

- 底栏：56dp 高玻璃/实色条，**选中项药丸指示器（64×34dp，secondary-container）**
  - 药丸可**横向拖拽**：跟手移动、跨槽位轻震、松手就近吸附并切页（拖动基准同步冻结，
    onDragCancel 与 onDragEnd 同等结算）
  - 点击直接切页 + 图标 1.15x 回弹缩放（spatial 弹簧）
- 页面切换：`AnimatedContent` 方向性转场（向右切从右滑入），规格取 AppMotion
- 二级覆盖页（课表管理/作息/小组件/关于/隐私）：`OverlayPage` 容器——淡入+上滑+缩放进入、
  快速淡出退场；主界面常驻组合（alpha 淡隐）保证**返回时保留滚动位置**；内容下方垫
  触摸拦截层防穿透
- 未实施：NavigationRail / Drawer / Navigation Compose（单 Activity + 状态切换已够用）

## 三、核心页面（实际实现）

### 1. 课表页（周视图，主屏）

- 顶行（无容器，直接贴磨砂背景）：「第 N 周」同行内联（`titleLarge` Bold）+
  日期范围副行（`labelSmall`）；右侧分享 / 新增 / 周选择三个图标
- 周切换：左右滑动网格（HorizontalPager）；点击标题弹出周选择面板（周次 chip + 课表切换）
- 表头：7 列（受"显示周末"开关控制）；今日列 `primary` 圆形日期徽标
- 网格：左侧节次轴（节次号 + 起止时间）；课程色块配色为 **12 组派生色板
  （seed 色相 30° 展开）按课程名去重分配**，动态取色下按 primary 色相 HSV 重派生；
  内容为课程名（≤3 行）+ ★○●◇ 类型标记 + 教师@教室；玻璃模式下块体为玻璃面 + 顶部 4dp 课程色条
- 非本周淡化、冲突分槽、当前时间红线；长按拖拽调课（落位重震、自动避让最近空位）
- 玻璃模式（`CustomBackgroundLayer`）：内置品牌渐变或自定义图片（模糊 0–28dp 可调 +
  表面 scrim），底栏/卡片/覆盖页同用玻璃层级

### 2. 今日页

正在上课 Hero 卡（进度条）、下一节课卡（倒计时）、今日时间轴列表；已结束项淡化。

### 3. 我的页（v2.1 分组卡片版）

分组卡片 + 卡内细分隔线，层次化而非零散堆放：

| 分组 | 内容 |
|---|---|
| 学期信息（视觉重心） | 第 N 周/倒计时大字 + 开学日期（DatePicker）+ 学期周数（对话框） |
| 课表 | 导入文件、作息时间、课表管理、桌面小组件、**同步到系统日历**、**清空系统日历中的课程**、导出 .ics（备用） |
| 显示 | 显示周末、非本周淡化、磨砂玻璃（展开：背景图选择/模糊滑杆/清除） |
| 外观 | 深色模式三选（FilterChip）、动态取色 |
| 提醒 | 课前提醒开关（展开：提前 5/10/15/20 分钟、测试通知） |
| 数据 | 课表统计 + 清除当前课表（danger 红） |
| 关于 | 版本（BuildConfig.VERSION_NAME）、隐私政策 |

- 设置存储为 SharedPreferences（`SettingsRepository` Flow 收集），**非 DataStore**
- 「导出 JSON」方案已评估并决定不做

### 4. 关于页 / 隐私政策页（v2.1 重写）

- 关于：真实应用图标头部 + 主要功能单卡分组 + **更新记录折叠收纳**（点击展开，
  当前版本默认展开，箭头旋转动画）+ 兼容性 + 开发者 + 开源仓库链接
- 隐私：核心离线承诺卡 + **权限清单**（逐项说明用途与触发时机）+ 不联网承诺
  （无 INTERNET 权限为系统层硬限制）+ 数据存储位置 + 兼容性说明

## 四、关键流程（实际实现）

- **导入**：SAF 选文件 / 系统分享直达 → 前台服务本地解析（PDF=OCR band 识别，
  Excel=BIFF8 直读）→ 导入确认弹窗（新建课表 / 覆盖现有 / 写入新建表）
- **系统日历**：申请 READ/WRITE_CALENDAR → 创建/定位「简课表」本地日历 →
  清空旧事件后批量写入（含提醒规则）；清空操作有确认弹窗；绝不触碰用户其他日历
- **课程编辑/新增**：ModalBottomSheet 表单（名称/教师/教室 + 星期 + 节次 + 周次 chip 流）

## 五、动效与触感（实际实现）

- AppMotion 四规格全量接入：底栏药丸吸附（spatialFast）、页签转场（spatial+effectsFast）、
  周数滚动、解析条展开、二级页 OverlayPage 转场、更新记录折叠、磨砂设置区展开
- 底栏选中图标 1.15x 回弹；主界面在二级页打开时 alpha 淡隐
- Haptics 接线约 20 处：底栏点击/跨槽位/吸附（tick）、课表拖起（tick）/落位（heavy）、
  保存/添加/切换/复制/同步/清空（click）、删除/清除（heavy）、开关与芯片（tick）

## 六、无障碍与适配

- 触控目标 ≥48dp；课程块合并单节点语义
  `contentDescription = "周一 1-2节 模拟电子线路 环宇楼A404"`
- 暗色模式：主题根部统一提供 LocalContentColor（修复透明容器下正文变黑）
- 玻璃界面正文对比度：暗色正文 E5E1E6 + 玻璃暗 tint，实测可读
- 支持系统字体缩放；手机单列布局（Rail/Drawer 未实施）

## 七、组件用量与版本边界

- 全量使用：Scaffold / TopAppBar / ModalBottomSheet / FilterChip / Switch / Slider /
  Card / DatePicker / AlertDialog / OutlinedTextField / LinearProgressIndicator /
  Snackbar / HorizontalDivider
- **Expressive 边界**：material3 1.4.0 稳定线的 Expressive API（MotionScheme、
  MaterialExpressiveTheme）为 internal；组件库（波浪进度、FloatingToolbar、ButtonGroup、
  LoadingIndicator）在 1.5.0-alpha 线——待其稳定后作为 v2 路线升级项
