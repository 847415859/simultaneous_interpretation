package com.yinanyi.app

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 让翻译服务在后台不被系统杀掉。
 * 原生 Android 靠「忽略电池优化」；小米 HyperOS / MIUI 还有自己的「省电策略」和「自启动」开关，
 * 这两个没有公开接口能查询状态，只能跳到对应页面让用户确认。
 */
object KeepAlive {
    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    // 小米的两个开关没法查询状态，只记录「用户是否点进去处理过」，用来把提示从「!」变成「已处理」
    private fun prefs(context: Context) = context.getSharedPreferences("keepalive", Context.MODE_PRIVATE)

    fun isDone(context: Context, key: String) = prefs(context).getBoolean(key, false)
    fun markDone(context: Context, key: String) = prefs(context).edit().putBoolean(key, true).apply()

    const val KEY_ASKED = "asked"
    const val KEY_XIAOMI_BATTERY = "xiaomi_battery"
    const val KEY_XIAOMI_AUTOSTART = "xiaomi_autostart"

    /** 系统弹窗：允许应用在后台不受电池优化限制。 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        launchFirst(context, direct, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(context))
    }

    /** 小米：应用的「省电策略」页，要选「无限制」。 */
    fun openXiaomiBatteryPolicy(context: Context) {
        val page = Intent().setComponent(
            ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
        ).putExtra("package_name", context.packageName).putExtra("package_label", "以南译")
        launchFirst(context, page, appDetails(context))
    }

    /** 小米：「自启动」管理页，要给以南译打开。 */
    fun openXiaomiAutostart(context: Context) {
        val page = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        launchFirst(context, page, appDetails(context))
    }

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** 依次尝试，哪个能打开就用哪个；都打不开就什么也不做，不崩溃。 */
    private fun launchFirst(context: Context, vararg intents: Intent) {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }
}
