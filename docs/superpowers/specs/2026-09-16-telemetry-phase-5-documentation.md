# Telemetry phase 5 — documentation

**Status:** spec
**Phase:** 5 of 5, the last
**Base:** `telemetry/phase-4` (PR #16)
**Parent design:** `docs/superpowers/specs/2026-09-02-kiosk-telemetry-design.md`

---

## What this phase is for

Four phases built a telemetry subsystem. Nothing outside the code describes it. The README still tells a reader the app has no analytics, no crash reporting and no tracking, which stopped being true the moment phase 3 landed and is the first thing a prospective self-hoster reads.

This phase closes that gap and one more: there is no published schema, so nobody outside this repository can stand up a backend that the app can actually talk to. The app sends JSON to three PostgREST endpoints and the exact column names live nowhere but in `TelemetryEvent.payloadJson()`.

Both deliverables are documentation. Neither is a change in what the app does.

---

## The double-sided approach

Documentation written from the code by someone who did not build it is two things at once: better documentation, and an audit. A reader who has to explain a subsystem in order to document it reads it the way no author can, because the author already knows what it was supposed to do.

That is the same principle as the whole-branch review, which has caught more real defects in this project than any other single practice. So phase 5 runs it deliberately rather than hoping for it.

**Two fresh agents, dispatched in parallel, neither seeing the other's output.**

- **The writer** reads the source material and writes the documentation. It carries one hard rule, stated below.
- **The auditor** reads the same material with a single job: find where the code, the specs, the PR descriptions and the README disagree with each other. It is given no steer, no priority list, and no draft documentation to check against.

Both seats run on the most capable model. Deriving a schema from code and auditing four phases of work are judgement tasks, not transcription. The drift test is an implementer task and takes the standard tier. Only the humanizer pass, which changes prose and nothing else, takes the cheap tier.

### The source material, named

"Read the PRs" is not actionable without the list. The telemetry subsystem is an eleven-PR stack, all currently open:

**#2** phase 1 outbox · **#3** 2a uploader · **#4** 2b credentials · **#5** 2c settings screen · **#6** 3a donation events · **#11** 3b diagnostics · **#12** 3c-i recovery · **#13** 3c-ii payment path · **#14** 3d-i per-table backoff · **#15** 3d-ii queue work · **#16** phase 4 disclosure

The code under `app/src/main/java/com/sadaqah/kiosk/telemetry/` is the authority on behaviour. The PR bodies and the specs under `docs/superpowers/specs/` are the record of what the behaviour was *claimed* to be, and the gap between those two is the entire point of the audit seat. Where an agent's budget forces a choice, the code is read completely and the prose selectively.

Dispatching two agents in parallel does not break subagent-driven-development's rule against parallel implementers. That rule exists to stop two agents editing the same working tree. These two write only to separate files in the plan workspace and touch no source file.

### The writer's hard rule

**The writer may never settle a discrepancy in prose.**

When the writer finds that the code does something the spec, a PR description or the README says it does not, it records the discrepancy and keeps writing. It does not decide which side is right, and it does not quietly document the code's actual behaviour as though that behaviour were intended.

This rule exists because the opposite is the natural failure and it is invisible afterwards. An agent asked to both document and audit will resolve almost every inconsistency by describing what the code does and moving on. The documentation then reads perfectly, the bug is now written down as a feature, and nothing in the artifact shows that a decision was ever made.

### Why the auditor is separate

An auditor whose other job is to ship finished prose has a reason to find fewer problems. An auditor with no prose to ship does not.

The evidence from this project is direct: during phase 3d-ii, a reviewer given a priority list and a reviewer given no steer found different defects, and the unsteered one found the deadlock that the steered one read past. Independence is the mechanism, not thoroughness.

### Reconciling the two lists

The controller reads both findings lists and rules on every entry.

- **In both lists** — high confidence. Treat as real.
- **Only in the auditor's list** — the most valuable category. This is what writing would have buried.
- **Only in the writer's list** — usually a documentation question rather than a defect, but ruled on the same way.

Every ruling is recorded in the ledger. No finding is dropped silently.

---

## What ships

1. The README privacy section, rewritten.
2. The README features list and settings reference, gaining analytics entries.
3. A new README analytics section carrying the reference schema.
4. A JVM test that fails when the schema and the code disagree.
5. `docs/known-debt.md` entries for whatever the audit turns up.
6. The version bump to `1.4.0-preview`.

## What does not ship

**No code fixes.** Phase 5 is a documentation phase and stays one.

A code defect the audit finds is ruled on and routed: to `docs/known-debt.md` with what it costs and what would fix it, or to a phase 6 spec if it deserves its own work. Anything serious enough that shipping it undone is the wrong call gets surfaced to the founder before the plan is written, not decided quietly.

The documentation describes what the code does today. Where that differs from what it should do, the gap is named in the open rather than papered over. A README that describes intended behaviour instead of real behaviour is worse than no README, because it is confidently wrong.

The one exception is the drift test in item 4, which is new code. It is a test, it changes no behaviour, and it exists to make the documentation itself verifiable.

---

## The reference schema

### Where it lives

Inline in the README, in a single fenced `sql` block, in a new **Analytics** section.

The parent design says the schema is "published in the app README as a reference a self-hoster can paste into their own project", and that is honoured literally. A separate `.sql` file was considered and rejected: it splits the documentation from the thing being documented, and it gives the drift test a second source of truth that can itself drift from the README.

The README block is the normative artifact. The test asserts against it.

### The columns

Settled by `TelemetryEvent.payloadJson()` and the three `addFields` overrides. Every row carries the four common fields first, then its own.

| Table | Columns |
|---|---|
| `donation_events` | `id`, `code`, `install_id`, `app_version`, `amount_cents`, `currency`, `occurred_at` |
| `diagnostic_events` | `id`, `code`, `install_id`, `app_version`, `occurred_at`, `kind`, `severity`, `detail`, `stack_trace` |
| `telemetry_activations` | `id`, `code`, `install_id`, `app_version`, `activated_at`, `privacy_policy_url`, `terms_url` |

### The DDL

```sql
create table donation_events (
  id           uuid primary key,
  code         text not null default '',
  install_id   text not null,
  app_version  text not null,
  amount_cents integer not null,
  currency     text not null,
  occurred_at  timestamptz not null
);

create table diagnostic_events (
  id          uuid primary key,
  code        text not null default '',
  install_id  text not null,
  app_version text not null,
  occurred_at timestamptz not null,
  kind        text not null,
  severity    text not null,
  detail      jsonb,
  stack_trace text
);

create table telemetry_activations (
  id                 uuid primary key,
  code               text not null default '',
  install_id         text not null,
  app_version        text not null,
  activated_at       timestamptz not null,
  privacy_policy_url text not null,
  terms_url          text not null
);
```

### Five rules the DDL must carry, and why

Each of these is a decision the code already made. A self-hoster who gets one wrong does not get an error message that explains it.

**1. `id` is the primary key, and that is what makes inserts idempotent.**

`TelemetryUploader.kt:187-198`. The client generates every `id`. A retry after an ambiguous failure collides on the primary key and returns 409, which `HttpResponse.isAlreadyStored` treats as already stored rather than as a failure. Without the primary key there is no collision, the retry succeeds, and the donation is counted twice.

A plain unique index does the same job. The primary key is named because it is one statement instead of two and a self-hoster is less likely to forget it.

**2. Grant INSERT. Never grant SELECT.**

The device key ships on every kiosk, so it is assumed leaked. What makes a leaked key worthless is that it cannot read anything back.

This is also why the uploader sends `Prefer: return=minimal` and deliberately not `resolution=ignore-duplicates` or `return=representation`. Both of those push PostgREST onto its upsert path, which requires SELECT on the table. The comment records that requesting either returns 401 against the live project.

So the no-SELECT rule is not advice. The app is built on it, and a self-hoster who grants SELECT out of habit turns a key printed on every kiosk in their fleet into a key that reads every donation they have ever taken.

This is the most consequential line in the schema and the README must say so plainly.

**3. `kind` must not be constrained.**

`TelemetryEvent.kt:48-52` states the reason in the code: the list of kinds will grow, and a database that rejects an unknown one stalls the outbox until it drops real donations. A `CHECK` or an enum type on `kind` trades a class of data loss for tidiness.

The eleven current kinds are documented as a reference for whoever writes the queries, explicitly not as a constraint.

**4. No CHECK regex on `code`.**

This **overrides** the parent design's documentation section, which lists "the code regex" as part of the reference schema.

`KioskCode.kt` settles it. Validation in the app is advisory by design: *"A fork of this app has no kiosk-code scheme and never will, so rejecting a non-conforming code would mean the software only runs for one vendor"*, and *"When in doubt, be permissive — this validation never blocks anything."*

A CHECK constraint would reject at the database exactly what the app deliberately declines to reject, and it would do it with the same failure mode as rule 3: every insert fails, the outbox stalls, donations drop. The convention is documented as a convention. The database does not enforce it.

**5. `detail` and `stack_trace` are nullable, and are absent rather than null when empty.**

`TelemetryEvent.Diagnostic.addFields` adds them only when non-null, so a diagnostic with neither sends a JSON object that contains neither key. This matters twice: the columns must be nullable, and the drift test must treat them as optional or it fails on the first event that has neither.

### One deviation, named

The parent design says `code` "must not be marked `not null`" because a fork with no code scheme leaves it empty.

This spec marks it `not null default ''` instead. The reasoning in the parent conflates empty with null: `EventIdentity.from` always populates `code` and `payloadJson` always writes the key, so the app never sends null and never omits the field. A fork that strips the field entirely gets the default. The column therefore never holds null, and a query has one empty case to handle rather than two.

The audit should challenge this if it disagrees.

### The diagnostic kinds

Documented as a table of the eleven kinds and their severities, from `DiagnosticKind`. Presented as reference for query authors, with rule 3 stated alongside it so nobody turns the list into a constraint.

---

## The drift test

**The problem.** The schema is a markdown code block. The unit suite cannot see it. A field renamed in `TelemetryEvent.kt` leaves the README silently wrong, and the first person to find out is a self-hoster whose inserts fail with a PostgREST error naming a column they never typed.

**The test.** A JVM test reads `README.md`, extracts the fenced `sql` block, parses the column names out of each `create table` statement, and compares them against the JSON keys of a real constructed event for that table.

Reading the README rather than a checked-in constant is the point: it leaves exactly one source of truth. A constant would need to be kept in step with the README by hand, which is the same problem one level down.

**Requirements:**

- **Real events, not hand-built JSON.** The test constructs each event through its actual constructor and calls `payloadJson()`. A test that asserts against a hand-written key list proves only that two hand-written lists match.
- **Optional columns handled.** `detail` and `stack_trace` are absent from an event that has neither. The test asserts the event's keys are a subset of the declared columns and that every non-optional column is present, with the optional set declared explicitly and small.
- **The file must be found.** Gradle runs unit tests with the working directory at the module, so the path is `../README.md`. A test that silently fails to find the file and passes anyway is worse than no test. It asserts the file was read and the block was found before it asserts anything about columns.
- **A malformed block fails.** If the `sql` block cannot be parsed, the test fails rather than skipping. Safe direction.

**Mutation checks the implementer runs:** rename a column in the README block and confirm the test goes red; delete a column from the README block and confirm red; remove a field from an `addFields` override and confirm red; point the path at a file that does not exist and confirm the test fails rather than passing vacuously.

---

## The README changes

### Privacy, rewritten

Current text:

> This app does **not** collect, store, or transmit any personal data beyond what the SumUp SDK requires for payment processing. No analytics, no crash reporting, no tracking.

All three clauses in the second sentence are false for a kiosk with analytics enabled.

**Rewritten, not softened.** The parent design is explicit about what that means, and the disclosure screen in phase 4 set the standard the README has to match. It must state:

- The shipped app has no endpoint configured and transmits nothing by default. This is true and it is the most important sentence in the section.
- The feature is optional and operator-enabled.
- The data is **identified, not anonymous**. A kiosk reports a code and an install ID with every row. This is the sentence a softened rewrite would lose, and it is the one that matters.
- The exact fields, per table.
- What is never sent: no donor identity, no card data, no uploaded logo, no location beyond what the operator typed into the kiosk's own code.
- That crash reports include a redacted stack trace, and what redaction does and does not cover.
- **SumUp transaction identifiers, stated honestly rather than absolutely.** The app never reads one, and nothing constructs an event containing one. But three sites forward the SumUp SDK's own error text into diagnostics, and the redactor strips only the affiliate key and runs of 32 or more characters. Whether that text can ever carry a transaction code is not determinable from this repository, because the SDK is a pinned binary. `docs/known-debt.md` records this as inferred rather than demonstrated, and the README says the same thing in the same register. It does not repeat the unqualified absolute.

  The disclosure screen states the absolute. That is a genuine divergence between two artifacts describing one system, and it is a finding for the audit to rule on, not something the writer resolves. Whichever way it is ruled, the README and the screen must end up saying the same thing.
- That switching analytics off stops collection except for the restart and rollback markers written to local disk, which are sent only if analytics is on at the next startup. Phase 4's disclosure copy carries the same carve-out and the two must not contradict each other.

The known gaps from `docs/known-debt.md` that a reader would reasonably want to know about are linked, not restated.

### Features list

One entry, in the register of the existing entries, pointing at the analytics section. It says the feature is optional and off by default.

### Settings reference

A row for the analytics settings, matching the table's existing style.

### Consistency with the disclosure screen

The README and `DisclosureScreen`'s copy describe the same system to two different audiences. Where they make the same claim they must not make it differently. The writer checks every factual claim in the README rewrite against the phase 4 disclosure strings and records any divergence as a finding rather than choosing a winner.

---

## Humanizer

The documentation is written to be read by people who are deciding whether to trust this app with donation data. It has to read like a person wrote it.

**`humanizer:humanizer` runs in file mode on `README.md`, after the facts are settled and before review.**

Sequencing is deliberate:

- **After facts.** Humanizer rewrites prose. Running it on a draft whose claims are still moving wastes the pass and invites a rewrite of text that is about to change.
- **Before review.** The reviewer reads the text that ships. A humanizer pass after review means the reviewed artifact is not the shipped artifact.
- **File mode specifically.** It changes prose only and leaves code blocks, inline code, commands, paths and link targets alone by contract, so the SQL block and the drift test's parse target are safe from it.

**The fact-retention check is mandatory and is its own step.** The skill warns about its own failure mode: a rewrite can drop a claim, and its own instructions call a lost claim an error. On a privacy section, a dropped claim is the exact failure this phase exists to prevent — the original sin in the current README is a sentence that says less than the truth.

So after the humanizer pass, every factual claim in the list above is checked present in the rewritten text, one by one, against the pre-humanizer draft. A claim that has gone missing is restored. A claim that has been softened is restored to its original strength.

---

## Version bump

`1.4.0-preview`, per the repo's convention of shipping the suffixed version on the preview line first. Required by phase 4's spec before device testing.

---

## Out of scope

- **Any code fix.** Covered above.
- **Standing up a real Supabase project to test the DDL against.** The schema is derived from the code and guarded by the drift test. Proving it against a live project is a device-testing task, and it joins the deferred hardware checklist rather than blocking this phase.
- **Arabic layout direction**, carried from phase 4.
- **A native read of the eight translations**, carried from phase 4.
- **Vendor backend documentation.** The reference schema is the shared base. What the vendor adds on top — `kiosks`, `kiosk_codes`, `organisations`, a server-side `received_at` — belongs to that repository.

---

## Verification

- Full JVM suite green, including the new drift test.
- `assembleDebug` green.
- The drift test's mutation checks, run by the implementer and spot-checked by the controller.
- Every factual claim in the privacy section checked present and unsoftened after the humanizer pass.
- Both findings lists ruled on, every ruling in the ledger, nothing dropped.
- No AI attribution anywhere in the branch or the PR body.
- Whole-branch review by a fresh reviewer on the most capable model.

## Carried forward to device testing

Everything on the deferred list from phases 3c-ii through 4, plus:

- Create the three tables in a real Supabase project from the README block, grant INSERT without SELECT, and confirm a kiosk's flush lands rows in all three.
- Confirm that granting SELECT is what makes the key readable, so the README's warning is demonstrated rather than asserted.
- Capture real SumUp failure messages and check whether any carries a transaction code, which settles the absolute the disclosure screen and now the README both state.
