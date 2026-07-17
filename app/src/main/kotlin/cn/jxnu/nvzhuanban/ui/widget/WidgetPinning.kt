package cn.jxnu.nvzhuanban.ui.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/**
 * 「一键添加桌面小组件」：请求当前桌面弹出系统的添加确认窗
 * （[AppWidgetManager.requestPinAppWidget]，API 26+，与 minSdk 持平，无需版本分支）。
 *
 * 返回 true 只代表桌面**受理**了请求，不保证确认窗真的弹出——部分国产桌面
 * （如 MIUI 未授予「桌面快捷方式」权限时）会静默吞掉，因此调用方的 UI 必须
 * 让「手动添加」引导始终可达，不能只在返回 false 时才展示。
 */
fun requestPinTodayScheduleWidget(context: Context): Boolean {
    val manager = context.getSystemService(AppWidgetManager::class.java) ?: return false
    if (!manager.isRequestPinAppWidgetSupported) return false
    val provider = ComponentName(context, TodayScheduleWidgetReceiver::class.java)
    // requestPinAppWidget 在 app 处于后台等场合会抛 IllegalStateException，按"不支持"兜住
    return runCatching { manager.requestPinAppWidget(provider, null, null) }.getOrDefault(false)
}

/** 当前桌面是否支持 pin 请求；不支持时 UI 直接展示手动添加引导，不显示一键按钮。 */
fun isPinTodayScheduleWidgetSupported(context: Context): Boolean =
    context.getSystemService(AppWidgetManager::class.java)?.isRequestPinAppWidgetSupported == true
