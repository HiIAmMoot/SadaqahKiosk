package com.sadaqah.kiosk.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class BackupStoreCopyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun copyReplacesThePreviousBackupAndLeavesNoTempFile() {
        val src = tmp.newFile("base.apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val dest = tmp.newFile("previous.apk").apply { writeBytes(byteArrayOf(9)) }

        val copied = BackupStore.copyAtomically(src, dest)

        assertEquals(4L, copied)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), dest.readBytes())
        assertEquals(listOf("base.apk", "previous.apk"), tmp.root.list()!!.sorted())
    }

    /** Pins cleanup and the untouched old backup on a failed copy. The
     *  power-cut-mid-copy case the temp file exists for cannot be simulated
     *  from a JVM test; it rests on rename being atomic within a directory. */
    @Test
    fun aFailedCopyLeavesThePreviousBackupAndNoTempFile() {
        val missingSrc = File(tmp.root, "gone.apk")
        val dest = tmp.newFile("previous.apk").apply { writeBytes(byteArrayOf(9, 9)) }

        try {
            BackupStore.copyAtomically(missingSrc, dest)
            fail("expected the copy of a missing source to throw")
        } catch (expected: IOException) {
        }

        assertArrayEquals(byteArrayOf(9, 9), dest.readBytes())
        assertFalse(File(tmp.root, "previous.apk.tmp").exists())
    }
}
