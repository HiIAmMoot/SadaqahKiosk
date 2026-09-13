package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings
import kotlinx.coroutines.CancellationException

/**
 * The guarded body of a diagnostic report, pulled out of MainActivity so the
 * guard itself — not just its call sites — is reachable from a JVM test.
 *
 * No Android imports and no assumption about which thread runs it: every
 * caller of [record] sits on a recovery path, and a diagnostic that breaks
 * the recovery it reports on is worse than no diagnostic. This is also the
 * synchronous variant a restart-triggered write needs, so it must not assume
 * it runs off the main thread the way MainActivity.reportDiagnostic does.
 */
object DiagnosticReporter {

    fun record(
        settings: () -> Settings?,
        appVersion: String,
        kind: DiagnosticKind,
        occurredAtMs: Long,
        detail: (() -> String?)?,
        affiliateKey: () -> String?,
        append: (TelemetryEvent.Diagnostic) -> Unit,
        onError: (Throwable) -> Unit = {}
    ) {
        try {
            // settings(), detail() and affiliateKey() are called inside the
            // guard, not before it: they run caller-supplied code (a volatile
            // read from another thread, JSON building) and any of them can
            // throw.
            val result = DiagnosticEvents.forKind(
                settings = settings(),
                appVersion = appVersion,
                kind = kind,
                occurredAtMs = occurredAtMs,
                detailJson = detail?.invoke(),
                affiliateKey = affiliateKey()
            )
            if (result is DiagnosticEventResult.Report) {
                append(result.event)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            onError(t)
        }
    }
}
