package com.yinanyi.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.yinanyi.app.engine.Models
import com.yinanyi.app.translate.MenuAliases
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class YinanyiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        Usage.init(this)
        History.init(this)
        runCatching { Models(this).ensureVad() }
        // 输入翻译的菜单入口随设置变化
        kotlinx.coroutines.MainScope().launch {
            SettingsStore.settings
                .map { listOf(it.inputMenu, it.inputQuick, it.inputLang) }
                .distinctUntilChanged()
                .collect { MenuAliases.apply(this@YinanyiApp, SettingsStore.settings.value) }
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "同声翻译", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL_ID = "translation"
    }
}
