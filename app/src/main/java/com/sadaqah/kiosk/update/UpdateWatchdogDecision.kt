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
        /** The build never proved healthy and a usable backup APK exists — roll back. */
        object RollBack : Decision()
    }

    /**
     * [lastStartupMs] `>=` [installAttemptedAt], not `>`: CR-3 moved the
     * heartbeat write to after the first frame is drawn rather than the top of
     * onCreate, so a build that starts and draws in the same millisecond an
     * install was marked is still a build that started. Treating equality as
     * unhealthy would fail a genuinely fast, correct boot.
     *
     * [backupApkUsable] is a lambda rather than a Boolean so the caller does not
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
        backupApkUsable: () -> Boolean
    ): Decision {
        if (installAttemptedAt == 0L) return Decision.NoPendingInstall
        val healthyStart = lastStartupMs >= installAttemptedAt
        return when {
            healthyStart -> Decision.HealthyStart
            !backupApkUsable() -> Decision.NoBackupToRollBackTo
            else -> Decision.RollBack
        }
    }

    /** What BootReceiver does with a pending install marker. */
    sealed class BootRearm {
        /** No install was pending: an ordinary boot. */
        object None : BootRearm()
        /** Re-arm against the original marker. */
        object KeepMarker : BootRearm()
        /** The clock is behind the marker, so it was reset at boot: move the
         *  marker to [markerMs] and drop the heartbeat stamped on the old clock. */
        data class RestartCheckAt(val markerMs: Long) : BootRearm()
    }

    /**
     * A marker still on disk at boot means the reboot swallowed the watchdog
     * alarm before it fired. The caller re-arms rather than checking now: the
     * new build has not had a chance to draw its first frame yet, so an
     * immediate check would roll back a healthy build.
     */
    fun bootRearm(installAttemptedAt: Long, nowMs: Long): BootRearm = when {
        installAttemptedAt == 0L -> BootRearm.None
        nowMs < installAttemptedAt -> BootRearm.RestartCheckAt(nowMs)
        else -> BootRearm.KeepMarker
    }

    /**
     * A missing backup reads as length 0. With no recorded size (backups taken
     * before it was recorded), any non-empty file counts: an unreadable APK is
     * only rejected by the installer, while refusing a good one strands the kiosk.
     */
    fun isBackupUsable(lengthBytes: Long, recordedSizeBytes: Long?): Boolean =
        lengthBytes > 0L && (recordedSizeBytes == null || lengthBytes == recordedSizeBytes)
}
