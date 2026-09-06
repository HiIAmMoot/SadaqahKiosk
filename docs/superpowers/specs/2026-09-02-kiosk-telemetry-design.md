# Kiosk Telemetry — Design

**Date:** 2026-09-02
**Revised:** 2026-09-04
**Target release:** `1.3.6-preview`
**Status:** Approved for planning

**Revision 2026-09-04** — reconciled with the kiosk code format and the customer
terms. Identity changed from a device-minted `kiosk_id` to `code` + `install_id`;
`kiosk_name` removed; `consent_events` became `telemetry_activations` and the
blocking consent gate became a disclosure at configuration; the schema is now
published as a reference others copy; privacy wording corrected from *anonymous* to
*identified*.

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

The default database is the **vendor's** — one Supabase project across all
customers, used for support, monitoring and revenue evidence. It is not the only
possible destination: the app is AGPL and must run pointed at anyone's Supabase,
so what follows describes the vendor deployment while the software stays
destination-agnostic.

The vendor deployment holds each customer's donation revenue data. That is not
donor PII, and the customer is a legal person rather than a data subject, so the
GDPR burden is light — but it *is* commercially sensitive to the customer. The
design treats that as a disclosure problem, not an encryption problem:

- The feature is visible and switchable in the kiosk's own settings.
- Configuring a destination surfaces a **disclosure** of what will be sent and
  where, recorded as an activation.
- The README documents exactly which fields are transmitted.

### The data is identified, not anonymous

Every event carries the kiosk code, which names the organisation, its city and its
province in plaintext. Any wording calling this anonymous is false, and the app is
open source, so a customer can read the claim and check it. The spec, the README
and the disclosure screen all say **identified**.

A deployment configured without a kiosk code is genuinely pseudonymous — only
`install_id` identifies it. The wording has to cover both cases honestly rather
than describing only the vendor's.

### Disclosure, not consent

An earlier revision gated first use behind agreement to a privacy policy and
terms. That was wrong on three counts and has been removed:

1. **Consent is not the legal basis.** The processing rests on contract
   performance and legitimate interest. Presenting it as consent implies a right
   of withdrawal that does not exist.
2. **Vendor provisioning would forge the record.** Most kiosks are configured by
   the vendor before delivery, so the person tapping *I agree* would be the vendor,
   on hardware the customer has not seen — producing a row asserting an agreement
   that never happened. Worse than no row.
3. **It is meaningless for a fork.** A third-party operator is their own
   controller; asking them to accept the vendor's privacy policy governs nothing.

What replaces it is a disclosure shown when an operator configures a destination.
Nothing is gated — supplying an endpoint and credentials *is* the decision, and
the app transmitting nothing without them is the property doing the protective
work. An operator can change or clear the credentials at any time; that control
exists whether or not the UI admits it, since the credentials live on their device.

Deactivation is deliberately not recorded. Clearing the endpoint removes the
transport that would carry the notice, and it would not change the support answer.

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
affiliate key (exact match), and any token-shaped run of 32+ base64/hex
characters excluding `/` so file paths survive. This rule gets a dedicated unit test.

---

## Data model

Three Supabase tables. All timestamps UTC. All inserts idempotent.

This schema is **published in the app README as a reference** a self-hoster can
paste into their own project. The vendor backend implements it and adds what only
it needs — `kiosks`, `kiosk_codes`, `organisations`, and a server-side
`received_at` — on top of this shared base.

### How a kiosk identifies itself

Two fields, doing different jobs:

- **`code`** — the human-readable identifier printed on the kiosk's panel, e.g.
  `nl-gld-arnhem-nour_al_houda-01`. Set at provisioning, survives a tablet swap,
  and is what an operator can read out during a support call. **Optional**: a fork
  with no code scheme leaves it empty, so the reference schema must not mark it
  `not null`.
- **`install_id`** — a random UUID minted on the device at first run. Changes on a
  factory reset or hardware replacement.

Together they say something neither says alone: *same code, new `install_id`* means
that unit was re-provisioned or its tablet was replaced.

An earlier revision had a single device-minted `kiosk_id` as identity. That was
wrong — a factory reset silently produced a new one and the fleet lost continuity
with no signal. Backend-minted UUIDs are equally unusable, since the app never sees
one. The app sends the code; the backend resolves it.

