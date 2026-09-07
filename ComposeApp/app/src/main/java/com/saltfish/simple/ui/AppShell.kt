package com.saltfish.simple.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.saltfish.simple.ui.theme.AppMotion
import com.saltfish.simple.ui.theme.Haptics
import com.saltfish.simple.ScheduleParseService
import com.saltfish.simple.data.CalendarSync
import com.saltfish.simple.data.EntryWithCourse
import com.saltfish.simple.data.ScheduleRepository
import com.saltfish.simple.data.ScheduleSettings
import com.saltfish.simple.data.SettingsRepository
import com.saltfish.simple.data.TimetableEntity
import com.saltfish.simple.reminder.AppRefresh
import com.saltfish.simple.schedule.ParsedSchedule
import com.saltfish.simple.ui.mine.MineScreen
import com.saltfish.simple.ui.timetable.NewTimetableDialog
import com.saltfish.simple.ui.timetable.RulePackChooseDialog
import com.saltfish.simple.ui.timetable.ImportChooseDialog
import com.saltfish.simple.ui.timetable.ImportTarget
import com.saltfish.simple.ui.timetable.TimetableInfo
import com.saltfish.simple.ui.timetable.TimetableManagePage
import com.saltfish.simple.ui.timetable.TimetableScreen
import com.saltfish.simple.ui.timetable.WidgetBindPage
import com.saltfish.simple.ui.timetable.CourseDetailSheet
import com.saltfish.simple.ui.timetable.TimetableScreen
import com.saltfish.simple.ui.today.TodayScreen
import com.saltfish.simple.ui.compare.CompareRepository
import com.saltfish.simple.ui.compare.CompareScreen
import com.saltfish.simple.ui.compare.CompareTimetable
import com.saltfish.simple.ui.compare.OccupancyDetection
import com.saltfish.simple.ui.compare.OccupancyReviewScreen
import com.saltfish.simple.schedule.OccupancyParser
import com.saltfish.simple.widget.ScheduleWidgetCompactProvider
import com.saltfish.simple.widget.ScheduleWidgetMediumProvider
import com.saltfish.simple.widget.ScheduleWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
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
    @OptIn(
        kotlinx.coroutines.ExperimentalCoroutinesApi::class,
        )
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
    // 上次手动选定的解析规则包（新建弹窗写入；分享导入等无选择界面的流程复用）
    var lastFilePackId by rememberSaveable { mutableStateOf("zfsoft") }
    var lastImagePackId by rememberSaveable { mutableStateOf("icon-grid") }
    // 直接导入（我的页/课表页空状态/系统分享）没有新建课表弹窗：解析前弹规则包选择
    var showPackChoose by rememberSaveable { mutableStateOf(false) }
    var packChooseForImage by rememberSaveable { mutableStateOf(false) }
    var pendingShareImport by remember { mutableStateOf<Pair<File, String>?>(null) }
    var showReminders by rememberSaveable { mutableStateOf(false) }
    var showRulePacks by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    var showTimetableManage by rememberSaveable { mutableStateOf(false) }
    var showWidgetBind by rememberSaveable { mutableStateOf(false) }
    var widgetBindRefresh by remember { mutableIntStateOf(0) }
    var showCompare by rememberSaveable { mutableStateOf(false) }
    var compareTimetables by remember { mutableStateOf<List<CompareTimetable>>(emptyList()) }
    var pendingOccupancy by remember { mutableStateOf<OccupancyDetection?>(null) }
    var pendingImport by remember { mutableStateOf<ParsedSchedule?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val showSnackbar: (String) -> Unit = { msg ->
        scope.launch {
            snackbarHostState.showSnackbar(msg)
        }
    }

    var rulePackRefresh by remember { mutableStateOf(0) }
    val rulePackPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    val text = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
                    }
                    com.saltfish.simple.schedule.RulePackStore.import(context, text)
                }
                result.onSuccess { pack ->
                    rulePackRefresh++
                    showSnackbar("已导入规则包：${pack.name}")
                }.onFailure { e ->
                    showSnackbar("导入失败：${e.message ?: "不是有效的规则包文件"}")
                }
            }
        }
    }

    // ---- 设置动作 ----
    val setSemesterStart: (java.time.LocalDate) -> Unit = { date ->
        scope.launch {
            settingsRepo.setSemesterStart(date)  // 写库完成后再刷新，避免小组件读到旧日期
            AppRefresh.onDataChanged(context)
            Haptics.tick(context)  // 学期设置轻震
        }
    }

    fun startParse(pdfPath: String, sourceName: String = "", packId: String = lastFilePackId) {
        parsing = true
        parseError = null
        ScheduleParseService.start(context, pdfPath, packId, sourceName)
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

    // ---- 截图识别导入（系统照片选择器；复制到缓存后走解析服务图片分支） ----
    val importImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val importFile = File(
                            context.cacheDir,
                            "import_${System.currentTimeMillis()}.png"
                        )
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            importFile.outputStream().use { out -> input.copyTo(out) }
                        } ?: throw IllegalStateException("无法读取图片")
                        importFile
                    }
                }.onSuccess { importFile ->
                    tab = 0
                    parsing = true
                    parseError = null
                    ScheduleParseService.startImage(context, importFile.absolutePath, lastImagePackId)
                }.onFailure { e ->
                    parsing = false
                    showSnackbar("导入失败：${e.message ?: "无法读取图片"}")
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
                // 文件已就绪：先选规则包再解析（与手动导入同一确认流程）
                pendingShareImport = importFile to sourceName
                packChooseForImage = false
                showPackChoose = true
            }.onFailure { e ->
                showSnackbar("导入失败：${e.message ?: "不支持的文件类型"}")
            }
        }
        // 消费完成：清 replay 缓存，配置变更重建时不再重复导入
        importUris.resetReplayCache()
    }

    // ---- 背景图导入（系统照片选择器 Photo Picker：零权限，旧版本自动回退 SAF） ----
    val bgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
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

    // ---- 课表对比：系统照片选择器 → 本地占用识别 → 人工校正 ----
    val compareImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val dst = File(context.cacheDir, "occ_" + System.currentTimeMillis() + ".jpg")
                        context.contentResolver.openInputStream(uri)!!.use { input ->
                            dst.outputStream().use { input.copyTo(it) }
                        }
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(dst.absolutePath, bounds)
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
                        val bmp = android.graphics.BitmapFactory.decodeFile(
                            dst.absolutePath,
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                        ) ?: throw IllegalStateException("无法解码图片")
                        val grid = OccupancyParser.parse(context, bmp)
                        val overlay = OccupancyParser.drawOverlay(bmp, grid)
                        val reviewFile = File(context.filesDir, "occ_review_" + System.currentTimeMillis() + ".png")
                        reviewFile.outputStream().use {
                            overlay.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it)
                        }
                        overlay.recycle(); bmp.recycle()
                        OccupancyDetection(reviewFile.absolutePath, grid)
                    }
                }
                result.onSuccess { pendingOccupancy = it }
                    .onFailure { e -> showSnackbar("识别失败：" + (e.message ?: "未知错误")) }
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
            val ics = com.saltfish.simple.data.CalendarExport.buildIcs(
                entries = entries,
                sectionTimes = settings.sectionTimes,
                semesterStart = start,
                opts = com.saltfish.simple.data.CalendarExport.Options(
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

    // ---- 系统日历：同步课程 / 清空已同步课程（共用一次日历权限申请） ----
    var calendarAction by remember { mutableStateOf<String?>(null) }
    var showClearCalendarConfirm by remember { mutableStateOf(false) }

    fun runCalendarSync() {
        scope.launch {
            val result = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val start = settings.semesterStart.takeIf { it > 0L }
                        ?.let {
                            java.time.Instant.ofEpochMilli(it)
                                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        }
                        ?: throw IllegalStateException("请先在上方设置开学时间")
                    val specs = CalendarSync.buildEventSpecs(
                        entries = entries,
                        sectionTimes = settings.sectionTimes,
                        semesterStart = start,
                        totalWeeks = settings.totalWeeks,
                    )
                    CalendarSync.sync(
                        context.contentResolver,
                        specs,
                        if (settings.remindEnabled) settings.remindMinutesBefore else 0,
                    )
                }
            }
            result.onSuccess { n ->
                Haptics.click(context)  // 同步完成确认触感
                showSnackbar("已把 $n 节课程写入系统日历「简课表」")
            }.onFailure { e ->
                showSnackbar("同步失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun runCalendarClear() {
        scope.launch {
            val removed = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    CalendarSync.clearSyncedEvents(context.contentResolver)
                }
            }
            removed.onSuccess { n ->
                Haptics.click(context)  // 清空完成确认触感
                showSnackbar(if (n > 0) "已从系统日历移除 $n 节课程" else "系统日历里没有可清理的课程")
            }.onFailure { e ->
                showSnackbar("清理失败：${e.message ?: "未知错误"}")
            }
        }
    }

    val calendarPerms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[android.Manifest.permission.WRITE_CALENDAR] == true &&
            grants[android.Manifest.permission.READ_CALENDAR] == true
        when (calendarAction) {
            "sync" -> if (granted) runCalendarSync()
            else showSnackbar("需要「日历」权限才能同步，请在弹窗或系统设置中允许")
            "clear" -> if (granted) runCalendarClear()
            else showSnackbar("需要「日历」权限才能清理，请在弹窗或系统设置中允许")
        }
        calendarAction = null
    }
    fun requestCalendarPermission(action: String) {
        calendarAction = action
        calendarPerms.launch(
            arrayOf(
                android.Manifest.permission.READ_CALENDAR,
                android.Manifest.permission.WRITE_CALENDAR,
            )
        )
    }
    val doSyncCalendar: () -> Unit = {
        if (settings.semesterStart <= 0L) {
            showSnackbar("请先设置开学时间")
        } else {
            requestCalendarPermission("sync")
        }
    }
    val doClearCalendar: () -> Unit = { showClearCalendarConfirm = true }


    // ---- 启动逻辑：回填默认课表（升级迁移）→ 重排提醒；adb 测试驱动解析保留 ----
    LaunchedEffect(Unit) {
        val activity = context as? android.app.Activity
        val intentPdf = activity?.intent?.getStringExtra(ScheduleParseService.EXTRA_PDF_PATH)
        val intentImage = activity?.intent?.getStringExtra(ScheduleParseService.EXTRA_IMAGE_PATH)
        val intentPack = activity?.intent?.getStringExtra(ScheduleParseService.EXTRA_RULE_PACK_ID)
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            settingsRepo.ensureActiveTimetableReady()
        }
        if (intentPdf != null) {
            startParse(intentPdf, packId = intentPack ?: lastFilePackId)
        } else if (intentImage != null) {
            // adb 联调截图识别：走与界面导入相同的图片解析分支
            parsing = true
            parseError = null
            ScheduleParseService.startImage(context, intentImage, intentPack ?: lastImagePackId)
        }
        // 应用更新/覆盖安装会清掉 AlarmManager 闹钟：每次启动重排一次课前提醒；
        // 同时强刷一次小组件（防升级后残留旧渲染数据）
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            com.saltfish.simple.reminder.ClassReminderScheduler.reschedule(context)
            com.saltfish.simple.widget.ScheduleWidgetProvider.requestUpdate(context)
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
    val confirmNewTimetable: (String, Long?, String) -> Unit = { name, copyFrom, packId ->
        showNewTimetableDialog = false
        showTimetableManage = false
        lastFilePackId = packId
        lastImagePackId = packId
        if (copyFrom == -1L) {
            // 截图识别：自动建表 → 选截图 → 占用+内容识别 → 导入确认弹窗内命名
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
                    importImagePicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                } else {
                    showSnackbar("创建失败")
                }
            }
        } else if (copyFrom != null) {
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
                    if (com.saltfish.simple.schedule.RulePackStore.isScreenshotPack(context, packId)) {
                        // 截图类规则包（网页大图/课程卡等）：文件入口从图片查看器（相册）选图
                        importImagePicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    } else {
                        filePicker.launch(IMPORT_MIMES)
                    }
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
    val overlayShown = showSectionTimes || showReminders || showRulePacks || showAbout || showPrivacy ||
        showTimetableManage || showWidgetBind || showCompare
    androidx.activity.compose.BackHandler(enabled = overlayShown) {
        when {
            pendingOccupancy != null -> pendingOccupancy = null
            showSectionTimes -> showSectionTimes = false
            showReminders -> showReminders = false
            showRulePacks -> showRulePacks = false
            showTimetableManage -> showTimetableManage = false
            showWidgetBind -> showWidgetBind = false
            showCompare -> showCompare = false
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

    com.saltfish.simple.ui.theme.ComposeAppTheme(
        darkTheme = darkTheme,
        dynamicColor = settings.dynamicColor && android.os.Build.VERSION.SDK_INT >= 31,
    ) {
        // 实验性：自定义背景层包裹整个 Scaffold（含底栏），玻璃风格随开关生效
        val glassOn = settings.customBgEnabled
        com.saltfish.simple.ui.theme.CustomBackgroundLayer(
            enabled = glassOn,
            imagePath = settings.customBgPath,
            blurDp = settings.customBgBlurDp,
        ) {
        // 主界面常驻组合：二级页只是盖在上面，返回时保留滚动位置等全部状态
        // （原先 if(!overlayShown) 会把整个 Scaffold 拆掉重组，返回即丢位置）；
        // alpha 跟随动画淡隐，避免生切换底
        val shellAlpha by animateFloatAsState(
            targetValue = if (overlayShown) 0f else 1f,
            animationSpec = AppMotion.effectsFast(),
            label = "shellAlpha",
        )
        Box(Modifier.fillMaxSize()) {
        Scaffold(
        modifier = Modifier.fillMaxSize().alpha(shellAlpha),
        containerColor = if (glassOn) androidx.compose.ui.graphics.Color.Transparent
        else MaterialTheme.colorScheme.surface,
        bottomBar = {
            // 迷你底栏：56dp 高，图标 + 选中态胶囊；玻璃模式下半透明 + 顶部细描边
            @Composable fun BottomBarRow() {
                // 胶囊位置以「槽位下标」为单位的连续值：点击/跳转时从当前位置弹簧到目标；
                // 拖动时直接跟手（dragPx 记录像素偏移），松手从当前位置连续吸附到最近槽位
                val pillSlot = remember { Animatable(tab.toFloat()) }
                var dragPx by mutableFloatStateOf(0f)
                // 拖动跟手：基准值在拖动开始时同步冻结，偏移只随手指变化
                var isDragging by mutableStateOf(false)
                var dragBase by mutableFloatStateOf(0f)
                // 拖动经过槽位时的触感记录：每跨过一个槽位轻震一次
                var lastTickSlot by mutableIntStateOf(tab)
                LaunchedEffect(tab) {
                    pillSlot.animateTo(tab.toFloat(), AppMotion.spatialFast())
                }
                BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
                    val slot = maxWidth / TAB_LABELS.size
                    val slotPx = with(LocalDensity.current) { slot.toPx() }
                    val pillW = 64.dp
                    val pillInsetPx = with(LocalDensity.current) { ((slot - pillW) / 2).toPx() }
                    // 滑移胶囊：绘制在图标层【之下】，仅作视觉指示，不拦截点击；
                    // CenterStart 对齐后再做横向偏移，否则默认 TopStart 会顶到导航条上沿
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset {
                                val base = if (isDragging) dragBase else pillSlot.value
                                IntOffset(
                                    (slotPx * base + pillInsetPx + dragPx).roundToInt(),
                                    0,
                                )
                            }
                            .width(pillW)
                            .height(34.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    )
                    // 图标与胶囊用同一套槽位公式：每槽位宽度 = slot，图标居中，
                    // 整槽位可点（比 64dp 胶囊点击区域大，且不会互相遮挡）；
                    // 横向拖动跟手移动胶囊，点击（未过滑动阈值）仍走各槽位的 clickable
                    // 拖动期间胶囊位置只由「拖动起点 + 手指位移」决定（基准同步冻结），
                    // 与可能仍在进行的弹簧动画完全解耦——否则动画推进叠加手指位移会感觉不跟手；
                    // onDragCancel 与 onDragEnd 同等处理，避免手势被打断后 dragPx 残留、胶囊卡在半路
                    fun settleDrag() {
                        if (!isDragging) return
                        isDragging = false
                        val from = dragBase + dragPx / slotPx
                        val target = from.roundToInt().coerceIn(0, TAB_LABELS.lastIndex)
                        dragPx = 0f
                        Haptics.tick(context)  // 松手吸附触感
                        lastTickSlot = target
                        scope.launch {
                            pillSlot.snapTo(from)
                            if (target == tab) {
                                pillSlot.animateTo(target.toFloat(), AppMotion.spatialFast())
                            }
                        }
                        tab = target  // 变化时由 LaunchedEffect 弹簧到目标
                    }
                    Row(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        isDragging = true
                                        dragBase = pillSlot.value
                                        lastTickSlot = dragBase.roundToInt()
                                        scope.launch { pillSlot.stop() }
                                    },
                                    onDragEnd = { settleDrag() },
                                    onDragCancel = { settleDrag() },
                                ) { change, dragAmount ->
                                    change.consume()
                                    val range = slotPx * (TAB_LABELS.size - 1)
                                    dragPx = (dragPx + dragAmount).coerceIn(-range, range)
                                    // 拖动每跨过一个槽位：轻震一格
                                    val hoveredSlot = (dragBase + dragPx / slotPx).roundToInt()
                                    if (hoveredSlot != lastTickSlot && hoveredSlot in TAB_LABELS.indices) {
                                        lastTickSlot = hoveredSlot
                                        Haptics.tick(context)
                                    }
                                }
                            }
                    ) {
                        TAB_LABELS.forEachIndexed { i, label ->
                            // 选中图标轻微放大回弹，Expressive 空间弹簧驱动
                            val iconScale by animateFloatAsState(
                                targetValue = if (tab == i) 1.15f else 1f,
                                animationSpec = AppMotion.spatial(),
                                label = "tabScale$i",
                            )
                            Box(
                                Modifier
                                    .width(slot)
                                    .fillMaxHeight()
                                    .clickable {
                                        tab = i
                                        Haptics.tick(context)  // 页签切换轻震
                                    },
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
                                    modifier = Modifier
                                        .size(22.dp)
                                        .graphicsLayer {
                                            scaleX = iconScale
                                            scaleY = iconScale
                                        },
                                )
                            }
                        }
                    }
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
                    com.saltfish.simple.ui.theme.GlassSurface(
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
        snackbarHost = {},  // Snackbar 已上移到根 Box 顶层，二级页打开时也能看到提示
    ) { padding ->
        // Tab 方向性转场：切到右边页从右滑入，切到左边页从左滑入；规格取 Expressive MotionScheme
        androidx.compose.animation.AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val move = AppMotion.spatial<androidx.compose.ui.unit.IntOffset>()
                val fade = AppMotion.effectsFast<Float>()
                if (targetState > initialState) {
                    (slideInHorizontally(move) { it / 6 } + fadeIn(fade))
                        .togetherWith(
                            slideOutHorizontally(move) { -it / 8 } + fadeOut(fade)
                        )
                } else {
                    (slideInHorizontally(move) { -it / 6 } + fadeIn(fade))
                        .togetherWith(
                            slideOutHorizontally(move) { it / 8 } + fadeOut(fade)
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
                    onImportClick = {
                        packChooseForImage = false
                        showPackChoose = true
                    },
                    onAddClick = { showAddCourse = true },
                    onMoveEntry = { entry, day, start, end ->
                        scope.launch {
                            runCatching {
                                kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    scheduleRepo.moveEntry(entry.entryId, day, start, end)
                                }
                            }.onSuccess {
                                AppRefresh.onDataChanged(context)
                                Haptics.heavy(context)  // 调课落位强反馈
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
                        Haptics.click(context)  // 切换课表触感
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
                    onPickPdf = {
                        packChooseForImage = false
                        showPackChoose = true
                    },
                    onSetSemesterStart = setSemesterStart,
                    onSetTotalWeeks = {
                        scope.launch {
                            settingsRepo.setTotalWeeks(it)
                            AppRefresh.onDataChanged(context)
                            Haptics.tick(context)  // 周数设置轻震
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
                    onOpenReminders = { showReminders = true },
                    onOpenRulePacks = { showRulePacks = true },
                    onSetCustomBgEnabled = { settingsRepo.setCustomBgEnabled(it) },
                    onPickBackground = {
                        bgPicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
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
                    onSyncCalendar = doSyncCalendar,
                    onClearCalendar = doClearCalendar,
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
                            Haptics.heavy(context)  // 危险操作完成的强反馈
                            showSnackbar("已清除《${affected?.timetable?.name ?: "当前课表"}》")
                        }
                    },
                    onShowSnackbar = showSnackbar,
                    onOpenAbout = { showAbout = true },
                    onOpenPrivacy = { showPrivacy = true },
                    onOpenTimetableManage = { showTimetableManage = true },
                    onOpenWidgetBind = { widgetBindRefresh++; showWidgetBind = true },
                    onOpenCompare = { showCompare = true },
                )
             }
        }
    }

        // 触摸拦截层已并入 OverlayPage（随进出场动画一同出现/消失）

    // ---- 课表管理页（全屏覆盖；玻璃模式下透出背景） ----
    OverlayPage(showTimetableManage) {
        com.saltfish.simple.ui.timetable.TimetableManagePage(
            timetables = timetableInfos,
            activeId = settings.timetableId,
            glass = glassOn,
            onSwitch = { t ->
                settingsRepo.setActiveTimetable(t.id)
                AppRefresh.onDataChanged(context)
                Haptics.click(context)  // 切换课表触感
                showSnackbar("已切换到《${t.name}》")
            },
            onUpdate = { t ->
                scope.launch {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        scheduleRepo.updateTimetable(t)
                    }
                    AppRefresh.onDataChanged(context)
                    Haptics.click(context)
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
                        Haptics.click(context)  // 复制成功触感
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
                        Haptics.heavy(context)  // 删除课表强反馈
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
    OverlayPage(showWidgetBind) {
        val widgetInstances = remember(showWidgetBind, widgetBindRefresh) {
            queryWidgetInstances(context)
        }
        val bindings = remember(widgetInstances, widgetBindRefresh) {
            widgetInstances.associate { it.widgetId to settingsRepo.getWidgetTimetableId(it.widgetId) }
        }
        com.saltfish.simple.ui.timetable.WidgetBindPage(
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
        val packEntries = remember(showNewTimetableDialog) {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    com.saltfish.simple.schedule.RulePackStore.listAll(context)
                }
            }
        }
        NewTimetableDialog(
            timetables = timetableInfos,
            packs = packEntries,
            defaultFilePackId = lastFilePackId,
            defaultImagePackId = lastImagePackId,
            onConfirm = confirmNewTimetable,
            onDismiss = { showNewTimetableDialog = false },
        )
    }
    // ---- 直接导入的规则包选择（我的页 / 课表页空状态 / 系统分享导入共用） ----
    if (showPackChoose) {
        val packEntries = remember(showPackChoose) {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    com.saltfish.simple.schedule.RulePackStore.listAll(context)
                }
            }
        }
        val share = pendingShareImport
        RulePackChooseDialog(
            packs = packEntries,
            initialPackId = if (packChooseForImage) lastImagePackId else lastFilePackId,
            forImage = packChooseForImage,
            fileName = share?.first?.name ?: "",
            onConfirm = { packId ->
                showPackChoose = false
                if (share != null) {
                    // 系统分享导入：文件已复制到缓存，选完包直接解析
                    pendingShareImport = null
                    lastFilePackId = packId
                    startParse(share.first.absolutePath, share.second, packId)
                } else if (packChooseForImage ||
                    com.saltfish.simple.schedule.RulePackStore.isScreenshotPack(context, packId)
                ) {
                    // 截图入口 / 选中截图类规则包：从图片查看器（相册）选图
                    lastImagePackId = packId
                    importImagePicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                } else {
                    lastFilePackId = packId
                    filePicker.launch(IMPORT_MIMES)
                }
            },
            onDismiss = {
                showPackChoose = false
                pendingShareImport = null
            },
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

    // ---- 清空系统日历课程：确认弹窗（防误触，只动本应用的「简课表」日历） ----
    if (showClearCalendarConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCalendarConfirm = false },
            title = { Text("清空系统日历中的课程") },
            text = {
                Text("将删除系统日历「简课表」里本应用写入的全部课程事件，不会影响你的其他日历与日程。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearCalendarConfirm = false
                    requestCalendarPermission("clear")
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCalendarConfirm = false }) { Text("取消") }
            },
        )
    }

    // ---- 作息时间独立页（全屏覆盖，含系统返回键处理；玻璃模式下透出背景） ----
    OverlayPage(showSectionTimes) {
        com.saltfish.simple.ui.mine.SectionTimePage(
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

    // ---- 课程提醒独立页（权限引导 / 运行诊断 / 提前量 / 测试） ----
    OverlayPage(showReminders) {
        com.saltfish.simple.ui.mine.ReminderPage(
            settings = settings,
            glass = glassOn,
            onSetRemindEnabled = {
                settingsRepo.setRemindEnabled(it)
                AppRefresh.onDataChanged(context)
            },
            onSetRemindMinutes = {
                settingsRepo.setRemindMinutesBefore(it)
                AppRefresh.onDataChanged(context)
            },
            onTestNow = {
                scope.launch {
                    com.saltfish.simple.reminder.ClassReminderScheduler.fireTest(context)
                }
            },
            onTestInOneMinute = {
                scope.launch {
                    com.saltfish.simple.reminder.ClassReminderScheduler.fireTestInOneMinute(context)
                }
            },
            onBack = { showReminders = false },
        )
    }

    // ---- 解析规则包管理页（内置/导入，PDF 与图片识别共用） ----
    OverlayPage(showRulePacks) {
        val rulePackEntries = remember(showRulePacks, rulePackRefresh) {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    com.saltfish.simple.schedule.RulePackStore.listAll(context)
                }
            }
        }
        com.saltfish.simple.ui.mine.RulePackPage(
            entries = rulePackEntries,
            glass = glassOn,
            onImport = { rulePackPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            onDelete = { id ->
                if (com.saltfish.simple.schedule.RulePackStore.isBuiltin(id)) {
                    showSnackbar("内置规则包不能删除")
                } else {
                    runCatching { com.saltfish.simple.schedule.RulePackStore.remove(context, id) }
                    rulePackRefresh++
                    showSnackbar("已删除规则包")
                }
            },
            onBack = { showRulePacks = false },
        )
    }

    // ---- 关于页 / 隐私政策页（全屏覆盖；玻璃模式下透出背景） ----
    OverlayPage(showAbout) {
            com.saltfish.simple.ui.mine.AboutPage(
                versionName = com.saltfish.simple.BuildConfig.VERSION_NAME,
            glass = glassOn,
            onBack = { showAbout = false },
        )
    }
        // ---- 课表对比（实验性）：数据库课表 + 图片对比课表 → 共同空闲 ----
        OverlayPage(showCompare) {
            androidx.compose.runtime.LaunchedEffect(showCompare) {
                if (showCompare) compareTimetables = CompareRepository.load(context)
            }
            CompareScreen(
                timetables = timetableInfos,
                compareTimetables = compareTimetables,
                glass = glassOn,
                sectionsPerDay = settings.sectionsPerDay,
                defaultWeek = maxOf(
                    1,
                    com.saltfish.simple.data.WeekCalculator.currentWeek(
                        settings.semesterStartDate,
                        java.time.LocalDate.now(),
                    ),
                ),
                loadEntries = { id ->
                    scheduleRepo.observeAllEntries(id).first()
                },
                onPickImage = {
                    compareImagePicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                onDeleteCompare = { id ->
                    scope.launch {
                        compareTimetables = CompareRepository.remove(context, id)
                        Haptics.heavy(context)
                        showSnackbar("已删除对比课表")
                    }
                },
                onBack = { showCompare = false },
            )
            pendingOccupancy?.let { det ->
                OccupancyReviewScreen(
                    detection = det,
                    glass = glassOn,
                    onSave = { name, dayCount, blocks ->
                        pendingOccupancy = null
                        scope.launch {
                            compareTimetables = CompareRepository.add(
                                context,
                                CompareTimetable(
                                    CompareRepository.nextId(compareTimetables),
                                    name, dayCount, blocks,
                                ),
                            )
                            Haptics.click(context)
                            showSnackbar("已添加对比课表「" + name + "」")
                        }
                    },
                    onCancel = { pendingOccupancy = null },
                )
            }
        }

    OverlayPage(showPrivacy) {
        com.saltfish.simple.ui.mine.PrivacyPage(
            glass = glassOn,
            onBack = { showPrivacy = false },
        )
    }

        // 全局 Snackbar：挂在根 Box 顶层，浮在主界面与所有二级页之上
        SnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 72.dp),
        )
        }  // Box(fillMaxSize)
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
                Haptics.heavy(context)  // 删除课程强反馈
                showSnackbar("已删除本节")
            },
            onDismiss = { selectedEntry = null },
        )
    }

    editingEntry?.let { e ->
        com.saltfish.simple.ui.timetable.CourseEditSheet(
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
                        Haptics.click(context)  // 保存成功触感
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
                Haptics.heavy(context)  // 删除课程强反馈
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
        com.saltfish.simple.ui.timetable.AddCourseSheet(
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
                        Haptics.click(context)  // 添加成功触感
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

/**
 * 二级覆盖页容器：Expressive 转场（淡入 + 轻微上滑 + 缩放进入，快速淡出退场）。
 * 内容外罩全屏触摸拦截层——主界面已常驻组合（alpha 0），挡住穿透到课表格子的误触；
 * 拦截层随动画一同出现/消失，退场期间也不会漏点。
 */
@Composable
private fun OverlayPage(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(AppMotion.effects()) +
            scaleIn(AppMotion.spatialFast(), initialScale = 0.96f) +
            slideInVertically(AppMotion.spatialFast()) { it / 16 },
        exit = fadeOut(AppMotion.effectsFast()) +
            slideOutVertically(AppMotion.spatialFast()) { it / 16 },
    ) {
        Box(Modifier.fillMaxSize()) {
            // 拦截层必须垫在内容【下方】（兄弟节点而非父布局）：
            // 作为父布局会在主传递中先于页面滚动消费事件，导致二级页拖不动；
            // 作为下方兄弟，页面滚动手势优先命中，空白处的点击才落进拦截层，
            // 不会穿透到 alpha 0 的主界面
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { change ->
                                    if (change.pressed) change.consume()
                                }
                            }
                        }
                    }
            )
            content()
        }
    }
}

