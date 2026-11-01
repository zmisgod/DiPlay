package com.shilapi.xcertplay

import android.graphics.*
import android.graphics.drawable.Drawable

/** Solid silhouettes from the retained concepts. Unhandled maneuvers keep their semantic vector. */
internal class DashboardGuidanceGlyph {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val turn = Path().apply {
        moveTo(3f, 23f); lineTo(3f, 12f); quadTo(3f, 5f, 10f, 5f)
        lineTo(15f, 5f); lineTo(15f, 1f); lineTo(23f, 8f); lineTo(15f, 15f)
        lineTo(15f, 11f); lineTo(10f, 11f); quadTo(9f, 11f, 9f, 13f)
        lineTo(9f, 23f); close()
    }
    private val straight = Path().apply {
        moveTo(9f, 23f); lineTo(9f, 11f); lineTo(3f, 11f); lineTo(12f, 1f)
        lineTo(21f, 11f); lineTo(15f, 11f); lineTo(15f, 23f); close()
    }
    fun draw(c: Canvas, bounds: RectF, icon: Int, color: Int, fallback: Drawable?) {
        if (icon !in 1..3) {
            // Material maneuver assets have internal padding; preserve the visible optical size.
            fallback?.setTint(color)
            val padding = bounds.width() * .25f
            fallback?.setBounds((bounds.left-padding).toInt(), (bounds.top-padding).toInt(),
                (bounds.right+padding).toInt(), (bounds.bottom+padding).toInt())
            fallback?.draw(c)
            return
        }
        c.save(); c.translate(bounds.left, bounds.top); c.scale(bounds.width()/24f, bounds.height()/24f)
        if (icon == 2) { c.translate(24f, 0f); c.scale(-1f, 1f) }
        paint.color = color; c.drawPath(if (icon == 1) straight else turn, paint)
        c.restore()
    }
}
