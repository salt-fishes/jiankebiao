package com.example.composeapp.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
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
import com.example.composeapp.schedule.ParsedSchedule
import com.example.composeapp.ui.mine.MineScreen
import com.example.composeapp.ui.timetable.CourseDetailSheet
import com.example.composeapp.ui.timetable.TimetableScreen
import com.example.composeapp.ui.today.TodayScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val TAB_LABELS = listOf("课表", "今日", "我的")

/** 应用外壳：底部导航三页 + 解析流程 + 全局状态。 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val scheduleRepo = remember { ScheduleRepository.getInstance(context) }

    val settings by settingsRepo.settings.collectAsState(initial = defaultSettings())
    val entries by scheduleRepo.observeAllEntries().collectAsState(initial = emptyList())
    val courses by scheduleRepo.observeCourses().collectAsState(initial = emptyList())

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var parsing by rememberSaveable { mutableStateOf(false) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var selectedEntry by remember { mutableStateOf<EntryWithCourse?>(null) }
    var editingEntry by remember { mutableStateOf<EntryWithCourse?>(null) }
    var showAddCourse by rememberSaveable { mutableStateOf(false) }
    var showSectionTimes by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val showSnackbar: (String) -> Unit = { msg ->
        scope.launch {
            snackbarHostState.showSnackbar(msg)
        }
    }

    // ---- 设置动作 ----
    val setSemesterStart: (java.time.LocalDate) -> Unit = {
        settingsRepo.setSemesterStart(it)
    }

    fun startParse(pdfPath: String) {
        parsing = true
        parseError = null
        ScheduleParseService.start(context, pdfPath)
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        // 复制到 cacheDir 唯一临时文件，避免覆盖 files/课表.pdf（旧 adb push 只读 444）
                        val importFile = File(
                            context.cacheDir,
                            "import_${System.currentTimeMillis()}.pdf"
                        )
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            importFile.outputStream().use { out ->
                                input.copyTo(out)
                            }
                        } ?: throw IllegalStateException("无法读取文件")
                        importFile
                    }
                }.onSuccess { importFile ->
                    startParse(importFile.absolutePath)
                }.onFailure { e ->
                    parsing = false
                    parseError = e.message ?: "无法读取文件"
                }
            }
        }
    }

    // ---- 启动逻辑：仅测试驱动（adb 传入 pdf_path）触发解析；课表数据一律由用户导入 ----
    LaunchedEffect(Unit) {
        val activity = context as? android.app.Activity
        val intentPdf = activity?.intent?.getStringExtra(ScheduleParseService.EXTRA_PDF_PATH)
        if (intentPdf != null) startParse(intentPdf)
    }

    // ---- 解析完成广播 ----
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                parsing = false
                val rf = File(context.filesDir, ScheduleParseService.RESULT_FILE)
                ParsedSchedule.fromJson(rf.readText()).fold(
                    onSuccess = { parsed ->
                        parseError = null
                        showSnackbar(
                            if (parsed.courses.isEmpty()) "未识别到课程，请检查 PDF 格式"
                            else "导入完成：${parsed.courses.size} 门课程，建议检查课表"
                        )
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
        Scaffold(
        bottomBar = {
            // 迷你底栏：56dp 高，图标 + 选中态胶囊
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TAB_LABELS.forEachIndexed { i, label ->
                        val selected = tab == i
                        Box(
                            Modifier
                                .width(64.dp)
                                .height(34.dp)
                                .clip(MaterialTheme.shapes.large)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else androidx.compose.ui.graphics.Color.Transparent
                                )
                                .clickable { tab = i },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                when (i) {
                                    0 -> Icons.Filled.Home
                                    1 -> Icons.Filled.List
                                    else -> Icons.Filled.Settings
                                },
                                contentDescription = label,
                                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        androidx.compose.animation.Crossfade(
            targetState = tab,
            modifier = Modifier.padding(padding),
            label = "tabs",
        ) { page ->
            when (page) {
                0 -> TimetableScreen(
                    entries = entries,
                    settings = settings,
                    parsing = parsing,
                    onCourseClick = { selectedEntry = it },
                    onShowSnackbar = showSnackbar,
                    onImportClick = { filePicker.launch(arrayOf("application/pdf")) },
                    onAddClick = { showAddCourse = true },
                )
                1 -> TodayScreen(entries = entries, settings = settings)
                else -> MineScreen(
                    settings = settings,
                    courseCount = courses.size,
                    entryCount = entries.size,
                    parsing = parsing,
                    parseError = parseError,
                    onPickPdf = { filePicker.launch(arrayOf("application/pdf")) },
                    onSetSemesterStart = setSemesterStart,
                    onSetTotalWeeks = { settingsRepo.setTotalWeeks(it) },
                    onSetShowWeekend = { settingsRepo.setShowWeekend(it) },
                    onSetShowNonCurrentWeek = { settingsRepo.setShowNonCurrentWeek(it) },
                    onSetDynamicColor = { settingsRepo.setDynamicColor(it) },
                    onSetDarkMode = { settingsRepo.setDarkMode(it) },
                    onOpenSectionTimes = { showSectionTimes = true },
                    onClearData = {
                        scope.launch {
                            kotlinx.coroutines.withContext(Dispatchers.IO) {
                                scheduleRepo.clearAll()
                            }
                            File(context.filesDir, ScheduleParseService.RESULT_FILE).delete()
                        }
                    },
                    onShowSnackbar = showSnackbar,
                    onOpenAbout = { showAbout = true },
                    onOpenPrivacy = { showPrivacy = true },
                )
            }
        }
    }

    // ---- 作息时间独立页（全屏覆盖，含系统返回键处理） ----
    if (showSectionTimes) {
        com.example.composeapp.ui.mine.SectionTimePage(
            settings = settings,
            onSetSectionTimes = { settingsRepo.setSectionTimes(it) },
            onSetSectionsPerDay = { settingsRepo.setSectionsPerDay(it) },
            onBack = { showSectionTimes = false },
        )
    }

    // ---- 关于页 / 隐私政策页（全屏覆盖） ----
    if (showAbout) {
        com.example.composeapp.ui.mine.AboutPage(
            versionName = "1.2",
            onBack = { showAbout = false },
        )
    }
    if (showPrivacy) {
        com.example.composeapp.ui.mine.PrivacyPage(
            onBack = { showPrivacy = false },
        )
    }

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
                                name, teacher,
                                campus = "", building = "", room = location,
                                day, s, e, weeks,
                            )
                        }
                    }.onSuccess {
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
    )
