package com.yinanyi.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceAppsTest {
    @Test
    fun `defaults to all bilibili variants`() {
        assertEquals(BiliPackages, SourceApps.packagesFor(AppSettings()))
    }

    @Test
    fun `uses the chosen package only`() {
        assertEquals(listOf("com.google.android.youtube"), SourceApps.packagesFor(AppSettings(sourcePkg = "com.google.android.youtube")))
    }

    @Test
    fun `defaults keep auto stop at 30 minutes and auto open on`() {
        val d = AppSettings()
        assertEquals(30, d.idleStopMinutes)
        assertEquals(true, d.autoOpen)
    }
}
