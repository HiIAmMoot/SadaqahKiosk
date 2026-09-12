package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KioskCrashHandlerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val appVersion = "1.4.0"
    private val atMs = 1_700_000_000_000L

    private val enabled = Settings(
        analyticsEnabled = true,
        kioskCode = "nl-gld-arnhem-nour_al_houda-01",
        installId = "11111111-2222-3333-4444-555555555555"
    )

    /** Records what it was handed, so a test can prove the chain ran with the
     *  same thread and throwable rather than merely that something ran. */
    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        var calls = 0
        var lastThread: Thread? = null
        var lastThrowable: Throwable? = null
        override fun uncaughtException(t: Thread, e: Throwable) {
            calls++
            lastThread = t
            lastThrowable = e
        }
    }

    private fun outbox(file: File = temp.newFile("outbox.jsonl")) =
        TelemetryOutbox(file, clock = { atMs })

    /**
     * There is no mocking library and TelemetryOutbox is final, so "append
     * throws" is produced with the real class over an impossible path: the
     * parent is a regular file, so mkdirs() returns false and appendText
     * throws FileNotFoundException.
     */
    private fun unwritableOutbox() =
        TelemetryOutbox(File(temp.newFile("blocker"), "outbox.jsonl"), clock = { atMs })

    private fun handler(
        previous: Thread.UncaughtExceptionHandler?,
        box: TelemetryOutbox = outbox(),
        settings: () -> Settings? = { enabled },
        affiliateKey: () -> String? = { null },
        exitProcess: (Int) -> Unit = { fail("must not exit while a previous handler exists") }
    ) = KioskCrashHandler(previous, box, settings, affiliateKey, appVersion, { atMs }, exitProcess)

    @Test
    fun chainsToThePreviousHandlerWithTheSameThreadAndThrowable() {
        val previous = RecordingHandler()
        val thread = Thread("payment-worker")
        val boom = IllegalStateException("boom")
        handler(previous).uncaughtException(thread, boom)
        assertEquals(1, previous.calls)
        assertSame(thread, previous.lastThread)
        assertSame(boom, previous.lastThrowable)
    }

    @Test
    fun appendsExactlyOneRowOnACrash() {
        val file = temp.newFile("outbox.jsonl")
        handler(RecordingHandler(), box = outbox(file))
            .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(1, outbox(file).size())
    }

    /**
     * The decisive test. hardRestart and the update watchdog both depend on
     * normal crash behaviour, so a failure to record must never cost the chain.
     */
    @Test
    fun chainsEvenWhenRecordingThrows() {
        val previous = RecordingHandler()
        handler(previous, box = unwritableOutbox())
            .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals("a failed append must not swallow the crash", 1, previous.calls)
    }

    @Test
    fun chainsEvenWhenTheSettingsSupplierThrows() {
        val previous = RecordingHandler()
        handler(previous, settings = { error("settings read failed") })
            .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(1, previous.calls)
    }

    @Test
    fun chainsAndRecordsNothingWhenAnalyticsIsOff() {
        val previous = RecordingHandler()
        val file = temp.newFile("outbox.jsonl")
        handler(previous, box = outbox(file), settings = { enabled.copy(analyticsEnabled = false) })
            .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(1, previous.calls)
        assertEquals(0, outbox(file).size())
    }

    @Test
    fun chainsAndRecordsNothingBeforeAnIdentityIsLoaded() {
        val previous = RecordingHandler()
        val file = temp.newFile("outbox.jsonl")
        handler(previous, box = outbox(file), settings = { null })
            .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(1, previous.calls)
        assertEquals(0, outbox(file).size())
    }

    /**
     * Returning from uncaughtException ends the thread, not the process. On the
     * main thread that leaves a kiosk running, unresponsive and showing its last
     * frame — which the watchdog's heartbeat cannot see either.
     */
    @Test
    fun exitsRatherThanReturningWhenThereIsNoPreviousHandler() {
        val exitCodes = mutableListOf<Int>()
        KioskCrashHandler(null, outbox(), { enabled }, { null }, appVersion, { atMs }) {
            exitCodes += it
        }.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(listOf(2), exitCodes)
    }

    /**
     * Chaining is deliberately unguarded, so a re-entrant call chains too and
     * the outer finally chains again. On a device the first chain kills the
     * process, so the second is only observable here.
     */
    @Test
    fun reEntrantInvocationRecordsOnceAndAlwaysChains() {
        val previous = RecordingHandler()
        val file = temp.newFile("outbox.jsonl")
        lateinit var handler: KioskCrashHandler
        var reentered = false
        val reentrantOutbox = object {
            fun trigger() {
                if (!reentered) {
                    reentered = true
                    handler.uncaughtException(Thread.currentThread(), IllegalStateException("again"))
                }
            }
        }
        handler = KioskCrashHandler(
            previous, outbox(file),
            { reentrantOutbox.trigger(); enabled },
            { null }, appVersion, { atMs }, { fail("must not exit") }
        )
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals("the re-entrant call must not record a second row", 1, outbox(file).size())
        assertEquals(
            "chaining is never suppressed: the re-entrant call's finally chains, and so does the outer one",
            2,
            previous.calls
        )
    }
}
