package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** Only a themed session can reveal the unmodified iPhone instrument stream. */
internal object UltraClusterOutput {
    var active = false
        private set
    private val listeners = linkedSetOf<(Boolean) -> Unit>()
    fun setActive(value: Boolean) { active = value; listeners.toList().forEach { it(value) } }
    fun observe(listener: (Boolean) -> Unit): () -> Unit {
        listeners += listener; listener(active)
        return { listeners -= listener }
    }
}

internal class UltraClusterView(context: Context, private val ready: (Boolean) -> Unit) : FrameLayout(context) {
    private val texture = TextureView(context)
    private val waiting = TextView(context).apply {
        setText(R.string.settings_dashboard_ultra_waiting); setTextColor(Color.WHITE)
        textSize = 20f; gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)
    }
    private val key = "ultra-instrument:${System.identityHashCode(this)}"
    private var surface: Surface? = null
    private var remove: (() -> Unit)? = null
    private var active = false
    private var frameReady = false
    private fun setReady(value: Boolean) {
        if (frameReady == value) return
        frameReady = value
        ready(value)
    }
    init {
        setBackgroundColor(Color.BLACK)
        addView(texture, LayoutParams(-1, -1)); addView(waiting, LayoutParams(-1, -1))
        // Keep TextureView drawing: alpha=0 can suppress updateTexImage/onSurfaceTextureUpdated.
        // The opaque waiting label covers stale frames until the first negotiated frame arrives.
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st); bind() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                if (active) { waiting.visibility = GONE; setReady(true) }
            }
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        remove = UltraClusterOutput.observe {
            active = it
            if (!it) { waiting.visibility = VISIBLE; setReady(false) }
            bind()
        }
    }
    private fun bind() { MapMirrors.set(key, surface.takeIf { active }) }
    private fun release() { MapMirrors.set(key, null); surface?.release(); surface = null; waiting.visibility = VISIBLE; setReady(false) }
    override fun onDetachedFromWindow() { remove?.invoke(); remove = null; release(); super.onDetachedFromWindow() }
}
