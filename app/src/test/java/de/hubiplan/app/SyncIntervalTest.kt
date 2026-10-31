package de.hubiplan.app

import de.hubiplan.app.sync.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncIntervalTest {
    @Test
    fun ohnePushSoOftWieAndroidErlaubt() {
        // WorkManager erlaubt höchstens alle 15 Minuten
        assertEquals(15L, SyncWorker.intervalMinutes(allPush = false))
    }

    @Test
    fun mitPushSelten() {
        assertEquals(360L, SyncWorker.intervalMinutes(allPush = true))
    }
}
