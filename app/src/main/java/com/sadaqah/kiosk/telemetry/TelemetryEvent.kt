package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.util.UUID

object TelemetryTables {
    const val DONATIONS = "donation_events"
    const val DIAGNOSTICS = "diagnostic_events"
    const val ACTIVATIONS = "telemetry_activations"
}

/**
 * Who is reporting. [code] is the printed panel code and may be empty — a
 * deployment with no kiosk-code scheme is identified by [installId] alone.
 */
data class EventIdentity(
    val code: String,
    val installId: String,
    val appVersion: String
)

enum class DiagnosticSeverity {
    INFO, WARN, ERROR;

    val wire: String get() = name.lowercase()
}

/**
 * Closed here for the app's convenience only. On the wire `kind` is plain text
 * and the database must not constrain it — this list will grow, and a rejecting
 * constraint would stall the outbox until it drops real donations.
 */
enum class DiagnosticKind(val wire: String, val severity: DiagnosticSeverity) {
    CRASH("crash", DiagnosticSeverity.ERROR),
    RESTART_TRIGGERED("restart_triggered", DiagnosticSeverity.ERROR),
    SUMUP_REINIT_FAILED("sumup_reinit_failed", DiagnosticSeverity.ERROR),
    CARD_READER_CONNECT_FAILED("card_reader_connect_failed", DiagnosticSeverity.WARN),
    CARD_READER_PAGE_TIMEOUT("card_reader_page_timeout", DiagnosticSeverity.WARN),
    CHECKOUT_NO_READER("checkout_no_reader", DiagnosticSeverity.WARN),
    BLUETOOTH_WATCHDOG_FIRED("bluetooth_watchdog_fired", DiagnosticSeverity.WARN),
    NETWORK_OUTAGE("network_outage", DiagnosticSeverity.WARN),
    UPDATE_INSTALLED("update_installed", DiagnosticSeverity.INFO),
    UPDATE_INSTALL_FAILED("update_install_failed", DiagnosticSeverity.ERROR),
    UPDATE_ROLLBACK("update_rollback", DiagnosticSeverity.ERROR)
}

/**
 * One reportable thing that happened. Events know how to render their own
 * Supabase payload and nothing about how that payload is stored or sent.
 */
sealed class TelemetryEvent {
    abstract val id: String
    abstract val table: String
    abstract val identity: EventIdentity

    /** The row to insert, already redacted. */
    fun payloadJson(): String {
        val row = JsonObject()
        row.addProperty("id", id)
        row.addProperty("code", identity.code)
        row.addProperty("install_id", identity.installId)
        row.addProperty("app_version", identity.appVersion)
        addFields(row)
        return row.toString()
    }

    protected abstract fun addFields(target: JsonObject)

    data class Donation(
        override val identity: EventIdentity,
        val amountCents: Int,
        val currency: String,
        val occurredAtIso: String,
        override val id: String = UUID.randomUUID().toString()
    ) : TelemetryEvent() {
        override val table = TelemetryTables.DONATIONS
        override fun addFields(target: JsonObject) {
            target.addProperty("amount_cents", amountCents)
            target.addProperty("currency", currency)
            target.addProperty("occurred_at", occurredAtIso)
        }
    }

    data class Diagnostic(
        override val identity: EventIdentity,
        val kind: DiagnosticKind,
        val occurredAtIso: String,
        val detailJson: String? = null,
        val stackTrace: String? = null,
        val affiliateKey: String? = null,
        override val id: String = UUID.randomUUID().toString()
    ) : TelemetryEvent() {
        override val table = TelemetryTables.DIAGNOSTICS

        override fun addFields(target: JsonObject) {
            target.addProperty("occurred_at", occurredAtIso)
            target.addProperty("kind", kind.wire)
            target.addProperty("severity", kind.severity.wire)

            detailAsObject()?.let { target.add("detail", it) }

            val cleaned = TelemetryRedactor.truncate(
                TelemetryRedactor.scrub(stackTrace, affiliateKey)
            )
            if (cleaned != null) target.addProperty("stack_trace", cleaned)
        }

        /** Malformed detail is dropped rather than thrown: a broken diagnostic
         *  must never take down the code path that reported it. */
        private fun detailAsObject(): JsonObject? {
            val raw = detailJson ?: return null
            return try {
                JsonParser.parseString(raw) as? JsonObject
            } catch (e: Exception) {
                null
            }
        }
    }

    data class Activation(
        override val identity: EventIdentity,
        val activatedAtIso: String,
        val privacyPolicyUrl: String,
        val termsUrl: String,
        override val id: String = UUID.randomUUID().toString()
    ) : TelemetryEvent() {
        override val table = TelemetryTables.ACTIVATIONS
        override fun addFields(target: JsonObject) {
            target.addProperty("activated_at", activatedAtIso)
            target.addProperty("privacy_policy_url", privacyPolicyUrl)
            target.addProperty("terms_url", termsUrl)
        }
    }

    companion object {
        /** UTC, second precision, always Z-suffixed. */
        fun nowIso(): String = Instant.now().toString()
    }
}
