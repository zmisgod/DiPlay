package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.CopyOnWriteArraySet

/** Primary CarPlay video only. Never switches or replaces the head-unit output surface. */
internal object MainScreenMirrors {
    private val main=Handler(Looper.getMainLooper())
    private val surfaces=linkedMapOf<String,Surface>()
    private val listeners=CopyOnWriteArraySet<(Boolean)->Unit>()
    private val aspectListeners=CopyOnWriteArraySet<()->Unit>()
    var sink: ((String,Surface?)->Unit)?=null
    var aspect=16.0/9
        set(value) { require(value.isFinite() && value>0);if(field==value)return;field=value;main.post { aspectListeners.forEach { it() } } }
    var active=false
        private set
    fun set(key:String,surface:Surface?) {
        if(surface==null) { if(surfaces.remove(key)==null)return }
        else { if(surfaces[key]===surface)return;surfaces[key]=surface }
        sink?.invoke(key,surface)
    }
    fun reapply() { surfaces.forEach { (key,surface)->sink?.invoke(key,surface) } }
    fun setActive(value:Boolean) { main.post { if(active!=value){active=value;listeners.forEach { it(value) }} } }
    fun addListener(listener:(Boolean)->Unit){listeners.add(listener)}
    fun removeListener(listener:(Boolean)->Unit){listeners.remove(listener)}
    fun addAspectListener(listener:()->Unit){aspectListeners.add(listener)}
    fun removeAspectListener(listener:()->Unit){aspectListeners.remove(listener)}
}

/** Uniform scale preserves circles/road geometry; offsets are fractions of the available window. */
internal object DashboardMirrorViewport {
    fun fit(box: android.graphics.RectF, aspect: Double, zoom: Int, x: Int, y: Int): android.graphics.RectF {
        require(aspect.isFinite() && aspect>0)
        val w=minOf(box.width(),box.height()*aspect.toFloat())*zoom.coerceIn(50,400)/100f
        val h=w/aspect.toFloat()
        val cx=box.centerX()+box.width()*x.coerceIn(-100,100)/100f
        val cy=box.centerY()+box.height()*y.coerceIn(-100,100)/100f
        return android.graphics.RectF(cx-w/2,cy-h/2,cx+w/2,cy+h/2)
    }
}
