package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings

/**
 * Records a crash, then gets out of the way.
 *
 * No Android imports, so the chaining behaviour — the part that must never
 * regress — is exercised on the JVM rather than trusted.
 */
class KioskCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val outbox: TelemetryOutbox,
    private val settings: () -> Settings?,
    private val affiliateKey: () -> String?,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val exitProcess: (Int) -> Unit = { Runtime.getRuntime().exit(it) }
) : Thread.UncaughtExceptionHandler {

    /** Set before recording and never cleared: on Android any uncaught
     *  exception reaching the default handler is fatal, so there is no second
     *  crash worth reporting. @Volatile gives the visibility a same-thread
     *  re-entry needs; two threads crashing at once can both record, which is
     *  two wanted rows the outbox tolerates. */
    @Volatile
    private var recording = false

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            if (!recording) {
                recording = true
                // The supplier calls are inside the guard because they can throw
                // too — settings() reads a holder another thread is writing.
                val result = DiagnosticEvents.crash(
                    settings = settings(),
                    appVersion = appVersion,
                    thread = thread,
                    throwable = throwable,
                    affiliateKey = affiliateKey(),
                    occurredAtMs = clock()
                )
                if (result is DiagnosticEventResult.Report) {
                    val event = result.event
                    outbox.append(event.id, event.table, event.payloadJson())
                }
            }
        } catch (_: Throwable) {
            // Deliberately swallowed, and deliberately not logged: this runs in
            // a dying process where a logging call is one more thing that can
            // fail before the chain below.
        } finally {
            // The single most important line in this phase. hardRestart and the
            // update watchdog both depend on normal crash behaviour, and the
            // watchdog's whole rollback decision is "did the new build write a
            // heartbeat before dying".
            val chain = previous
            if (chain != null) {
                chain.uncaughtException(thread, throwable)
            } else {
                // Returning here would end the thread, not the process, leaving
                // an unresponsive kiosk the watchdog cannot detect either.
                // Runtime.exit runs shutdown hooks and can in principle hang
                // where halt() cannot; exit matches hardRestart and this branch
                // is near-unreachable, since RuntimeInit installs a default
                // handler before any app code runs.
                exitProcess(2)
            }
        }
    }
}
