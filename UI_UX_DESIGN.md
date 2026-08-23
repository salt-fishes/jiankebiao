# 课表 App · UI/UX 设计方案(MD3)

> 版本:v1.1(含需求修订)· 2026-08-22
> 依据:material-3-skill(Material Design 3 规范)
> 约束:Compose BOM 2024.06.00 / M3 1.2.1、compileSdk 34、AGP 8.7.3、Kotlin 2.1.21

## 一、设计基调

- **Material You 三原则**:Personal(动态取色)/ Adaptive(窗口尺寸自适应)/ Expressive(形变圆角+弹簧动效)
- **种子色:`#333464`(深蓝紫,品牌色)**。静态方案为默认:由 seed 经 Material Theme Builder /
  material-color-utilities 生成亮/暗两套 scheme 写入 `Color.kt/Theme.kt`
  - 参考映射(最终以生成器输出为准):亮色 primary≈tone40、primary-container≈tone90;暗色 primary≈tone80
  - 「我的→外观→动态取色」开关**默认关**(保证品牌一致),开启后 API 31+ 跟随壁纸
- **深度表达**:全部用色调表面(surface-container 五级容器)替代阴影;阴影仅在浮层必要时使用
- Expressive 新组件(XS–XL 按钮、expressive search bar 等)在当前 BOM 不可用,只用 M3 1.2.1 已有 API

## 二、信息架构与导航

3 个一级目的地,按 MD3 决策树:

```
┌ 课表(周视图,首页) ┐
│ 今日               │ ← NavigationBar 三项:课表 / 今日 / 我的
└ 我的(导入/设置)   ┘
```

- Compact(<600dp):底部 **NavigationBar**(80dp,始终显示 label,激活项 filled 图标+指示药丸,
  container 色 = `surface-container`)
- Medium(600–839dp):NavigationRail;Expanded(840dp+):标准 Drawer
- 页面切换用 Navigation Compose(NavHost)

## 三、核心页面设计

### 1. 课表页(周视图,主屏)

- **TopAppBar(Small 型)**:左侧标题「第 N 周」(`titleLarge`)+ 副行日期范围(`labelMedium`,
  `onSurfaceVariant`);右侧动作:今天图标按钮、溢出菜单
- **周切换交互**:
  - 左右滑动网格区 = 上/下周(HorizontalPager,弹簧物理)
  - 点击「第 N 周」→ ModalBottomSheet 周选择器(17 个周次 chip 流式排列,选中 = `secondary-container`)
  - 点「今天」一键回本周,shared-axis 过渡
- **表头**:「一 二 三 四 五 六 日」7 列(受"显示周末"开关控制,见 §五);今日列头 `primary`
  圆形日期徽标(primary-container 底),非今日 `onSurfaceVariant`
- **网格主体**:
  - 左侧节次轴(宽 ~32dp):节次号 `labelSmall` + 起止时间 `labelSmall`;「上午/下午/晚上」分段
    用 `surface-container-low` 分隔带 + 旋转 90° 的 `labelMedium`;节次时间映射在"我的"中配置
  - 课程色块:
    - 配色:按课程名哈希循环取三组色调对——`primary/on-*-container`
      (primary-container、secondary-container、tertiary-container),同课程恒定同色;
      禁止硬编码 hex,全部走 `MaterialTheme.colorScheme`
    - 形状:`shapeScheme.small`(8dp),内边距 8dp;跨节连堂块垂直连续无缝
    - **内容只显示:课程名(`titleSmall`,≤2 行 ellipsis)+ @教室(`labelSmall`)**
      ——教师与学分一律不上格子,仅在详情中展示
    - 正在上的课:右上角 `primary` 实心圆点 Badge
    - 非本周课程:开关关闭时直接隐藏;开启时淡化展示(alpha 0.38 + `outline-variant` 虚线边)
    - 时间冲突:同行双课各占 50% 宽并列;>2 门折叠为「+N」块,点开列表
  - 当前时间指示线:贯穿今日列的 2dp `primary` 横线 + 左端圆点,每分钟刷新,仅本周视图显示
- **手势**:单击色块 → 详情 Sheet;长按空白格 → 以该天+节次预填的添加页;左右滑切周;
  双击色块跳今日页对应课
- Scaffold innerPadding 处理 NavigationBar 与 edge-to-edge insets

### 2. 今日页

- **Hero 卡片**(`surface-container-high`,corner large):「正在上课」——课程名 `headlineSmall`、
  教室/教师 `bodyMedium`、剩余进度 determinate `LinearProgressIndicator`(primary);
  无课时显示空态插画 + `bodyLarge`
- **下一节课卡**:`surface-container-low`,`titleMedium` + 倒计时 `labelLarge`(如「25 分钟后」)
- **今日时间轴列表**:`ListItem`——leading 双行时间(`labelLarge` 起/讫)、headline 课程名、
  supporting 教室·教师;已结束项 `onSurfaceVariant` + 0.6 alpha
- 顶部近 7 天日期 `FilterChip` 流,选中 chip = `secondary-container`

