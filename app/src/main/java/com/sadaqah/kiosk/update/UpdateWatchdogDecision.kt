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
        /** The running build wrote a heartbeat after the install attempt. */
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
     *
     * [installSeq] and [heartbeatSeq] come from one counter both writes draw
     * from, so their order is the order the writes happened in, whatever the
     * wall clock did in between. Only when both are present: a marker armed by
     * a build that predates the counter has none, and must still be decided by
     * the wall clock rather than read as "no proof of a healthy start".
     */
    fun decide(
        installAttemptedAt: Long,
        lastStartupMs: Long,
        installSeq: Long? = null,
        heartbeatSeq: Long? = null,
        backupApkUsable: () -> Boolean
    ): Decision {
        if (installAttemptedAt == 0L) return Decision.NoPendingInstall
        val healthyStart = if (installSeq != null && heartbeatSeq != null) {
            heartbeatSeq > installSeq
        } else {
            lastStartupMs >= installAttemptedAt
        }
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

    /** @return the stored form of [seq], bound to the wall-clock value written alongside it. */
    fun seqTag(wallMs: Long, seq: Long): String = "$wallMs:$seq"

    /**
     * @return the sequence in [tag] if it was written with [wallMs], else null.
     *
     * The binding is what makes a stale tag harmless: a build that predates the
     * counter (the target of a rollback) rewrites the wall-clock keys but never
     * the tags, and a leftover tag paired with its new value would order a
     * heartbeat against the wrong write.
     */
    fun seqFor(tag: String?, wallMs: Long): Long? {
        val parts = tag?.split(':') ?: return null
        if (parts.size != 2 || parts[0].toLongOrNull() != wallMs) return null
        return parts[1].toLongOrNull()?.takeIf { it > 0L }
    }

    /**
     * @return [tag] rebound from [oldWallMs] to [newWallMs] with its sequence
     * kept, or null if it did not belong to [oldWallMs].
     *
     * For [BootRearm.RestartCheckAt], which moves the marker's wall-clock value:
     * left unbound, the tag would drop a sequenced marker back to the clock
     * comparison the restart exists to work around.
     */
    fun retag(tag: String?, oldWallMs: Long, newWallMs: Long): String? =
        seqFor(tag, oldWallMs)?.let { seqTag(newWallMs, it) }

    /**
     * A missing backup reads as length 0. With no recorded size (backups taken
     * before it was recorded), any non-empty file counts: an unreadable APK is
     * only rejected by the installer, while refusing a good one strands the kiosk.
     */
    fun isBackupUsable(lengthBytes: Long, recordedSizeBytes: Long?): Boolean =
        lengthBytes > 0L && (recordedSizeBytes == null || lengthBytes == recordedSizeBytes)
}
