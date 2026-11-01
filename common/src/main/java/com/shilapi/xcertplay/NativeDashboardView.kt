package com.shilapi.xcertplay

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.ultra.VehicleSignal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/** Coordinates are design-space units, scaled uniformly to any preview or the exact FX11 panel. */
internal data class DashboardLayout(val map: RectF?, val navigation: RectF?, val music: RectF?, val tires: RectF? = null) {
    companion object {
        const val MIDNIGHT_FOOTER_TOP = 472f
        const val MIDNIGHT_MAP_WIDTH = 1464
        const val MIDNIGHT_MAP_HEIGHT = 456
        const val PANORAMA_MAP_WIDTH = 1488
        const val PANORAMA_MAP_HEIGHT = 472
        const val REPLICA_MAP_WIDTH = 1388
        const val REPLICA_MAP_HEIGHT = 532
        const val REPLICA_DIAL_RADIUS = 264f
        const val CONCEPT_DIAL_SCALE = 264f / 249f
        const val REPLICA_DIAL_Y = 266f
        const val REPLICA_LEFT_DIAL_X = 266f
        const val REPLICA_RIGHT_DIAL_X = 1654f
        const val REPLICA_SAFE_TOP = 160
        const val REPLICA_SAFE_BOTTOM = 68
        const val REPLICA_SAFE_SIDE = 282
        fun of(style: DashboardStyle, mode: DashboardMode): DashboardLayout {
            if (style.concept) {
                val map = if (mode.forStyle(style) == DashboardMode.NAVIGATION) when(style) {
                    DashboardStyle.TRACK -> RectF(240f,0f,1780f,532f)
                    DashboardStyle.OBSIDIAN -> RectF(0f,0f,1920f,532f)
                    else -> RectF(266f,0f,1654f,532f)
                } else null
                val content=RectF(550f,76f,1370f,464f)
                return DashboardLayout(map, if(map!=null) { when(style) {
                    DashboardStyle.OBSIDIAN -> RectF(704f,76f,1216f,174f)
                    DashboardStyle.TRACK -> RectF(810f,76f,1110f,198f)
                    else -> RectF(784f,76f,1136f,160f)
                } } else null,
                    content.takeIf { mode.forStyle(style)==DashboardMode.MUSIC },
                    content.takeIf { mode.forStyle(style)==DashboardMode.TIRES })
            }
            if (style == DashboardStyle.OEM_OVERLAY) return oem(mode, navigation = true, music = true)
            if (style == DashboardStyle.REPLICA) return when (mode.forStyle(style)) {
                DashboardMode.NAVIGATION -> DashboardLayout(RectF(REPLICA_LEFT_DIAL_X, 0f, REPLICA_RIGHT_DIAL_X, 532f), RectF(548f, 70f, 1372f, 150f), null)
                DashboardMode.TIRES -> DashboardLayout(null, null, null, RectF(548f, 68f, 1372f, 464f))
                else -> DashboardLayout(null, null, RectF(548f, 68f, 1372f, 464f))
            }
            if (style == DashboardStyle.PANORAMA) {
                val card = RectF(18f, 364f, 414f, 512f)
                return DashboardLayout(RectF(432f, 60f, 1920f, 532f), RectF(456f, 76f, 1032f, 204f),
                    music = card.takeIf { mode.forStyle(style) == DashboardMode.MUSIC },
                    tires = card.takeIf { mode.forStyle(style) == DashboardMode.TIRES })
            }
            return when (mode.forStyle(style)) {
                DashboardMode.NAVIGATION -> DashboardLayout(RectF(432f, 60f, 1896f, 516f), RectF(456f, 76f, 1032f, 204f), RectF(18f, 364f, 414f, 512f))
                DashboardMode.ALL, DashboardMode.MUSIC -> DashboardLayout(null, null, RectF(488f, 60f, 1432f, 516f))
                DashboardMode.TIRES -> DashboardLayout(null, null, null, RectF(488f, 60f, 1432f, 516f))
            }
        }
        fun oem(mode: DashboardMode, navigation: Boolean, music: Boolean): DashboardLayout {
            val page = mode.forStyle(DashboardStyle.OEM_OVERLAY)
            val showMusic = music && page != DashboardMode.NAVIGATION
            val showNavigation = navigation && page != DashboardMode.MUSIC
            val musicBox = if (!showMusic) null else if (page == DashboardMode.MUSIC)
                RectF(600f, 172f, 1320f, 284f) else RectF(600f, 376f, 1320f, 488f)
            return DashboardLayout(
                map = if (page == DashboardMode.MUSIC) null else
                    RectF(600f, if (showNavigation) 136f else 48f, 1320f, if (showMusic) 368f else 488f),
                navigation = if (showNavigation) RectF(600f, 48f, 1320f, 128f) else null,
                music = musicBox,
            )
        }
    }
}

/** Tick labels, arc and marker share one linear scale, including the 1,600 rpm position. */
internal data class DashboardDial(val maximum: Double, val divisions: Int, val majorEvery: Int) {
    fun valueAtTick(index: Int) = maximum * index / divisions
    fun sweep(value: Double) = (value / maximum).coerceIn(0.0, 1.0).toFloat() * 270f
    fun angle(value: Double) = 135f + sweep(value)
    companion object {
        val SPEED = DashboardDial(240.0, 48, 6)
        val CLASSIC_SPEED = DashboardDial(240.0, 48, 4)
        val RPM = DashboardDial(8000.0, 40, 5)
    }
}

