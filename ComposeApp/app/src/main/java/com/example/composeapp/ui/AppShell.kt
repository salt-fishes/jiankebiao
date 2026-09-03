package com.example.composeapp.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.composeapp.ScheduleParseService
import com.example.composeapp.data.EntryWithCourse
import com.example.composeapp.data.ScheduleRepository
import com.example.composeapp.data.ScheduleSettings
import com.example.composeapp.data.SettingsRepository
import com.example.composeapp.data.TimetableEntity
import com.example.composeapp.reminder.AppRefresh
import com.example.composeapp.schedule.ParsedSchedule
import com.example.composeapp.ui.mine.MineScreen
import com.example.composeapp.ui.timetable.NewTimetableDialog
import com.example.composeapp.ui.timetable.ImportChooseDialog
import com.example.composeapp.ui.timetable.ImportTarget
import com.example.composeapp.ui.timetable.TimetableInfo
import com.example.composeapp.ui.timetable.TimetableManagePage
import com.example.composeapp.ui.timetable.TimetableScreen
import com.example.composeapp.ui.timetable.WidgetBindPage
import com.example.composeapp.ui.timetable.CourseDetailSheet
import com.example.composeapp.ui.timetable.TimetableScreen
import com.example.composeapp.ui.today.TodayScreen
import com.example.composeapp.widget.ScheduleWidgetCompactProvider
import com.example.composeapp.widget.ScheduleWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

private val TAB_LABELS = listOf("课表", "今日", "我的")

/** 可导入的文件类型：课表 PDF（个人课表）+ 班级课表 Excel（.xls）。 */
private val IMPORT_MIMES = arrayOf(
    "application/pdf",
    "application/vnd.ms-excel",
    "application/msexcel",
    "application/x-xls",
)

/**
 * 魔数判定分享文件的真实类型（分享方常报 octet-stream，mime 不可信）。
 * 返回临时文件扩展名：pdf / xls / xlsx（xlsx 当前不支持，由解析器给出明确报错）；
 * 嗅探不出返回 null。
 */
private fun detectImportExt(head: ByteArray): String? {
    fun at(i: Int, c: Char) = head.getOrNull(i) == c.code.toByte()
    fun at(i: Int, b: Int) = head.getOrNull(i) == b.toByte()
    return when {
        head.size >= 4 && at(0, '%') && at(1, 'P') && at(2, 'D') && at(3, 'F') -> "pdf"
        // CDF/OLE2 头：BIFF8 .xls，也是微信分享 .xls 常见伪装（octet-stream / CDFV2）
        head.size >= 4 && at(0, 0xD0) && at(1, 0xCF) && at(2, 0x11) && at(3, 0xE0) -> "xls"
        // zip 容器（PK\u0003\u0004）：xlsx 属此类；当前解析器不支持，交给解析器明确报错
        head.size >= 4 && at(0, 'P') && at(1, 'K') && at(2, 3) && at(3, 4) -> "xlsx"
        else -> null
    }
}