`kiosk_name` was removed. It duplicated what the code already carries, was free
text an operator could change at any time, and put customer-identifying data in
tables that do not need it.

### `donation_events`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | Generated **client-side** for idempotency |
| `code` | text | As printed on the panel at the time; may be empty |
| `install_id` | uuid | Device-minted; changes on reset or tablet swap |
| `amount_cents` | integer | Never a float |
| `currency` | text | `EUR` / `USD` / `GBP` |
| `occurred_at` | timestamptz | Device clock — see below |
| `app_version` | text | |

The event records the code **as printed at the time**, not the kiosk's current
code. After a transfer and reissue, historical donations stay attributed to the
organisation that actually received them, with nobody reasoning about effective
dates. The consequence is that any query must join through the alias table
(`kiosk_codes`) rather than `kiosks.code`, or every donation taken before a
reissue silently disappears from the total.

`occurred_at` is the device clock, and a kiosk offline for days with a drifted RTC
will report confidently wrong times. The vendor backend adds
`received_at timestamptz default now()` so skew is detectable.

No SumUp transaction code, no card data, no donor attributes. If reconciliation
against SumUp is wanted later, adding `tx_code` is a deliberate future decision,
not an oversight.

### `diagnostic_events`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | Client-side |
| `code` | text | May be empty |
| `install_id` | uuid | |
| `app_version` | text | |
| `occurred_at` | timestamptz | |
| `severity` | text | `info` / `warn` / `error` |
| `kind` | text | Plain text — see below |
| `detail` | jsonb | Structured per kind; nullable |
| `stack_trace` | text | Crashes only, truncated to 8 KB |

`jsonb` rather than text so each kind carries its own shape without a column per
variant.

**`kind` and `severity` must be plain text — never an enum or a `CHECK`.** The
list below is the current contents of a text column, not a closed set the database
enforces, and it will grow. A constraint here is a permanent-rejection trap: the
day the app ships a twelfth kind, every event from an updated kiosk is refused, the
outbox retries forever, and the 5,000-event cap starts dropping real donations to
make room.

That generalises to a rule for every table the app writes: **no constraint may
reject a well-formed row.** No foreign key on `code`, no regex `CHECK` on the event
tables. A code mistyped at provisioning should land as one inspectable bad row, not
silently block that kiosk forever. Validate on reconcile, never at insert.

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

### `telemetry_activations`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `code` | text | May be empty |
| `install_id` | uuid | |
| `activated_at` | timestamptz | |
| `privacy_policy_url` | text | As shown at activation |
| `terms_url` | text | |
| `app_version` | text | |

A record that reporting was switched on — **not** a consent record. No version of
the terms is stored: disclosure describes what the software does *now*, so there is
no moment to preserve, and a link always resolves to the current text.

Expect **more than one row per kiosk**. A re-provisioned unit gets a new
`install_id` and activates again, which is correct and useful; nothing may assume
uniqueness on `code`.

There is no deactivation counterpart. Clearing the endpoint removes the transport
that would carry the notice, and its absence does not change the support answer.

### Idempotency

*Corrected against the live backend on 2026-09-06 — the original version of this
section, which called for `Prefer: resolution=ignore-duplicates`, was wrong and
would have been re-implemented by anyone reading it. See below.*

Every insert sends `Prefer: return=minimal` and nothing else in that header.
`resolution=ignore-duplicates` is deliberately not sent: PostgREST implements it
as an upsert, and the upsert path requires `SELECT` on the target table. The
device key is granted `INSERT` only, on purpose — every kiosk ships the same
key, so it is assumed leaked, and withholding `SELECT` is what keeps a leaked
key from reading any donation row in the fleet. Requesting
`resolution=ignore-duplicates` (or `return=representation`, which is a `SELECT`
for the same reason) against a key with no `SELECT` grant fails outright, with
`401`.

