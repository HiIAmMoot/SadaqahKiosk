package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/**
 * Pins DiagnosticReporter.record's central safety claim: a diagnostic must
 * never break the recovery path reporting it, whatever throws while building
 * one. No Android imports here either, on purpose — MainActivity is where
 * this guard used to live, and MainActivity is unreachable from a JVM test.
 */
class DiagnosticReporterTest {

    private val appVersion = "1.4.0"
    private val atMs = 1_700_000_000_000L

    private val enabled = Settings(
        analyticsEnabled = true,
        kioskCode = "nl-gld-arnhem-nour_al_houda-01",
        installId = "11111111-2222-3333-4444-555555555555"
    )

    private fun record(
        settings: Settings? = enabled,
        kind: DiagnosticKind = DiagnosticKind.NETWORK_OUTAGE,
        detail: (() -> String?)? = { "{}" },
        affiliateKey: () -> String? = { null },
        append: (TelemetryEvent.Diagnostic) -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        DiagnosticReporter.record(
            settings = settings,
            appVersion = appVersion,
            kind = kind,
            occurredAtMs = atMs,
            detail = detail,
            affiliateKey = affiliateKey,
            append = append,
            onError = onError
        )
    }

    @Test
    fun aThrowingDetailBuilderDoesNotPropagateAndAppendIsNotCalled() {
        var appendCalls = 0
        record(detail = { error("detail boom") }, append = { appendCalls++ })
        assertEquals(0, appendCalls)
    }

    @Test
    fun aThrowingAffiliateKeySupplierDoesNotPropagate() {
        var appendCalls = 0
        record(affiliateKey = { error("affiliate boom") }, append = { appendCalls++ })
        assertEquals(0, appendCalls)
    }

    @Test
    fun aThrowingAppendDoesNotPropagateAndOnErrorSeesIt() {
        var seen: Throwable? = null
        record(
            append = { error("append boom") },
            onError = { seen = it }
        )
        assertNotNull("onError must be handed the failure append raised", seen)
        assertEquals("append boom", seen?.message)
    }

    @Test
    fun cancellationFromDetailIsRethrownRatherThanSwallowed() {
        assertThrows(CancellationException::class.java) {
            record(detail = { throw CancellationException("cancelled") })
        }
    }

    @Test
    fun cancellationFromAffiliateKeyIsRethrownRatherThanSwallowed() {
        assertThrows(CancellationException::class.java) {
            record(affiliateKey = { throw CancellationException("cancelled") })
        }
    }

    @Test
    fun cancellationFromAppendIsRethrownRatherThanSwallowed() {
        assertThrows(CancellationException::class.java) {
            record(append = { throw CancellationException("cancelled") })
        }
    }

    @Test
    fun analyticsOffCallsAppendZeroTimes() {
        var appendCalls = 0
        record(settings = enabled.copy(analyticsEnabled = false), append = { appendCalls++ })
        assertEquals(0, appendCalls)
    }

    @Test
    fun nullSettingsCallsAppendZeroTimes() {
        var appendCalls = 0
        record(settings = null, append = { appendCalls++ })
        assertEquals(0, appendCalls)
    }

    @Test
    fun theHappyPathCallsAppendExactlyOnceWithTheRightKind() {
        val appended = mutableListOf<TelemetryEvent.Diagnostic>()
        record(kind = DiagnosticKind.BLUETOOTH_WATCHDOG_FIRED, append = { appended += it })
        assertEquals(1, appended.size)
        assertEquals(DiagnosticKind.BLUETOOTH_WATCHDOG_FIRED, appended.single().kind)
    }
}
