package com.shilapi.xcertplay

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PathMeasure

/** Source curve traced in panel-local proportions, preserving its tall, asymmetric silhouette. */
internal class ObsidianSpeedScale {
    val path = Path().apply {
        moveTo(200f,414f)
        cubicTo(132f,410f,82f,370f,68f,310f)
        cubicTo(49f,242f,71f,174f,116f,130f)
        cubicTo(149f,100f,190f,88f,236f,86f)
        transform(Matrix().apply { setScale(.80f, 1f) })
    }
    private val measure = PathMeasure(path,false)
    val ticks = List(49) { index ->
        val position=FloatArray(2);val tangent=FloatArray(2)
        measure.getPosTan(measure.length*index/48f,position,tangent)
        floatArrayOf(position[0],position[1],-tangent[1],tangent[0])
    }
    // Labels, ticks, progress and endpoint share the same normalized distance, never separate angles.
    val labels = (0..6).map { index ->
        val point = ticks[index * 8]
        (point[0] + point[2] * 34f) to (point[1] + point[3] * 34f + 7f)
    }
    fun marker(speed: Double, position: FloatArray, tangent: FloatArray): Boolean =
        speed.isFinite() && speed >= 0 && measure.getPosTan(
            measure.length * (speed / 240.0).coerceIn(0.0, 1.0).toFloat(), position, tangent)
    fun progress(speed: Double, target: Path) {
        target.rewind()
        if(speed.isFinite() && speed>0) measure.getSegment(0f,
            measure.length*(speed/240.0).coerceAtMost(1.0).toFloat(),target,true)
    }
}
