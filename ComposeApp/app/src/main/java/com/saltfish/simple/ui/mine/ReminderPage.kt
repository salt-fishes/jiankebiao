package com.saltfish.simple.ui.mine

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.saltfish.simple.data.ScheduleSettings
import com.saltfish.simple.data.SettingsRepository
import com.saltfish.simple.reminder.ClassReminderScheduler
import com.saltfish.simple.reminder.ReminderDiagnostics
import com.saltfish.simple.ui.theme.GlassSurface
import java.time.format.DateTimeFormatter

/**
 * 课程提醒页：权限引导（通知 / 精确闹钟）+ 提前量 + 「提醒运行证据」诊断 + 测试。
 * 参考成熟课表应用的做法：本地提醒最常见的故障是「通知被关 / 精确闹钟被拒 /
 * 厂商省电延迟交付」，本页把这三件事变成看得见的状态，而不是让用户以为应用坏了。
 */
@Composable
fun ReminderPage(
    settings: ScheduleSettings,
    glass: Boolean = false,
    onSetRemindEnabled: (Boolean) -> Unit,
    onSetRemindMinutes: (Int) -> Unit,
    onTestNow: () -> Unit,
    onTestInOneMinute: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 从系统设置页返回时刷新权限状态（ON_RESUME 计数器驱动重查）
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var notifEnabled by remember { mutableStateOf(true) }
    var exactAlarm by remember { mutableStateOf(true) }
    var nextLine by remember { mutableStateOf("查询中…") }
    LaunchedEffect(resumeTick) {
        notifEnabled = ReminderDiagnostics.notificationsEnabled(context)
        exactAlarm = ReminderDiagnostics.alarmExact(context)
        val next = runCatching { ClassReminderScheduler.findNext(context) }.getOrNull()
        nextLine = when {
            !settings.remindEnabled -> "提醒已关闭"
            next == null -> "当前没有可登记的未来课程"
            else -> {
                val day = listOf("一", "二", "三", "四", "五", "六", "日")[next.startAt.dayOfWeek.value - 1]
                val ahead = settings.remindMinutesBefore
                val aheadText = if (ahead == 0) "准点" else "提前 $ahead 分钟"
                "${next.entry.courseName} · 第${next.week}周 周$day " +
                    next.startAt.format(DateTimeFormatter.ofPattern("HH:mm")) + "（$aheadText）"
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- 页头 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "课程提醒",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        // ---- 说明卡 ----
        PageCard(glass) {
            Text(
                "不用后台常驻",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "自动提醒会在调课后重排；重启或升级应用后自动恢复。" +
                    "手动测试只验证通知效果，不会改动已排任务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- 自动课程提醒 ----
        PageCard(glass) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "上课提醒",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "应用到当前课表的全部课程",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.remindEnabled, onCheckedChange = onSetRemindEnabled)
            }
            if (settings.remindEnabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "提前多久",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SettingsRepository.REMIND_MINUTES_CHOICES.sorted().forEach { m ->
                        FilterChip(
                            selected = settings.remindMinutesBefore == m,
                            onClick = { onSetRemindMinutes(m) },
                            label = { Text(if (m == 0) "准点" else "$m 分") },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "提醒在手机本地触发，时间取自作息表设置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 权限引导：通知总开关 ----
        if (!notifEnabled) {
            PermissionCard(
                title = "允许通知",
                body = "系统需要你的授权，才会展示课程提醒。当前通知总开关已关闭，提醒不会显示。",
                buttonText = "打开系统设置",
                onAction = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    runCatching { context.startActivity(intent) }
                },
            )
        }

        // ---- 权限引导：精确闹钟 ----
        if (Build.VERSION.SDK_INT >= 31 && !exactAlarm) {
            PermissionCard(
                title = "提高准时性",
                body = "当前使用系统近似闹钟（±10 分钟）。允许精确闹钟后，锁屏和省电模式下更准。",
                buttonText = "允许精确闹钟",
                onAction = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:${context.packageName}"))
                        )
                    }
                },
            )
        }

        // ---- 提醒运行证据（诊断面板） ----
        PageCard(glass) {
            Text(
                "提醒运行证据",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            ReminderDiagnostics.snapshot(context, nextLine).forEachIndexed { i, row ->
                if (i > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        row.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = when (row.ok) {
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                            true -> MaterialTheme.colorScheme.primary
                            false -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.weight(2f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
        }

        // ---- 测试 ----
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onTestNow,
                modifier = Modifier.weight(1f),
            ) { Text("立即测试") }
            OutlinedButton(
                onClick = onTestInOneMinute,
                modifier = Modifier.weight(1f),
            ) { Text("一分钟测试") }
        }
        Text(
            "立即测试只验证通知展示；一分钟测试会真排一个 60 秒后的闹钟，能暴露精确闹钟与省电策略问题。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- 风险提示 ----
        PageCard(glass, container = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)) {
            Text(
                "重要课程请设置双保险",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "课程提醒依赖系统通知、闹钟权限、设备时间和厂商省电策略。强制停止应用、" +
                    "关闭通知或修改系统时间可能导致提醒失效。重要课程和考试请同时设置系统闹钟。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 页面卡片容器：与「我的」页一致的玻璃/实色双形态。 */
@Composable
private fun PageCard(
    glass: Boolean,
    container: androidx.compose.ui.graphics.Color? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (glass && container == null) {
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
        }
    } else {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = container
                    ?: MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
        }
    }
}

/** 权限引导红卡：标题 + 说明 + 动作按钮（深链系统设置）。 */
@Composable
private fun PermissionCard(
    title: String,
    body: String,
    buttonText: String,
    onAction: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onAction,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f),
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text(buttonText) }
        }
    }
}
