package com.saltfish.simple.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 操作触感反馈（Haptics）。
 *
 * 统一入口，供底栏切换、调课落位、日历同步等关键节点调用；
 * API 29+ 用系统预定义效果（厂商 ROM 会被映射为自家触感语言），
 * 更低版本退化为短振。VIBRATE 为普通权限，无需运行时申请。
 */
object Haptics {

    private var cached: Vibrator? = null

    /** 轻点：底栏页签切换、拖动吸附经过槽位、设置开关。 */
    fun tick(context: Context) = predefined(context, VibrationEffect.EFFECT_TICK)

    /** 单击确认：同步/清空日历等操作成功。 */
    fun click(context: Context) = predefined(context, VibrationEffect.EFFECT_CLICK)

    /** 重击：调课落位、清除课表等强反馈。 */
    fun heavy(context: Context) = predefined(context, VibrationEffect.EFFECT_HEAVY_CLICK)

    private fun predefined(context: Context, effectId: Int) {
        runCatching {
            val v = obtain(context) ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(effectId))
            } else {
                val ms = if (effectId == VibrationEffect.EFFECT_HEAVY_CLICK) 30L else 12L
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun obtain(context: Context): Vibrator? = cached ?: runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()?.also { cached = it }
}
