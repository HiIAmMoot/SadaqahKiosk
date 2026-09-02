# Kiosk Telemetry — Design

**Date:** 2026-09-02
**Target release:** `1.3.6-preview`
**Status:** Approved for planning

---

## Purpose

Sadaqah Kiosk units are deployed unattended at customer sites. Today the only way
to learn that a kiosk has stopped taking donations, or why, is for the customer to
report it. This adds an optional reporting path so the vendor can see donation
throughput and diagnose failures across the fleet without a site visit.

Two kinds of data are collected:

- **Donation events** — amount, timestamp, kiosk. No donor information of any kind.
- **Diagnostic events** — crashes, the recovery conditions the app already detects,
  and update outcomes.

## Constraints

1. **The donation flow is sacred.** Telemetry must never block, slow, or crash a
   payment. Enqueue is a local append; all network work happens elsewhere.
2. **Nothing ships configured.** The open-source app contains no endpoint and no
   credentials. It transmits nothing until an operator supplies both.
3. **Offline is the normal case.** These kiosks routinely lose connectivity for
   hours. Events queue on disk and flush opportunistically.
4. **Bounded disk.** A kiosk offline for a month must not fill its storage.
5. **No donor PII, ever.** Not collected, so it cannot leak.

## Ownership and privacy posture

The database is the **vendor's** — one Supabase project across all customers, used
for support, monitoring and revenue evidence.

This means the vendor holds each customer's donation revenue data. That is not
donor PII and carries a light GDPR burden, but it *is* commercially sensitive to
the customer. The design treats that as a disclosure problem, not an encryption
problem:

- The feature is visible and switchable in the kiosk's own settings.
- First use requires explicit agreement to a privacy policy and terms, recorded
  locally and server-side.
- The README documents exactly which fields are transmitted.

An operator can revoke consent or change the credentials at any time. This is not
a weakness to design around — the credentials live on the operator's device, so
that control exists whether or not the UI admits it. Surfacing it costs nothing
and is the only defensible posture for an AGPL project.

---

## Security model

### The credential can leak. Design for it.

The Supabase key is stored encrypted with an AES key in AndroidKeyStore, with the
ciphertext in `SharedPreferences`. The Keystore key is hardware-backed and
non-exportable, which defeats `adb pull`, cloud backup extraction, and offline
attack on a stolen device.

It does **not** defeat someone with root on a running kiosk. Any credential the
app can decrypt in order to use, an attacker in that position can also obtain.

Therefore the real boundary is server-side:

- Use a Supabase **anon/publishable** key, never a service-role key.
- Row-level security permits **INSERT only** on the three tables. No SELECT,
  UPDATE or DELETE for the anon role.
- A leaked key can therefore append rows and nothing else. It cannot read any
  customer's data.
- Rate-limit inserts server-side so a leaked key means spam, not a denial of
  service.

### Fleet provisioning: password-encrypted secrets in the export file

A Keystore key is per-device, so credentials cannot be exported as Keystore
ciphertext and imported elsewhere. Rather than exporting them in the clear, the
export encrypts them under a **password the operator types at export time**, and
import requires that same password.

This keeps fleet cloning practical — configure one kiosk, export, import onto the
rest — without the exported file being a bearer secret. A leaked export is then
only as weak as the chosen password, rather than immediately usable.

**Scope: this covers the SumUp affiliate key too.** The affiliate key is exported
in plaintext today; once this mechanism exists there is no reason to keep a second,
weaker path for a secret of the same sensitivity. Both secrets move into one
encrypted blob.

**File format.** Non-secret settings stay in plaintext so the file remains
inspectable and diffable. Secrets go into a single encrypted envelope:

```json
{
  "settings": { ... },
  "secrets": {
    "v": 1,
    "kdf": "PBKDF2WithHmacSHA256",
    "iterations": 600000,
    "salt": "<base64, 16 random bytes>",
    "iv": "<base64, 12 random bytes>",
    "ciphertext": "<base64, AES-256-GCM, 128-bit tag>"
  }
}
```

The plaintext inside the envelope is a JSON object of secret key/values, so new
secrets can be added later without another format change. `v` allows the KDF
parameters to be raised in future without breaking old files.

**Parameters.** PBKDF2-HMAC-SHA256 at 600,000 iterations (OWASP guidance),
AES-256-GCM. Derivation takes a few seconds on a Lenovo M9 — acceptable for a
one-off operation, but it **must run off the UI thread** with a progress
indicator, or it will read as a frozen kiosk.

**Import behaviour:**

- Decrypt and validate *before* applying anything. A wrong password must leave the
  device untouched — the current import applies settings first and reads the
  affiliate key afterwards, which would half-apply on failure.
- A wrong password fails GCM authentication and reports plainly. It is not
  silently treated as "no secrets".
- Importing without the password is still allowed as an **explicit** separate
  action ("import settings only"), for cloning theme and configuration without
  credentials. Never a silent downgrade.

**Backward compatibility.** Exports created before this change carry
`affiliateKey` as a plaintext field. Import must continue to accept that shape so
existing operator backups keep working. New exports always use the encrypted
envelope.