### 3. 我的页

分组 `ListItem` 列表(section header:`labelLarge` + `primary`):

| 分组 | 条目 | 控件 |
|---|---|---|
| 课表数据 | 导入课表 PDF / 当前学期信息卡 | 进入导入流 |
| 学期设置 | 第一周日期(开学时间) | `DatePickerDialog` |
| | 作息时间(每节课起止时刻表) | 节次时间编辑页 |
| 提醒 | 上课前提醒 | `Switch` + 提前分钟 `SegmentedButton` |
| 外观/显示 | 显示周末 | `Switch`,默认**关**(不显示周末);开=7 列,关=5 列自动重排 |
| | 显示非本周课程 | `Switch`,默认**开**(淡化显示);关=直接隐藏 |
| | 动态取色(API 31+) | `Switch`,默认关 |
| | 深色模式(跟随系统·亮·暗) | 单选对话框 |
| 数据 | 导出 JSON / 清除数据(二次确认 `AlertDialog`) | ListItem |

- 设置存 DataStore,课表页以 Flow 收集即时生效

## 四、关键流程 UX

### 导入解析流(全屏 route)

1. 选择文件(SAF)→ 文件名确认页
2. 解析中:determinate 进度(按页)+ 可取消;前台服务通知同步进度
3. 结果预览:「识别到 X 门课 · Y 条安排」摘要 + 按天分组确认列表;可疑项(未排地点等)
   标 `error-container` 提示条
4. 「覆盖现有课表」二次确认;成功后 Snackbar(inverse-surface)「导入完成」

### 添加/编辑课程(共用表单页,全屏 Dialog)

- `OutlinedTextField`(课程名/教师/教室)+ 星期单选 `SegmentedButton` + 起止节次 stepper +
  周次 `FilterChip` 多选流(快捷 chip:「1-17 周」「单/双周」)
- 底部固定操作区:取消(TextButton)/保存(FilledButton);非法输入字段级 error 态

### 课程详情(ModalBottomSheet)

- corner extra-large (28dp);头部:色点 + 课程名 `titleLarge`;徽标行:**学分 AssistChip**
  (如「3.5 学分」)+ 类型★○●徽标
- 信息 List 四行:`时间(节次·周次)` / `地点(校区·楼·教室)` / **`教师(bodyLarge)`** / 备注
- 操作行:编辑(FilledTonalButton)、删除(TextButton→确认框)、单次提醒开关(Switch)

## 五、设置行为细则(修订新增)

- **隐藏周末且今天是周六/日**:网格无周末列可挂今日徽标 → 顶栏日期行仍显示真实今天,
  视图停在本周一,一次性 Snackbar「今天是周末,已显示本周一」
- **隐藏非本周课程且本周整周无课**:网格居中空态「本周没有课程安排」+ 跳转下周按钮
- 小组件同步遵循两个显示开关(隐藏周末则组件只列工作日课)

## 六、动效(BOM 2024.06.00 能力内)

- 页面切换(NavHost):shared-axis X/Y,500ms emphasized 曲线 `cubic-bezier(0.2,0,0,1)`
- 切周:HorizontalPager 自带弹簧感;回到本周加 scale 0.96→1 过渡
- 色块按压:`animateFloatAsState` 缩放 0.97;详情 Sheet 默认 spring
- 列表入场:fade+slide 250ms standard-decelerate,克制不逐项 stagger

## 七、无障碍与适配

- 触控目标 ≥48dp;色块语义合并单节点:
  `contentDescription = "周一 1-2节 模拟电子线路 环宇楼A404"`
- 正文对比 ≥4.5:1、大字/图形 ≥3:1;淡化块附文字标注供色弱用户
- 全程 `MaterialTheme.typography`,支持系统字体缩放(格子高度 dp 固定、文字 ellipsis 兜底)
- 折叠屏/平板:600dp+ 周视图横向铺满 + NavigationRail

## 八、组件用量速查(M3 1.2.1 全可用)

`Scaffold / TopAppBar / NavigationBar(+Item) / HorizontalPager / ModalBottomSheet /
FilterChip / AssistChip / SegmentedButton / ListItem / Card / LinearProgressIndicator /
CircularProgressIndicator / Snackbar / AlertDialog / DatePickerDialog / OutlinedTextField /
Switch / DropdownMenu / Badge`

## 九、实施顺序

1. **阶段 1 — 数据层**:Room 实体(Course/ScheduleEntry/SemesterConfig)+ DAO + Repository;
   解析结果写入 Room;"其他课程"(尔雅网课)单独存储
2. **阶段 2 — 周次逻辑**:开学时间设置 + 当前第 N 周计算(DataStore + Room)
3. **阶段 3 — UI 重构**:主题(#333464)+ 导航壳 → 周视图网格 → 今日页 → 我的页
4. **阶段 4 — 小组件**:读 Room 展示今日/下一节课,遵循显示开关
5. **阶段 5 — 提醒/编辑/导出** → 阶段 6 测试与收尾
