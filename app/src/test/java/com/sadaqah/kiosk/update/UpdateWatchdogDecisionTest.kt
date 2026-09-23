package com.sadaqah.kiosk.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** A clock set back between the install and the first frame must not fail
     *  a build whose heartbeat was written after the marker. */
    @Test
    fun aLaterHeartbeatSequenceIsHealthyEvenWhenTheClockWentBack() {
        assertEquals(
            UpdateWatchdogDecision.Decision.HealthyStart,
            UpdateWatchdogDecision.decide(
                installAttemptedAt = 1_000L, lastStartupMs = 400L,
                installSeq = 5L, heartbeatSeq = 6L, backupApkUsable = { true }
            )
        )
    }

    /** A clock jumping forward must not pass the heartbeat written before the install. */
    @Test
    fun anEarlierHeartbeatSequenceRollsBackEvenWhenTheClockJumpedForward() {
        assertEquals(
            UpdateWatchdogDecision.Decision.RollBack,
            UpdateWatchdogDecision.decide(
                installAttemptedAt = 1_000L, lastStartupMs = 9_000L,
                installSeq = 5L, heartbeatSeq = 4L, backupApkUsable = { true }
            )
        )
    }

    @Test
    fun anEqualSequenceIsNotHealthy() {
        assertEquals(
            UpdateWatchdogDecision.Decision.RollBack,
            UpdateWatchdogDecision.decide(
                installAttemptedAt = 1_000L, lastStartupMs = 1_000L,
                installSeq = 5L, heartbeatSeq = 5L, backupApkUsable = { true }
            )
        )
    }

    /** The first update into this build is armed by the previous build, which
     *  writes no sequence: the wall-clock comparison still has to decide it. */
    @Test
    fun aMarkerWithoutASequenceFallsBackToTheWallClock() {
        assertEquals(
            UpdateWatchdogDecision.Decision.RollBack,
            UpdateWatchdogDecision.decide(
                installAttemptedAt = 1_000L, lastStartupMs = 999L,
                installSeq = null, heartbeatSeq = 6L, backupApkUsable = { true }
            )
        )
        assertEquals(
            UpdateWatchdogDecision.Decision.HealthyStart,
            UpdateWatchdogDecision.decide(
                installAttemptedAt = 1_000L, lastStartupMs = 1_000L,
                installSeq = null, heartbeatSeq = 4L, backupApkUsable = { true }
            )
        )
    }

    @Test
    fun aHeartbeatWithoutASequenceFallsBackToTheWallClock() {
        assertEquals(
            UpdateWatchdogDecision.Decision.RollBack,
            UpdateWatchdogDecision.decide(
                installAttemptedAt = 1_000L, lastStartupMs = 999L,
                installSeq = 5L, heartbeatSeq = null, backupApkUsable = { true }
            )
        )
        assertEquals(
            UpdateWatchdogDecision.Decision.HealthyStart,
            UpdateWatchdogDecision.decide(
                installAttemptedAt = 1_000L, lastStartupMs = 1_000L,
                installSeq = 5L, heartbeatSeq = null, backupApkUsable = { true }
            )
        )
    }

    @Test
    fun aSequenceTagReadsBackForItsOwnWallClockValue() {
        assertEquals(7L, UpdateWatchdogDecision.seqFor(UpdateWatchdogDecision.seqTag(1_000L, 7L), wallMs = 1_000L))
    }

    /** After a rollback to a build that predates the sequence, that build
     *  rewrites the wall-clock key but leaves the newer build's tag behind. */
    @Test
    fun aSequenceTagForAnotherWallClockValueIsIgnored() {
        assertNull(UpdateWatchdogDecision.seqFor(UpdateWatchdogDecision.seqTag(1_000L, 7L), wallMs = 2_000L))
    }

    @Test
    fun aMissingOrMalformedSequenceTagIsIgnored() {
        assertNull(UpdateWatchdogDecision.seqFor(null, wallMs = 1_000L))
        assertNull(UpdateWatchdogDecision.seqFor("", wallMs = 1_000L))
        assertNull(UpdateWatchdogDecision.seqFor("1000", wallMs = 1_000L))
        assertNull(UpdateWatchdogDecision.seqFor("1000:x", wallMs = 1_000L))
        assertNull(UpdateWatchdogDecision.seqFor("1000:7:1", wallMs = 1_000L))
        assertNull(UpdateWatchdogDecision.seqFor("1000:0", wallMs = 1_000L))
        assertNull(UpdateWatchdogDecision.seqFor("1000:-3", wallMs = 1_000L))
    }

    /** Restarting the check moves the marker's wall-clock value; its sequence
     *  has to move with it or the marker falls back to the clock. */
    @Test
    fun aRetaggedMarkerKeepsItsSequenceUnderTheNewWallClockValue() {
        val moved = UpdateWatchdogDecision.retag(
            UpdateWatchdogDecision.seqTag(1_000L, 7L), oldWallMs = 1_000L, newWallMs = 400L
        )
        assertEquals(7L, UpdateWatchdogDecision.seqFor(moved, wallMs = 400L))
    }

    /** Power cut in the window, clock reset at boot: the marker moves back, the
     *  pre-install heartbeat is kept, and only a heartbeat from after the boot passes. */
    @Test
    fun afterARestartedCheckOnlyAHeartbeatWrittenAfterTheMarkerIsHealthy() {
        val markerTag = UpdateWatchdogDecision.seqTag(1_000L, 5L)
        val rearm = UpdateWatchdogDecision.bootRearm(installAttemptedAt = 1_000L, nowMs = 400L)
        val movedTo = (rearm as UpdateWatchdogDecision.BootRearm.RestartCheckAt).markerMs
        val installSeq = UpdateWatchdogDecision.seqFor(
            UpdateWatchdogDecision.retag(markerTag, oldWallMs = 1_000L, newWallMs = movedTo), movedTo
        )

        val preInstall = UpdateWatchdogDecision.seqFor(UpdateWatchdogDecision.seqTag(900L, 4L), 900L)
        assertEquals(
            UpdateWatchdogDecision.Decision.RollBack,
            UpdateWatchdogDecision.decide(
                installAttemptedAt = movedTo, lastStartupMs = 900L,
                installSeq = installSeq, heartbeatSeq = preInstall, backupApkUsable = { true }
            )
        )

        val afterBoot = UpdateWatchdogDecision.seqFor(UpdateWatchdogDecision.seqTag(300L, 6L), 300L)
        assertEquals(
            UpdateWatchdogDecision.Decision.HealthyStart,
            UpdateWatchdogDecision.decide(
                installAttemptedAt = movedTo, lastStartupMs = 300L,
                installSeq = installSeq, heartbeatSeq = afterBoot, backupApkUsable = { true }
            )
        )
    }

    /** A heartbeat without its own sequence is decided by the clock, and after
     *  a reset it would read as newer than the moved marker: it must be dropped. */
    @Test
    fun aRestartedCheckKeepsTheOldHeartbeatOnlyWhenBothSequencesResolve() {
        assertTrue(UpdateWatchdogDecision.keepsHeartbeatOnRestart(installSeq = 5L, heartbeatSeq = 4L))
        assertFalse(UpdateWatchdogDecision.keepsHeartbeatOnRestart(installSeq = null, heartbeatSeq = 4L))
        assertFalse(UpdateWatchdogDecision.keepsHeartbeatOnRestart(installSeq = 5L, heartbeatSeq = null))
        assertFalse(UpdateWatchdogDecision.keepsHeartbeatOnRestart(installSeq = null, heartbeatSeq = null))
    }

    @Test
    fun aStaleTagIsNotRetagged() {
        assertNull(
            UpdateWatchdogDecision.retag(
                UpdateWatchdogDecision.seqTag(2_000L, 7L), oldWallMs = 1_000L, newWallMs = 400L
            )
        )
        assertNull(UpdateWatchdogDecision.retag(null, oldWallMs = 1_000L, newWallMs = 400L))
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
