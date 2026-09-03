package com.example.composeapp.ui.timetable

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.composeapp.data.EntryWithCourse
import com.example.composeapp.data.ScheduleRepository
import com.example.composeapp.data.ScheduleSettings
import com.example.composeapp.data.SectionTime
import com.example.composeapp.data.SettingsRepository
import com.example.composeapp.data.TimeUtils
import com.example.composeapp.data.WeekCalculator
import com.example.composeapp.ui.theme.courseBlockColors
import com.example.composeapp.ui.theme.courseBlockColorsDynamic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToInt
import java.time.LocalTime

private val ROW_HEIGHT = 56.dp
private val AXIS_WIDTH = 46.dp
private val WEEKDAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

/** 课程类型 -> 表格标记符（与 PDF 图例一致）。 */
internal fun typeSymbol(type: String): String = when (type) {
    "讲课" -> "★"
    "实验" -> "○"
    "上机" -> "●"
    "实践" -> "◇"
    "集中实践" -> ":"
    else -> ""
}

/** 课表页：第 N 周标题 + 网格（可隐藏周末）+ 左右滑动切周。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimetableScreen(
    entries: List<EntryWithCourse>,
    settings: ScheduleSettings,
    parsing: Boolean = false,
    glass: Boolean = false,
    onCourseClick: (EntryWithCourse) -> Unit,
    onShowSnackbar: (String) -> Unit,
    onImportClick: () -> Unit = {},
    onAddClick: () -> Unit = {},
    onMoveEntry: (EntryWithCourse, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    timetables: List<TimetableInfo> = emptyList(),
    onSwitchTimetable: (Long) -> Unit = {},
    onNewTimetable: () -> Unit = {},   // 自动新建未命名课表并进入导入流程
    onOpenManage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    val semesterStart = settings.semesterStartDate
    val rawCurrentWeek = WeekCalculator.currentWeek(settings.semesterStartDate, today)
    val currentWeek = rawCurrentWeek.coerceAtLeast(1)
    // 展示中的周是否包含今天（开学前 currentWeek 被钳到 1，不能据此点亮"今日"）
    fun weekContainsToday(w: Int): Boolean {
        val start = semesterStart?.let { WeekCalculator.mondayOfWeek(it, w) } ?: return false
        return !today.isBefore(start) && today.isBefore(start.plusDays(7))
    }
    val maxEntryWeek = entries.maxOfOrNull { e -> e.weeks.maxOrNull() ?: 0 } ?: 0
    // 学期总周数由设置决定（默认 17），至少覆盖当前周与已识别最长周
    val totalWeeks = maxOf(settings.totalWeeks, maxEntryWeek, currentWeek)

    val pagerState = rememberPagerState(
        initialPage = (currentWeek - 1).coerceIn(0, totalWeeks - 1)
    ) { totalWeeks }
    // 切换课表后：按新课表的开学时间重新定位周次（未开学则停在第 1 页，标题显示"未开学"）
    LaunchedEffect(settings.timetableId) {
        val w = WeekCalculator.currentWeek(semesterStart, today)
            .coerceIn(1, totalWeeks)
        pagerState.scrollToPage(w - 1)
    }
    val selectedWeek = pagerState.currentPage + 1
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showWeekPicker by rememberSaveable { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    // 动态取色开关：课表块颜色随壁纸主题联动
    val dynamicColor = settings.dynamicColor

    val visibleDays = remember(settings.showWeekend) {
        if (settings.showWeekend) (1..7).toList() else (1..5).toList()
    }
    // 一日节数由设置决定（作息页可调），网格按此渲染
    val maxSection = settings.sectionsPerDay

    LaunchedEffect(Unit) {
        if (!settings.showWeekend && today.dayOfWeek.value >= 6) {
            onShowSnackbar("今天是周末，已显示本周一")
        }
    }

    Column(modifier.fillMaxSize()) {
        // ---- 空状态：无课表数据时引导导入（首启引导）；解析中则显示进度 ----
        if (entries.isEmpty()) {
            if (parsing) {
                // 引导页导入后：全屏"解析中"提示
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "正在解析课表…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "本地识别课程与排课，请稍候",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    // 空态 stagger 入场：主标题 → 副标题 → 三步引导逐个浮现
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { shown = true }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = shown,
                        enter = fadeIn(tween(500)) + slideInVertically(
                            initialOffsetY = { it / 12 },
                            animationSpec = tween(500, easing = FastOutSlowInEasing),
                        ),
                    ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    ) {
                        Text(
                            "欢迎使用简课表",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "导入课表 PDF / Excel，自动识别课程与排课",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(24.dp))
                        // 三步引导
                        GuideStep(1, "从教务系统导出课表文件", "个人课表 PDF 或班级课表 Excel")
                        Spacer(Modifier.height(12.dp))
                        GuideStep(2, "点击下方按钮选择文件", "解析在手机本地完成，不上传")
                        Spacer(Modifier.height(12.dp))
                        GuideStep(3, "在「我的」里设置开学时间", "用于计算当前第几周")
                        Spacer(Modifier.height(28.dp))
                        androidx.compose.material3.Button(
                            onClick = onImportClick,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("从手机导入课表文件")
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "也可在「我的」页随时重新导入",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "目前适配正方教务导出的课表 PDF 与班级课表 Excel\n有适配需求请发邮件至 xunguang255@163.com",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    }
                }
            }
            return@Column
        }

        // ---- 解析中指示（首页导入后进入解析状态）----
        if (parsing) {
            androidx.compose.material3.LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Text(
                "正在解析课表…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        // ---- 标题栏：第 N 周大字 + 日期范围小字（玻璃模式包一层玻璃舱） ----
        val headerContent: @Composable () -> Unit = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showWeekPicker = true }
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    if (rawCurrentWeek < 1) {
                        // 边界处理：周数为 0/负（未开学或未设置开学时间）时改显开学倒计时
                        val daysToStart =
                            semesterStart?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }
                        Text(
                            text = when {
                                semesterStart == null -> "未设置开学时间"
                                daysToStart != null && daysToStart > 0 -> "距开学还有 $daysToStart 天"
                                else -> "今天开学"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (semesterStart == null) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.primary,
                        )
                        semesterStart?.let {
                            Text(
                                "开学日：${it.year}年${it.monthValue}月${it.dayOfMonth}日",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                    } else if (rawCurrentWeek < 1) {
                        // 开学前：不显示周数（未开学），避免与真实第一周混淆
                        Text(
                            "未开学",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "开学日：${semesterStart?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: "未设置"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    } else {
                        Text(
                            "第 ",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        // 周数数字滚动切换（水平方向与翻页一致）
                        androidx.compose.animation.AnimatedContent(
                            targetState = selectedWeek,
                            transitionSpec = {
                                if (targetState > initialState) {
                                    (slideInHorizontally { it / 3 } + fadeIn(tween(160)))
                                        .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(120)))
                                } else {
                                    (slideInHorizontally { -it / 3 } + fadeIn(tween(160)))
                                        .togetherWith(slideOutHorizontally { it / 3 } + fadeOut(tween(120)))
                                }
                            },
                            label = "weekNumber",
                        ) { week ->
                            Text(
                                "$week",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            " 周",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = weekDateRangeLabel(settings.semesterStartDate, selectedWeek),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // 分享整周课表：离屏绘制 PNG 后调起系统分享
                IconButton(onClick = {
                    if (sharing) return@IconButton
                    val monday = settings.semesterStartDate?.let {
                        WeekCalculator.mondayOfWeek(it, selectedWeek)
                    }
                    if (monday == null) {
                        onShowSnackbar("请先在「我的」设置开学时间")
                        return@IconButton
                    }
                    sharing = true
                    scope.launch {
                        runCatching {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val bmp = TimetableShare.renderWeek(
                                    week = selectedWeek,
                                    monday = monday,
                                    entries = entries,
                                    visibleDays = visibleDays,
                                    maxSection = maxSection,
                                    showNonCurrentWeek = settings.showNonCurrentWeek,
                                    timetableName = settings.timetableName,
                                )
                                TimetableShare.share(context, bmp, selectedWeek)
                            }
                        }.onFailure {
                            onShowSnackbar("生成分享图失败：${it.message ?: "未知错误"}")
                        }
                        sharing = false
                    }
                }) {
                    if (sharing) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = "分享本周课表",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onAddClick) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "新增课程",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    // 周数滑杆 + 课表切换面板（原"回到本周"职能并入面板）
                    showWeekPicker = true
                }) {
                    Icon(
                        Icons.Filled.DateRange,
                        contentDescription = "周数与课表",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (glass) {
            com.example.composeapp.ui.theme.GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { headerContent() }
        } else {
            headerContent()
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant
                .copy(alpha = if (glass) 0.35f else 1f)
        )

        // ---- 星期表头（轴角落显示展示周的月份，随滑动切换） ----
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Box(
                Modifier.width(AXIS_WIDTH),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${cornerMonth(semesterStart, selectedWeek, today)}月",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            for (d in visibleDays) {
                DayHeader(
                    dayIndex = d,
                    date = semesterStart?.let {
                        WeekCalculator.mondayOfWeek(it, selectedWeek).plusDays((d - 1).toLong())
                    },
                    isToday = weekContainsToday(selectedWeek) && today.dayOfWeek.value == d,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---- 网格主体（外层统一纵向滚动） ----
        val gridHeight = maxSection * ROW_HEIGHT.value
        Row(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            SectionAxis(
                sections = 1..maxSection,
                sectionTimes = settings.sectionTimes,
                modifier = Modifier
                    .width(AXIS_WIDTH)
                    .height(gridHeight.dp),
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).height(gridHeight.dp),
            ) { page ->
                WeekGridPage(
                    week = page + 1,
                    allEntries = entries,
                    visibleDays = visibleDays,
                    maxSection = maxSection,
                    isCurrentWeek = (page + 1) == currentWeek && weekContainsToday(page + 1),
                    today = today,
                    sectionTimes = settings.sectionTimes,
                    showNonCurrentWeek = settings.showNonCurrentWeek,
                    dynamicColor = dynamicColor,
                    glass = glass,
                    onCourseClick = onCourseClick,
                    onMoveEntry = onMoveEntry,
                )
            }
        }
    }

    if (showWeekPicker) {
        ModalBottomSheet(onDismissRequest = { showWeekPicker = false }) {
            // ---- 周数：滑杆 + 回到本周 ----
            var panelWeek by remember(selectedWeek) { mutableStateOf(selectedWeek.toFloat()) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("周数", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch { pagerState.animateScrollToPage(currentWeek - 1) }
                    showWeekPicker = false
                }) { Text("回到本周") }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Slider(
                    value = panelWeek,
                    onValueChange = { panelWeek = it },
                    onValueChangeFinished = {
                        scope.launch { pagerState.scrollToPage(panelWeek.toInt() - 1) }
                    },
                    valueRange = 1f..totalWeeks.toFloat(),
                    steps = (totalWeeks - 2).coerceAtLeast(0),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${panelWeek.toInt()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---- 课表：卡片切换 + 新建/管理 ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("课表", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    showWeekPicker = false
                    onNewTimetable()
                }) { Text("新建课表") }
                TextButton(onClick = {
                    showWeekPicker = false
                    onOpenManage()
                }) { Text("管理") }
            }
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 24.dp),
            ) {
                items(timetables, key = { it.timetable.id }) { info ->
                    TimetableCard(
                        info = info,
                        active = info.timetable.id == settings.timetableId,
                        maxSection = maxSection,
                        onClick = {
                            onSwitchTimetable(info.timetable.id)
                            showWeekPicker = false
                        },
                    )
                }
            }
        }
    }
}

/** 课表切换卡片：迷你课表缩略图（异步渲染）+ 名称 + 选中勾。 */
@Composable
private fun TimetableCard(
    info: TimetableInfo,
    active: Boolean,
    maxSection: Int,
    onClick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 缩略图按课表内容异步渲染（与分享图同源绘制，等比缩小）
    val thumb by produceState<android.graphics.Bitmap?>(
        null, info.timetable.id, maxSection,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val entries = ScheduleRepository.getInstance(context).entriesOf(info.timetable.id)
                val tt = info.timetable
                val today = LocalDate.now()
                val startMillis = if (tt.startMillis == 0L) SettingsRepository.DEFAULT_SEMESTER_START_MILLIS else tt.startMillis
                val startDate = java.time.Instant.ofEpochMilli(startMillis)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                val week = WeekCalculator.currentWeek(startDate, today).coerceAtLeast(1)
                val bmp = TimetableShare.renderWeek(
                    week = week,
                    monday = WeekCalculator.mondayOfWeek(startDate, week),
                    entries = entries,
                    visibleDays = (1..7).toList(),
                    maxSection = maxSection,
                    showNonCurrentWeek = true,
                )
                Bitmap.createScaledBitmap(bmp, 220, 264, true)
                    .also { scaled -> if (scaled != bmp) bmp.recycle() }
            }.getOrNull()
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 110.dp, height = 132.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (active) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            thumb?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
            if (active) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "使用中",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0x66000000), RoundedCornerShape(50))
                        .padding(4.dp),
                )
            }
        }
        Text(
            info.timetable.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun DayHeader(
    dayIndex: Int,
    date: LocalDate?,
    isToday: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            WEEKDAY_NAMES[dayIndex - 1],
            style = MaterialTheme.typography.labelMedium,
            color = if (isToday) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Box(contentAlignment = Alignment.Center) {
            if (isToday) {
                Box(
                    Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
            Text(
                text = date?.dayOfMonth?.toString() ?: "",
                fontSize = 13.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (isToday) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionAxis(
    sections: IntRange,
    sectionTimes: List<SectionTime>,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        for (s in sections) {
            val t = sectionTimes.firstOrNull { it.section == s }
            Column(
                modifier = Modifier.height(ROW_HEIGHT),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    s.toString(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                t?.let {
                    Text(
                        TimeUtils.hm(it.start),
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        TimeUtils.hm(it.end),
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekGridPage(
    week: Int,
    allEntries: List<EntryWithCourse>,
    visibleDays: List<Int>,
    maxSection: Int,
    isCurrentWeek: Boolean,
    today: LocalDate,
    sectionTimes: List<SectionTime>,
    showNonCurrentWeek: Boolean,
    dynamicColor: Boolean,
    glass: Boolean,
    onCourseClick: (EntryWithCourse) -> Unit,
    onMoveEntry: (EntryWithCourse, Int, Int, Int) -> Unit,
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { ROW_HEIGHT.toPx() }
    // 拖拽状态：grab=手指抓取点（块内偏移），pointerLocal=手指当前块内位置（均为块局部像素）
    var drag by remember { mutableStateOf<GridDrag?>(null) }
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    // 刚落位的块：唯一弹入一次（拖拽回弹动效）
    var bounceEntryId by remember { mutableStateOf<Long?>(null) }

    fun handleDragEnd() {
        val d = drag
        drag = null
        if (d == null || gridSize.width <= 0 || visibleDays.isEmpty()) return
        // 幽灵块左上角（网格像素坐标）
        val topLeft = d.ghostTopLeft()
        val colW = gridSize.width.toFloat() / visibleDays.size
        val start0 = d.entry.startSection ?: 1
        val end0 = d.entry.endSection ?: start0
        val dur = end0 - start0
        // 目标列按块中心 x；目标起始节按块顶 y 取整
        val dayIdx = ((topLeft.x + d.widthPx / 2) / colW).toInt()
            .coerceIn(0, visibleDays.size - 1)
        val newDay = visibleDays[dayIdx]
        var newStart = (topLeft.y / rowHeightPx).roundToInt() + 1
        newStart = newStart.coerceIn(1, (maxSection - dur).coerceAtLeast(1))
        // 冲突避让：与本周同天其他课程重叠时，向上/向下找最近空位
        fun conflicts(s: Int): Boolean = allEntries.any {
            it.entryId != d.entry.entryId && it.dayOfWeek == newDay && it.isInWeek(week) &&
                (it.startSection ?: 1) <= s + dur && (it.endSection ?: it.startSection ?: 1) >= s
        }
        val finalStart = if (!conflicts(newStart)) newStart else {
            var found = -1
            for (off in 1 until maxSection) {
                val up = newStart - off
                if (up >= 1 && !conflicts(up)) { found = up; break }
                val down = newStart + off
                if (down + dur <= maxSection && !conflicts(down)) { found = down; break }
            }
            found
        }
        if (finalStart >= 1) {
            bounceEntryId = d.entry.entryId  // 落位后目标块弹入一次
            onMoveEntry(d.entry, newDay, finalStart, finalStart + dur)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                gridCoords = it
                gridSize = it.size
            }
    ) {
        Row(Modifier.fillMaxSize()) {
            for (d in visibleDays) {
                val dayEntries = allEntries.filter { it.dayOfWeek == d }
                DayColumn(
                    dayEntries = dayEntries,
                    week = week,
                    maxSection = maxSection,
                    isToday = isCurrentWeek && today.dayOfWeek.value == d,
                    sectionTimes = sectionTimes,
                    showNonCurrentWeek = showNonCurrentWeek,
                    dynamicColor = dynamicColor,
                    glass = glass,
                    draggedEntryId = drag?.entry?.entryId,
                    bounceEntryId = bounceEntryId,
                    onDragStart = { entry, grab, blockCoords, sizePx ->
                        // 块在网格内的位置用 localPositionOf 直接换算，
                        // 不经窗口坐标（窗口坐标不含链上 offset，会跳到列顶）
                        val grid = gridCoords
                        val originInGrid = if (grid != null && blockCoords.isAttached) {
                            grid.localPositionOf(blockCoords, Offset.Zero)
                        } else Offset.Zero
                        drag = GridDrag(
                            entry,
                            originInGrid,
                            grab,
                            grab,
                            sizePx.width.toFloat(),
                            sizePx.height.toFloat(),
                        )
                    },
                    onDragDelta = { pointerLocal ->
                        // 手指位置为块内绝对坐标，逐帧替换而非累计增量，保证严格跟手
                        drag?.let { drag = it.copy(pointerLocal = pointerLocal) }
                    },
                    onDragEnd = { handleDragEnd() },
                    onCourseClick = onCourseClick,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

        // 拖拽幽灵块：跟随手指浮于网格之上
        drag?.let { d ->
            DragGhost(
                drag = d,
                dynamicColor = dynamicColor,
            )
        }
    }
}

/** 拖拽中的课程块（位置均为「网格」像素坐标系，不经窗口坐标换算）。 */
private data class GridDrag(
    val entry: EntryWithCourse,
    val blockOrigin: Offset,   // 拖起时块在网格中的位置
    val grab: Offset,          // 手指抓取点（块内偏移）
    val pointerLocal: Offset,  // 手指当前在块内的位置（每帧绝对替换）
    val widthPx: Float,
    val heightPx: Float,
) {
    /** 幽灵块左上角（网格 px）：保持抓取点相对块的位置不变。 */
    fun ghostTopLeft(): Offset = blockOrigin + pointerLocal - grab
}

@Composable
private fun DragGhost(
    drag: GridDrag,
    dynamicColor: Boolean,
) {
    val (container, onContainer) = if (dynamicColor) {
        courseBlockColorsDynamic(drag.entry.colorIndex)
    } else {
        val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        courseBlockColors(drag.entry.colorIndex, isDark)
    }
    val topLeft = drag.ghostTopLeft()
    Box(
        Modifier
            .absoluteOffset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .size(with(LocalDensity.current) { drag.widthPx.toDp() }, with(LocalDensity.current) { drag.heightPx.toDp() })
            .shadow(8.dp, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .background(container, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(
            text = drag.entry.courseName + typeSymbol(drag.entry.type),
            color = onContainer,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DayColumn(
    dayEntries: List<EntryWithCourse>,
    week: Int,
    maxSection: Int,
    isToday: Boolean,
    sectionTimes: List<SectionTime>,
    showNonCurrentWeek: Boolean,
    dynamicColor: Boolean,
    glass: Boolean,
    draggedEntryId: Long?,
    bounceEntryId: Long?,
    onDragStart: (EntryWithCourse, Offset, LayoutCoordinates, IntSize) -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onCourseClick: (EntryWithCourse) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val colWidth = maxWidth

        // 节次背景分隔线
        Column(Modifier.fillMaxSize()) {
            repeat(maxSection) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        .padding(top = 0.5.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }

        // 课程块（先过滤掉不显示的，再冲突分槽，避免隐藏条目占槽位）
        val visibleEntries = remember(dayEntries, week, showNonCurrentWeek) {
            if (showNonCurrentWeek) dayEntries
            else dayEntries.filter { it.isInWeek(week) }
        }
        val clusters = remember(visibleEntries) { clusterByOverlap(visibleEntries) }
        for (cluster in clusters) {
            val slotCount = cluster.maxOf { it.second } + 1
            val cellW = colWidth / slotCount
            for ((entry, slot) in cluster) {
                // 块的布局坐标（拖拽起点换算用；localPositionOf 需要完整链坐标）
                var blockCoords by remember(entry.entryId) { mutableStateOf<LayoutCoordinates?>(null) }
                CourseBlock(
                    entry = entry,
                    dimmed = !entry.isInWeek(week),
                    isDragging = draggedEntryId == entry.entryId,
                    bounce = bounceEntryId == entry.entryId,
                    dynamicColor = dynamicColor,
                    glass = glass,
                    onDragStart = { grab ->
                        blockCoords?.let {
                            onDragStart(
                                entry, grab, it,
                                IntSize(it.size.width, it.size.height),
                            )
                        }
                    },
                    onDragDelta = onDragDelta,
                    onDragEnd = onDragEnd,
                    // 四周留距：块与块/网格线之间保留 2dp 间隙
                    modifier = Modifier
                        .offset(x = cellW * slot + 2.dp, y = blockTop(entry) + 3.dp)
                        .width(cellW - 4.dp)
                        .height(blockHeight(entry) - 6.dp)
                        .onGloballyPositioned { blockCoords = it },
                    onClick = { onCourseClick(entry) },
                )
            }
        }

        // 当前时间指示线（仅今日列）
        if (isToday) {
            NowIndicator(
                sectionTimes = sectionTimes,
                maxSection = maxSection,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

private fun blockTop(e: EntryWithCourse) =
    ((((e.startSection ?: 1) - 1) * ROW_HEIGHT.value) + 1f).dp

private fun blockHeight(e: EntryWithCourse) =
    (((e.endSection ?: e.startSection ?: 1) - (e.startSection ?: 1) + 1) * ROW_HEIGHT.value - 2f).dp

/** 重叠课程聚类：返回若干簇，每簇为 (entry, 槽位)。 */
private fun clusterByOverlap(
    entries: List<EntryWithCourse>
): List<List<Pair<EntryWithCourse, Int>>> {
    val sorted = entries.sortedBy { it.startSection ?: 99 }
    val clusters = mutableListOf<MutableList<EntryWithCourse>>()
    for (e in sorted) {
        val c = clusters.lastOrNull()
        if (c != null && c.any { it.overlaps(e) }) c.add(e) else clusters.add(mutableListOf(e))
    }
    return clusters.map { members ->
        val placed = mutableListOf<Pair<EntryWithCourse, Int>>()
        for (e in members.sortedBy { it.startSection ?: 99 }) {
            var slot = 0
            while (placed.any { (other, s) -> s == slot && other.overlaps(e) }) slot++
            placed.add(e to slot)
        }
        placed
    }
}

@Composable
private fun CourseBlock(
    entry: EntryWithCourse,
    dimmed: Boolean,
    dynamicColor: Boolean,
    glass: Boolean,
    onClick: () -> Unit,
    isDragging: Boolean = false,
    bounce: Boolean = false,
    onDragStart: ((Offset) -> Unit)? = null,
    onDragDelta: ((Offset) -> Unit)? = null,   // 参数 = 手指在本块内的位置（绝对坐标）
    onDragEnd: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 动态取色开启：从主题派生三组容器色；关闭：十组品牌色（自动适配亮暗主题）
    val (container, onContainer) = if (dynamicColor) {
        courseBlockColorsDynamic(entry.colorIndex)
    } else {
        val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        courseBlockColors(entry.colorIndex, isDark)
    }
    // 课程名按块宽自适应：保证每行约显示三个字（参考主流课表排版）
    // 按压缩放动效（MD3：0.97，弹簧回弹）
    var pressed by remember(entry.entryId) { mutableStateOf(false) }
    val pressScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed || isDragging) 0.97f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
        ),
        label = "blockPress",
    )
    val pressModifier = Modifier.graphicsLayer {
        scaleX = pressScale
        scaleY = pressScale
    }
    // 拖拽落位回弹：仅拖拽落地的目标块弹入一次（0.92→1）
    val bounceScale = remember(entry.entryId) { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(bounce) {
        if (bounce) {
            bounceScale.snapTo(0.92f)
            bounceScale.animateTo(
                1f,
                androidx.compose.animation.core.spring(
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                ),
            )
        }
    }
    val scaleModifier = Modifier.graphicsLayer {
        val s = pressScale * bounceScale.value
        scaleX = s
        scaleY = s
    }
    // 长按拖拽换位置（与单击手势独立：短按点击、长按拖起）
    val dragModifier = if (onDragStart != null && onDragDelta != null) {
        Modifier.pointerInput(entry.entryId) {
            detectDragGesturesAfterLongPress(
                onDragStart = { onDragStart?.invoke(it) },
                onDrag = { change, _ ->
                    change.consume()
                    // 绝对坐标：每次上报手指在块内的位置，避免增量累计漂移
                    onDragDelta?.invoke(change.position)
                },
                onDragEnd = { onDragEnd?.invoke() },
                onDragCancel = { onDragEnd?.invoke() },
            )
        }
    } else Modifier
    val gestureModifier = Modifier.pointerInput(entry.entryId) {
        detectTapGestures(
            onPress = {
                pressed = true
                try { awaitRelease() } finally { pressed = false }
            },
            onTap = { onClick() },
        )
    }
    if (glass) {
        // 磨砂玻璃模式：块体为玻璃面，顶部 4dp 课程色条做区分，文字用主题色保证可读；
        // 字号随块宽自适应（一行约 3 字，与实色模式一致）
        val cs = MaterialTheme.colorScheme
        BoxWithConstraints(
            modifier = modifier
                .alpha(if (isDragging) 0.25f else if (dimmed) 0.38f else 1f)
                .then(scaleModifier)
                .then(dragModifier)
        ) {
            // 一行约 3 字：按去掉内边距后的可用宽度计算（CJK 全角 ≈ 字号）
            val nameSize = (((maxWidth.value - 8f) / 3f).coerceIn(8f, 14f))
            com.example.composeapp.ui.theme.GlassSurface(
                modifier = Modifier.fillMaxSize(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .then(gestureModifier)
                        .padding(horizontal = 3.dp, vertical = 4.dp)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                            .background(container)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.courseName + typeSymbol(entry.type),
                        fontSize = nameSize.sp,
                        fontWeight = FontWeight.Medium,
                        color = cs.onSurface,
                        lineHeight = (nameSize * 1.22f).sp,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 6,
                    )
                    val location = condensedLocation(entry)
                    if (location.isNotBlank() || entry.teacher.isNotBlank()) {
                        Text(
                            text = buildString {
                                if (entry.teacher.isNotBlank()) append(entry.teacher)
                                if (location.isNotBlank()) append("@").append(location)
                            },
                            fontSize = (nameSize * 0.82f).sp,
                            color = cs.onSurfaceVariant,
                            lineHeight = (nameSize * 0.98f).sp,
                        )
                    }
                }
            }
        }
        return
    }
    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .alpha(if (isDragging) 0.25f else if (dimmed) 0.38f else 1f)
            .then(scaleModifier)
            .then(dragModifier)
            .background(container, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .clipToBounds()
            .then(gestureModifier)
            .padding(horizontal = 3.dp, vertical = 4.dp)
    ) {
        // 一行约 3 字：按去掉内边距后的可用宽度计算（CJK 全角 ≈ 字号）
        val nameSize = (((maxWidth.value - 8f) / 3f).coerceIn(8f, 14f))
        Column {
            // 课程名 + 类型标记（如 模拟电子线路★）
            Text(
                text = entry.courseName + typeSymbol(entry.type),
                fontSize = nameSize.sp,
                fontWeight = FontWeight.Medium,
                color = onContainer,
                lineHeight = (nameSize * 1.22f).sp,
                overflow = TextOverflow.Ellipsis,
                maxLines = 6,
            )
            Spacer(Modifier.height(2.dp))
            // 教师@地点（换行自然铺满块高）
            val location = condensedLocation(entry)
            if (location.isNotBlank() || entry.teacher.isNotBlank()) {
                Text(
                    text = buildString {
                        if (entry.teacher.isNotBlank()) append(entry.teacher)
                        if (location.isNotBlank()) append("@").append(location)
                    },
                    fontSize = (nameSize * 0.82f).sp,
                    color = onContainer.copy(alpha = 0.85f),
                    lineHeight = (nameSize * 0.98f).sp,
                )
            }
        }
    }
}

/** 压缩地点：楼号与场地重复时去重（环宇楼 + 环宇楼A404 → 环宇楼A404）；未排地点只留原文。 */
private fun condensedLocation(e: EntryWithCourse): String {
    if (e.room.isBlank() || e.room == "未排地点") return e.room.ifBlank { e.building.ifBlank { e.campus } }
    val buildingPart = if (!e.room.contains(e.building)) e.building else ""
    return listOf(buildingPart, e.room).filter { it.isNotBlank() }.joinToString("")
}

/** 当前时间红线（今日列）。 */
@Composable
private fun NowIndicator(
    sectionTimes: List<SectionTime>,
    maxSection: Int,
    modifier: Modifier = Modifier,
) {
    val now = remember { LocalTime.now() }
    val nowMinutes = now.hour * 60 + now.minute
    val first = sectionTimes.firstOrNull() ?: return
    val last = sectionTimes.lastOrNull() ?: return
    val firstM = first.start.hour * 60 + first.start.minute
    val lastM = last.end.hour * 60 + last.end.minute
    if (nowMinutes < firstM || nowMinutes > lastM) return

    var yRatio = 0f
    for (i in sectionTimes.indices) {
        val st = sectionTimes[i]
        if (st.section > maxSection) break
        val sM = st.start.hour * 60 + st.start.minute
        val eM = st.end.hour * 60 + st.end.minute
        if (nowMinutes <= eM) {
            yRatio = if (nowMinutes >= sM) {
                (st.section - 1) + (nowMinutes - sM).toFloat() / (eM - sM).toFloat()
            } else {
                (st.section - 1).toFloat()
            }
            break
        }
    }
    val y = (yRatio.coerceIn(0f, maxSection.toFloat()) * ROW_HEIGHT.value).dp
    Box(
        modifier
            .fillMaxWidth()
            .offset(y = y)
            .height(2.dp)
            .background(MaterialTheme.colorScheme.primary)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .offset(x = (-4).dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
    }
}

private fun weekDateRangeLabel(start: LocalDate?, week: Int): String {
    start ?: return ""
    val mon = WeekCalculator.mondayOfWeek(start, week)
    val sun = mon.plusDays(6)
    return "${mon.monthValue}月${mon.dayOfMonth}日 到 ${sun.monthValue}月${sun.dayOfMonth}日"
}

/** 轴角落月份：跟随展示中的周（开学前回退到今天所在月）。 */
private fun cornerMonth(start: LocalDate?, week: Int, today: LocalDate): Int =
    start?.let { WeekCalculator.mondayOfWeek(it, week).monthValue } ?: today.monthValue

/** 首启引导步骤行：编号圆点 + 标题/说明。 */
@Composable
private fun GuideStep(number: Int, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
