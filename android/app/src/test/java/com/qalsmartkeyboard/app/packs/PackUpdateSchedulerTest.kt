package com.qalsmartkeyboard.app.packs

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class PackUpdateSchedulerTest {
    @Test
    fun wifiOnlyUnlessTheUserAllowsMobileData() {
        assertEquals(NetworkType.UNMETERED, PackUpdateScheduler.requiredNetwork(false))
        assertEquals(NetworkType.CONNECTED, PackUpdateScheduler.requiredNetwork(true))
    }
}
