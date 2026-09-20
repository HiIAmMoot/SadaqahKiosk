package com.sadaqah.kiosk.update

/**
 * What the update watchdog does when its alarm fires, decided from three
 * plain values instead of a `Context` and two `SharedPreferences` reads.
 *
 * Pure, and that is the point: the caller is [UpdateWatchdogReceiver], a
 * `BroadcastReceiver`, which no unit test can reach — the same reasoning
 * `DonationEvents` and `DiagnosticEvents` were pulled out for on the telemetry
 * side, and the same bug shape they exist to avoid: a wrong decision sitting
 * in a branch nothing can exercise directly.
 */
object UpdateWatchdogDecision {

    sealed class Decision {
        /** No install was pending; the alarm has nothing to check. */
        object NoPendingInstall : Decision()
        /** The running build wrote a heartbeat at or after the install attempt. */
        object HealthyStart : Decision()
        /** The build never proved healthy, and there is nothing to roll back to. */
        object NoBackupToRollBackTo : Decision()
        /** The build never proved healthy and a backup APK exists — roll back. */
        object RollBack : Decision()
    }

    /**
     * [lastStartupMs] `>=` [installAttemptedAt], not `>`: CR-3 moved the
     * heartbeat write to after the first frame is drawn rather than the top of
     * onCreate, so a build that starts and draws in the same millisecond an
     * install was marked is still a build that started. Treating equality as
     * unhealthy would fail a genuinely fast, correct boot.
     *
     * [backupApkExists] is a lambda rather than a Boolean so the caller does not
     * have to answer a question this may never ask. Answering it means touching
     * `BackupStore`, whose `backupDir` is a lazy that calls `mkdirs()`, so an
     * eagerly-evaluated argument created an empty backup directory on every
     * healthy start — a path that previously returned without going near it.
     * Still pure and still trivially testable: tests pass `{ true }` or
     * `{ false }`, and can assert it was never invoked.
     */
    fun decide(
        installAttemptedAt: Long,
        lastStartupMs: Long,
        backupApkExists: () -> Boolean
    ): Decision {
        if (installAttemptedAt == 0L) return Decision.NoPendingInstall
        val healthyStart = lastStartupMs >= installAttemptedAt
        return when {
            healthyStart -> Decision.HealthyStart
            !backupApkExists() -> Decision.NoBackupToRollBackTo
            else -> Decision.RollBack
        }
    }
}
