package com.example.composeapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableSharedFlow
import com.example.composeapp.ui.AppRoot

class MainActivity : ComponentActivity() {

    /**
     * 分享/「用其他应用打开」进来的待导入文件（ACTION_SEND / ACTION_VIEW）。
     * replay=1：冷启动时 onCreate 先于 Compose 订阅发出，靠重放送达；
     * AppRoot 消费后调用 resetReplayCache()，避免配置变更重建时重复导入。
     */
    private val importUris = MutableSharedFlow<Uri>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleImportIntent(intent)
        // 边到边绘制：背景图延伸至状态栏/导航条下方，内容用 insets 避让
        enableEdgeToEdge()
        // 解析耗时较长，保持屏幕常亮避免息屏导致前台退出
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Android 13+ 前台服务通知需运行时权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContent {
            AppRoot(importUris = importUris)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop/standard 均可能复用已有实例：热启动分享走这里
        handleImportIntent(intent)
    }

    /** 提取 SEND（EXTRA_STREAM）与 VIEW（data）中的 Uri，交给 Compose 层统一处理。 */
    private fun handleImportIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null && intent != null) {
            importUris.tryEmit(uri)
            // 消费掉，避免配置变更（旋转/重建）后重复导入同一文件
            intent.removeExtra(Intent.EXTRA_STREAM)
            intent.action = null
            intent.data = null
        }
    }
}
