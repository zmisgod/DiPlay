package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Copies the already decoded instrument-navigation output; never attaches another codec or captures Android UI. */
internal object Esp32CarPlayFrames {
    private val main=Handler(Looper.getMainLooper())
    var provider: (((Bitmap?)->Unit)->Unit)?=null // Main-thread registration/lifetime, owned by host activity.

    fun capture(): Bitmap? {
        check(Looper.myLooper()!=Looper.getMainLooper())
        val pending=PendingBitmap()
        main.post {
            if(!pending.isPending()) return@post
            val source=provider
            if(source==null) pending.complete(null)
            else runCatching { source(pending::complete) }.onFailure { pending.complete(null) }
        }
        return pending.await(1500)
    }

    fun centerCrop(width: Int,height: Int): android.graphics.Rect {
        require(width>0 && height>0)
        val side=minOf(width,height)
        return android.graphics.Rect((width-side)/2,(height-side)/2,(width+side)/2,(height+side)/2)
    }
    /** Discard only continuous near-black edge mattes, not dark roads or interior regions.
     * The bounded scan leaves completely dark/unavailable frames unchanged. */
    fun contentCrop(bitmap: Bitmap): android.graphics.Rect {
        val w=bitmap.width;val h=bitmap.height
        val line=IntArray(maxOf(w,h))
        fun blackRow(y:Int):Boolean {
            bitmap.getPixels(line,0,w,0,y,w,1)
            for(x in 0 until w step maxOf(1,w/96)) if(!matte(line[x]))return false
            return true
        }
        fun blackColumn(x:Int,top:Int,bottom:Int):Boolean {
            bitmap.getPixels(line,0,1,x,top,1,bottom-top)
            for(y in 0 until bottom-top step maxOf(1,(bottom-top)/96)) if(!matte(line[y]))return false
            return true
        }
        fun border(limit:Int,black:(Int)->Boolean):Int {
            var n=0
            while(n<limit && black(n))n++
            return if(n in 4 until limit)n else 0
        }
        val top=border(h/3,::blackRow)
        val bottom=h-border(h/3) { blackRow(h-1-it) }
        val left=border(w/3) { blackColumn(it,top,bottom) }
        val right=w-border(w/3) { blackColumn(w-1-it,top,bottom) }
        val square=centerCrop(right-left,bottom-top)
        square.offset(left,top)
        return square
    }
    private fun matte(pixel:Int):Boolean = (pixel ushr 24)<8 ||
        ((pixel shr 16 and 255)<=8 && (pixel shr 8 and 255)<=8 && (pixel and 255)<=8)



}

internal class PendingBitmap {
    private val ready=CountDownLatch(1)
    private var closed=false
    private var bitmap: Bitmap?=null
    private var completed=false
    @Synchronized fun isPending() = !closed && !completed
    @Synchronized fun complete(value: Bitmap?) {
        if(closed || completed) value?.recycle()
        else { bitmap=value;completed=true;ready.countDown() }
    }
    fun await(timeoutMillis: Long): Bitmap? {
        try { ready.await(timeoutMillis,TimeUnit.MILLISECONDS) }
        catch(e: InterruptedException) {
            synchronized(this) { closed=true;bitmap?.recycle();bitmap=null }
            throw e
        }
        synchronized(this) { closed=true;return bitmap.also { bitmap=null } }
    }
}