/** 枚举桌面上的简课表小组件实例（2×4 / 2×3 / 2×2）。 */
private fun queryWidgetInstances(context: Context): List<com.saltfish.simple.ui.timetable.WidgetInstanceInfo> {
    val mgr = android.appwidget.AppWidgetManager.getInstance(context)
    val large = android.content.ComponentName(context, ScheduleWidgetProvider::class.java)
    val medium = android.content.ComponentName(context, ScheduleWidgetMediumProvider::class.java)
    val compact = android.content.ComponentName(context, ScheduleWidgetCompactProvider::class.java)
    return mgr.getAppWidgetIds(large).map {
        com.saltfish.simple.ui.timetable.WidgetInstanceInfo(it, compact = false, sizeLabel = "2×4 列表")
    } + mgr.getAppWidgetIds(medium).map {
        com.saltfish.simple.ui.timetable.WidgetInstanceInfo(it, compact = false, sizeLabel = "2×3 列表")
    } + mgr.getAppWidgetIds(compact).map {
        com.saltfish.simple.ui.timetable.WidgetInstanceInfo(it, compact = true, sizeLabel = "2×2 紧凑")
    }
}

/** collectAsState 初始值（真实默认值由 SettingsRepository 首帧后发出）。 */
private fun defaultSettings(): ScheduleSettings =
    ScheduleSettings(
        semesterStart = com.saltfish.simple.data.SettingsRepository.DEFAULT_SEMESTER_START_MILLIS,
        sectionsPerDay = 12,
        totalWeeks = com.saltfish.simple.data.SettingsRepository.DEFAULT_TOTAL_WEEKS,
        showWeekend = true,
        showNonCurrentWeek = false,
        dynamicColor = false,
        darkMode = "system",
        sectionTimes = com.saltfish.simple.data.SettingsRepository.DEFAULT_SECTION_TIMES.take(12),
        remindEnabled = false,
        remindMinutesBefore = com.saltfish.simple.data.SettingsRepository.REMIND_MINUTES_DEFAULT,
        customBgEnabled = true,
        customBgPath = "",
        customBgBlurDp = com.saltfish.simple.data.SettingsRepository.CUSTOM_BG_BLUR_DEFAULT,
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
