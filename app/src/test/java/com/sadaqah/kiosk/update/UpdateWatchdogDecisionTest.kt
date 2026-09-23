package com.sadaqah.kiosk.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateWatchdogDecisionTest {

    @Test
    fun noMarkerMeansNoPendingInstall() {
        assertEquals(
            UpdateWatchdogDecision.Decision.NoPendingInstall,
            UpdateWatchdogDecision.decide(installAttemptedAt = 0L, lastStartupMs = 999L, backupApkUsable ={ true })
        )
    }

    /**
     * CR-3 moved the heartbeat write to after the first frame is drawn rather
     * than the top of onCreate, so a build that starts and draws in the same
     * millisecond an install was marked is still a build that started.
     * Equality must count as healthy, not just strictly-after.
     */
    @Test
    fun aHeartbeatExactlyAtTheInstallMomentIsHealthy() {
        assertEquals(
            UpdateWatchdogDecision.Decision.HealthyStart,
            UpdateWatchdogDecision.decide(installAttemptedAt = 1_000L, lastStartupMs = 1_000L, backupApkUsable ={ true })
        )
    }

    @Test
    fun aHeartbeatAfterTheInstallIsHealthy() {
        assertEquals(
            UpdateWatchdogDecision.Decision.HealthyStart,
            UpdateWatchdogDecision.decide(installAttemptedAt = 1_000L, lastStartupMs = 1_001L, backupApkUsable ={ true })
        )
    }

    @Test
    fun aHeartbeatBeforeTheInstallWithNoBackupGivesUp() {
        assertEquals(
            UpdateWatchdogDecision.Decision.NoBackupToRollBackTo,
            UpdateWatchdogDecision.decide(installAttemptedAt = 1_000L, lastStartupMs = 999L, backupApkUsable ={ false })
        )
    }

    @Test
    fun aHeartbeatBeforeTheInstallWithABackupRollsBack() {
        assertEquals(
            UpdateWatchdogDecision.Decision.RollBack,
            UpdateWatchdogDecision.decide(installAttemptedAt = 1_000L, lastStartupMs = 999L, backupApkUsable ={ true })
        )
    }

    /** A stale marker from before the watchdog existed, or a boot that never
     *  wrote a heartbeat at all — the never-started case, indistinguishable
     *  from "started before the install" by this decision, and correctly so:
     *  both mean "no proof of a healthy new build". */
    @Test
    fun noHeartbeatAtAllWithABackupRollsBack() {
        assertEquals(
            UpdateWatchdogDecision.Decision.RollBack,
            UpdateWatchdogDecision.decide(installAttemptedAt = 1_000L, lastStartupMs = 0L, backupApkUsable ={ true })
        )
    }

    /** The healthy path must not even ASK whether a backup exists. Answering
     *  means touching BackupStore, whose lazy backupDir calls mkdirs(), so an
     *  eager argument created an empty backup directory on every healthy start.
     *  Asserting the lambda is never invoked is the only way to pin that from
     *  a unit test — the directory it would create is invisible from here. */
    @Test
    fun aHealthyStartNeverAsksWhetherABackupExists() {
        var asked = false
        val decision = UpdateWatchdogDecision.decide(
            installAttemptedAt = 1_000L,
            lastStartupMs = 1_001L,
            backupApkUsable ={ asked = true; true }
        )
        assertEquals(UpdateWatchdogDecision.Decision.HealthyStart, decision)
        assertFalse("the healthy branch must not touch BackupStore", asked)
    }

    @Test
    fun anOrdinaryBootWithNoMarkerDoesNotRearm() {
        assertEquals(
            UpdateWatchdogDecision.BootRearm.None,
            UpdateWatchdogDecision.bootRearm(installAttemptedAt = 0L, nowMs = 5_000L)
        )
    }

    /** A power cut inside the watchdog window loses the alarm but not the marker. */
    @Test
    fun aBootWithAPendingMarkerAndASaneClockKeepsTheMarker() {
        assertEquals(
            UpdateWatchdogDecision.BootRearm.KeepMarker,
            UpdateWatchdogDecision.bootRearm(installAttemptedAt = 1_000L, nowMs = 5_000L)
        )
    }

    /** A clock behind the marker means it was reset at boot: a heartbeat
     *  stamped on it would read as older than the install and roll back a
     *  healthy build, so the check restarts from the reset clock's "now". */
    @Test
    fun aBootWithTheClockBehindTheMarkerRestartsTheCheckFromNow() {
        assertEquals(
            UpdateWatchdogDecision.BootRearm.RestartCheckAt(400L),
            UpdateWatchdogDecision.bootRearm(installAttemptedAt = 1_000L, nowMs = 400L)
        )
    }

    @Test
    fun aMissingBackupIsNotUsable() {
        assertFalse(UpdateWatchdogDecision.isBackupUsable(lengthBytes = 0L, recordedSizeBytes = null))
    }

    @Test
    fun aZeroLengthBackupIsNotUsableEvenIfZeroWasRecorded() {
        assertFalse(UpdateWatchdogDecision.isBackupUsable(lengthBytes = 0L, recordedSizeBytes = 0L))
    }

    @Test
    fun aTruncatedBackupIsNotUsable() {
        assertFalse(UpdateWatchdogDecision.isBackupUsable(lengthBytes = 4_096L, recordedSizeBytes = 8_192L))
    }

    @Test
    fun aBackupMatchingItsRecordedSizeIsUsable() {
        assertTrue(UpdateWatchdogDecision.isBackupUsable(lengthBytes = 8_192L, recordedSizeBytes = 8_192L))
    }

    /** Backups taken before the size was recorded still have to roll back:
     *  an unreadable APK is rejected by the installer anyway, a refused
     *  rollback of a good one strands the kiosk. */
    @Test
    fun aNonEmptyBackupWithNoRecordedSizeIsUsable() {
        assertTrue(UpdateWatchdogDecision.isBackupUsable(lengthBytes = 8_192L, recordedSizeBytes = null))
    }
}
