package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UltraClusterViewTest {
    @Test fun waitingCoverKeepsTextureDrawableAndReadyNotifiesOnlyOnTransitions() {
        UltraClusterOutput.setActive(false)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val states = mutableListOf<Boolean>()
        val view = UltraClusterView(activity.get()) { states += it }
        val root = FrameLayout(activity.get())
        activity.get().setContentView(root)
        root.addView(view)
        val texture = view.getChildAt(0) as TextureView
        val waiting = view.getChildAt(1) as TextView
        val frame = SurfaceTexture(0)
        try {
            assertEquals(1f, texture.alpha, 0f)
            assertEquals(View.VISIBLE, waiting.visibility)
            texture.surfaceTextureListener!!.onSurfaceTextureUpdated(frame)
            assertTrue(states.isEmpty()) // An unnegotiated video frame is never accepted.
            UltraClusterOutput.setActive(true)
            repeat(10) { texture.surfaceTextureListener!!.onSurfaceTextureUpdated(frame) }
            assertEquals(listOf(true), states)
            assertEquals(View.GONE, waiting.visibility)
            UltraClusterOutput.setActive(false)
            assertEquals(listOf(true, false), states)
            assertEquals(View.VISIBLE, waiting.visibility)
            UltraClusterOutput.setActive(true)
            texture.surfaceTextureListener!!.onSurfaceTextureUpdated(frame)
            assertEquals(listOf(true, false, true), states)
            root.removeView(view)
            assertEquals(listOf(true, false, true, false), states)
        } finally {
            root.removeView(view)
            UltraClusterOutput.setActive(false)
            frame.release()
            activity.pause().stop().destroy()
        }
    }
}
