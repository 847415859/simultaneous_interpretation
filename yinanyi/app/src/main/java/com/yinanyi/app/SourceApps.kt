package com.yinanyi.app

import android.content.Intent
import android.content.pm.PackageManager

/** 常见的视频 / 直播 App，选来源时排在前面。 */
val KnownSourceApps = listOf(
    "tv.danmaku.bili" to "哔哩哔哩",
    "com.google.android.youtube" to "YouTube",
    "tv.twitch.android.app" to "Twitch",
    "com.ss.android.ugc.aweme" to "抖音",
    "com.zhiliaoapp.musically" to "TikTok",
    "com.netflix.mediaclient" to "Netflix",
)

/** 来源 App：既是「开始翻译」后要打开的 App，也是「只录它的声音」时的采集对象。 */
object SourceApps {
    /** 没选时默认 B站，国内版、HD 版、国际版都算。 */
    fun packagesFor(s: AppSettings): List<String> = if (s.sourcePkg.isEmpty()) BiliPackages else listOf(s.sourcePkg)

    fun launchIntent(pm: PackageManager, s: AppSettings): Intent? =
        packagesFor(s).firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }

    /** 界面上显示的名字；选的 App 已经被卸载时退回包名。 */
    fun label(pm: PackageManager, s: AppSettings): String {
        if (s.sourcePkg.isEmpty()) return "B站"
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(s.sourcePkg, 0)).toString() }.getOrDefault(s.sourcePkg)
    }

    fun uids(pm: PackageManager, s: AppSettings): List<Int> =
        packagesFor(s).mapNotNull { pkg -> runCatching { pm.getApplicationInfo(pkg, 0).uid }.getOrNull() }
}