/** 应用外壳：底部导航三页 + 解析流程 + 全局状态。 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun AppRoot(
    // 系统分享/「用其他应用打开」进来的待导入文件（MainActivity 转发）
    importUris: MutableSharedFlow<Uri> = MutableSharedFlow(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val scheduleRepo = remember { ScheduleRepository.getInstance(context) }

    val settings by settingsRepo.settings.collectAsState(initial = defaultSettings())
    // 多课表：条目/课程按活动课表作用域，切换活动课表自动换数据源
    val entries by settingsRepo.activeTimetableIdFlow
        .flatMapLatest { scheduleRepo.observeAllEntries(it) }
        .collectAsState(initial = emptyList())
    val courses by settingsRepo.activeTimetableIdFlow
        .flatMapLatest { scheduleRepo.observeCourses(it) }
        .collectAsState(initial = emptyList())
    val timetables by scheduleRepo.observeTimetables().collectAsState(initial = emptyList())
    val courseCounts by scheduleRepo.observeCourseCounts().collectAsState(initial = emptyList())
    val timetableInfos = remember(timetables, courseCounts) {
        timetables.map { t ->
            TimetableInfo(t, courseCounts.firstOrNull { it.timetableId == t.id }?.courseCount ?: 0)
        }
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var parsing by rememberSaveable { mutableStateOf(false) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var selectedEntry by remember { mutableStateOf<EntryWithCourse?>(null) }
    var editingEntry by remember { mutableStateOf<EntryWithCourse?>(null) }
    var showAddCourse by rememberSaveable { mutableStateOf(false) }
    var showSectionTimes by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    var showTimetableManage by rememberSaveable { mutableStateOf(false) }
    var showWidgetBind by rememberSaveable { mutableStateOf(false) }
    var widgetBindRefresh by remember { mutableIntStateOf(0) }
    var pendingImport by remember { mutableStateOf<ParsedSchedule?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val showSnackbar: (String) -> Unit = { msg ->
        scope.launch {
            snackbarHostState.showSnackbar(msg)
        }
    }

    // ---- 设置动作 ----
    val setSemesterStart: (java.time.LocalDate) -> Unit = { date ->
        scope.launch {
            settingsRepo.setSemesterStart(date)  // 写库完成后再刷新，避免小组件读到旧日期
            AppRefresh.onDataChanged(context)
        }
    }

    fun startParse(pdfPath: String, sourceName: String = "") {
        parsing = true
        parseError = null
        ScheduleParseService.start(context, pdfPath, sourceName)
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        // 复制到 cacheDir 唯一临时文件（保留扩展名供服务分派 PDF/Excel 解析），
                        // 避免覆盖 files/课表.pdf（旧 adb push 只读 444）
                        val name = runCatching {
                            context.contentResolver.query(
                                uri,
                                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                                null, null, null,
                            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        }.getOrNull() ?: ""
                        val ext = if (name.contains('.')) name.substringAfterLast('.') else "pdf"
                        val importFile = File(
                            context.cacheDir,
                            "import_${System.currentTimeMillis()}.$ext"
                        )
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            importFile.outputStream().use { out ->
                                input.copyTo(out)
                            }
                        } ?: throw IllegalStateException("无法读取文件")
                        // 保留原始文件名（自动命名课表用："张三(2026-2027-1)课表" → "张三的课表"）
                        importFile to name
                    }
                }.onSuccess { (importFile, sourceName) ->
                    startParse(importFile.absolutePath, sourceName)
                }.onFailure { e ->
                    parsing = false
                    parseError = e.message ?: "无法读取文件"
                }
            }
        }
    }

    // ---- 系统分享/「用其他应用打开」导入：魔数嗅探类型，与文件选择器共用解析管道 ----
    LaunchedEffect(importUris) {
        importUris.collect { uri ->
            val result = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    // 先读文件头嗅探真实类型（分享方常报 octet-stream，mime 不可信）
                    val head = resolver.openInputStream(uri)?.use { input ->
                        val buf = ByteArray(8)
                        val n = input.read(buf)
                        buf.copyOf(if (n > 0) n else 0)
                    } ?: throw IllegalStateException("无法读取文件")
                    val ext = detectImportExt(head)
                        ?: throw IllegalArgumentException("不是支持的课表文件（需 PDF 或 .xls）")
                    // 原始文件名仅用于自动命名课表（服务从名字提取「xx的课表」）
                    val name = runCatching {
                        resolver.query(
                            uri,
                            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null, null, null,
                        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    }.getOrNull() ?: ""
                    val importFile = File(
                        context.cacheDir,
                        "import_${System.currentTimeMillis()}.$ext"
                    )
                    resolver.openInputStream(uri)?.use { input ->
                        importFile.outputStream().use { out ->
                            input.copyTo(out)
                        }
                    } ?: throw IllegalStateException("无法读取文件")
                    importFile to name
                }
            }
            result.onSuccess { (importFile, sourceName) ->
                tab = 0  // 跳到课表页：解析进度与导入确认弹窗都在可见位置
                startParse(importFile.absolutePath, sourceName)
            }.onFailure { e ->
                showSnackbar("导入失败：${e.message ?: "不支持的文件类型"}")
            }
        }
        // 消费完成：清 replay 缓存，配置变更重建时不再重复导入
        importUris.resetReplayCache()
    }

    // ---- 实验性：背景图导入（降采样后存应用私有目录） ----
    val bgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val dst = File(context.filesDir, "bg_custom.jpg")
                        importBackground(context.contentResolver, uri, dst)
                        dst.absolutePath
                    }
                }.onSuccess { path ->
                    settingsRepo.setCustomBgPath(path)
                    settingsRepo.setCustomBgEnabled(true)
                    showSnackbar("背景已更新")
                }.onFailure { e ->
                    showSnackbar("无法读取图片：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    // ---- 导出到系统日历（.ics）：SAF 选保存位置后写入，零权限 ----
    var pendingIcsExport by remember { mutableStateOf<String?>(null) }
    val icsSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri ->
        if (uri != null) {
            val content = pendingIcsExport
            pendingIcsExport = null
            if (content != null) {
                scope.launch {
                    val result = runCatching {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(content.toByteArray(Charsets.UTF_8))
                            } ?: throw IllegalStateException("无法写入文件")
                        }
                    }
                    result.onSuccess { showSnackbar("已导出：可导入到系统日历或日历应用") }
                        .onFailure { e ->
                            showSnackbar("导出失败：${e.message ?: "未知错误"}")
                        }
                }
            }
        } else {
            pendingIcsExport = null
        }
    }
    val doExportIcs: () -> Unit = {
        val start = settings.semesterStart.takeIf { it > 0L }
            ?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
        if (start == null) {
            showSnackbar("请先设置开学时间")
        } else {
            val ics = com.example.composeapp.data.CalendarExport.buildIcs(
                entries = entries,
                sectionTimes = settings.sectionTimes,
                semesterStart = start,
                opts = com.example.composeapp.data.CalendarExport.Options(
                    timetableId = settings.timetableId,
                    timetableName = settings.timetableName,
                    totalWeeks = settings.totalWeeks,
                    remindMinutesBefore = if (settings.remindEnabled) settings.remindMinutesBefore else 0,
                ),
            )
            pendingIcsExport = ics
            icsSaver.launch("课表_${settings.timetableName.ifBlank { "我的课表" }}.ics")
        }
    }

    // ---- 启动逻辑：回填默认课表（升级迁移）→ 重排提醒；adb 测试驱动解析保留 ----
    LaunchedEffect(Unit) {
        val activity = context as? android.app.Activity
        val intentPdf = activity?.intent?.getStringExtra(ScheduleParseService.EXTRA_PDF_PATH)
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            settingsRepo.ensureActiveTimetableReady()
        }
        if (intentPdf != null) startParse(intentPdf)
        // 应用更新/覆盖安装会清掉 AlarmManager 闹钟：每次启动重排一次课前提醒；
        // 同时强刷一次小组件（防升级后残留旧渲染数据）
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            com.example.composeapp.reminder.ClassReminderScheduler.reschedule(context)
            com.example.composeapp.widget.ScheduleWidgetProvider.requestUpdate(context)
        }
    }

    // ---- 解析完成广播：弹出导入选择（新建课表 / 覆盖现有），确认后才入库 ----
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                parsing = false
                val rf = File(context.filesDir, ScheduleParseService.RESULT_FILE)
                ParsedSchedule.fromJson(rf.readText()).fold(
                    onSuccess = { parsed ->
                        parseError = null
                        if (parsed.courses.isEmpty()) {
                            showSnackbar("未识别到课程，请检查文件格式")
                        } else {
                            pendingImport = parsed
                        }
                    },
                    onFailure = { e -> parseError = e.message ?: "解析失败" },
                )
            }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(ScheduleParseService.ACTION_PARSE_DONE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    // ---- 新建课表：弹窗选来源（导入文件 / 复制现有），名字可留空稍后设置 ----
    // saveable：选文件期间 Activity 可能被系统回收重建，这两个 id 丢了会导致导入目标错乱
    var showNewTimetableDialog by rememberSaveable { mutableStateOf(false) }
    var autoCreatedTimetableId by rememberSaveable { mutableStateOf(0L) }
    var pendingAutoName by rememberSaveable { mutableStateOf("") }
    val confirmNewTimetable: (String, Long?) -> Unit = { name, copyFrom ->
        showNewTimetableDialog = false
        showTimetableManage = false
        if (copyFrom != null) {
            // 复制现有课表：结构原样复制，周次重置整学期
            scope.launch {
                val result = runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val src = scheduleRepo.getTimetable(copyFrom)
                            ?: throw IllegalStateException("源课表不存在")
                        scheduleRepo.copyTimetable(
                            src, name.ifBlank { "未命名" }, src.startMillis, src.totalWeeks,
                        )
                    }
                }
                result.onSuccess { id ->
                    settingsRepo.setActiveTimetable(id)
                    AppRefresh.onDataChanged(context)
                    showSnackbar("已创建《${name.ifBlank { "未命名" }}》")
                }.onFailure { e ->
                    showSnackbar("创建失败：${e.message ?: "未知错误"}")
                }
            }
        } else {
            // 导入文件：自动建表 → 选文件 → 解析 → 导入弹窗内命名
            scope.launch {
                val id = runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        scheduleRepo.createTimetable(
                            name.ifBlank { "未命名" },
                            settings.semesterStart,
                            settings.totalWeeks,
                        )
                    }
                }.getOrNull()
                if (id != null) {
                    pendingAutoName = name
                    autoCreatedTimetableId = id
                    settingsRepo.setActiveTimetable(id)
                    AppRefresh.onDataChanged(context)
                    filePicker.launch(IMPORT_MIMES)
                } else {
                    showSnackbar("创建失败")
                }
            }
        }
    }

    // ---- 导入确认：新建课表（自动命名/开学日）或覆盖指定课表 ----
    // parsed 由调用方传入（弹窗宿主在调用前已清 pendingImport，不能再回头读状态）
    val confirmImport: (ParsedSchedule, ImportTarget) -> Unit = { parsed, target ->
        scope.launch {
            runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    when (target) {
                        is ImportTarget.NewTimetable -> {
                            val id = scheduleRepo.createTimetable(
                                target.name,
                                target.startMillis,
                                target.totalWeeks,
                            )
                            scheduleRepo.importSchedule(parsed, id)
                            settingsRepo.setActiveTimetable(id)
                            id
                        }
                        is ImportTarget.IntoCreated -> {
                            scheduleRepo.importSchedule(parsed, target.timetableId)
                            // 自动新建的课表：把弹窗里的命名与日期落库
                            scheduleRepo.getTimetable(target.timetableId)?.let { tt ->
                                scheduleRepo.updateTimetable(
                                    tt.copy(
                                        name = target.name,
                                        startMillis = target.startMillis,
                                        totalWeeks = target.totalWeeks,
                                    )
                                )
                            }
                            target.timetableId
                        }
                        is ImportTarget.Existing -> {
                            scheduleRepo.importSchedule(parsed, target.timetableId)
                            target.timetableId
                        }
                    }
                }
            }.onSuccess { id ->
                AppRefresh.onDataChanged(context)  // 入库：刷新小组件 + 重排提醒
                val name = when (target) {
                    is ImportTarget.NewTimetable -> target.name
                    is ImportTarget.IntoCreated -> target.name
                    is ImportTarget.Existing ->
                        timetableInfos.firstOrNull { it.timetable.id == id }?.timetable?.name ?: ""
                }
                android.util.Log.i(
                    "ScheduleImport",
                    "导入成功 target=$id name=$name courses=${parsed.courses.size} entries=${parsed.entries.size}",
                )
                showSnackbar("已导入到《$name》：${parsed.courses.size} 门课程，建议检查课表")
            }.onFailure { e ->
                android.util.Log.w("ScheduleImport", "导入失败 target=$target", e)
                parseError = "导入失败：${e.message ?: "未知错误"}"
                showSnackbar("导入失败：${e.message ?: "未知错误"}")
            }
        }
    }

    // ---- 解析看门狗：长时间未收到完成广播则复位，避免界面停留在"解析中" ----
    LaunchedEffect(parsing) {
        if (parsing) {
            delay(300_000)
            if (parsing) {
                parsing = false
                parseError = "解析超时，请重新导入"
            }
        }
    }

    // ---- 系统手势/按键返回：覆盖页显示时返回先关闭覆盖页，回到原界面原位置 ----
    val overlayShown = showSectionTimes || showAbout || showPrivacy ||
        showTimetableManage || showWidgetBind
    androidx.activity.compose.BackHandler(enabled = overlayShown) {
        when {
            showSectionTimes -> showSectionTimes = false
            showTimetableManage -> showTimetableManage = false
            showWidgetBind -> showWidgetBind = false
            showAbout -> showAbout = false
            showPrivacy -> showPrivacy = false
        }
    }

    // ---- 深色模式解析 ----
    val darkTheme = when (settings.darkMode) {
        "light" -> false
        "dark" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    com.example.composeapp.ui.theme.ComposeAppTheme(
        darkTheme = darkTheme,
        dynamicColor = settings.dynamicColor && android.os.Build.VERSION.SDK_INT >= 31,
    ) {
        // 实验性：自定义背景层包裹整个 Scaffold（含底栏），玻璃风格随开关生效
        val glassOn = settings.customBgEnabled
        com.example.composeapp.ui.theme.CustomBackgroundLayer(
            enabled = glassOn,
            imagePath = settings.customBgPath,
            blurDp = settings.customBgBlurDp,
        ) {
        if (!overlayShown) Scaffold(
        containerColor = if (glassOn) androidx.compose.ui.graphics.Color.Transparent
        else MaterialTheme.colorScheme.surface,
        bottomBar = {
            // 迷你底栏：56dp 高，图标 + 选中态胶囊；玻璃模式下半透明 + 顶部细描边
            @Composable fun BottomBarRow() {
                // 选中胶囊平滑滑移：目标位置按均分槽位计算，胶囊在槽位间连续移动
                val indicatorIndex by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = tab.toFloat(),
                    animationSpec = androidx.compose.animation.core.spring(
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                    ),
                    label = "bottomBarIndicator",
                )
                BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
                    val slot = maxWidth / TAB_LABELS.size
                    val pillOffset = slot * indicatorIndex + (slot - 64.dp) / 2
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TAB_LABELS.forEachIndexed { i, label ->
                            Box(
                                Modifier
                                    .width(64.dp)
                                    .height(34.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    when (i) {
                                        0 -> Icons.Filled.Home
                                        1 -> Icons.AutoMirrored.Filled.List
                                        else -> Icons.Filled.Settings
                                    },
                                    contentDescription = label,
                                    tint = if (tab == i) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                    // 滑移胶囊：绘制在图标层之下，点击仍在图标 Box 上（天然在上层）
                    Box(
                        Modifier
                            .offset(x = pillOffset)
                            .width(64.dp)
                            .height(34.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) { tab = indicatorIndex.roundToInt() },
                    )
                }
            }
            if (glassOn) {
                // 磨砂玻璃底栏：悬浮圆角矩形，四周留距；避让底部导航条
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 10.dp, top = 2.dp)
                ) {
                    com.example.composeapp.ui.theme.GlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                    ) { BottomBarRow() }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 2.dp,
                    modifier = Modifier.navigationBarsPadding(),
                ) { BottomBarRow() }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // Tab 方向性转场：切到右边页从右滑入，切到左边页从左滑入（替代无方向 Crossfade）
        androidx.compose.animation.AnimatedContent(
            targetState = tab,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it / 6 } +
                        fadeIn(tween(240)))
                        .togetherWith(
                            slideOutHorizontally(tween(200)) { -it / 8 } + fadeOut(tween(160))
                        )
                } else {
                    (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it / 6 } +
                        fadeIn(tween(240)))
                        .togetherWith(
                            slideOutHorizontally(tween(200)) { it / 8 } + fadeOut(tween(160))
                        )
                }
            },
            label = "tabSwitch",
            modifier = Modifier.padding(padding),
        ) { page ->
            when (page) {
                0 -> TimetableScreen(
                    entries = entries,
                    settings = settings,
                    parsing = parsing,
                    glass = glassOn,
                    onCourseClick = { selectedEntry = it },
                    onShowSnackbar = showSnackbar,
                    onImportClick = { filePicker.launch(IMPORT_MIMES) },
                    onAddClick = { showAddCourse = true },
                    onMoveEntry = { entry, day, start, end ->
                        scope.launch {
                            runCatching {
                                kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    scheduleRepo.moveEntry(entry.entryId, day, start, end)
                                }
                            }.onSuccess {
                                AppRefresh.onDataChanged(context)
                                val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
                                showSnackbar("已移动至${dayNames[day - 1]} 第 $start-$end 节")
                            }.onFailure {
                                showSnackbar("移动失败：${it.message ?: "未知错误"}")
                            }
                        }
                    },
                    timetables = timetableInfos,
                    onSwitchTimetable = { id ->
                        settingsRepo.setActiveTimetable(id)
                        AppRefresh.onDataChanged(context)
                        val name = timetableInfos.firstOrNull { it.timetable.id == id }?.timetable?.name ?: ""
                        showSnackbar("已切换到《$name》")
                    },
                    onNewTimetable = { showNewTimetableDialog = true },
                    onOpenManage = { showTimetableManage = true },
                )
                1 -> TodayScreen(
                    entries = entries,
                    settings = settings,
                    glass = glassOn,
                )
                else -> MineScreen(
                    settings = settings,
                    glass = glassOn,
                    courseCount = courses.size,
                    entryCount = entries.size,
                    parsing = parsing,
                    parseError = parseError,
                    onPickPdf = { filePicker.launch(IMPORT_MIMES) },
                    onSetSemesterStart = setSemesterStart,
                    onSetTotalWeeks = {
                        scope.launch {
                            settingsRepo.setTotalWeeks(it)
                            AppRefresh.onDataChanged(context)
                        }
                    },
                    onSetShowWeekend = {
                        settingsRepo.setShowWeekend(it)
                        AppRefresh.onDataChanged(context)  // 周末开关影响小组件与提醒
                    },
                    onSetShowNonCurrentWeek = { settingsRepo.setShowNonCurrentWeek(it) },
                    onSetDynamicColor = { settingsRepo.setDynamicColor(it) },
                    onSetDarkMode = { settingsRepo.setDarkMode(it) },
                    onOpenSectionTimes = { showSectionTimes = true },
                    onSetRemindEnabled = {
                        settingsRepo.setRemindEnabled(it)
                        AppRefresh.onDataChanged(context)
                    },
                    onSetRemindMinutes = {
                        settingsRepo.setRemindMinutesBefore(it)
                        AppRefresh.onDataChanged(context)
                    },
                    onSendTestReminder = {
                        scope.launch {
                            com.example.composeapp.reminder.ClassReminderScheduler.fireTest(context)
                        }
                    },
                    onSetCustomBgEnabled = { settingsRepo.setCustomBgEnabled(it) },
                    onPickBackground = { bgPicker.launch(arrayOf("image/*")) },
                    onClearBackground = {
                        scope.launch {
                            kotlinx.coroutines.withContext(Dispatchers.IO) {
                                if (settings.customBgPath.isNotBlank()) {
                                    File(settings.customBgPath).delete()
                                }
                                // 只清图片，保留玻璃开启状态：回退到内置渐变背景
                                settingsRepo.setCustomBgPath("")
                            }
                            showSnackbar("已恢复默认渐变背景")
                        }
                    },
                    onSetCustomBgBlur = { settingsRepo.setCustomBgBlur(it) },
                    onExportIcs = doExportIcs,
                    onClearData = {
                        scope.launch {
                            val affected = timetableInfos
                                .firstOrNull { it.timetable.id == settings.timetableId }
                            kotlinx.coroutines.withContext(Dispatchers.IO) {
                                scheduleRepo.clearTimetable(settings.timetableId)
                            }
                            File(context.filesDir, ScheduleParseService.RESULT_FILE).delete()
                            AppRefresh.onDataChanged(context)
                            showSnackbar("已清除《${affected?.timetable?.name ?: "当前课表"}》")
                        }
                    },
                    onShowSnackbar = showSnackbar,
                    onOpenAbout = { showAbout = true },
                    onOpenPrivacy = { showPrivacy = true },
                    onOpenTimetableManage = { showTimetableManage = true },
                    onOpenWidgetBind = { widgetBindRefresh++; showWidgetBind = true },
                )
             }
        }
    }

    // ---- 课表管理页（全屏覆盖；玻璃模式下透出背景） ----
    if (showTimetableManage) {
        com.example.composeapp.ui.timetable.TimetableManagePage(
            timetables = timetableInfos,
            activeId = settings.timetableId,
            glass = glassOn,
            onSwitch = { t ->
                settingsRepo.setActiveTimetable(t.id)
                AppRefresh.onDataChanged(context)
                showSnackbar("已切换到《${t.name}》")
            },
            onUpdate = { t ->
                scope.launch {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        scheduleRepo.updateTimetable(t)
                    }
                    AppRefresh.onDataChanged(context)
                    showSnackbar("已保存")
                }
            },
            onEditSchedule = { t ->
                // 切到该课表后进入作息编辑（作息页编辑的是活动课表）
                settingsRepo.setActiveTimetable(t.id)
                AppRefresh.onDataChanged(context)
                showTimetableManage = false
                showSectionTimes = true
                showSnackbar("已切换到《${t.name}》，请调整作息时间")
            },
            onCopy = { t ->
                scope.launch {
                    val result = runCatching {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            scheduleRepo.copyTimetable(
                                t, "${t.name} 副本", t.startMillis, t.totalWeeks,
                            )
                        }
                    }
                    result.onSuccess {
                        AppRefresh.onDataChanged(context)
                        showSnackbar("已复制为《${t.name} 副本》")
                    }.onFailure { e ->
                        showSnackbar("复制失败：${e.message ?: "未知错误"}")
                    }
                }
            },
            onDelete = { t ->
                scope.launch {
                    val result = runCatching {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            scheduleRepo.deleteTimetable(t.id, settings.timetableId)
                        }
                    }
                    result.onSuccess {
                        AppRefresh.onDataChanged(context)
                        showSnackbar("已删除《${t.name}》")
                    }.onFailure { e ->
                        showSnackbar(e.message ?: "删除失败")
                    }
                }
            },
            onCreate = {
                showTimetableManage = false
                showNewTimetableDialog = true
            },
            onBack = { showTimetableManage = false },
        )
    }

    // ---- 小组件绑定页（全屏覆盖） ----
    if (showWidgetBind) {
        val widgetInstances = remember(showWidgetBind, widgetBindRefresh) {
            queryWidgetInstances(context)
        }
        val bindings = remember(widgetInstances, widgetBindRefresh) {
            widgetInstances.associate { it.widgetId to settingsRepo.getWidgetTimetableId(it.widgetId) }
        }
        com.example.composeapp.ui.timetable.WidgetBindPage(
            widgets = widgetInstances,
            timetables = timetableInfos,
            bindings = bindings,
            glass = glassOn,
            onBind = { widgetId, ttId ->
                settingsRepo.setWidgetTimetableId(widgetId, ttId)
                widgetBindRefresh++
                AppRefresh.onDataChanged(context)
            },
            onBack = { showWidgetBind = false },
        )
    }

    // ---- 新建课表弹窗 / 导入选择弹窗 ----
    if (showNewTimetableDialog) {
        NewTimetableDialog(
            timetables = timetableInfos,
            onConfirm = confirmNewTimetable,
            onDismiss = { showNewTimetableDialog = false },
        )
    }
    pendingImport?.let { parsed ->
        val preset = timetableInfos.firstOrNull { it.timetable.id == autoCreatedTimetableId }
        ImportChooseDialog(
            parsedName = parsed.suggestedName,
            suggestedStartMillis = parsed.suggestedStartMillis,
            suggestedTotalWeeks = parsed.suggestedTotalWeeks,
            timetables = timetableInfos,
            defaultStartMillis = settings.semesterStart,
            defaultTotalWeeks = settings.totalWeeks,
            presetTarget = preset,
            presetName = pendingAutoName,
            onConfirm = { target ->
                val p = pendingImport
                pendingImport = null
                autoCreatedTimetableId = 0L
                pendingAutoName = ""
                if (p != null) confirmImport(p, target)
            },
            onDismiss = {
                pendingImport = null
                autoCreatedTimetableId = 0L
                pendingAutoName = ""
            },
        )
    }

    // ---- 作息时间独立页（全屏覆盖，含系统返回键处理；玻璃模式下透出背景） ----
    if (showSectionTimes) {
        com.example.composeapp.ui.mine.SectionTimePage(
            settings = settings,
            glass = glassOn,
            onSetSectionTimes = {
                scope.launch {
                    settingsRepo.setSectionTimes(it)
                    AppRefresh.onDataChanged(context)  // 作息变化影响提醒触发时刻
                }
            },
            onSetSectionsPerDay = {
                scope.launch {
                    settingsRepo.setSectionsPerDay(it)
                    AppRefresh.onDataChanged(context)
                }
            },
            onBack = { showSectionTimes = false },
        )
    }

    // ---- 关于页 / 隐私政策页（全屏覆盖；玻璃模式下透出背景） ----
    if (showAbout) {
            com.example.composeapp.ui.mine.AboutPage(
                versionName = "1.5",
            glass = glassOn,
            onBack = { showAbout = false },
        )
    }
    if (showPrivacy) {
        com.example.composeapp.ui.mine.PrivacyPage(
            glass = glassOn,
            onBack = { showPrivacy = false },
        )
    }
    }  // CustomBackgroundLayer

    selectedEntry?.let { e ->
        CourseDetailSheet(
            entry = e,
            dynamicColor = settings.dynamicColor,
            onEdit = { editingEntry = it },
            onDelete = { entry ->
                scope.launch {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        scheduleRepo.deleteEntry(entry.entryId)
                    }
                    AppRefresh.onDataChanged(context)
                }
                showSnackbar("已删除本节")
            },
            onDismiss = { selectedEntry = null },
        )
    }

    editingEntry?.let { e ->
        com.example.composeapp.ui.timetable.CourseEditSheet(
            entry = e,
            onSave = { name, teacher, campus, building, room ->
                scope.launch {
                    runCatching {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            if (name != e.courseName) scheduleRepo.renameCourse(e.courseId, name)
                            scheduleRepo.updateEntryInfo(e.entryId, teacher, campus, building, room)
                        }
                    }.onSuccess {
                        AppRefresh.onDataChanged(context)
                        showSnackbar("已保存")
                    }.onFailure {
                        showSnackbar("保存失败：${it.message ?: "未知错误"}")
                    }
                }
            },
            onDelete = { entry ->
                scope.launch {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        scheduleRepo.deleteEntry(entry.entryId)
                    }
                    AppRefresh.onDataChanged(context)
                }
                showSnackbar("已删除本节")
            },
            onDismiss = { editingEntry = null },
        )
    }

    // ---- 新增课程弹窗 ----
    if (showAddCourse) {
        // 周次上限用设置的总周数；默认选中识别到的最长周（不超过总周数）
        val maxWeek = (entries.maxOfOrNull { e -> e.weeks.maxOrNull() ?: 0 } ?: 0)
            .coerceIn(1, settings.totalWeeks)
        com.example.composeapp.ui.timetable.AddCourseSheet(
            maxWeek = maxWeek,
            onSave = { name, teacher, location, day, s, e, weeks ->
                scope.launch {
                    runCatching {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            scheduleRepo.addEntry(
                                settings.timetableId,
                                name, teacher,
                                campus = "", building = "", room = location,
                                day, s, e, weeks,
                            )
                        }
                    }.onSuccess {
                        AppRefresh.onDataChanged(context)
                        showSnackbar("已添加：$name")
                    }.onFailure {
                        showSnackbar("添加失败：${it.message ?: "未知错误"}")
                    }
                }
                showAddCourse = false
            },
            onDismiss = { showAddCourse = false },
        )
    }
    }
}

/** 枚举桌面上的简课表小组件实例（3×2 / 2×2）。 */
private fun queryWidgetInstances(context: Context): List<com.example.composeapp.ui.timetable.WidgetInstanceInfo> {
    val mgr = android.appwidget.AppWidgetManager.getInstance(context)
    val standard = android.content.ComponentName(context, ScheduleWidgetProvider::class.java)
    val compact = android.content.ComponentName(context, ScheduleWidgetCompactProvider::class.java)
    return mgr.getAppWidgetIds(standard).map {
        com.example.composeapp.ui.timetable.WidgetInstanceInfo(it, compact = false)
    } + mgr.getAppWidgetIds(compact).map {
        com.example.composeapp.ui.timetable.WidgetInstanceInfo(it, compact = true)
    }
}

