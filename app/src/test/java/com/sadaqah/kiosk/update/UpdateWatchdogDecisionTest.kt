package com.sadaqah.kiosk.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UpdateWatchdogDecisionTest {

    @Test
    fun noMarkerMeansNoPendingInstall() {
        assertEquals(
            UpdateWatchdogDecision.Decision.NoPendingInstall,
            UpdateWatchdogDecision.decide(installAttemptedAt = 0L, lastStartupMs = 999L, backupApkExists = { true })
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
            UpdateWatchdogDecision.decide(installAttemptedAt = 1_000L, lastStartupMs = 1_000L, backupApkExists = { true })
        )
    }

    @Test
    fun aHeartbeatAfterTheInstallIsHealthy() {
        assertEquals(
            UpdateWatchdogDecision.Decision.HealthyStart,
            UpdateWatchdogDecision.decide(installAttemptedAt = 1_000L, lastStartupMs = 1_001L, backupApkExists = { true })
        )
    }

    @Test
    fun aHeartbeatBeforeTheInstallWithNoBackupGivesUp() {
        assertEquals(
            UpdateWatchdogDecision.Decision.NoBackupToRollBackTo,
            UpdateWatchdogDecision.decide(installAttemptedAt = 1_000L, lastStartupMs = 999L, backupApkExists = { false })
        )
    }

    @Test
    fun aHeartbeatBeforeTheInstallWithABackupRollsBack() {
        assertEquals(
            UpdateWatchdogDecision.Decision.RollBack,
            UpdateWatchdogDecision.decide(installAttemptedAt = 1_000L, lastStartupMs = 999L, backupApkExists = { true })
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
            UpdateWatchdogDecision.decide(installAttemptedAt = 1_000L, lastStartupMs = 0L, backupApkExists = { true })
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
            backupApkExists = { asked = true; true }
        )
        assertEquals(UpdateWatchdogDecision.Decision.HealthyStart, decision)
        assertFalse("the healthy branch must not touch BackupStore", asked)
    }
}
