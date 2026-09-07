package com.sadaqah.kiosk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

class ClearCredentialsTest {

    @get:Rule val temp = TemporaryFolder()

    /**
     * Queued rows name a kiosk. An operator clearing the destination has withdrawn
     * the basis for holding them, so the queue goes with the credentials — leaving
     * it would strand identified data on disk with nothing left that could ever
     * send it.
     */
    @Test
    fun clearingCredentialsAlsoDropsTheQueueAndTheStatus() {
        val outbox = TelemetryOutbox(temp.newFile())
        outbox.append("a", TelemetryTables.DONATIONS, """{"id":"a"}""")
        val credentials = TelemetryCredentials(InMemorySecretStore())
        credentials.save("https://abc.supabase.co", "publishable-key")
        val statusStore = InMemoryStatusStore()
        statusStore.write(TelemetryStatus(lastError = "HTTP 503", consecutiveFailures = 3, droppedCount = 9))

        TelemetryTeardown.clearEverything(credentials, outbox, statusStore)

        assertFalse("the destination is gone", credentials.isConfigured())
        assertEquals("no identified row may outlive the destination", 0, outbox.size())
        assertNull("a stale error would describe a destination that no longer exists",
            statusStore.read().lastError)
        assertEquals(0, statusStore.read().consecutiveFailures)
        // Plan Task 5 Step 3: nothing pinned this before. It does reset today
        // (via the default TelemetryStatus()), but a future teardown that
        // preserved the count would have stayed green without this assertion.
        assertEquals("a dropped count must not survive a teardown", 0, statusStore.read().droppedCount)
    }

    @Test
    fun clearingIsSafeWhenNothingWasEverConfigured() {
        val outbox = TelemetryOutbox(temp.newFile())
        TelemetryTeardown.clearEverything(
            TelemetryCredentials(InMemorySecretStore()), outbox, InMemoryStatusStore()
        )
        assertEquals(0, outbox.size())
    }
}
