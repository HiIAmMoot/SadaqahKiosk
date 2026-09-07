package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sadaqah.kiosk.model.Settings
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
) {
    companion object {
        /** Identity is built here rather than at each call site, so the manager's
         *  runtime snapshot and a donation event can never disagree about who this
         *  kiosk is. Both call this; neither maps the fields itself. */
        fun from(settings: Settings, appVersion: String) = EventIdentity(
            code = settings.kioskCode,
            installId = settings.installId,
            appVersion = appVersion
        )
    }
}

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

    /**
     * Scrubbing happens in the constructor rather than in [addFields], so
     * "redacted" is an invariant of a constructed Diagnostic rather than
     * something the serialiser remembers to do. The raw trace and the key are
     * constructor parameters, not properties, so they cannot be read back or
     * printed by a log statement.
     *
     * Deliberately not a data class: a generated toString would print the key.
     */
    class Diagnostic(
        override val identity: EventIdentity,
        val kind: DiagnosticKind,
        val occurredAtIso: String,
        detailJson: String? = null,
        stackTrace: String? = null,
        affiliateKey: String?,
        override val id: String = UUID.randomUUID().toString()
    ) : TelemetryEvent() {
        override val table = TelemetryTables.DIAGNOSTICS

        private val cleanedStackTrace: String? =
            TelemetryRedactor.truncate(TelemetryRedactor.scrub(stackTrace, affiliateKey))

        /** Scrubbed before parsing: `[redacted]` is safe inside a JSON string
         *  literal, and if scrubbing breaks the document the parse fails and the
         *  field is dropped — the safe direction. Oversized detail is dropped
         *  rather than truncated, since a cut mid-document stops it parsing. */
        private val cleanedDetail: JsonObject? = run {
            val raw = TelemetryRedactor.scrub(detailJson, affiliateKey)
            when {
                raw == null -> null
                raw.toByteArray(Charsets.UTF_8).size > TelemetryRedactor.MAX_TEXT_BYTES -> null
                else -> try {
                    JsonParser.parseString(raw) as? JsonObject
                } catch (_: Exception) {
                    null
                }
            }
        }

        override fun addFields(target: JsonObject) {
            target.addProperty("occurred_at", occurredAtIso)
            target.addProperty("kind", kind.wire)
            target.addProperty("severity", kind.severity.wire)
            cleanedDetail?.let { target.add("detail", it) }
            if (cleanedStackTrace != null) target.addProperty("stack_trace", cleanedStackTrace)
        }

        override fun toString(): String =
            "Diagnostic(id=$id, kind=${kind.wire}, occurredAt=$occurredAtIso)"
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
        fun nowIso(): String = Instant.now().toString()
    }
}