/** collectAsState 初始值（真实默认值由 SettingsRepository 首帧后发出）。 */
private fun defaultSettings(): ScheduleSettings =
    ScheduleSettings(
        semesterStart = com.example.composeapp.data.SettingsRepository.DEFAULT_SEMESTER_START_MILLIS,
        sectionsPerDay = 12,
        totalWeeks = com.example.composeapp.data.SettingsRepository.DEFAULT_TOTAL_WEEKS,
        showWeekend = true,
        showNonCurrentWeek = false,
        dynamicColor = false,
        darkMode = "system",
        sectionTimes = com.example.composeapp.data.SettingsRepository.DEFAULT_SECTION_TIMES.take(12),
        remindEnabled = false,
        remindMinutesBefore = com.example.composeapp.data.SettingsRepository.REMIND_MINUTES_DEFAULT,
        customBgEnabled = true,
        customBgPath = "",
        customBgBlurDp = com.example.composeapp.data.SettingsRepository.CUSTOM_BG_BLUR_DEFAULT,
)

/** 背景图导入：两次解码（先边界后位图）降采样至 ≤2048px，JPEG 存私有目录。 */
private fun importBackground(
    cr: android.content.ContentResolver,
    uri: android.net.Uri,
    dst: File,
) {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
    val bmp = cr.openInputStream(uri)!!.use {
        android.graphics.BitmapFactory.decodeStream(
            it, null,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } ?: throw IllegalStateException("无法解码图片")
    dst.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
    bmp.recycle()
}
