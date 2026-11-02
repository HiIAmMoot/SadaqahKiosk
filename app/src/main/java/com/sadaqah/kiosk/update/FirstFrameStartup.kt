package com.sadaqah.kiosk.update

import android.view.View
import android.view.ViewTreeObserver

object FirstFrameStartup {
    /**
     * @notice Runs [heartbeat] on [view]'s first predraw, then posts [afterFirstFrame] so it
     *         starts once that frame is out. Later frames run neither, and [afterFirstFrame]
     *         is dropped if the view left its window first.
     * @dev The startup SumUp login must go in [afterFirstFrame], never before it: the login
     *      opens SumUp's activity on top of this one, which can keep this window from ever
     *      drawing until the login returns. After an update, a login slower than the
     *      watchdog window then reads as a failed build and a healthy one is rolled back.
     *      The attach check matters because the activity is not configChanges-guarded: a
     *      recreation between the frame and the post would otherwise open a login from the
     *      dead instance, whose result reaches nobody, next to the new instance's own.
     */
    fun onFirstFrame(view: View, heartbeat: () -> Unit, afterFirstFrame: () -> Unit) {
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.viewTreeObserver.removeOnPreDrawListener(this)
                heartbeat()
                view.post { if (view.isAttachedToWindow) afterFirstFrame() }
                return true
            }
        })
    }
}