This narrows the window but does not change where the real boundary sits: a
determined attacker with root on a running kiosk still reaches the credential, so
the insert-only RLS policy remains what actually contains the damage.

### Redaction

`detail` and `stack_trace` are scrubbed before being written to the outbox, not at
upload time — an unscrubbed value must never reach disk. Redacted: the SumUp
affiliate key (exact match), and any token-shaped run of 20+ base64/hex
characters. This rule gets a dedicated unit test.

---

## Data model

Three Supabase tables. All timestamps UTC. All inserts idempotent.

### `donation_events`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | Generated **client-side** for idempotency |
| `kiosk_id` | uuid | Stable per install, random at first run |
| `kiosk_name` | text | Operator-set name; identifies the customer |
| `amount_cents` | integer | Never a float |
| `currency` | text | `EUR` / `USD` / `GBP` |
| `occurred_at` | timestamptz | |
| `app_version` | text | |

No SumUp transaction code, no card data, no donor attributes. If reconciliation
against SumUp is wanted later, adding `tx_code` is a deliberate future decision,
not an oversight.

### `diagnostic_events`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | Client-side |
| `kiosk_id` | uuid | |
| `kiosk_name` | text | |
| `app_version` | text | |
| `occurred_at` | timestamptz | |
| `severity` | text | `info` / `warn` / `error` |
| `kind` | text | Closed set, below |
| `detail` | jsonb | Structured per kind; nullable |
| `stack_trace` | text | Crashes only, truncated to 8 KB |

`jsonb` rather than text so each kind carries its own shape without a column per
variant.

**Kinds:**

| Kind | Severity | Emitted when |
|---|---|---|
| `crash` | error | Uncaught exception |
| `restart_triggered` | error | `RestartManager` returns `RESTART` |
| `sumup_reinit_failed` | error | Login result fails after reinit |
| `card_reader_connect_failed` | warn | Reader page returns without a connection |
| `card_reader_page_timeout` | warn | 10-minute pairing timeout fires |
| `checkout_no_reader` | warn | Checkout fails with no reader connected |
| `bluetooth_watchdog_fired` | warn | Auto re-enable triggered after 60 s off |
| `network_outage` | warn | Outage exceeded `longDowntimeThresholdSec` |
| `update_installed` | info | New build's first run after replacement |
| `update_install_failed` | error | `PackageInstaller` returned failure |
| `update_rollback` | error | Watchdog restored the backup APK |

### `consent_events`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `kiosk_id` | uuid | |
| `kiosk_name` | text | |
| `agreed_at` | timestamptz | |
| `privacy_policy_url` | text | The URL as shown when agreed |
| `terms_url` | text | |
| `app_version` | text | |

Consent is recorded server-side so agreement survives a device wipe or
re-provisioning.

### Idempotency

Every insert uses the client-generated `id` with
`Prefer: resolution=ignore-duplicates`. Retrying after an ambiguous network
failure is therefore safe.

This matters more than usual: these kiosks lose connectivity mid-request
routinely, and a double-counted donation produces revenue figures that are
silently wrong — worse than having none.

### Reporting a successful update

A successful install kills the process, so the new build reports it. Before
committing an install, `UpdateManager` records the outgoing version. On the first
run after `MY_PACKAGE_REPLACED`, the new build enqueues `update_installed` with
`{from_version, to_version}`. The rollback path in `UpdateWatchdogReceiver`
enqueues `update_rollback` instead.

---

## Architecture

New package `com.sadaqah.kiosk.telemetry`:

```
telemetry/
├── TelemetryEvent.kt       # Sealed event types + JSON serialisation
├── TelemetryOutbox.kt      # Append-only JSONL queue: append, peek batch, remove, cap
├── TelemetryRedactor.kt    # Scrubs secrets before anything reaches disk
├── TelemetryCredentials.kt # AndroidKeyStore-encrypted Supabase URL + anon key
├── SecretsEnvelope.kt      # Password-based encrypt/decrypt for the settings export
├── TelemetryGate.kt        # Pure: may we flush right now?
├── TelemetryUploader.kt    # Supabase REST insert over HttpURLConnection
└── TelemetryManager.kt     # Orchestrator: enqueue, schedule, flush
```

This mirrors the existing `recovery/` and `update/` split: pure decision logic
isolated from Android dependencies so it can be unit-tested, with a thin
orchestrator owning timing and I/O.

### Why a dedicated outbox rather than reusing `DonationHistory`

`DonationHistory` is user-facing and has a `clearAll()` wired to a button on the
History screen. If telemetry read from it, an operator clearing their history
would silently destroy unsent telemetry; conversely telemetry retention would
fight the history's year-sharded retention. They are separate concerns with
separate lifetimes, so they get separate storage.

### Queue

Single append-only `filesDir/telemetry/outbox.jsonl`, one JSON object per line,
every event kind sharing the file. Batch reads of 100. On successful upload the
uploaded ids are removed by rewriting the file.

