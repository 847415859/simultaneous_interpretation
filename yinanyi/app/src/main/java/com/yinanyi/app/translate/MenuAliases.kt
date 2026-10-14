package com.yinanyi.app.translate

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.yinanyi.app.AppSettings

/**
 * 系统选择菜单里的「译成英文」这类文字是写死在清单里的，没法随设置变。
 * 所以清单里准备了英、日、韩、其他四个快速入口和一个「译…」入口，这里按设置只启用需要的。
 */
object MenuAliases {
    private const val PKG = "com.yinanyi.app.translate."

    fun apply(context: Context, s: AppSettings) {
        val quickName = when (s.inputLang) {
            "en" -> "MenuQuickEn"
            "ja" -> "MenuQuickJa"
            "ko" -> "MenuQuickKo"
            else -> "MenuQuickOther"
        }
        for (n in listOf("MenuQuickEn", "MenuQuickJa", "MenuQuickKo", "MenuQuickOther")) {
            set(context, n, s.inputMenu && s.inputQuick && n == quickName)
        }
        set(context, "MenuPanel", s.inputMenu)
        // 快速替换关掉时，主入口改成打开面板的那个：菜单里至少留一个以当前语言命名的
    }

    private fun set(context: Context, name: String, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            context.packageManager.setComponentEnabledSetting(ComponentName(context, PKG + name), state, PackageManager.DONT_KILL_APP)
        }
    }
}
