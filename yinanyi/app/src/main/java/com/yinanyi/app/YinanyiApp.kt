package com.yinanyi.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class YinanyiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "同声翻译", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL_ID = "translation"
    }
}