The idempotency mechanism is the client-generated `id` itself: it is the
primary key on the target table, so an insert that repeats an `id` already
stored collides on that key rather than creating a duplicate row. The server's
answer to a collision is `409` (`SQLSTATE 23505`), and that is the signal a
retry was absorbed — on a single-row request, `409` means this exact row is
already safely stored, which is success, not a rejection. A collision inside a
multi-row request is different: PostgREST executes a batch insert as one
statement, so any row colliding aborts the whole statement and the other rows
in it never landed. A `409` on a batch therefore falls back to sending its rows
individually, the same way a row-level refusal does, so each row's true outcome
is resolved on its own.

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
*activated*, *network available*, *backoff deadline* and *queue depth*, should we
flush now? Keeping this separate from the manager makes the policy testable
without a device.

Password-based encryption for the settings export shipped separately as
`settingsio/SecretsCrypto.kt` — it protects the SumUp affiliate key whether or not
telemetry is ever configured, so it does not belong under `telemetry/`.

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
| `analyticsActivatedAtMs` | `0L` | 0 = disclosure not yet shown |
| `analyticsPrivacyPolicyUrl` | `""` | |
| `analyticsTermsUrl` | `""` | |
| `kioskCode` | `""` | Optional; printed panel code |
| `installId` | `""` | Random UUID minted on first run |

`installId` is a random UUID, never a hardware identifier — `ANDROID_ID` and
friends carry restrictions and privacy baggage for no benefit here.

`kioskCode` is **optional and its validation is advisory**. A fork has no
kiosk-code scheme and never will, so requiring one would mean the app only runs for
this vendor. The regex is a convention this vendor follows, not a property of the
software: warn at provisioning on a non-conforming value, then accept it. With no
code configured, `installId` alone identifies the device — which is all a
single-site operator needs.

The regex lives as a constant in the app with a comment naming the site repository
as the convention's source, since the two cannot import from each other.

`analyticsActivatedAtMs` exists only so the disclosure is not re-shown on every
visit to the Analytics screen. It is not proof of anything.

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
- Kiosk code (optional, advisory validation)
- Supabase URL, anon key (masked)
- Test connection — see below
- Status: queued events, last successful upload, last error
- Privacy policy URL, terms URL
- Activation state, and a control to clear the credentials

**Test connection and activation are the same action**, and must not be built
twice. Entering a destination writes a `telemetry_activations` row, which flushes
immediately; a successful insert is the confirmation shown to the operator. That
gives activation a second job as a configuration smoke test — a mistyped endpoint
is caught at the bench rather than three weeks later when someone notices a kiosk
that never reported.

### `DisclosureScreen`

**Not blocking, and not a consent gate.** Shown when an operator configures a
destination, because supplying an endpoint and credentials is already the decision.
It states:

- What is sent: donation amount, time and kiosk code; diagnostics; app version.
- What is never sent: donor names, card data, SumUp transaction identifiers.
- Where it goes — the endpoint just entered, shown back to them.
- That it can be turned off by clearing the endpoint.
- A link to the privacy statement, as the policy applicable when the destination is
  the vendor's, not as something the operator is agreeing to.

The copy must be true in a fork as well as a vendor deployment, which rules out
naming Sadaqah Kiosk as the recipient.

Under lock-task mode a browser cannot be launched, so each URL renders as **text
plus a QR code** for the operator's phone. This constraint came from the consent
screen and survives intact.

Showing it writes `analyticsActivatedAtMs` so it is not repeated on every visit.

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
  fields, and the fact that the data is **identified rather than anonymous** are
  documented plainly.
- Features list gains the optional analytics entry.
- New Analytics section carrying the **reference schema** — the three tables, the
  diagnostic kinds, the insert-only RLS policy, and the code regex — as something a
  self-hoster can paste into their own Supabase project. The app repository is the
  home for this definition, because the app is the artefact people copy; the vendor
  backend is one implementation of it.
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
4. **Disclosure screen + translations.** `DisclosureScreen` across eight languages.
5. **Documentation.**

Translations are the quiet cost: two new screens' worth of copy across eight
languages in `Translations.kt`. Removing the consent gate did **not** remove this —
17.2 of the customer terms makes the disclosure a contractual commitment, so the
screen and its translations are still built. What disappeared is the blocking gate
at first login, the re-prompt on material change, the stored terms version and the
startup comparison.

## Out of scope

- Any dashboard or reporting UI over the collected data
- Per-customer database isolation or customer read access
- Periodic health heartbeats (considered, rejected as too much volume for now)
- SumUp transaction reconciliation
