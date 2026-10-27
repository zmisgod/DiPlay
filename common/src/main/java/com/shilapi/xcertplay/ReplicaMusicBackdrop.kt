package com.shilapi.xcertplay

import android.graphics.*
import android.os.Build

/** Small, cached cover blur works on the FX11 API level without RenderEffect or per-frame copies. */
internal class ReplicaMusicBackdrop {
    private var source: Bitmap? = null
    private var generation = 0
    private var blurred: Bitmap? = null
    private var tint = Color.rgb(13, 31, 29)
    var palette: ReplicaArtworkPalette? = null
        private set
    var buildCount = 0
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun update(artwork: Bitmap?) {
        val valid = artwork?.takeUnless { it.isRecycled }
        if (valid === source && (valid == null || valid.generationId == generation)) return
        clear()
        if (valid == null) return
        source = valid; generation = valid.generationId
        var copy: Bitmap? = null
        var small: Bitmap? = null
        try {
            val readable = if (Build.VERSION.SDK_INT >= 26 && valid.config == Bitmap.Config.HARDWARE) {
                valid.copy(Bitmap.Config.ARGB_8888, false)?.also { copy = it } ?: return
            } else valid
            // 64² cached pixels: a separable Gaussian needs no full-screen allocations or GPU effects.
            val size=64
            small = Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
            Canvas(small).drawColor(Color.WHITE)
            paint.shader=null; paint.color=Color.WHITE;paint.alpha=255
            Canvas(small).drawBitmap(readable, null, Rect(0,0,size,size), paint)
            val pixels=IntArray(size*size).also { small.getPixels(it,0,size,0,0,size,size) }
            palette=ReplicaArtworkPalette.fromPixels(pixels)
            tint=palette!!.dominant
            val radius=8;val sigma=3.5
            val kernel=DoubleArray(radius*2+1) { i -> kotlin.math.exp(-((i-radius)*(i-radius))/(2*sigma*sigma)) }
            val sum=kernel.sum();kernel.indices.forEach { kernel[it]/=sum }
            fun pass(input:IntArray,horizontal:Boolean):IntArray {
                val result=IntArray(input.size)
                for(y in 0 until size) for(x in 0 until size) {
                    var red=0.0;var green=0.0;var blue=0.0
                    for(k in -radius..radius) {
                        val px=if(horizontal) (x+k).coerceIn(0,size-1) else x
                        val py=if(horizontal) y else (y+k).coerceIn(0,size-1)
                        val color=input[py*size+px];val weight=kernel[k+radius]
                        red+=Color.red(color)*weight;green+=Color.green(color)*weight;blue+=Color.blue(color)*weight
                    }
                    result[y*size+x]=Color.rgb(red.toInt(),green.toInt(),blue.toInt())
                }
                return result
            }
            small.setPixels(pass(pass(pixels,true),false),0,size,0,0,size,size)
            buildCount++
            blurred = small; small = null
        } catch (_: RuntimeException) {
            // Recycled/unreadable artwork falls back to a neutral panel, never a failed dashboard.
            tint = Color.rgb(13, 31, 29); palette=null
        } finally { copy?.recycle(); small?.recycle() }
    }

    /** Full-cover color masses remain visible; contrast comes from opaque coordinated dial faces. */
    fun drawAlbum(canvas: Canvas, bounds: RectF) {
        val image=blurred ?: return
        paint.shader=null;paint.color=Color.WHITE;paint.alpha=255
        canvas.drawBitmap(image,null,bounds,paint)
        paint.color=if(palette?.light==true) Color.argb(38,255,255,255) else Color.argb(58,0,0,0)
        canvas.drawRect(bounds,paint)
    }

    fun draw(canvas: Canvas, bounds: RectF, fadeEdges: Boolean = true, light: Boolean = false) {
        paint.shader = null; paint.alpha = 255; paint.color = tint
        canvas.drawRect(bounds, paint)
        blurred?.let { canvas.drawBitmap(it, null, bounds, paint) }
        paint.color = if(light) Color.argb(218,248,250,247) else Color.argb(100, 0, 0, 0); canvas.drawRect(bounds, paint)
        paint.alpha = 255
        if (!fadeEdges) return
        val edge = if(light) ReplicaDayPalette.background else Color.BLACK
        // Fade to the surrounding palette at the two dial centers and at the status strip/footer, cover color in the middle.
        paint.shader = LinearGradient(bounds.left, 0f, bounds.right, 0f,
            intArrayOf(edge, Color.TRANSPARENT, Color.TRANSPARENT, edge),
            floatArrayOf(0f, .23f, .77f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = LinearGradient(0f, 0f, 0f, 532f,
            intArrayOf(edge, Color.TRANSPARENT, Color.TRANSPARENT, edge),
            floatArrayOf(0f, .23f, .80f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint); paint.shader = null
    }

    fun clear() {
        blurred?.recycle(); blurred = null; source = null; generation = 0; palette=null
        tint = Color.rgb(13, 31, 29)
    }
}