**Caps:** 5,000 events or 30 days, whichever binds first; oldest dropped. Enforced
on append, so an offline kiosk degrades by losing the oldest telemetry rather than
filling its disk.

### Flush scheduling

Flush is attempted on:

- network restored (existing connectivity poll in `MainActivity`)
- the existing 02:00 maintenance window, after update maintenance
- an opportunistic 30-minute timer while idle

Failures back off exponentially to a 60-minute ceiling. No `WorkManager`
dependency — the codebase schedules with `lifecycleScope` and `AlarmManager`, and
the 02:00 window already exists.

### Gate

`TelemetryGate` is pure and answers one question: given *enabled*, *configured*,
*consented*, *network available*, *backoff deadline* and *queue depth*, should we
flush now? Keeping this separate from the manager makes the policy testable
without a device.

### Crash handler

`Thread.setDefaultUncaughtExceptionHandler` writes the event synchronously, then
**chains to the previously installed handler**. It must not swallow the crash —
`hardRestart` and the update watchdog both depend on normal crash behaviour.

---

## Configuration and consent

### `Settings` additions

| Field | Default | Notes |
|---|---|---|
| `analyticsEnabled` | `false` | Master switch |
| `analyticsConsentAgreedAtMs` | `0L` | 0 = not yet agreed |
| `analyticsPrivacyPolicyUrl` | `""` | |
| `analyticsTermsUrl` | `""` | |
| `kioskId` | `""` | Random UUID minted on first run |

`kioskId` is a random UUID, never a hardware identifier — `ANDROID_ID` and friends
carry restrictions and privacy baggage for no benefit here.

Credentials are **not** in `Settings`; they live in `TelemetryCredentials` so they
are never written to the settings JSON except through the deliberate export path,
where they are password-encrypted.

Migration follows the existing pattern in `MainActivity.onCreate`: GSON ignores
Kotlin defaults for absent fields, so absent keys are detected against the raw
JSON string and defaults restored.

### `AnalyticsSettingsScreen`

Reached from `SettingsScreen`, a sibling of Donation History behind the same
biometric gate:

- Master toggle
- Supabase URL, anon key (masked)
- Test connection
- Status: queued events, last successful upload, last error
- Privacy policy URL, terms URL
- Consent state, with revoke (revoking disables telemetry)

### `ConsentScreen`

Blocking, shown after the first successful login when analytics is **enabled and
configured** and consent has not been recorded. It summarises exactly what is
collected and links to both policies.

Under lock-task mode a browser cannot be launched, so each URL renders as **text
plus a QR code** for the operator's phone. Accepting writes
`analyticsConsentAgreedAtMs` and enqueues a `consent_events` row. Declining leaves
telemetry off until someone re-enables it in settings.

---

## Testing

Unit-testable without a device:

- `TelemetryOutbox` — append, batch read, remove, count cap, age eviction,
  malformed-line tolerance (temp directory)
- `TelemetryRedactor` — affiliate key and token-shaped strings never survive
- `TelemetryEvent` — JSON round-trip for every kind
- `TelemetryGate` — every combination of enabled/configured/consented/network/backoff
- `SecretsEnvelope` — encrypt/decrypt round-trip; wrong password fails cleanly;
  tampered ciphertext fails GCM authentication; a legacy plaintext export still
  imports; a failed import leaves settings untouched

Not unit-testable, and must be labelled as such:

- `TelemetryUploader` against real Supabase — needs a live project
- Keystore encryption — needs a device
- The crash handler firing on a real uncaught exception

The uploader and Keystore paths are verified manually on a preview kiosk before
the feature is trusted.

---

## Documentation

- README privacy section **rewritten, not softened**: the shipped app has no
  endpoint and transmits nothing by default; the optional feature, the exact
  fields, and the consent requirement are documented plainly.
- Features list gains the optional analytics entry.
- New Analytics section: setup, the RLS policy operators must apply, and the exact
  schema.
- Export/import documentation updated: secrets are now password-encrypted, the
  password is unrecoverable if lost, and pre-existing plaintext exports still
  import.

---

## Build order

Each phase leaves the app shippable.

1. **Outbox, events, redaction, gate.** Inert — nothing enqueues, nothing uploads.
   Fully unit-tested.
2. **Credentials + password-encrypted export + uploader + `AnalyticsSettingsScreen`.**
   Configurable and testable by hand; still nothing enqueues. The export change
   also moves the affiliate key into the encrypted envelope, so it ships with
   legacy-import compatibility.
3. **Instrumentation.** Donation events, diagnostic events, crash handler,
   update outcome reporting.
4. **Consent gate + translations.** `ConsentScreen` across eight languages.
   Telemetry stays inert until consent exists.
5. **Documentation.**

Translations are the quiet cost: two new screens' worth of copy across eight
languages in `Translations.kt`.

## Out of scope

- Any dashboard or reporting UI over the collected data
- Per-customer database isolation or customer read access
- Periodic health heartbeats (considered, rejected as too much volume for now)
- SumUp transaction reconciliation
