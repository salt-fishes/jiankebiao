package com.example.composeapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.composeapp.data.ScheduleRepository
import com.example.composeapp.schedule.SchedulePdfParser
import com.example.composeapp.schedule.ScheduleXlsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 前台解析服务：课表 PDF 解析耗时较长（OCR），在前台服务中执行，
 * 即使界面被系统回收，解析仍继续，完成后写入 result.json 并广播通知界面恢复。
 * 班级课表 Excel（.xls）为纯文本解析，秒级完成，同样走此服务以复用入库与广播流程。
 */
class ScheduleParseService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra(EXTRA_PDF_PATH)
        if (path == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val options = SchedulePdfParser.ParseOptions(
            bandCount = intent.getIntExtra(EXTRA_BAND_COUNT, 4),
            // 行高约 36px（2x 渲染），接缝处需 ≥2 行重叠才能保证整行完整进入某一 band
            bandOverlap = intent.getIntExtra(EXTRA_BAND_OVERLAP, 80),
            renderScale = intent.getFloatExtra(EXTRA_RENDER_SCALE, 2f),
        )
        startForegroundCompat("正在解析课表…")
        scope.launch {
            val result = runCatching {
                if (ScheduleXlsParser.isXlsLike(path)) {
                    // 班级课表 Excel：结构化文本直接解析
                    ScheduleXlsParser.parse(File(path))
                } else {
                    SchedulePdfParser(this@ScheduleParseService, options).parse(File(path))
                }
            }
            // 解析成功：写入 Room（失败不影响 result.json 流程）
            result.getOrNull()?.let { parsed ->
                runCatching { ScheduleRepository.getInstance(this@ScheduleParseService).importSchedule(parsed) }
                    .onFailure { t ->
                        android.util.Log.w(TAG, "课表入库失败", t)
                    }
            }
            val json = result.fold(
                { it.toJson() },
                { t -> "{\"error\": ${org.json.JSONObject.quote(t.message ?: "解析失败")}}" }
            )
            File(filesDir, RESULT_FILE).writeText(json)
            // 必须指定包名：Android 13+ 隐式广播无法送达 RECEIVER_NOT_EXPORTED 的运行时接收器
            sendBroadcast(Intent(ACTION_PARSE_DONE).setPackage(packageName))
            stopForegroundCompat()
            // 清理导入临时文件（cacheDir 下 import_*.pdf / import_*.xls），避免堆积
            runCatching {
                cacheDir.listFiles()?.filter { it.name.startsWith("import_") }?.forEach { it.delete() }
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(text: String) {
        val channelId = CHANNEL_ID
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "课表解析", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("课表解析")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val TAG = "ScheduleParseService"
        private const val CHANNEL_ID = "schedule_parse"
        private const val NOTIF_ID = 1001
        const val EXTRA_PDF_PATH = "pdf_path"
        const val EXTRA_BAND_COUNT = "band_count"
        const val EXTRA_BAND_OVERLAP = "band_overlap"
        const val EXTRA_RENDER_SCALE = "render_scale"
        const val RESULT_FILE = "result.json"
        const val ACTION_PARSE_DONE = "com.example.composeapp.PARSE_DONE"

        fun start(context: Context, pdfPath: String) {
            val intent = Intent(context, ScheduleParseService::class.java)
                .putExtra(EXTRA_PDF_PATH, pdfPath)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startWithOptions(
            context: Context,
            pdfPath: String,
            bandCount: Int,
            bandOverlap: Int,
            renderScale: Float,
        ) {
            val intent = Intent(context, ScheduleParseService::class.java)
                .putExtra(EXTRA_PDF_PATH, pdfPath)
                .putExtra(EXTRA_BAND_COUNT, bandCount)
                .putExtra(EXTRA_BAND_OVERLAP, bandOverlap)
                .putExtra(EXTRA_RENDER_SCALE, renderScale)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
