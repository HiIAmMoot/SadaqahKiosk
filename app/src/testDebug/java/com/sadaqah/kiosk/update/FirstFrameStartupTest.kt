package com.sadaqah.kiosk.update

import android.os.Looper
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * The watchdog heartbeat must not wait on SumUp's startup login: that login opens an
 * activity on top of the kiosk, and a login slower than the watchdog window would
 * roll a healthy build back. Debug-only for the same ComponentActivity reason as
 * SettingsScreenLayoutTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class FirstFrameStartupTest {

    private val calls = mutableListOf<String>()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
    private val decor = controller.get().window.decorView

    private fun showWindow() {
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun register() = FirstFrameStartup.onFirstFrame(
        decor,
        heartbeat = { calls += "heartbeat" },
        afterFirstFrame = { calls += "login" }
    )

    @Test
    fun nothingRunsBeforeTheFirstFrame() {
        register()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun theWindowsFirstRealFrameRunsBoth() {
        register()
        showWindow()

        assertEquals(listOf("heartbeat", "login"), calls)
    }

    @Test
    fun theHeartbeatIsRecordedOnTheFirstFrameAndTheLoginStartsOnlyAfterIt() {
        showWindow()
        register()

        decor.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(listOf("heartbeat"), calls)

        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("heartbeat", "login"), calls)
    }

    @Test
    fun theLoginIsDroppedWhenTheActivityIsDestroyedBeforeItRuns() {
        showWindow()
        register()
        decor.viewTreeObserver.dispatchOnPreDraw()

        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("heartbeat"), calls)
    }

    @Test
    fun laterFramesRunNeitherAgain() {
        showWindow()
        register()
        decor.viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idle()

        decor.viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("heartbeat", "login"), calls)
    }
}
