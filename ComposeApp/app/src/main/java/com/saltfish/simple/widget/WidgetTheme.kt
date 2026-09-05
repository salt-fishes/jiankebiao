package com.saltfish.simple.widget

import android.content.Context
import android.content.res.Configuration
import com.saltfish.simple.R
import com.saltfish.simple.data.SettingsRepository

/**
 * 小组件配色：深浅色 × 实色/玻璃 四变体。
 *
 * RemoteViews 无法传 Drawable 对象，背景用 setBackgroundResource 在
 * 四个预置 drawable 间切换；文字色直接 setTextColor 程序化写入。
 * 深浅色跟随应用内深色模式设置（system/light/dark），玻璃跟随
 * 「磨砂玻璃风格」开关——小组件没有公开 API 模糊壁纸，玻璃变体用
 * 半透明 tint + 描边 + 顶部高光逼近应用内 GlassSurface 的观感，
 * 依赖启动器对半透明小组件的系统模糊（MagicOS/Pixel 等支持，不支持时
 * 由 tint 自身的 scrim 保底可读性）。
 */
object WidgetTheme {

    /** 一套配色：背景资源 + 三级文字色。 */
    data class Palette(
        val backgroundRes: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accent: Int,
    )

    // 亮色（与 Material 浅色 surface 对齐）
    private const val L_PRIMARY = 0xFF1C1B1F.toInt()
    private const val L_SECONDARY = 0xFF5B5962.toInt()
    private const val L_ACCENT = 0xFF585992.toInt()
    // 暗色
    private const val D_PRIMARY = 0xFFE6E1E9.toInt()
    private const val D_SECONDARY = 0xFF9A948F.toInt()
    private const val D_ACCENT = 0xFFBFC2E8.toInt()

    fun resolve(context: Context): Palette {
        val settings = SettingsRepository.getInstance(context).current
        val dark = when (settings.darkMode) {
            "light" -> false
            "dark" -> true
            else -> (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        return when {
            settings.customBgEnabled && dark ->
                Palette(R.drawable.widget_bg_glass_dark, D_PRIMARY, D_SECONDARY, D_ACCENT)
            settings.customBgEnabled ->
                Palette(R.drawable.widget_bg_glass_light, L_PRIMARY, L_SECONDARY, L_ACCENT)
            dark ->
                Palette(R.drawable.widget_bg_dark, D_PRIMARY, D_SECONDARY, D_ACCENT)
            else ->
                Palette(R.drawable.widget_bg, L_PRIMARY, L_SECONDARY, L_ACCENT)
        }
    }
}