internal class NativeDashboardView(context: Context, private val preview: Boolean = false,
    private val viewport: RectF = RectF(0f, 0f, 1920f, 532f)) : FrameLayout(context) {
    private val texture = TextureView(context)
    private val videoClip = FrameLayout(context).apply { clipChildren = true; clipToPadding = true }
    private var mainMirror = NativeDashboardSettings.mirrorMain(context)
    private var mirrorTransform: List<Int>? = null
    private val frameMetrics = DashboardMapFrameMetrics()
    internal val artworkView = DashboardCanvas(context, viewport)
    private val mirrorKey = "native-dashboard:${System.identityHashCode(this)}"
    private var surface: Surface? = null
    private var removeObserver: (() -> Unit)? = null
    private var mirrored = false
    @Volatile internal var mapDiagnostic = "Map surface not attached"
        private set
    private val frames = DashboardMapFrames()
    private val streamChanged: (Boolean) -> Unit = { if(!mainMirror) { frames.stream(it); updateMap() } }
    private val mainStreamChanged: (Boolean) -> Unit = { if(mainMirror) { frames.stream(it); updateMap() } }
    private val aspectChanged: () -> Unit = { requestLayout() }

    init {
        setBackgroundColor(when { NativeDashboardSettings.style(context) == DashboardStyle.OEM_OVERLAY -> Color.TRANSPARENT
            NativeDashboardSettings.style(context) == DashboardStyle.REPLICA && NativeDashboardSettings.effectiveAppearance(context) == DashboardAppearance.DAY -> ReplicaDayPalette.background
            else -> Color.BLACK })
        addView(videoClip)
        videoClip.addView(texture)
        addView(artworkView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                surface = Surface(st); updateMap()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { releaseSurface(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                frameMetrics.frame(if(mainMirror) "main" else "cluster")
                if (mirrored && !frames.visible) { frames.frame(); updateMap() }
            }
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        frames.stream(if(mainMirror) MainScreenMirrors.active else MapMirrors.streamActive)
        MapMirrors.addStreamListener(streamChanged); MapMirrors.addAspectListener(aspectChanged)
        MainScreenMirrors.addListener(mainStreamChanged); MainScreenMirrors.addAspectListener(aspectChanged)
        removeObserver = NativeDashboardRuntime.observe(context) { snapshot ->
            val style = NativeDashboardSettings.style(context).let { if (it == DashboardStyle.OFF) DashboardStyle.MIDNIGHT else it }
            val mode = NativeDashboardSettings.mode(context)
            val appearance = NativeDashboardSettings.effectiveAppearance(context)
            if (artworkView.style != style || artworkView.mode != mode || artworkView.appearance != appearance) {
                artworkView.style = style; artworkView.mode = mode; artworkView.appearance = appearance
                setBackgroundColor(when { style == DashboardStyle.OEM_OVERLAY -> Color.TRANSPARENT
                    style == DashboardStyle.REPLICA && appearance == DashboardAppearance.DAY -> ReplicaDayPalette.background
                    else -> Color.BLACK })
                requestLayout()
            }
            val oldGeometry = artworkView.geometry
            artworkView.panelScale = NativeDashboardSettings.panelScale(context)
            val requestedMain = NativeDashboardSettings.mirrorMain(context)
            if(mainMirror != requestedMain) {
                MapMirrors.set(mirrorKey,null); MainScreenMirrors.set(mirrorKey,null)
                mirrored=false;frames.resetSurface(); mainMirror=requestedMain
                frames.stream(if(mainMirror) MainScreenMirrors.active else MapMirrors.streamActive)
                appliedMapSize=null;requestLayout()
            }
            val transform=listOf(NativeDashboardSettings.mirrorZoom(context),NativeDashboardSettings.mirrorX(context),NativeDashboardSettings.mirrorY(context))
            if(mirrorTransform != transform) { mirrorTransform=transform;requestLayout() }
            artworkView.snapshot = snapshot
            if (oldGeometry != artworkView.geometry) requestLayout()
            artworkView.menuPagingActive = GeelyWheelKeys.isMenuPagingActive(context)
            updateMap()
        }
    }
    override fun onDetachedFromWindow() {
        removeObserver?.invoke(); removeObserver = null
        artworkView.menuPagingActive = false
        MapMirrors.removeStreamListener(streamChanged); MapMirrors.removeAspectListener(aspectChanged)
        MainScreenMirrors.removeListener(mainStreamChanged); MainScreenMirrors.removeAspectListener(aspectChanged)
        releaseSurface()
        super.onDetachedFromWindow()
    }
    private fun releaseSurface() {
        MapMirrors.set(mirrorKey, null); MainScreenMirrors.set(mirrorKey,null); mirrored = false; frames.resetSurface()
        surface?.release(); surface = null
    }
    private var appliedMapWidth = -1
    private var appliedMapHeight = -1
    private var appliedMapSize: DashboardMapSize? = null
    private fun updateMap() {
        val mapSize = NativeDashboardSettings.mapSize(context)
        if (appliedMapWidth != texture.width || appliedMapHeight != texture.height || appliedMapSize != mapSize) {
            texture.setTransform(if(mainMirror) Matrix() else mapTransform(texture.width, texture.height, mapSize))
            appliedMapWidth = texture.width; appliedMapHeight = texture.height; appliedMapSize = mapSize
        }
        val showMap = artworkView.geometry.map != null && (!preview || NativeDashboardSettings.enabled(context))
        val attach = showMap && surface?.isValid == true
        if (attach != mirrored) {
            frames.resetSurface()
            if(mainMirror) MainScreenMirrors.set(mirrorKey,if(attach) surface else null)
            else MapMirrors.set(mirrorKey, if (attach) surface else null)
            mirrored = attach
        }
        artworkView.mapActive = showMap && mirrored && frames.visible
        // An empty TextureView is opaque by default. Hide its pixels until a fresh frame arrives.
        texture.alpha = if (artworkView.mapActive) 1f else 0f
        mapDiagnostic = "mapWindow source=${if(mainMirror) "main" else "cluster"} zoom=${NativeDashboardSettings.mirrorZoom(context)} viewport=$viewport measured=${width}x${height} " +
            "texture=${texture.left},${texture.top},${texture.right},${texture.bottom} " +
            "mirrorAttached=$mirrored stream=${if(mainMirror) MainScreenMirrors.active else MapMirrors.streamActive} freshVisible=${artworkView.mapActive} " +
            "renderScale=${NativeDashboardSettings.mapSize(context).renderScale} " +
            "navigationCard=${artworkView.geometry.navigation != null} musicCard=${artworkView.geometry.music != null}"
        artworkView.invalidateIfContentChanged()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        artworkView.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        val fitted = fittedMap()
        val scale = min(w / viewport.width(), h / viewport.height())
        val box=artworkView.geometry.map ?: RectF()
        videoClip.measure(MeasureSpec.makeMeasureSpec(max(1,(box.width()*scale).roundToInt()),MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(max(1,(box.height()*scale).roundToInt()),MeasureSpec.EXACTLY))
        texture.measure(MeasureSpec.makeMeasureSpec(max(1, (fitted.width() * scale).roundToInt()), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(max(1, (fitted.height() * scale).roundToInt()), MeasureSpec.EXACTLY))
    }
    private fun fittedMap(): RectF {
        val box = artworkView.geometry.map ?: return RectF()
        if(mainMirror) return DashboardMirrorViewport.fit(box,MainScreenMirrors.aspect,
            NativeDashboardSettings.mirrorZoom(context),NativeDashboardSettings.mirrorX(context),NativeDashboardSettings.mirrorY(context))
        val ratio = MapMirrors.STREAM_ASPECT.toFloat()
        // The full-height Replica map crops excess edges instead of introducing letterboxing.
        // Other dashboards retain their existing contain fit.
        val w = if ((artworkView.style == DashboardStyle.REPLICA || artworkView.style.concept)) max(box.width(), box.height() * ratio)
            else min(box.width(), box.height() * ratio)
        val h = w / ratio
        return RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val scale = min(width / viewport.width(), height / viewport.height())
        val dx = (width - viewport.width() * scale) / 2 - viewport.left * scale
        val dy = (height - viewport.height() * scale) / 2 - viewport.top * scale
        val fitted = fittedMap()
        artworkView.mapWindow = if (!mainMirror && (artworkView.style == DashboardStyle.REPLICA || artworkView.style.concept))
            artworkView.geometry.map?.let(::RectF) ?: RectF() else fitted
        val box=artworkView.geometry.map ?: RectF()
        if(mainMirror) artworkView.mapWindow=RectF(fitted).apply { if(!intersect(box)) setEmpty() }
        val clipX=(dx+box.left*scale).roundToInt();val clipY=(dy+box.top*scale).roundToInt()
        videoClip.layout(clipX,clipY,clipX+videoClip.measuredWidth,clipY+videoClip.measuredHeight)
        val x=((fitted.left-box.left)*scale).roundToInt();val y=((fitted.top-box.top)*scale).roundToInt()
        texture.layout(x,y,x+texture.measuredWidth,y+texture.measuredHeight)
        artworkView.layout(0, 0, width, height)
        updateMap()
    }
    companion object {
        internal fun mapTransform(width: Int, height: Int, size: DashboardMapSize) = Matrix().apply {
            setScale(size.renderScale, size.renderScale, width / 2f, height / 2f)
        }
    }
}

/** Always renders real readings. Missing or expired readings are dashes, never simulated zeroes. */
internal class DashboardCanvas(context: Context, private val viewport: RectF = RectF(0f, 0f, 1920f, 532f)) : View(context) {
    var style = DashboardStyle.MIDNIGHT
        set(value) {
            if(field.concept && !value.concept) concepts.release()
            field=value
        }
    var appearance = NativeDashboardSettings.effectiveAppearance(context)
    private var artworkTheme: ReplicaArtworkPalette? = null
    private var artworkSweep: SweepGradient? = null
    private val day get() = artworkTheme?.light ?: (style.supportsAppearance && appearance == DashboardAppearance.DAY)
    var panelScale = NativeDashboardSettings.panelScale(context)
    private val concepts = DashboardConceptRenderer()
    private val guidanceGlyph = DashboardGuidanceGlyph()
    var mode = DashboardMode.ALL
    var snapshot = DashboardSnapshot()
        set(value) {
            if (ClusterMusicState.trackChanged(field.music.info, value.music.info) ||
                field.music.info.artworkTransferId != value.music.info.artworkTransferId) musicMarquee.clear()
            field = value
        }
    private val renderMetrics = DashboardRenderMetrics()
    private var nextMarqueeAt = Long.MAX_VALUE
    private val marqueeFrame = Runnable { nextMarqueeAt = Long.MAX_VALUE; invalidate() }
    private fun scheduleMarquee(delay: Long) {
        if (!isAttachedToWindow || windowVisibility != VISIBLE || !isShown) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now + delay >= nextMarqueeAt) return
        removeCallbacks(marqueeFrame)
        nextMarqueeAt = now + delay
        postDelayed(marqueeFrame, delay)
    }
    private var lastVisualState: DashboardSnapshot? = null
    private var lastArtworkGeneration: Int? = null
    private var lastMapWindow: RectF? = null
    private var lastVisualLayout: List<Any>? = null
    /** Vehicle timestamps refresh independently of the visible values. */
    internal fun invalidateIfContentChanged(): Boolean {
        val layout = listOf(style, mode, appearance, panelScale, mapActive, menuPagingActive, System.currentTimeMillis() / 60_000)
        val previous = lastVisualState
        val generation = snapshot.music.artwork?.takeUnless { it.isRecycled }?.generationId
        val changed = previous == null || lastVisualLayout != layout || lastMapWindow != mapWindow ||
            lastArtworkGeneration != generation ||
            previous.navigation != snapshot.navigation || previous.music.info != snapshot.music.info ||
            previous.music.artwork !== snapshot.music.artwork ||
            previous.vehicle.size != snapshot.vehicle.size ||
            snapshot.vehicle.any { (signal, reading) -> previous.vehicle[signal]?.value != reading.value }
        lastVisualState = snapshot; lastVisualLayout = layout
        lastArtworkGeneration = generation
        if (lastMapWindow != mapWindow) lastMapWindow = RectF(mapWindow)
        if (changed) postInvalidateOnAnimation()
        return changed
    }
    private val musicMarquee = DashboardMarquee()
    var mapActive = false
    var mapWindow = RectF()
    var menuPagingActive = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    val geometry get() = if (style == DashboardStyle.OEM_OVERLAY) DashboardLayout.oem(mode,
        navigation = snapshot.navigation != null,
        music = snapshot.music.info.let { !it.title.isNullOrBlank() || !it.artist.isNullOrBlank() || !it.album.isNullOrBlank() })
        else DashboardLayout.of(style, mode)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bg get() = when { style.concept -> if(day) 0xffd4d2dd.toInt() else 0xff222139.toInt(); else -> when (style) { DashboardStyle.REPLICA -> if (day) ReplicaDayPalette.background else Color.BLACK; DashboardStyle.PANORAMA -> Color.rgb(6, 17, 23); else -> Color.rgb(6, 14, 23) } }
    private val panel get() = if(day) 0xffe4e3e1.toInt() else Color.rgb(17, 29, 43)
    private val muted get() = if (day) ReplicaDayPalette.secondary else Color.rgb(141, 162, 182)
    private val accent get() = when (style) { DashboardStyle.REPLICA -> Color.rgb(36, 215, 191); DashboardStyle.PANORAMA -> Color.rgb(111, 226, 189); else -> Color.rgb(106, 222, 255) }
    private val white get() = artworkTheme?.text ?: if (day) ReplicaDayPalette.text else Color.rgb(243, 248, 255)
    private val line get() = if (day) ReplicaDayPalette.inactive else Color.rgb(38, 57, 74)
    private var glyphId = 0
    private var glyph: Drawable? = null
    private val followCarGlyph by lazy { context.getDrawable(R.drawable.ic_dashboard_follow_car)?.mutate() }
    private val hillDescentGlyph by lazy { context.getDrawable(R.drawable.ic_dashboard_hill_descent)?.mutate() }
    private val musicBackdrop = ReplicaMusicBackdrop()
    private val dialMusicBackdrop = ReplicaMusicBackdrop()
    private val menuHint = DashboardMenuHint(context)
    private val REPLICA_STATUS_ICON_SIZE = 36f
    private val REPLICA_STATUS_HORIZONTAL_PADDING = 24f
    private val REPLICA_HEADER_HEIGHT = 60f
    private val REPLICA_FOOTER_HEIGHT = 54f
    private val replicaDaySweep by lazy {
        // Android sweep starts at 3 o’clock; rotate zero to the scale’s fixed 135-degree origin.
        SweepGradient(0f,0f,ReplicaDayPalette.dialSweep + ReplicaDayPalette.dialSweep.last(),
            floatArrayOf(0f,.1875f,.375f,.5625f,.75f,1f)).apply {
            setLocalMatrix(Matrix().apply { setRotate(135f) })
        }
    }
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateArtworkTheme()
        val drawStarted = android.os.SystemClock.elapsedRealtimeNanos()
        removeCallbacks(marqueeFrame); nextMarqueeAt = Long.MAX_VALUE
        try {
        val scale = min(width / viewport.width(), height / viewport.height())
        if (scale <= 0f) return
        canvas.save()
        canvas.translate((width - viewport.width() * scale) / 2, (height - viewport.height() * scale) / 2)
        canvas.scale(scale, scale); canvas.translate(-viewport.left, -viewport.top)
        canvas.clipRect(viewport)
        if (style == DashboardStyle.OEM_OVERLAY) {
            // No background, gauges, vehicle readings or placeholder panels: OEM owns those pixels.
            geometry.navigation?.let { if (snapshot.navigation != null) navigation(canvas, it) }
            geometry.music?.let { oemMusic(canvas, it) }
            if (menuPagingActive) menuHint.draw(canvas, style)
            canvas.restore()
            return
        }
        canvas.save()
        if (mapActive) canvas.clipRect(mapWindow, Region.Op.DIFFERENCE)
        if(style.concept) concepts.background(canvas,style,day) else {
            paint.color = bg; paint.style = Paint.Style.FILL; canvas.drawRect(0f, 0f, 1920f, 532f, paint)
            if (artworkTheme != null) {
                musicBackdrop.drawAlbum(canvas,RectF(0f,0f,1920f,532f))
            } else if (style == DashboardStyle.REPLICA && day) {
                paint.color = ReplicaDayPalette.centerOverlay
                canvas.drawRect(266f,0f,1654f,532f,paint)
            }
        }
        geometry.map?.let { rect ->
            if(!style.concept && artworkTheme == null) box(canvas, rect, if(style==DashboardStyle.REPLICA) { if(day) ReplicaDayPalette.centerOverlay else Color.BLACK } else Color.rgb(10, 22, 33), 14f)
            if (!mapActive) {
                text(canvas, context.getString(R.string.dashboard_no_map), rect.centerX(), rect.centerY() + 10, 24f, muted, Paint.Align.CENTER, rect.width() - 40)
            }
        }
        canvas.restore()
        if (style.concept) {
            if (mapActive && geometry.map != null) concepts.videoEdges(canvas,style,day)
            concepts.instruments(canvas, style, day, snapshot, ::conceptMusic)
        } else if (style == DashboardStyle.REPLICA) {
            replicaMapEdges(canvas)
            replicaGauge(canvas, DashboardLayout.REPLICA_LEFT_DIAL_X, true)
            replicaGauge(canvas, DashboardLayout.REPLICA_RIGHT_DIAL_X, false)
        } else if (style == DashboardStyle.MIDNIGHT) {
            geometry.map?.let {
                paint.style=Paint.Style.STROKE;paint.strokeWidth=1.2f;paint.color=Color.rgb(76,100,121)
                canvas.save();canvas.clipRect(it)
                canvas.drawRoundRect(it,12f,12f,paint)
                canvas.restore()
            }
            if (mode.forStyle(style) == DashboardMode.NAVIGATION) {
                // Navigation keeps music under the compact speed dial, outside the video viewport.
                canvas.save(); canvas.translate(216f, 214f); canvas.scale(.80f, .80f)
                canvas.translate(-224f, -245f); gauge(canvas, 224f, true, compact = true); canvas.restore()
                text(canvas, number(VehicleSignal.ENGINE_RPM) + " rpm", 216f, 340f, 22f, muted, Paint.Align.CENTER, 340f)
            } else {
                fullHeightMidnightGauge(canvas, 240f, true); fullHeightMidnightGauge(canvas, 1680f, false)
            }
        } else {
            panoramaSpeed(canvas)
        }
        if (snapshot.navigation != null) geometry.navigation?.let { navigation(canvas, it) }
        geometry.music?.let { music(canvas, it) }
        geometry.tires?.let { when (style) {
            DashboardStyle.PANORAMA -> panoramaTires(canvas, it)
            DashboardStyle.REPLICA, DashboardStyle.TRACK, DashboardStyle.POLAR, DashboardStyle.OBSIDIAN, DashboardStyle.STARFIELD -> replicaTires(canvas, it)
            else -> tirePage(canvas, it)
        } }
        // Reserve a stable header surface: card antialiasing must not bleed into status gaps.
        if(style == DashboardStyle.MIDNIGHT || style == DashboardStyle.PANORAMA) {
            paint.style=Paint.Style.FILL;paint.color=bg
            canvas.drawRect(0f,0f,1920f,60f,paint)
        }
        // Every full dashboard uses the same icons, state semantics and anchored modules.
        replicaStatus(canvas)
        if(style == DashboardStyle.MIDNIGHT || style == DashboardStyle.PANORAMA) topVehicleInfo(canvas)
        else replicaFooter(canvas)
        if (menuPagingActive) menuHint.draw(canvas, style)
        canvas.restore()
        } finally {
            renderMetrics.drawFinished(drawStarted, style.name, mapActive, dialFaceBuildCount)
        }
    }
    /** A real video texture spans behind the dials; only these edge fades obscure it. */
    private fun replicaMapEdges(c: Canvas) {
        if (!mapActive || geometry.map == null) return
        fun fade(r: RectF, x0: Float, y0: Float, x1: Float, y1: Float, from: Int, to: Int) {
            paint.style=Paint.Style.FILL
            paint.shader=LinearGradient(x0,y0,x1,y1,from,to,Shader.TileMode.CLAMP)
            c.drawRect(r,paint); paint.shader=null
        }
        val mask = artworkTheme?.surface ?: if(day) ReplicaDayPalette.centerOverlay else bg
        fade(RectF(266f,0f,548f,532f),266f,0f,548f,0f,mask,Color.TRANSPARENT)
        fade(RectF(1372f,0f,1654f,532f),1372f,0f,1654f,0f,Color.TRANSPARENT,mask)
    }
    /** Original silver sector / jade ring vocabulary, with km/h and a correct 0–8 ×1000 RPM scale. */
    private fun replicaGauge(c: Canvas, centerX: Float, speed: Boolean) {
        c.save()
        c.translate(centerX,DashboardLayout.REPLICA_DIAL_Y)
        val scale=DashboardLayout.REPLICA_DIAL_RADIUS/208f
        c.scale(scale,scale)
        val value=if(speed) snapshot.speedKph?.roundToInt()?.toDouble() else snapshot.number(VehicleSignal.ENGINE_RPM)?.let { (it/10).roundToInt()*10.0 }
        val face=if(speed) speedFace else rpmFace
        face.draw(c, day, value, artworkTheme) { replicaGaugeFace(it, speed, value) }
        val cx=0f; val cy=0f
        if(speed) {
            text(c,speedNumber(),cx,cy+27,90f,white,Paint.Align.CENTER,216f)
            text(c,"km/h",cx,cy+63,25f,artworkTheme?.secondary ?: if(day) ReplicaDayPalette.secondary else Color.rgb(166,190,178),Paint.Align.CENTER,170f)
            coolantIcon(c,cx-49,cy+151)
            text(c,(snapshot.coolantGaugePercent?.roundToInt()?.toString() ?: "—")+"%",cx+26,cy+159,25f,
                white,Paint.Align.CENTER,112f)
        } else {
            replicaDialMusic(c)
            text(c,number(VehicleSignal.ENGINE_RPM)+" rpm",cx,cy+159,25f,white,Paint.Align.CENTER,210f, bold = day)
        }
        c.restore()
    }
    private val speedFace = DashboardDialFaceCache()
    private val rpmFace = DashboardDialFaceCache()
    internal val dialFaceBuildCount get() = speedFace.buildCount + rpmFace.buildCount
    private fun replicaGaugeFace(c: Canvas, speed: Boolean, value: Double?) {
        val theme=artworkTheme
        val cx=0f; val cy=0f
        val dial=if(speed) DashboardDial.CLASSIC_SPEED else DashboardDial.RPM
        val silver=theme?.text ?: Color.rgb(226,232,229)
        paint.shader=null; paint.style=Paint.Style.FILL; paint.color=theme?.surface ?: if(day) ReplicaDayPalette.surface else Color.BLACK
        c.drawCircle(cx,cy,208f,paint) // The stream can never reduce gauge contrast.
        paint.shader=LinearGradient(-180f,-190f,160f,195f,
            theme?.dialBase ?: if(day) ReplicaDayPalette.dialBase else intArrayOf(Color.rgb(3,13,11),Color.rgb(6,23,18),Color.rgb(22,37,31)),
            floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(cx,cy,197f,paint); paint.shader=null
        // Thin neutral metallic bezel, rather than a thick green outline.
        paint.style=Paint.Style.STROKE; paint.strokeWidth=1.2f
        paint.shader=LinearGradient(-180f,-208f,150f,208f,
            theme?.bezel ?: if(day) ReplicaDayPalette.bezel else intArrayOf(Color.rgb(88,101,94),Color.rgb(22,31,27),Color.rgb(109,118,109)),
            floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(cx,cy,207f,paint); paint.shader=null
        paint.strokeWidth=1f; paint.color=theme?.accent ?: if(day) ReplicaDayPalette.divider else Color.rgb(18,29,24)
        c.drawCircle(cx,cy,201f,paint)
        val arc=RectF(cx-164,cy-164,cx+164,cy+164)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=64f
        if(day || theme != null) {
            // The unnumbered 90-degree bridge stays pale even at maximum speed/RPM.
            paint.shader=LinearGradient(-164f,130f,164f,190f,theme?.bottomBand ?: ReplicaDayPalette.bottomBand,null,Shader.TileMode.CLAMP)
            c.drawArc(arc,45f,90f,false,paint)
            paint.shader=artworkSweep ?: replicaDaySweep
            if(value!=null) c.drawArc(arc,135f,dial.sweep(value),false,paint)
        } else {
            paint.shader=LinearGradient(-190f,-100f,120f,170f,
                intArrayOf(Color.rgb(245,247,244),Color.rgb(225,231,226),Color.rgb(164,178,168),Color.rgb(105,122,112),Color.rgb(6,20,15)),
                floatArrayOf(0f,.32f,.52f,.78f,1f),Shader.TileMode.CLAMP)
            c.drawArc(arc,45f,90f,false,paint)
            if(value!=null) c.drawArc(arc,135f,dial.sweep(value),false,paint)
        }
        paint.shader=null
        paint.strokeWidth=2f
        paint.shader=LinearGradient(-160f,180f,160f,180f,
            theme?.bezel ?: if(day) ReplicaDayPalette.bezel else intArrayOf(Color.rgb(217,225,216),Color.rgb(143,158,146),Color.rgb(35,48,40)),
            floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
        c.drawArc(RectF(-204f,-204f,204f,204f),45f,90f,false,paint); paint.shader=null
        for(i in 0..dial.divisions) {
            val tickValue=dial.valueAtTick(i)
            val a=Math.toRadians(dial.angle(tickValue).toDouble())
            val major=i%dial.majorEvery==0
            val silverTick=value!=null && tickValue<=value
            paint.style=Paint.Style.STROKE
            paint.color=when { !speed && tickValue>=7000 -> if(day) ReplicaDayPalette.red else Color.rgb(255,73,68); theme != null -> if(silverTick) theme.progressTextColor((tickValue/dial.maximum).toFloat()) else theme.text; day -> if(silverTick) ReplicaDayPalette.progressTextColor((tickValue/dial.maximum).toFloat()) else ReplicaDayPalette.text; silverTick -> Color.rgb(22,38,32); else -> silver }
            paint.strokeWidth=if(major) 2.2f else 1.1f
            val inner=if(major) 178f else 184f
            if(speed && !major) {
                paint.style=Paint.Style.FILL
                c.drawCircle(cx+cos(a).toFloat()*187.5f,cy+sin(a).toFloat()*187.5f,1.5f,paint)
            } else c.drawLine(cx+cos(a).toFloat()*inner,cy+sin(a).toFloat()*inner,
                cx+cos(a).toFloat()*191f,cy+sin(a).toFloat()*191f,paint)
            if(major) {
                val label=if(speed) tickValue.roundToInt().toString() else (tickValue/1000).roundToInt().toString()
                // A label straddling a sector edge needs contrast on both silver and jade.
                val edgeLabel=tickValue==0.0 || tickValue==dial.maximum || (value!=null && abs(tickValue-value)<dial.maximum/dial.divisions)
                val labelColor=if(!speed && tickValue>=7000) paint.color else if(edgeLabel && !day) white else paint.color
                if(edgeLabel && (!day || labelColor == Color.WHITE)) paint.setShadowLayer(2f,0f,0f,Color.BLACK)
                text(c,label,cx+cos(a).toFloat()*157f,cy+sin(a).toFloat()*157f+7,
                    if(speed) 20f else 22f,labelColor,Paint.Align.CENTER,60f)
                paint.clearShadowLayer()
            }
        }
        // Only RPM keeps an inner tick ring; speed has exactly three dots between each 20 km/h label.
        if(!speed) for(i in 0..90) {
            val a=Math.toRadians(135.0+i*3)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=1f
            val tickValue=dial.maximum*i/90.0
            paint.color=if(theme!=null) { if(value!=null && tickValue<=value) theme.progressTextColor((tickValue/dial.maximum).toFloat()) else theme.secondary } else if(day) { if(value!=null && tickValue<=value) ReplicaDayPalette.progressTextColor((tickValue/dial.maximum).toFloat()) else ReplicaDayPalette.secondary } else if(value!=null && tickValue<=value) Color.rgb(91,109,98) else Color.rgb(43,76,62)
            c.drawLine(cx+cos(a).toFloat()*130,cy+sin(a).toFloat()*130,
                cx+cos(a).toFloat()*135,cy+sin(a).toFloat()*135,paint)
        }
        paint.style=Paint.Style.FILL; paint.color=theme?.surface ?: if(day) ReplicaDayPalette.surface else Color.BLACK; c.drawCircle(cx,cy,123f,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=theme?.accent ?: if(day) ReplicaDayPalette.inactive else Color.rgb(18,67,52)
        c.drawCircle(cx,cy,126f,paint)
        if(value!=null) {
            paint.color=theme?.accent ?: if(day) ReplicaDayPalette.green else Color.rgb(75,232,192); paint.strokeWidth=2.5f
            c.drawArc(RectF(cx-126,cy-126,cx+126,cy+126),135f,dial.sweep(value),false,paint)
        }
    }
    /** Crisp artwork stays in the inner well; its palette is shared with both gauge faces. */
    private fun replicaDialMusic(c: Canvas) {
        val info = snapshot.music.info
        val title = info.title.orEmpty().ifBlank { info.album.orEmpty().ifBlank { info.artist.orEmpty() } }
        val bitmap = snapshot.music.artwork?.takeIf { title.isNotBlank() && !it.isRecycled }
        dialMusicBackdrop.update(bitmap.takeIf { artworkTheme == null })
        c.save()
        c.clipPath(Path().apply { addCircle(0f, 0f, 122f, Path.Direction.CW) })
        if (bitmap != null && artworkTheme == null) dialMusicBackdrop.draw(c, RectF(-123f, -123f, 123f, 123f), fadeEdges = false, light = day)
        if (title.isNotBlank()) {
            // About 203 physical pixels; rounded corners remain fully inside the inner well.
            val cover = RectF(-83f, -92f, 83f, 74f)
            if (bitmap != null) {
                c.save(); c.clipPath(Path().apply { addRoundRect(cover, 10f, 10f, Path.Direction.CW) })
                val side = min(bitmap.width, bitmap.height)
                val crop = Rect((bitmap.width-side)/2, (bitmap.height-side)/2, (bitmap.width+side)/2, (bitmap.height+side)/2)
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                c.drawBitmap(bitmap, crop, cover, paint); c.restore()
            } else text(c,"♪",0f,20f,70f,muted,Paint.Align.CENTER,180f)
            musicText(c,title,0f,94f,17f,white,Paint.Align.CENTER,140f)
            musicText(c,info.artist.orEmpty(),0f,111f,13f,artworkTheme?.secondary ?: if(day) ReplicaDayPalette.secondary else Color.rgb(185,196,190),Paint.Align.CENTER,80f)
        } else {
            text(c,"♪",0f,-4f,58f,muted,Paint.Align.CENTER,150f)
            text(c,context.getString(R.string.dashboard_no_music),0f,35f,18f,muted,Paint.Align.CENTER,196f)
        }
        c.restore()
    }
    private fun replicaMusic(c: Canvas,r: RectF) {
        val info=snapshot.music.info
        val hasTrack=!info.title.isNullOrBlank() || !info.artist.isNullOrBlank() || !info.album.isNullOrBlank()
        val art=RectF(r.centerX()-120,r.top+12,r.centerX()+120,r.top+252)
        box(c,art,if(day) ReplicaDayPalette.divider else Color.rgb(11,28,25),12f)
        val bitmap=snapshot.music.artwork?.takeIf { !it.isRecycled && hasTrack }
        if(bitmap!=null) {
            c.save(); c.clipPath(Path().apply { addRoundRect(art,12f,12f,Path.Direction.CW) })
            val side=min(bitmap.width,bitmap.height)
            paint.style=Paint.Style.FILL; paint.color=Color.WHITE
            c.drawBitmap(bitmap,Rect((bitmap.width-side)/2,(bitmap.height-side)/2,(bitmap.width+side)/2,(bitmap.height+side)/2),art,paint)
            c.restore()
        } else text(c,"♪",art.centerX(),art.centerY()+30,90f,muted,Paint.Align.CENTER,160f)
        val title=if(hasTrack) info.title.orEmpty().ifBlank { info.album.orEmpty().ifBlank { info.artist.orEmpty() } }
            else label(R.string.dashboard_no_music)
        musicText(c,title,r.centerX(),r.bottom-101,32f,if(hasTrack) white else muted,Paint.Align.CENTER,r.width()-48)
        musicText(c,listOfNotNull(info.artist?.takeIf { it.isNotBlank() },info.album?.takeIf { it.isNotBlank() }).joinToString(" · "),
            r.centerX(),r.bottom-64,24f,muted,Paint.Align.CENTER,r.width()-48)
        if(hasTrack) {
            val duration=info.durationMillis?.takeIf { it>0 }; val elapsed=info.elapsedMillis?.coerceAtLeast(0)
            val left=r.centerX()-210f
            box(c,RectF(left,r.bottom-31,left+420,r.bottom-26),Color.argb(115,160,170,165),3f)
            if(duration!=null && elapsed!=null) box(c,RectF(left,r.bottom-31,
                left+420*(elapsed.toFloat()/duration).coerceIn(0f,1f),r.bottom-26),artworkTheme?.accent ?: if(day) ReplicaDayPalette.green else Color.WHITE,3f)
        }
    }
    private fun replicaTires(c: Canvas,r: RectF) {
        c.save()
        val fit=min(1f,r.height()/396f)
        c.scale(fit,fit,r.centerX(),r.centerY())
        vehicleSilhouette(c,r.centerX(),r.centerY())
        val tires=listOf(
            Triple(VehicleSignal.TIRE_FL_BAR,R.string.dashboard_front_left,PointF(r.centerX()-218,r.centerY()-100)),
            Triple(VehicleSignal.TIRE_FR_BAR,R.string.dashboard_front_right,PointF(r.centerX()+218,r.centerY()-100)),
            Triple(VehicleSignal.TIRE_RL_BAR,R.string.dashboard_rear_left,PointF(r.centerX()-218,r.centerY()+86)),
            Triple(VehicleSignal.TIRE_RR_BAR,R.string.dashboard_rear_right,PointF(r.centerX()+218,r.centerY()+86)))
        tires.forEach { (signal,title,p) ->
            text(c,label(title),p.x,p.y-25,21f,muted,Paint.Align.CENTER,160f)
            text(c,snapshot.number(signal)?.let { String.format(Locale.US,"%.2f",it) } ?: "—",
                p.x,p.y+22,43f,white,Paint.Align.CENTER,180f)
            text(c,"bar",p.x,p.y+52,21f,muted,Paint.Align.CENTER,100f)
        }
        c.restore()
    }
    /** A single, opaque, centered strip is drawn last on every full dashboard page. */
    private inline fun replicaIcon(c: Canvas, x: Float, y: Float, sourceSize: Float, targetSize: Float = REPLICA_STATUS_ICON_SIZE, draw: () -> Unit) {
        c.save(); c.scale(targetSize * panelScale / sourceSize, targetSize * panelScale / sourceSize, x, y)
        draw(); c.restore()
    }
    // Only the edge facing the content is rounded; the screen edge stays flush.
    private val replicaBarPath = Path()
    private val replicaTopBarRadii = floatArrayOf(0f,0f,0f,0f,20f,20f,20f,20f)
    private val replicaBottomBarRadii = floatArrayOf(20f,20f,20f,20f,0f,0f,0f,0f)
    private fun replicaBar(c: Canvas, bounds: RectF, roundTop: Boolean) {
        if (style.concept) {
            concepts.bar(c,bounds,roundTop,style,day)
            return
        }
        replicaBarPath.reset()
        replicaBarPath.addRoundRect(bounds, if (roundTop) replicaBottomBarRadii else replicaTopBarRadii, Path.Direction.CW)
        paint.color = artworkTheme?.surface ?: if(day) { if(style.concept) 0xffe4e3e1.toInt() else ReplicaDayPalette.surface } else bg; paint.style = Paint.Style.FILL
        c.drawPath(replicaBarPath, paint)
    }
    private fun replicaStatus(c: Canvas) {
        // Expand both sides equally without crowding or shifting the status icons.
        replicaBar(c, RectF(526f - REPLICA_STATUS_HORIZONTAL_PADDING,0f,
            1394f + REPLICA_STATUS_HORIZONTAL_PADDING,REPLICA_HEADER_HEIGHT), roundTop = false)
        replicaIcon(c,546f,30f,30f) { lampIcon(c,VehicleSignal.LEFT_TURN,546f,30f) }
        replicaIcon(c,600f,30f,40f) { replicaHeadlamp(c,600f,30f) }
        replicaIcon(c,666.67f,30f,34f) { lampIcon(c,VehicleSignal.HIGH_BEAM,666.67f,30f) }
        replicaIcon(c,733.33f,30f,36f) { lampIcon(c,VehicleSignal.POSITION_LIGHT,733.33f,30f) }
        replicaIcon(c,794f,30f,36f) { lampIcon(c,VehicleSignal.REAR_FOG,794f,30f) }
        if(snapshot.vehicle[VehicleSignal.HILL_DESCENT_ACTIVE]?.value==true) replicaIcon(c,850f,30f,36f,32f) {
            hillDescentGlyph?.apply { setTint(green); setBounds(832,12,868,48); draw(c) }
        }
        replicaIcon(c,906f,30f,50f) { laneCenteringIcon(c,906f,30f) }
        val assist=snapshot.assistance
        val color=when { assist.state==DashboardAssistState.FAULT -> amber; snapshot.assistanceIconActive -> green; else -> muted }
        when(assist.mode) {
            DashboardAssistMode.GPILOT -> replicaIcon(c,964f,30f,40f) { steeringIcon(c,964f,30f,color) }
            DashboardAssistMode.ACC -> replicaIcon(c,964f,30f,48f) { followingCarIcon(c,964f,30f,color) }
            else -> replicaIcon(c,964f,30f,40f,28f) { cruiseIcon(c,964f,30f,color) }
        }
        val title=when(assist.mode) {
            DashboardAssistMode.CRUISE, DashboardAssistMode.OFF -> label(R.string.dashboard_cruise)
            DashboardAssistMode.ACC -> "ACC"
            DashboardAssistMode.GPILOT -> "GPilot"
            DashboardAssistMode.UNKNOWN -> ""
        }
        // State is conveyed by color, with an explicit fault mark; no active/standby text.
        paint.textSize=23f; paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        val size=23f*min(1f,82f/paint.measureText(title).coerceAtLeast(1f))
        if(title.isNotEmpty()) text(c,title,996f,39f,size,if(assist.state==DashboardAssistState.FAULT) amber else white,maxWidth=83f)
        val targetX=if(title.isEmpty()) 996f else 996f+paint.measureText(title)+12f
        if(assist.state==DashboardAssistState.FAULT) text(c,"!",985f,17f,18f,amber,Paint.Align.CENTER,10f)
        if(assist.showTarget) text(c,(snapshot.accSetSpeedKph?.roundToInt()?.toString() ?: "—")+" km/h",
            targetX,40f,27f,if(snapshot.accSetSpeedKph!=null) white else muted,maxWidth=140f)
        if(assist.showGap) {
            replicaIcon(c,1254f,30f,48f) { followingCarIcon(c,1254f,30f,white) }
            val gap=snapshot.followGap ?: 0
            c.save(); c.scale(panelScale,panelScale,1302f,30f)
            for(i in 0..2) {
                val halfWidth=20f-i*6f
                box(c,RectF(1302f-halfWidth,18f+i*11,1302f+halfWidth,22f+i*11),if(i<gap) white else line,2f)
            }
            c.restore()
        }
        replicaIcon(c,1374f,30f,30f) { lampIcon(c,VehicleSignal.RIGHT_TURN,1374f,30f) }
    }
    /** Midnight / Panorama reserve the top wings for vehicle data, leaving the map bottom unobstructed. */
    private fun topVehicleInfo(c: Canvas) {
        replicaBar(c,RectF(16f,0f,482f,60f),roundTop=false)
        replicaBar(c,RectF(1438f,0f,1904f,60f),roundTop=false)
        text(c,clockFormat.format(Date()),80f,39f,24f,white,Paint.Align.CENTER,100f)
        text(c,(snapshot.ambientC?.roundToInt()?.toString() ?: "—")+"°C",212f,39f,24f,white,Paint.Align.CENTER,104f)
        replicaIcon(c,310f,30f,34f,26f) { fuelIcon(c,310f,30f) }
        text(c,(snapshot.rangeKm?.roundToInt()?.toString() ?: "—")+" km",344f,39f,24f,white,maxWidth=126f)
        for(x in listOf(145f,278f,1582f,1726f)) box(c,RectF(x,17f,x+1f,43f),muted,0f)
        val hold=snapshot.vehicle[VehicleSignal.AUTO_HOLD_ACTIVE]?.value==true
        val parking=snapshot.vehicle[VehicleSignal.PARKING_BRAKE_ON]?.value==true
        replicaIcon(c,1510f,30f,38f) { brakeIcon(c,1510f,30f,"A",if(hold) green else muted,active=hold) }
        replicaIcon(c,1654f,30f,38f) { brakeIcon(c,1654f,30f,"P",if(parking) red else muted,active=parking) }
        text(c,snapshot.gear,1810f,41f,32f,white,Paint.Align.CENTER,80f)
    }
    private fun replicaHeadlamp(c: Canvas,x: Float,y: Float) {
        // Merge AUTO into the low-beam body. An A requires confirmed selector feedback;
        // the lamp's color still follows actual low-beam feedback, not selector position.
        val color=when(snapshot.vehicle[VehicleSignal.LOW_BEAM]?.value) { true -> green; false -> line; else -> muted }
        paint.style=Paint.Style.STROKE; paint.color=color; paint.strokeWidth=2.5f
        c.drawPath(Path().apply { moveTo(x+1,y-13); cubicTo(x+22,y-14,x+22,y+14,x+1,y+13); close() },paint)
        for(i in 0..3) c.drawLine(x-20,y-11+i*7,x-5,y-14+i*7,paint)
        if(snapshot.vehicle[VehicleSignal.AUTO_HEADLIGHTS]?.value==true)
            text(c,"A",x+10,y+5,14f,color,Paint.Align.CENTER,16f)
    }
    private fun replicaFooter(c: Canvas) {
        val hold = snapshot.vehicle[VehicleSignal.AUTO_HOLD_ACTIVE]?.value == true
        val parking = snapshot.vehicle[VehicleSignal.PARKING_BRAKE_ON]?.value == true
        // Both indicators keep a stable slot; confirmed AutoHold is green and confirmed parking brake is red.
        // Fixed cells leave room for 1.5× glyphs; values, separators and the panel never scale.
        val contentWidth = 706f
        val left = 960f - contentWidth / 2f
        replicaBar(c, RectF(left-20f,532f-REPLICA_FOOTER_HEIGHT,left+contentWidth+20f,532f), roundTop = true)
        text(c,clockFormat.format(Date()),left+50f,514f,24f,white,Paint.Align.CENTER,100f)
        for(x in listOf(left+114f,left+242f,left+464f,left+556f,left+648f))
            box(c,RectF(x,492f,x+1,518f),muted,0f)
        text(c,(snapshot.ambientC?.roundToInt()?.toString() ?: "—")+"°C",left+178f,514f,24f,white,Paint.Align.CENTER,100f)
        val value=(snapshot.rangeKm?.roundToInt()?.toString() ?: "—")+" km"
        replicaIcon(c,left+274f,505f,34f,26f) { fuelIcon(c,left+274f,505f) }
        text(c,value,left+308f,514f,24f,white,maxWidth=140f)
        replicaIcon(c,left+510f,505f,38f) { brakeIcon(c,left+510f,505f,"A",if (hold) green else muted,active=hold) }
        replicaIcon(c,left+602f,505f,38f) { brakeIcon(c,left+602f,505f,"P",if (parking) red else muted,active=parking) }
        text(c,snapshot.gear,left+684f,516f,30f,white,Paint.Align.CENTER,44f)
    }
    private fun coolantIcon(c:Canvas,x:Float,y:Float) {
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2.6f; paint.color=white
        c.drawPath(Path().apply { moveTo(x-4,y+3); lineTo(x-4,y-16); quadTo(x,y-21,x+4,y-16); lineTo(x+4,y+3) },paint)
        c.drawCircle(x,y+6,6f,paint)
        for(i in 0..2) c.drawLine(x+5,y-13+i*5,x+11,y-13+i*5,paint)
        for(dy in listOf(15f,21f)) c.drawPath(Path().apply {
            moveTo(x-19,y+dy); quadTo(x-13,y+dy-5,x-7,y+dy); quadTo(x-1,y+dy+5,x+5,y+dy)
            quadTo(x+11,y+dy-5,x+17,y+dy)
        },paint)
    }
    private fun brakeIcon(c:Canvas,x:Float,y:Float,value:String,color:Int,active:Boolean) {
        paint.style=Paint.Style.STROKE; paint.strokeWidth=if(active) 3.8f else 2.2f; paint.color=color
        c.drawCircle(x,y,13f,paint)
        c.drawArc(RectF(x-19,y-19,x+19,y+19),145f,70f,false,paint)
        c.drawArc(RectF(x-19,y-19,x+19,y+19),-35f,70f,false,paint)
        text(c,value,x,y+6,17f,color,Paint.Align.CENTER,20f,bold=active)
    }
    private fun updateArtworkTheme() {
        val info=snapshot.music.info
        val hasTrack=!info.title.isNullOrBlank() || !info.artist.isNullOrBlank() || !info.album.isNullOrBlank()
        // Explicit playback feedback gates the full-screen theme; pause returns to the chosen day/night palette.
        val cover=snapshot.music.artwork.takeIf { style==DashboardStyle.REPLICA && hasTrack && info.playing }
        musicBackdrop.update(cover)
        val next=musicBackdrop.palette
        if(next!=artworkTheme) {
            artworkTheme=next
            artworkSweep=next?.let { theme ->
                SweepGradient(0f,0f,theme.sweep+theme.sweep.last(),floatArrayOf(0f,.1875f,.375f,.5625f,.75f,1f)).apply {
                    setLocalMatrix(Matrix().apply { setRotate(135f) })
                }
            }
        }
    }
    internal val artworkBlurBuildCount get() = musicBackdrop.buildCount
    override fun onDetachedFromWindow() {
        removeCallbacks(marqueeFrame); nextMarqueeAt = Long.MAX_VALUE
        speedFace.clear(); rpmFace.clear()
        lastVisualState = null; lastVisualLayout = null
        musicBackdrop.clear(); artworkTheme=null; artworkSweep=null
        dialMusicBackdrop.clear()
        musicMarquee.clear()
        super.onDetachedFromWindow()
    }
    private fun number(signal: VehicleSignal) = snapshot.number(signal)?.roundToInt()?.toString() ?: "—"
    private fun speedNumber() = snapshot.speedKph?.roundToInt()?.toString() ?: "—"
    /** Music/tire double-dial pages use all available height below the status row. */
    private fun fullHeightMidnightGauge(c: Canvas,cx:Float,speed:Boolean) {
        c.save();c.translate(cx,296f)
        paint.style=Paint.Style.FILL;paint.color=Color.rgb(8,21,32)
        c.drawCircle(0f,0f,234f,paint)
        val factor=234f/189.5f
        c.scale(factor,factor);c.translate(-224f,-245f)
        gauge(c,224f,speed);c.restore()
    }
    private fun gauge(c: Canvas, cx: Float, speed: Boolean, compact: Boolean = false) {
        val cy = 245f; val radius = 180f
        val dial = if (speed) DashboardDial.SPEED else DashboardDial.RPM
        paint.color = Color.rgb(10,47,65); paint.style = Paint.Style.STROKE; paint.strokeWidth = 13f
        val oval = RectF(cx-radius, cy-radius, cx+radius, cy+radius)
        c.drawArc(oval, 135f, 270f, false, paint)
        val value = if (speed) snapshot.speedKph else snapshot.number(VehicleSignal.ENGINE_RPM)
        if (!speed) {
            paint.color = red; paint.strokeWidth = 7f
            c.drawArc(oval, dial.angle(7000.0), dial.sweep(1000.0), false, paint)
        }
        if (value != null) {
            paint.strokeCap = Paint.Cap.ROUND
            for ((width,alpha) in listOf(19f to 8,12f to 18)) {
                paint.color=Color.argb(alpha,Color.red(accent),Color.green(accent),Color.blue(accent));paint.strokeWidth=width
                c.drawArc(oval,135f,dial.sweep(value),false,paint)
            }
            paint.color = accent; paint.strokeWidth = 7f; paint.strokeCap = Paint.Cap.ROUND
            c.drawArc(oval, 135f, dial.sweep(value), false, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }
        for (i in 0..dial.divisions) {
            val angle = Math.toRadians(dial.angle(dial.valueAtTick(i)).toDouble())
            val major = i % dial.majorEvery == 0
            paint.color = if (major) white else Color.rgb(147,172,187); paint.strokeWidth = if (major) 3f else 1.1f
            val inner = if (major) radius - 24 else radius - 15
            c.drawLine(cx + cos(angle).toFloat()*inner, cy + sin(angle).toFloat()*inner,
                cx + cos(angle).toFloat()*(radius-6), cy + sin(angle).toFloat()*(radius-6), paint)
            if (major) text(c, dial.valueAtTick(i).roundToInt().toString(), cx + cos(angle).toFloat()*130,
                cy + sin(angle).toFloat()*130 + 7, if (speed) 20f else 18f, Color.rgb(203,218,230), Paint.Align.CENTER)
        }
        if (value != null) {
            val angle = Math.toRadians(dial.angle(value).toDouble())
            paint.color = accent; paint.strokeWidth = 4f
            c.drawLine(cx + cos(angle).toFloat()*(radius-23), cy + sin(angle).toFloat()*(radius-23),
                cx + cos(angle).toFloat()*radius, cy + sin(angle).toFloat()*radius, paint)
        }
        val display = if (speed) speedNumber() else number(VehicleSignal.ENGINE_RPM)
        text(c, display, cx, 278f, if (speed) { if(compact) 122f else 102f } else 76f, white, Paint.Align.CENTER, 250f)
        text(c, if (speed) "km/h" else "rpm", cx, 314f, 24f, muted, Paint.Align.CENTER)

    }
    private fun navigation(c: Canvas, r: RectF) {
        val nav = snapshot.navigation ?: return
        val res = when(nav.icon) {
            2 -> R.drawable.ic_turn_card_turn_left; 3 -> R.drawable.ic_turn_card_turn_right
            4 -> R.drawable.ic_turn_card_turn_slight_left; 5 -> R.drawable.ic_turn_card_turn_slight_right
            6 -> R.drawable.ic_turn_card_turn_sharp_left; 7 -> R.drawable.ic_turn_card_turn_sharp_right
            8 -> R.drawable.ic_turn_card_u_turn_left; 19 -> R.drawable.ic_turn_card_u_turn_right
            11,12,17,18 -> R.drawable.ic_turn_card_roundabout_right; 15 -> R.drawable.ic_turn_card_sports_score
            else -> R.drawable.ic_turn_card_straight
        }
        if (res != glyphId) { glyphId = res; glyph = context.getDrawable(res)?.mutate() }
        if (style.concept) {
            val distance = if (nav.distanceMeters < 0) "—" else if (nav.distanceMeters <= 20)
                context.getString(R.string.turn_card_now) else distance(nav.distanceMeters)
            val remaining = listOfNotNull(nav.remainingMeters?.takeIf { it >= 0 }?.let { distance(it.toInt()) },
                nav.remainingSeconds?.takeIf { it >= 0 }?.let { "${(it + 59) / 60} min" }).joinToString(" · ")
            concepts.navigation(c, r, style, day, glyph, nav.icon, distance, nav.road, remaining)
            return
        }
        box(c,r,if(style.concept) { if(day) 0xffe4e3e1.toInt() else 0xe6222537.toInt() } else when(style) {
            DashboardStyle.REPLICA -> if(day) Color.argb(242,248,250,247) else Color.argb(205,6,18,24)
            DashboardStyle.MIDNIGHT -> Color.argb(235,6,18,24)
            else -> panel
        },16f)
        if (style == DashboardStyle.MIDNIGHT || style == DashboardStyle.PANORAMA) {
            paint.style=Paint.Style.STROKE;paint.strokeWidth=1.2f;paint.color=line
            c.drawRoundRect(r,14f,14f,paint)
            guidanceGlyph.draw(c,RectF(r.left+24,r.top+22,r.left+90,r.top+90),nav.icon,accent,glyph)
            val d=if(nav.distanceMeters<0) "—" else if(nav.distanceMeters<=20) context.getString(R.string.turn_card_now) else distance(nav.distanceMeters)
            val road=if(nav.roundaboutExit in 1..9) context.getString(R.string.turn_card_exit,nav.roundaboutExit)+" · "+nav.road else nav.road
            val remaining=listOfNotNull(nav.remainingMeters?.takeIf { it>=0 }?.let { distance(it.toInt()) },
                nav.remainingSeconds?.takeIf { it>=0 }?.let { "${(it+59)/60} min" }).joinToString(" · ")
            text(c,d,r.left+120,r.top+40,37f,white,maxWidth=r.width()-144)
            text(c,road,r.left+120,r.top+76,25f,white,maxWidth=r.width()-144)
            text(c,remaining,r.left+120,r.bottom-18,21f,muted,maxWidth=r.width()-144)
            return
        }
        glyph?.setTint(if(style==DashboardStyle.REPLICA) white else accent) // A live style change may retain the same turn glyph.
        val size = r.height() - 26
        glyph?.setBounds((r.left+14).toInt(), (r.top+13).toInt(), (r.left+14+size).toInt(), (r.bottom-13).toInt()); glyph?.draw(c)
        val x = r.left + size + 32
        val distance = if (nav.distanceMeters < 0) "—" else if (nav.distanceMeters <= 20) context.getString(R.string.turn_card_now) else distance(nav.distanceMeters)
        if (style == DashboardStyle.REPLICA) {
            text(c, distance, x, r.top+35, 31f, white, maxWidth=r.width()-size-246)
            text(c, nav.road, x, r.bottom-14, 23f, muted, maxWidth=r.width()-size-246)
            text(c, nav.remainingMeters?.takeIf { it >= 0 }?.let { distance(it.toInt()) } ?: "—", r.right-22, r.top+33,
                25f, white, Paint.Align.RIGHT, 180f)
            text(c, nav.remainingSeconds?.takeIf { it >= 0 }?.let { "${(it+59)/60} min" } ?: "—", r.right-22, r.bottom-14,
                22f, muted, Paint.Align.RIGHT, 180f)
            return
        }
        text(c, distance, x, r.top+34, 29f, white, maxWidth = r.width()-size-60)
        val road = if (nav.roundaboutExit in 1..9) context.getString(R.string.turn_card_exit, nav.roundaboutExit) + " · " + nav.road else nav.road
        text(c, road, x, r.bottom-15, 21f, muted, maxWidth = r.width()-size-60)

    }
    private fun oemMusic(c: Canvas, r: RectF) {
        val info = snapshot.music.info
        val title = info.title.orEmpty().ifBlank { info.album.orEmpty().ifBlank { info.artist.orEmpty() } }
        if (title.isBlank()) return
        box(c, r, Color.argb(230, 7, 17, 24), 16f)
        val art = RectF(r.left + 18, r.top + 18, r.left + 94, r.top + 94)
        val bitmap = snapshot.music.artwork?.takeIf { !it.isRecycled }
        if (bitmap != null) {
            c.save(); c.clipPath(Path().apply { addRoundRect(art, 10f, 10f, Path.Direction.CW) })
            val side = min(bitmap.width, bitmap.height)
            val crop = Rect((bitmap.width-side)/2, (bitmap.height-side)/2, (bitmap.width+side)/2, (bitmap.height+side)/2)
            paint.color = Color.WHITE; paint.style = Paint.Style.FILL
            c.drawBitmap(bitmap, crop, art, paint); c.restore()
        } else text(c, "♪", art.centerX(), art.centerY()+15, 42f, accent, Paint.Align.CENTER)
        musicText(c, title, art.right+22, r.top+47, 29f, white, maxWidth=r.right-art.right-44)
        musicText(c, info.artist.orEmpty(), art.right+22, r.top+82, 22f, muted, maxWidth=r.right-art.right-44)
    }

    private fun distance(meters: Int): String = if (meters < 1000) "$meters m" else String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
    private fun trip(c: Canvas, r: RectF) {
        contentPanel(c, r)
        text(c, context.getString(R.string.dashboard_arrival), r.left+23, r.top+32, 20f, muted, maxWidth=r.width()-46)
        val nav = snapshot.navigation
        val arrival = nav?.arrivalEpochSeconds?.takeIf { it > 0 }?.let { clockFormat.format(Date(it * 1000)) } ?: "—"
        text(c, arrival, r.left+23, r.top+81, 38f, white)
        val remaining = listOfNotNull(nav?.remainingSeconds?.takeIf { it >= 0 }?.let { "${(it+59)/60} min" },
            nav?.remainingMeters?.takeIf { it >= 0 }?.let { distance(it.toInt()) }).joinToString(" · ").ifBlank { "—" }
        text(c, remaining, r.left+23, r.bottom-18, 21f, accent, maxWidth=r.width()-46)
    }
    private fun music(c: Canvas, r: RectF) {
        if(style.concept) { conceptMusic(c,r);return }
        if (style == DashboardStyle.REPLICA) { replicaMusic(c, r); return }
        if (style == DashboardStyle.MIDNIGHT && r.width() > 450f) { midnightMusic(c, r); return }
        contentPanel(c, r)
        val info = snapshot.music.info
        val hasTrack = !info.title.isNullOrBlank() || !info.artist.isNullOrBlank() || !info.album.isNullOrBlank()
        val art = RectF(r.left+16, r.top+16, r.left+120, r.top+120)
        box(c, art, Color.rgb(23, 43, 51), 9f)
        val bitmap = snapshot.music.artwork?.takeIf { !it.isRecycled && hasTrack }
        if (bitmap != null) {
            c.save(); c.clipPath(Path().apply { addRoundRect(art, 9f, 9f, Path.Direction.CW) })
            val side = min(bitmap.width, bitmap.height)
            val crop = Rect((bitmap.width-side)/2, (bitmap.height-side)/2, (bitmap.width+side)/2, (bitmap.height+side)/2)
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE; c.drawBitmap(bitmap, crop, art, paint); c.restore()
        } else text(c, "♪", art.centerX(), art.centerY()+16, 47f, muted, Paint.Align.CENTER)
        val x = art.right+18
        val title = if (hasTrack) info.title.orEmpty().ifBlank { info.album.orEmpty().ifBlank { info.artist.orEmpty() } }
            else label(R.string.dashboard_no_music)
        musicText(c, title, x, r.top+45, 28f, if (hasTrack) white else muted, maxWidth=r.right-x-16)
        val detail = listOfNotNull(info.artist?.takeIf { it.isNotBlank() }, info.album?.takeIf { it.isNotBlank() }).joinToString(" · ")
        musicText(c, detail, x, r.top+78, 20f, muted, maxWidth=r.right-x-16)
        if (!hasTrack) return
        val left=x; val right=r.right-16; val y=r.bottom-30
        box(c, RectF(left,y,right,y+5),line,3f)
        val duration=info.durationMillis?.takeIf { it>0 }; val elapsed=info.elapsedMillis?.coerceAtLeast(0)
        if (duration!=null && elapsed!=null) box(c,RectF(left,y,left+(right-left)*(elapsed.toFloat()/duration).coerceIn(0f,1f),y+5),accent,3f)
        // The compact Panorama card intentionally has no elapsed/total time labels or controls.
    }
    private fun conceptMusic(c: Canvas, r: RectF) {
        val info=snapshot.music.info
        val title=info.title.orEmpty().ifBlank { info.album.orEmpty().ifBlank { info.artist.orEmpty() } }
        val art=snapshot.music.artwork?.takeUnless { it.isRecycled || title.isBlank() }
        val side=min(r.width()-20f,r.height()-72f).coerceAtLeast(10f)
        val cover=RectF(r.centerX()-side/2,r.top,r.centerX()+side/2,r.top+side)
        if(art!=null) {
            c.save();c.clipPath(Path().apply { addRoundRect(cover,10f,10f,Path.Direction.CW) })
            val crop=min(art.width,art.height)
            paint.style=Paint.Style.FILL;paint.shader=null;paint.color=Color.WHITE
            c.drawBitmap(art,Rect((art.width-crop)/2,(art.height-crop)/2,(art.width+crop)/2,(art.height+crop)/2),cover,paint);c.restore()
        } else text(c,"♪",r.centerX(),cover.centerY()+20,64f,muted,Paint.Align.CENTER,r.width())
        val inset = r.width() < 400f
        val leftAligned = inset && style == DashboardStyle.OBSIDIAN
        val tx=if(leftAligned) cover.left else r.centerX()
        val align=if(leftAligned) Paint.Align.LEFT else Paint.Align.CENTER
        val textWidth=if(leftAligned) r.right-cover.left-10 else r.width()-16
        val titleSize=if(inset && style==DashboardStyle.TRACK) 30f else 23f
        musicText(c,title.ifBlank { label(R.string.dashboard_no_music) },tx,cover.bottom+titleSize+5,titleSize,white,align,textWidth)
        musicText(c,info.artist.orEmpty(),tx,cover.bottom+titleSize+34,20f,muted,align,textWidth)
        // Starfield's original inset is deliberately just cover, metadata and RPM; the center page retains playback progress.
        if(title.isNotBlank() && !(inset && style==DashboardStyle.STARFIELD)) {
            val duration=info.durationMillis?.takeIf { it>0 }
            val elapsed=info.elapsedMillis?.coerceAtLeast(0)
            if(duration!=null && elapsed!=null) {
                val y=r.bottom-5f
                val progressColor=if(style==DashboardStyle.OBSIDIAN) { if(day) 0xff00b58d.toInt() else 0xff4de4b9.toInt() } else accent
                box(c,RectF(r.left+10,y,r.right-10,y+4),line,2f)
                box(c,RectF(r.left+10,y,r.left+10+(r.width()-20)*(elapsed.toFloat()/duration).coerceIn(0f,1f),y+4),progressColor,2f)
            }
        }
    }
    private fun durationLabel(millis: Long) = String.format(Locale.US, "%d:%02d", millis/60000, (millis/1000)%60)
    private fun contentPanel(c: Canvas, r: RectF) {
        box(c, r, if (style == DashboardStyle.PANORAMA) Color.rgb(8, 25, 32) else Color.rgb(8, 23, 36), 14f)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=1.5f; paint.color=line
        c.drawRoundRect(r,14f,14f,paint)
    }
    private fun midnightMusic(c: Canvas, r: RectF) {
        contentPanel(c,r)
        val info=snapshot.music.info
        val hasTrack=!info.title.isNullOrBlank() || !info.artist.isNullOrBlank() || !info.album.isNullOrBlank()
        val art=RectF(r.left+28,r.centerY()-138,r.left+304,r.centerY()+138)
        box(c,art,Color.rgb(23,43,59),14f)
        val bitmap=snapshot.music.artwork?.takeIf { !it.isRecycled && hasTrack }
        if(bitmap!=null) {
            c.save(); c.clipPath(Path().apply { addRoundRect(art,14f,14f,Path.Direction.CW) })
            val side=min(bitmap.width,bitmap.height)
            paint.style=Paint.Style.FILL; paint.color=Color.WHITE
            c.drawBitmap(bitmap,Rect((bitmap.width-side)/2,(bitmap.height-side)/2,(bitmap.width+side)/2,(bitmap.height+side)/2),art,paint)
            c.restore()
        } else text(c,"♪",art.centerX(),art.centerY()+26,76f,muted,Paint.Align.CENTER,200f)
        val x=art.right+32; val right=r.right-30
        if(!hasTrack) {
            text(c,label(R.string.dashboard_no_music),x,r.centerY()+10,30f,muted,maxWidth=right-x)
            return
        }
        text(c,label(if(info.playing) R.string.dashboard_playing else R.string.dashboard_paused),x,art.top+30,20f,muted,maxWidth=right-x)
        val title=info.title.orEmpty().ifBlank { info.album.orEmpty().ifBlank { info.artist.orEmpty() } }
        musicText(c,title,x,art.top+108,47f,white,maxWidth=right-x-58)
        text(c,if(info.playing) "Ⅱ" else "▶",right,art.top+104,30f,muted,Paint.Align.RIGHT,46f)
        musicText(c,listOfNotNull(info.artist?.takeIf { it.isNotBlank() },info.album?.takeIf { it.isNotBlank() }).joinToString(" · "),
            x,art.top+156,25f,muted,maxWidth=right-x)
        val y=art.bottom-38
        val duration=info.durationMillis?.takeIf { it>0 }; val elapsed=info.elapsedMillis?.coerceAtLeast(0)
        box(c,RectF(x,y,right,y+5),line,3f)
        if(duration!=null && elapsed!=null) box(c,RectF(x,y,x+(right-x)*(elapsed.toFloat()/duration).coerceIn(0f,1f),y+5),accent,3f)
        text(c,elapsed?.let(::durationLabel) ?: "—",x,y+30,20f,muted)
        text(c,duration?.let(::durationLabel) ?: "—",right,y+30,20f,muted,Paint.Align.RIGHT)
    }
    private fun footer(c: Canvas) {
        if (style == DashboardStyle.MIDNIGHT) midnightFooter(c) else panoramaFooter(c)
        // Clock and temperature remain independent of the selected content page.
        val temperature = snapshot.ambientC?.let { String.format(Locale.getDefault(), "%.1f°C", it) } ?: "—°C"
        if (style == DashboardStyle.MIDNIGHT) {
            text(c, clockFormat.format(Date()), 26f, 35f, 22f, white)
            text(c, temperature, 1894f, 35f, 22f, white, Paint.Align.RIGHT)
        } else {
            text(c, clockFormat.format(Date()), 30f, 511f, 23f, white, maxWidth=95f)
            text(c, temperature, 160f, 511f, 23f, muted, maxWidth=160f)
        }
    }
    private val amber get() = if(day) ReplicaDayPalette.amber else Color.rgb(255, 193, 90)
    private val red get() = if(day) ReplicaDayPalette.red else Color.rgb(255, 105, 104)
    private val green get() = if(day) ReplicaDayPalette.green else Color.rgb(100, 224, 166)
    private fun label(id: Int) = context.getString(id)
    private fun panoramaSpeed(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f,0f,432f,532f,0xff07141b.toInt(),0xff020b11.toInt(),Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,432f,532f,paint)
        paint.shader = null
        text(c, speedNumber(), 216f, 246f, 184f, white, Paint.Align.CENTER, 390f)
        text(c, "km/h", 216f, 286f, 29f, muted, Paint.Align.CENTER, 240f)
        text(c, number(VehicleSignal.ENGINE_RPM)+" rpm", 216f, 326f, 24f, muted, Paint.Align.CENTER, 270f)
        box(c,RectF(431f,0f,432f,532f),line,0f)

    }
    private fun panoramaTires(c: Canvas, r: RectF) {
        contentPanel(c,r)
        text(c,label(R.string.dashboard_tires),r.centerX(),r.top+26,19f,muted,Paint.Align.CENTER,r.width()-30)
        car(c,r.centerX(),r.top+88,34f,65f)
        val positions=listOf(
            Triple(VehicleSignal.TIRE_FL_BAR,R.string.dashboard_front_left,PointF(r.left+16,r.top+64)),
            Triple(VehicleSignal.TIRE_FR_BAR,R.string.dashboard_front_right,PointF(r.centerX()+36,r.top+64)),
            Triple(VehicleSignal.TIRE_RL_BAR,R.string.dashboard_rear_left,PointF(r.left+16,r.top+114)),
            Triple(VehicleSignal.TIRE_RR_BAR,R.string.dashboard_rear_right,PointF(r.centerX()+36,r.top+114)))
        positions.forEach { (signal,title,p) ->
            text(c,label(title),p.x,p.y,17f,muted,maxWidth=54f)
            text(c,snapshot.number(signal)?.let { String.format(Locale.US,"%.2f",it) } ?: "—",
                p.x+60,p.y+1,26f,white,maxWidth=66f)
        }
    }
    private fun panoramaFooter(c: Canvas) {
        box(c,RectF(0f,472f,1920f,532f),bg,0f)
        box(c,RectF(0f,472f,1920f,473f),line,0f)
        for (x in listOf(744f,1070f,1440f)) box(c,RectF(x,488f,x+1,518f),line,0f)
        val assist=snapshot.assistance
        val color=when { assist.state==DashboardAssistState.FAULT -> amber; snapshot.assistanceIconActive -> green; else -> muted }
        when(assist.mode) {
            DashboardAssistMode.GPILOT -> steeringIcon(c,506f,502f,color)
            DashboardAssistMode.ACC -> adaptiveCruiseIcon(c,506f,502f,color)
            else -> cruiseIcon(c,506f,502f,color)
        }
        val title=when(assist.mode) {
            DashboardAssistMode.OFF -> label(R.string.dashboard_cruise_off)
            DashboardAssistMode.CRUISE -> label(R.string.dashboard_cruise)
            DashboardAssistMode.ACC -> "ACC"
            DashboardAssistMode.GPILOT -> "GPilot"
            DashboardAssistMode.UNKNOWN -> label(R.string.dashboard_assist_unknown)
        }
        val state=when(assist.state) {
            DashboardAssistState.STANDBY -> R.string.dashboard_standby
            DashboardAssistState.OVERRIDE -> R.string.dashboard_override
            DashboardAssistState.STOPPED -> R.string.dashboard_stopped
            DashboardAssistState.WAITING -> R.string.dashboard_waiting
            DashboardAssistState.FAULT -> R.string.dashboard_fault
            DashboardAssistState.UNKNOWN -> if(assist.mode==DashboardAssistMode.UNKNOWN) null else R.string.dashboard_assist_unknown
            else -> null
        }
        text(c,title+(state?.let { " · "+label(it) } ?: ""),542f,511f,22f,
            if(assist.state==DashboardAssistState.FAULT) amber else white,maxWidth=188f)
        if(assist.showTarget) {
            text(c,label(if(assist.mode==DashboardAssistMode.ACC || assist.mode==DashboardAssistMode.GPILOT)
                R.string.dashboard_acc_set else R.string.dashboard_cruise_set),770f,511f,20f,muted,maxWidth=151f)
            text(c,snapshot.accSetSpeedKph?.roundToInt()?.toString() ?: "—",978f,514f,33f,
                if(snapshot.accSetSpeedKph!=null) white else muted,Paint.Align.RIGHT,70f)
            text(c,"km/h",991f,511f,20f,muted,maxWidth=70f)
        }
        if(assist.showGap) {
            followingCarIcon(c,1120f,502f,white)
            val gap=snapshot.followGap
            if(gap==null) text(c,"—",1177f,511f,24f,muted,Paint.Align.CENTER,56f)
            else for(i in 0..2) {
                val halfWidth=19f-i*5f
                box(c,RectF(1177f-halfWidth,486f+i*13,1177f+halfWidth,492f+i*13),if(i<gap) white else line,3f)
            }
        }
        val fuel=snapshot.number(VehicleSignal.FUEL_PERCENT)
        fuelIcon(c,1490f,502f)
        box(c,RectF(1528f,498f,1632f,507f),line,4f)
        fuel?.let { box(c,RectF(1528f,498f,1528f+(104*it.coerceIn(0.0,100.0)/100).toFloat(),507f),if(it<=10) amber else accent,4f) }
        text(c,(fuel?.roundToInt()?.toString() ?: "—")+"%",1710f,511f,24f,white,Paint.Align.RIGHT,70f)
        text(c,(snapshot.rangeKm?.roundToInt()?.toString() ?: "—")+" km",1894f,511f,24f,white,Paint.Align.RIGHT,160f)
    }
    private fun modeTabs(c: Canvas) {
        val tabs = listOf(DashboardMode.MUSIC to R.string.dashboard_tab_music,
            DashboardMode.NAVIGATION to R.string.dashboard_tab_navigation, DashboardMode.TIRES to R.string.dashboard_tab_tires)
        tabs.forEachIndexed { index, (page, title) ->
            val x = 1185f + index * 140f
            val selected = mode.forStyle(style) == page
            text(c, label(title), x, 35f, 22f, if (selected) accent else muted, Paint.Align.CENTER, 128f)
            if (selected) box(c, RectF(x-38, 46f, x+38, 49f), accent, 2f)
        }
    }
    private fun statusIcons(c: Canvas) {
        laneCenteringIcon(c,470f,27f)
        listOf(VehicleSignal.LEFT_TURN,VehicleSignal.AUTO_HEADLIGHTS,VehicleSignal.LOW_BEAM,
            VehicleSignal.HIGH_BEAM,VehicleSignal.POSITION_LIGHT,VehicleSignal.REAR_FOG,VehicleSignal.RIGHT_TURN)
            .forEachIndexed { index, signal -> lampIcon(c,signal,550f+index*(476f/6f),27f) }
        box(c,RectF(1094f,10f,1095f,45f),line,0f)
    }
    private fun laneCenteringIcon(c: Canvas,x: Float,y: Float) {
        // Confirmed lane centering uses white; unknown/inactive remain gray, without inferred engagement.
        val color=if(snapshot.vehicle[VehicleSignal.LANE_CENTERING_ACTIVE]?.value==true) white else muted
        paint.style=Paint.Style.STROKE; paint.color=color; paint.strokeWidth=2.8f
        c.drawLine(x-25,y+17,x-19,y-17,paint); c.drawLine(x+25,y+17,x+19,y-17,paint)
        c.drawRoundRect(RectF(x-8,y-14,x+8,y+14),4f,4f,paint)
        c.drawLine(x-6,y-5,x+6,y-5,paint); c.drawLine(x-6,y+7,x+6,y+7,paint)
        for(side in listOf(-1,1)) for(axle in listOf(-1,1)) c.drawLine(x+side*11,y+axle*7-3,x+side*11,y+axle*7+3,paint)
    }
    private fun tirePage(c: Canvas, r: RectF) {
        contentPanel(c,r)
        val cx = r.centerX()
        vehicleSilhouette(c, cx, r.centerY())
        val positions = listOf(
            Triple(VehicleSignal.TIRE_FL_BAR, R.string.dashboard_front_left, PointF(cx-242, 143f)),
            Triple(VehicleSignal.TIRE_FR_BAR, R.string.dashboard_front_right, PointF(cx+242, 143f)),
            Triple(VehicleSignal.TIRE_RL_BAR, R.string.dashboard_rear_left, PointF(cx-242, 319f)),
            Triple(VehicleSignal.TIRE_RR_BAR, R.string.dashboard_rear_right, PointF(cx+242, 319f)))
        positions.forEach { (signal, title, point) ->
            text(c, label(title), point.x, point.y, 22f, muted, Paint.Align.CENTER, 180f)
            text(c, snapshot.number(signal)?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                point.x, point.y+48, 46f, white, Paint.Align.CENTER, 190f)
            text(c,"bar",point.x,point.y+76,20f,muted,Paint.Align.CENTER,100f)
            val side = if (point.x < cx) -1 else 1
            val y = point.y+26
            paint.color=line; paint.style=Paint.Style.STROKE; paint.strokeWidth=2f
            val connector=Path().apply { moveTo(cx+side*155,y); lineTo(cx+side*121,y); lineTo(cx+side*73,y+15) }
            c.drawPath(connector,paint)
        }
    }
    /** Vector artwork stays sharp at the instrument panel's native resolution. Nose points up. */
    private fun vehicleSilhouette(c: Canvas, x: Float, y: Float) {
        for (side in listOf(-1,1)) for (axle in listOf(-1,1)) {
            box(c, RectF(x+side*60-9,y+axle*79-24,x+side*60+9,y+axle*79+24), line, 6f)
            box(c, RectF(x+side*60-5,y+axle*79-19,x+side*60+5,y+axle*79+19), muted, 4f)
        }
        val body = Path().apply {
            moveTo(x,y-125); cubicTo(x+51,y-125,x+56,y-105,x+56,y-68)
            lineTo(x+56,y+86); quadTo(x+55,y+120,x+30,y+124)
            quadTo(x,y+132,x-30,y+124); quadTo(x-55,y+120,x-56,y+86)
            lineTo(x-56,y-68); cubicTo(x-56,y-105,x-51,y-125,x,y-125); close()
        }
        paint.style=Paint.Style.FILL
        paint.shader=LinearGradient(x-58,y,x+58,y,intArrayOf(Color.rgb(100,116,130),Color.rgb(211,224,233),Color.rgb(114,134,150)),null,Shader.TileMode.CLAMP)
        c.drawPath(body,paint); paint.shader=null
        paint.style=Paint.Style.STROKE; paint.color=muted; paint.strokeWidth=2f; c.drawPath(body,paint)
        val windshield=Path().apply {
            moveTo(x-44,y-55); quadTo(x,y-75,x+44,y-55)
            lineTo(x+35,y-19); quadTo(x,y-29,x-35,y-19); close()
        }
        paint.style=Paint.Style.FILL; paint.color=panel; c.drawPath(windshield,paint)
        box(c,RectF(x-34,y-15,x+34,y+65),Color.rgb(43,60,76),12f)
        val rear=Path().apply { moveTo(x-35,y+72); lineTo(x+35,y+72); lineTo(x+41,y+99); quadTo(x,y+110,x-41,y+99); close() }
        paint.color=panel; c.drawPath(rear,paint)
        paint.color=white; paint.style=Paint.Style.STROKE; paint.strokeWidth=3f
        c.drawLine(x-41,y-100,x-24,y-110,paint); c.drawLine(x+41,y-100,x+24,y-110,paint)
        paint.color=Color.rgb(151,77,81)
        c.drawLine(x-41,y+111,x-23,y+118,paint); c.drawLine(x+41,y+111,x+23,y+118,paint)
    }
    private fun midnightFooter(c: Canvas) {
        box(c,RectF(0f,DashboardLayout.MIDNIGHT_FOOTER_TOP,1920f,532f),bg,0f)
        box(c,RectF(24f,472f,1896f,473f),line,0f)
        val assist=snapshot.assistance
        val dividers=if(assist.mode==DashboardAssistMode.CRUISE) listOf(500f,1170f) else listOf(500f,970f,1470f)
        for(x in dividers) box(c,RectF(x,484f,x+1,521f),line,0f)
        val fuel=snapshot.number(VehicleSignal.FUEL_PERCENT)
        val fuelColor=if(fuel!=null && fuel<=10) amber else accent
        fuelIcon(c,50f,502f)
        box(c,RectF(88f,498f,216f,507f),line,5f)
        fuel?.let { box(c,RectF(88f,498f,88f+(128*it.coerceIn(0.0,100.0)/100).toFloat(),507f),fuelColor,5f) }
        text(c,(fuel?.roundToInt()?.toString() ?: "—")+"%",300f,512f,25f,white,Paint.Align.RIGHT,75f)
        text(c,(snapshot.rangeKm?.roundToInt()?.toString() ?: "—")+" km",470f,512f,25f,white,Paint.Align.RIGHT,152f)

        val color=when { assist.state==DashboardAssistState.FAULT -> amber; snapshot.assistanceIconActive -> green; else -> muted }
        when(assist.mode) {
            DashboardAssistMode.GPILOT -> steeringIcon(c,543f,502f,color)
            DashboardAssistMode.ACC -> adaptiveCruiseIcon(c,543f,502f,color)
            else -> cruiseIcon(c,543f,502f,color)
        }
        val title=when(assist.mode) {
            DashboardAssistMode.OFF -> label(R.string.dashboard_cruise_off)
            DashboardAssistMode.CRUISE -> label(R.string.dashboard_cruise)
            DashboardAssistMode.ACC -> "ACC"
            DashboardAssistMode.GPILOT -> "GPilot"
            DashboardAssistMode.UNKNOWN -> label(R.string.dashboard_assist_unknown)
        }
        val state=when(assist.state) {
            DashboardAssistState.STANDBY -> R.string.dashboard_standby
            DashboardAssistState.OVERRIDE -> R.string.dashboard_override
            DashboardAssistState.STOPPED -> R.string.dashboard_stopped
            DashboardAssistState.WAITING -> R.string.dashboard_waiting
            DashboardAssistState.FAULT -> R.string.dashboard_fault
            DashboardAssistState.UNKNOWN -> if(assist.mode==DashboardAssistMode.UNKNOWN) null else R.string.dashboard_assist_unknown
            else -> null
        }
        text(c,title+(state?.let { " · "+label(it) } ?: ""),582f,512f,25f,
            if(assist.state==DashboardAssistState.FAULT) amber else white,maxWidth=if(assist.mode==DashboardAssistMode.CRUISE) 560f else 365f)
        if(assist.showTarget) {
            val x=if(assist.mode==DashboardAssistMode.CRUISE) 1210f else 1002f
            val titleId=if(assist.mode==DashboardAssistMode.ACC || assist.mode==DashboardAssistMode.GPILOT) R.string.dashboard_acc_set else R.string.dashboard_cruise_set
            text(c,label(titleId),x,511f,22f,muted,maxWidth=188f)
            text(c,snapshot.accSetSpeedKph?.roundToInt()?.toString() ?: "—",x+289,515f,35f,
                if(snapshot.accSetSpeedKph!=null) accent else muted,Paint.Align.RIGHT,90f)
            text(c,"km/h",x+310,511f,21f,muted,maxWidth=100f)
        }
        if(assist.showGap) {
            text(c,label(R.string.dashboard_gap_short),1510f,511f,22f,muted,maxWidth=150f)
            val gap=snapshot.followGap
            if(gap==null) text(c,"—",1856f,511f,25f,muted,Paint.Align.RIGHT)
            else for(i in 0..2) box(c,RectF(1690f+i*60,496f,1738f+i*60,508f),if(i<gap) white else line,4f)
        }
    }
    private fun cruiseIcon(c: Canvas,x:Float,y:Float,color:Int) {
        paint.style=Paint.Style.STROKE; paint.color=color; paint.strokeWidth=2.8f
        c.drawArc(RectF(x-20,y-20,x+20,y+20),135f,270f,false,paint)
        for(i in 0..4) {
            val a=Math.toRadians(135.0+i*67.5)
            c.drawLine(x+cos(a).toFloat()*14,y+sin(a).toFloat()*14,x+cos(a).toFloat()*20,y+sin(a).toFloat()*20,paint)
        }
        c.drawLine(x,y,x-10,y-10,paint)
        paint.style=Paint.Style.FILL; c.drawCircle(x,y,3f,paint)
    }
    private fun adaptiveCruiseIcon(c: Canvas,x:Float,y:Float,color:Int) {
        paint.style=Paint.Style.STROKE; paint.color=color; paint.strokeWidth=2.8f
        c.drawRoundRect(RectF(x-12,y-16,x+12,y+8),4f,4f,paint)
        c.drawLine(x-10,y-5,x+10,y-5,paint)
        c.drawLine(x-10,y+14,x+10,y+14,paint); c.drawLine(x-15,y+21,x+15,y+21,paint)
    }
    private fun followingCarIcon(c: Canvas,x:Float,y:Float,color:Int) {
        followCarGlyph?.apply {
            setTint(color)
            setBounds((x-24).roundToInt(),(y-24).roundToInt(),(x+24).roundToInt(),(y+24).roundToInt())
            draw(c)
        }
    }
    private fun fuelIcon(c:Canvas,x:Float,y:Float) {
        paint.style=Paint.Style.STROKE; paint.color=white; paint.strokeWidth=2.5f
        c.drawRoundRect(RectF(x-10,y-15,x+6,y+15),2f,2f,paint)
        c.drawRect(x-7,y-12,x+3,y-3,paint)
        val hose=Path().apply { moveTo(x+6,y-7); lineTo(x+13,y-3); lineTo(x+13,y+11); quadTo(x+20,y+19,x+20,y+8); lineTo(x+20,y-7); lineTo(x+12,y-15) }
        c.drawPath(hose,paint); c.drawLine(x-14,y+16,x+10,y+16,paint)
    }
    private fun steeringIcon(c:Canvas,x:Float,y:Float,color:Int) {
        paint.style=Paint.Style.STROKE; paint.color=color; paint.strokeWidth=3f
        c.drawCircle(x,y,20f,paint)
        val spokes=Path().apply { moveTo(x-18,y-5); quadTo(x,y-12,x+18,y-5); moveTo(x-18,y-5); lineTo(x-6,y+3); lineTo(x-4,y+18); moveTo(x+18,y-5); lineTo(x+6,y+3); lineTo(x+4,y+18) }
        c.drawPath(spokes,paint)
    }
    private fun lampIcon(c:Canvas,signal:VehicleSignal,x:Float,y:Float) {
        val on=snapshot.vehicle[signal]?.value as? Boolean
        // An unconfirmed selector must not leave a visible A that looks like automatic lights are enabled.
        if (signal == VehicleSignal.AUTO_HEADLIGHTS && on == null) return
        val color=when(on) { null -> muted; false -> line; true -> when(signal) {
            VehicleSignal.HIGH_BEAM -> if(day) ReplicaDayPalette.blue else Color.rgb(79,165,255); VehicleSignal.REAR_FOG -> amber; else -> green
        } }
        paint.color=color; paint.style=Paint.Style.STROKE; paint.strokeWidth=2.5f
        when(signal) {
            VehicleSignal.LEFT_TURN,VehicleSignal.RIGHT_TURN -> {
                val sign=if(signal==VehicleSignal.LEFT_TURN) -1 else 1
                val arrow=Path().apply { moveTo(x+sign*15,y); lineTo(x-sign*1,y-11); lineTo(x-sign*1,y-5); lineTo(x-sign*14,y-5); lineTo(x-sign*14,y+5); lineTo(x-sign*1,y+5); lineTo(x-sign*1,y+11); close() }
                paint.style=Paint.Style.FILL; c.drawPath(arrow,paint)
            }
            VehicleSignal.POSITION_LIGHT -> {
                for (side in listOf(-1,1)) {
                    c.drawOval(RectF(x+side*5-3,y-7,x+side*5+3,y+7),paint)
                    for(i in -1..1) c.drawLine(x+side*11,y+i*6,x+side*18,y+i*9,paint)
                }
            }
            VehicleSignal.REAR_FOG -> {
                val lamp=Path().apply { moveTo(x-2,y-11); cubicTo(x-20,y-12,x-20,y+12,x-2,y+11); close() }
                c.drawPath(lamp,paint)
                for(i in 0..2) c.drawLine(x+3,y-8+i*8,x+18,y-8+i*8,paint)
                val fog=Path().apply { moveTo(x+10,y-15); cubicTo(x+3,y-5,x+18,y+5,x+10,y+15) }
                c.drawPath(fog,paint)
            }
            else -> {
                val lamp=Path().apply { moveTo(x+1,y-11); cubicTo(x+19,y-12,x+19,y+12,x+1,y+11); close() }
                c.drawPath(lamp,paint)
                for(i in 0..2) c.drawLine(x-17,y-8+i*8,x-5,y-8+i*8+(if(signal==VehicleSignal.LOW_BEAM) -3 else 0),paint)
                if(signal==VehicleSignal.AUTO_HEADLIGHTS) text(c,"A",x+9,y+5,13f,color,Paint.Align.CENTER,14f)
            }
        }
    }
    private fun car(c: Canvas,x:Float,y:Float,w:Float,h:Float) {
        box(c,RectF(x-w/2,y-h/2,x+w/2,y+h/2),white,8f)
        box(c,RectF(x-w*.34f,y-h*.22f,x+w*.34f,y-h*.04f),panel,3f)
        box(c,RectF(x-w*.32f,y+h*.2f,x+w*.32f,y+h*.3f),panel,2f)
    }
    private fun box(c: Canvas, rect: RectF, color: Int, radius: Float) {
        paint.style=Paint.Style.FILL; paint.color=color; c.drawRoundRect(rect,radius,radius,paint)
    }
    private fun stroke(c: Canvas, rect: RectF, color: Int, width: Float) {
        paint.style=Paint.Style.STROKE; paint.strokeWidth=width; paint.color=color; c.drawRect(rect,paint); paint.style=Paint.Style.FILL
    }
    private fun musicText(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int,
                          align: Paint.Align = Paint.Align.LEFT, maxWidth: Float = 500f) {
        paint.style = Paint.Style.FILL; paint.color = color; paint.textSize = size
        paint.typeface = Typeface.create(if (style == DashboardStyle.REPLICA) "sans-serif" else "sans-serif-medium", Typeface.NORMAL)
        val limit = maxWidth.coerceAtLeast(1f)
        val overflow = paint.measureText(value) - limit
        if (overflow <= 0f) { text(c, value, x, y, size, color, align, maxWidth); return }
        val now = android.os.SystemClock.uptimeMillis()
        val slot = "$style:$mode:$x:$y:$size:$limit"
        val offset = musicMarquee.offset(slot, value, overflow, now)
        val left = when (align) { Paint.Align.CENTER -> x - limit / 2; Paint.Align.RIGHT -> x - limit; else -> x }
        val metrics = paint.fontMetrics
        c.save()
        c.clipRect(left, y + metrics.top, left + limit, y + metrics.bottom)
        paint.textAlign = Paint.Align.LEFT
        c.drawText(value, left - offset, y, paint)
        c.restore()
        scheduleMarquee(musicMarquee.nextDelay(slot, now))
    }

    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int,
                     align: Paint.Align = Paint.Align.LEFT, maxWidth: Float = 500f, bold: Boolean = false) {
        paint.style=Paint.Style.FILL; paint.color=color; paint.textSize=size; paint.textAlign=align
        paint.typeface=Typeface.create(if(style==DashboardStyle.REPLICA) "sans-serif" else "sans-serif-medium", if(bold) Typeface.BOLD else Typeface.NORMAL)
        val limit=maxWidth.coerceAtLeast(1f)
        var label=value
        if (paint.measureText(label)>limit) {
            val count=paint.breakText(label,true,(limit-paint.measureText("…")).coerceAtLeast(0f),null)
            label=label.take(count)+"…"
        }
        c.drawText(label,x,y,paint)
    }
}
