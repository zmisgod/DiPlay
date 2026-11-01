package com.shilapi.xcertplay

import android.graphics.*
import android.graphics.drawable.Drawable
import com.shilapi.xcertplay.ultra.VehicleSignal
import kotlin.math.*

/** Measured, independent faces for the retained concepts. Coordinates are in the 1920 × 532 canvas. */
internal class DashboardConceptRenderer {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val guidanceGlyph = DashboardGuidanceGlyph()
    private val obsidianScale = ObsidianSpeedScale()
    private val obsidianProgress = Path()
    private val markerPosition = FloatArray(2)
    private val markerTangent = FloatArray(2)
    // Unit-radius light stamp transformed at runtime: no per-frame bitmap, blur or shader allocation.
    private val polarGlint = RadialGradient(0f, 0f, 1f,
        intArrayOf(0xf0eaffff.toInt(), 0x9522e8ff.toInt(), 0x3022dfff, 0x0022dfff),
        floatArrayOf(0f, .12f, .46f, 1f), Shader.TileMode.CLAMP)
    private val racing = Typeface.create("sans-serif-black", Typeface.ITALIC)
    private var key: Pair<DashboardStyle, Boolean>? = null
    private var backdrop: Bitmap? = null
    private var faces: Bitmap? = null
    private var mapEdges: Bitmap? = null
    private var topBar: Picture? = null
    private var bottomBar: Picture? = null
    private val lighting = DashboardSurfaceLighting()
    private val polarSector = RadialGradient(0f,0f,240f,
        intArrayOf(Color.TRANSPARENT,0x0012daef,0x2412daef,0x7012daef,0xf018cfe8.toInt()),
        floatArrayOf(0f,.65f,.8f,.95f,1f),Shader.TileMode.CLAMP)
    // Track meter's reflected highlight is a reusable shader, not a frame-time blur.
    private val trackMeterReflection = LinearGradient(0f,124f,0f,155f,
        intArrayOf(0xffff8c16.toInt(),0xffffd28a.toInt(),0xffff9b28.toInt()),
        floatArrayOf(0f,.52f,1f),Shader.TileMode.CLAMP)
    private var ticks = Picture()
    internal var buildCount = 0
        private set
    internal val surfaceBytes get() = (backdrop?.allocationByteCount ?: 0) +
        (faces?.allocationByteCount ?: 0) + (mapEdges?.allocationByteCount ?: 0)

    fun release() {
        // Do not recycle bitmaps still referenced by a hardware Canvas display list.
        backdrop=null; faces=null; mapEdges=null; topBar=null; bottomBar=null; key=null
    }

    private data class Palette(val ink: Int, val muted: Int, val face: Int, val edge: Int, val accent: Int)
    private fun palette(style: DashboardStyle, day: Boolean) = if (day)
        Palette(0xff242936.toInt(), 0xff586172.toInt(),
            if (style == DashboardStyle.STARFIELD) 0xffd7d7e1.toInt() else 0xfff5f4f0.toInt(),
            if (style == DashboardStyle.STARFIELD) 0xff555d71.toInt() else 0xffabb1bd.toInt(), if (style == DashboardStyle.TRACK) 0xffff940b.toInt() else if(style==DashboardStyle.OBSIDIAN) 0xff00b58d.toInt() else 0xff009e9e.toInt())
    else Palette(0xfff5f7fc.toInt(), 0xffb0bfd6.toInt(),
        if (style == DashboardStyle.STARFIELD) 0xff232238.toInt() else 0xff050e15.toInt(),
        if (style == DashboardStyle.STARFIELD) 0xffc4c6d6.toInt() else 0xff28414f.toInt(),
        when(style) { DashboardStyle.TRACK -> 0xffff950c.toInt(); DashboardStyle.OBSIDIAN -> 0xff4de4b9.toInt(); else -> 0xff12e5e2.toInt() })

    private fun fill(color: Int, shader: Shader? = null) {
        p.style = Paint.Style.FILL; p.color = color; p.shader = shader; p.strokeCap = Paint.Cap.BUTT
    }
    private fun stroke(color: Int, width: Float, shader: Shader? = null) {
        fill(color, shader); p.style = Paint.Style.STROKE; p.strokeWidth = width
    }
    private fun line(c: Canvas, x: Float, y: Float, xx: Float, yy: Float, color: Int, width: Float = 1f) {
        stroke(color, width); c.drawLine(x, y, xx, yy, p)
    }
    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int,
        face: Typeface = regular, width: Float = Float.MAX_VALUE, stretch: Float = 1f) {
        fill(color); p.textSize = size; p.typeface = face; p.textAlign = Paint.Align.CENTER; p.textScaleX = stretch
        if (p.measureText(value) > width) p.textSize *= width / p.measureText(value)
        c.drawText(value, x, y, p); p.textScaleX = 1f
    }
    private fun record(draw: (Canvas) -> Unit) = Picture().apply { draw(beginRecording(1920, 532)); endRecording() }
    private fun surface(draw: (Canvas) -> Unit) = Bitmap.createBitmap(1920, 532, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
    private fun prepare(style: DashboardStyle, day: Boolean) {
        if (key == (style to day)) return
        val colors = palette(style, day)
        backdrop = surface { backgroundFace(it, style, day) }
        faces = surface { staticFaces(it, style, day, colors) }
        mapEdges = if(style==DashboardStyle.POLAR || style==DashboardStyle.STARFIELD) {
            val pixels=IntArray(1920*532)
            backdrop!!.getPixels(pixels,0,1920,0,0,1920,532)
            for(y in 0 until 532) for(x in 0 until 1920) {
                val alpha=when(x) {
                    in 266..548 -> (1f-(x-266)/282f).pow(2)*255
                    in 1372..1654 -> ((x-1372)/282f).pow(2)*255
                    else -> 0f
                }
                val i=y*1920+x
                pixels[i]=(pixels[i] and 0x00ffffff) or (alpha.roundToInt() shl 24)
            }
            Bitmap.createBitmap(pixels,1920,532,Bitmap.Config.ARGB_8888)
        } else null
        ticks = record { staticTicks(it, style, day, colors) }
        topBar=null;bottomBar=null
        key = style to day; buildCount++
    }
    fun background(c: Canvas, style: DashboardStyle, day: Boolean) {
        prepare(style, day); backdrop?.let { c.drawBitmap(it, 0f, 0f, null) }
    }
    fun bar(c: Canvas, bounds: RectF, roundTop: Boolean, style: DashboardStyle, day: Boolean) {
        prepare(style,day)
        val cached=(if(roundTop) bottomBar else topBar) ?: record {
            lighting.bar(it,bounds,roundTop,style,day)
        }.also { if(roundTop) bottomBar=it else topBar=it }
        c.drawPicture(cached)
    }
    fun videoEdges(c: Canvas, style: DashboardStyle, day: Boolean) {
        prepare(style,day);mapEdges?.let { c.drawBitmap(it,0f,0f,null) }
    }
    private fun backgroundFace(c: Canvas, style: DashboardStyle, day: Boolean) {
        lighting.background(c, style, day)
        if (style == DashboardStyle.STARFIELD) stars(c, day, 4711, RectF(0f, 0f, 1920f, 532f), 420)
    }
    private fun stars(c: Canvas, day: Boolean, seed: Long, r: RectF, count: Int) {
        val random = java.util.Random(seed)
        repeat(count) { i ->
            val x = r.left + random.nextFloat() * r.width(); val y = r.top + random.nextFloat() * r.height()
            // Sub-pixel dots vanished at the panel's physical viewing distance. Keep visible cores.
            val bright=i%13==0
            val a=if(bright) 250 else if(day) 190+random.nextInt(60) else 155+random.nextInt(90)
            if(bright) {
                fill(Color.WHITE,RadialGradient(x,y,6f,Color.argb(if(day) 85 else 110,225,218,255),Color.TRANSPARENT,Shader.TileMode.CLAMP))
                c.drawCircle(x,y,6f,p)
                line(c,x-3f,y,x+3f,y,Color.argb(105,243,239,255),.7f)
                line(c,x,y-3f,x,y+3f,Color.argb(105,243,239,255),.7f)
            }
            fill(Color.argb(a,243,239,255))
            c.drawCircle(x,y,if(bright) 1.65f else .85f+random.nextFloat()*.55f,p)
        }
    }
    private fun staticFaces(c: Canvas, style: DashboardStyle, day: Boolean, colors: Palette) {
        when (style) {
            DashboardStyle.TRACK -> lighting.track(c, day)
            DashboardStyle.OBSIDIAN -> {
                lighting.panel(c, RectF(10f, 64f, 310f, 474f), day)
                lighting.panel(c, RectF(1550f, 64f, 1910f, 474f), day)
                line(c, 34f, 429f, 286f, 429f, if (day) 0xffcbd0d2.toInt() else 0xff1b2c35.toInt())
                line(c, 1580f, 365f, 1880f, 365f, if (day) 0xffcbd0d2.toInt() else 0xff1b2c35.toInt())
            }
            else -> for ((cx, left) in listOf(266f to true, 1654f to false)) {
                c.save(); c.translate(cx, 266f); c.scale(DashboardLayout.CONCEPT_DIAL_SCALE,DashboardLayout.CONCEPT_DIAL_SCALE)
                if (style == DashboardStyle.STARFIELD) {
                    lighting.starDial(c, day)
                    c.save(); c.clipPath(Path().apply { addCircle(0f, 0f, 245f, Path.Direction.CW) })
                    stars(c, day, if (left) 26 else 16, RectF(-246f, -246f, 246f, 246f), 90); c.restore()
                } else {
                    lighting.polarDial(c)
                }
                c.restore()
            }
        }
    }
    private fun staticTicks(c: Canvas, style: DashboardStyle, day: Boolean, colors: Palette) {
        if (style == DashboardStyle.TRACK) {
            rpmBar(c, RectF(30f, 124f, 387f, 155f), null, colors, labelsAbove = true); return
        }
        if (style == DashboardStyle.OBSIDIAN) {
            stroke(if(day) 0xff68737b.toInt() else 0xffbdcbd1.toInt(),2.5f)
            c.drawPath(obsidianScale.path,p)
            for ((i,point) in obsidianScale.ticks.withIndex()) {
                if(i==0 || i==48) continue // Open ends stay clear of endpoint labels.
                val (x,y,nx,ny)=point
                val major=i%8==0
                line(c,x+nx*7,y+ny*7,x+nx*(if(major) 18 else 13),y+ny*(if(major) 18 else 13),colors.muted,if(major) 1.6f else .9f)
            }
            obsidianScale.labels.forEachIndexed { i,at -> text(c,(i*40).toString(),at.first,at.second,21f,colors.muted) }
            rpmBar(c, RectF(1594f, 431f, 1866f, 444f), null, colors, labelsAbove = false); return
        }
        for ((cx, speed) in listOf(266f to true, 1654f to false)) {
            c.save(); c.translate(cx, 266f); c.scale(DashboardLayout.CONCEPT_DIAL_SCALE,DashboardLayout.CONCEPT_DIAL_SCALE)
            val dial = if (style == DashboardStyle.POLAR) {
                if (speed) DashboardDial(240.0, 120, 10) else DashboardDial(8000.0, 80, 10)
            } else if (speed) DashboardDial.CLASSIC_SPEED else DashboardDial.RPM
            for (i in 0..dial.divisions) {
                val value = dial.valueAtTick(i); val a = Math.toRadians(dial.angle(value).toDouble())
                val major = i % dial.majorEvery == 0
                val color = if (!speed && value >= 7000) 0xffee343c.toInt() else colors.muted
                val r = if (major) 220f else if (style == DashboardStyle.POLAR && i % 5 == 0) 227f else 232f
                line(c, cos(a).toFloat() * r, sin(a).toFloat() * r, cos(a).toFloat() * 239, sin(a).toFloat() * 239, color, if (major) 2.4f else 1f)
                if (major) text(c, if (speed) value.roundToInt().toString() else (value / 1000).roundToInt().toString(),
                    cos(a).toFloat() * 202, sin(a).toFloat() * 202 + 8, if (speed) 23f else 27f, color)
            }
            if (style == DashboardStyle.POLAR) for (i in 0..100) {
                val a = Math.toRadians(135.0 + i * 2.7)
                line(c, cos(a).toFloat() * 161, sin(a).toFloat() * 161, cos(a).toFloat() * 168, sin(a).toFloat() * 168, 0xff32617a.toInt(), 1f)
            }
            c.restore()
        }
    }
    fun instruments(c: Canvas, style: DashboardStyle, day: Boolean, state: DashboardSnapshot, music: (Canvas, RectF) -> Unit) {
        prepare(style, day)
        val colors = palette(style, day); val rpm = state.number(VehicleSignal.ENGINE_RPM)
        val speed = state.speedKph?.roundToInt()?.toString() ?: "—"
        val temp = state.coolantGaugePercent?.roundToInt()?.toString() ?: "—"
        val temperature = "水温  $temp%"
        faces?.let { c.drawBitmap(it, 0f, 0f, null) }
        if (style == DashboardStyle.POLAR || style == DashboardStyle.STARFIELD) {
            for ((cx, value, maximum) in listOf(Triple(266f, state.speedKph, 240.0), Triple(1654f, rpm, 8000.0))) {
                if (value == null || !value.isFinite()) continue
                c.save(); c.translate(cx, 266f); c.scale(DashboardLayout.CONCEPT_DIAL_SCALE,DashboardLayout.CONCEPT_DIAL_SCALE)
                val sweep = (value / maximum).coerceIn(0.0, 1.0).toFloat() * 270
                if (style == DashboardStyle.POLAR) {
                    stroke(Color.WHITE, 75f, polarSector)
                    c.drawArc(RectF(-199f, -199f, 199f, 199f), 135f, sweep, false, p)
                    // Separate illuminated inner lip, visible in the original between the well and sector.
                    stroke(0xb512e5e2.toInt(), 2f)
                    c.drawArc(RectF(-158f,-158f,158f,158f),135f,sweep,false,p)
                }
                // Starfield deliberately has only a uniform line: no translucent progress sector.
                stroke(colors.accent, if (style == DashboardStyle.STARFIELD) 6f else 4f)
                c.drawArc(RectF(-240f, -240f, 240f, 240f), 135f, sweep, false, p)
                c.restore()
            }
        }
        c.drawPicture(ticks)
        // Endpoint reflections are drawn after the tick picture so the white core remains crisp.
        if (style == DashboardStyle.POLAR || style == DashboardStyle.STARFIELD) {
            for ((cx, value, maximum) in listOf(Triple(266f, state.speedKph, 240.0), Triple(1654f, rpm, 8000.0))) {
                if (value == null || !value.isFinite() || value <= 0) continue
                c.save(); c.translate(cx, 266f); c.scale(DashboardLayout.CONCEPT_DIAL_SCALE,DashboardLayout.CONCEPT_DIAL_SCALE)
                c.rotate(135f + (value / maximum).coerceIn(0.0, 1.0).toFloat() * 270f)
                if (style == DashboardStyle.POLAR) {
                    for ((radius, rx, ry) in listOf(Triple(240f, 13f, 25f), Triple(158f, 8f, 15f))) {
                        c.save(); c.translate(radius, 0f); c.scale(rx, ry)
                        fill(Color.WHITE, polarGlint); c.drawCircle(0f, 0f, 1f, p); c.restore()
                    }
                    line(c, 161f, 0f, 240f, 0f, 0x3014dfea, 12f)
                    line(c, 161f, 0f, 240f, 0f, 0xff23e7f1.toInt(), 4f)
                    line(c, 162f, 0f, 246f, 0f, 0xffeaffff.toInt(), 1.5f)
                    line(c, 240f, -7f, 240f, 7f, 0xffeaffff.toInt(), 1.2f)
                } else {
                    line(c, 222f, 0f, 240f, 0f, colors.accent, 5f)
                }
                c.restore()
            }
        }
        if (style == DashboardStyle.OBSIDIAN) state.speedKph?.let {
            obsidianScale.progress(it, obsidianProgress)
            stroke(colors.accent, 4.5f); c.drawPath(obsidianProgress, p)
            if (obsidianScale.marker(it, markerPosition, markerTangent)) {
                val x=markerPosition[0]; val y=markerPosition[1]
                val nx=-markerTangent[1]; val ny=markerTangent[0]
                line(c, x-nx*2f, y-ny*2f, x+nx*19f, y+ny*19f,
                    if(day) 0xff00795f.toInt() else 0xffc5ffed.toInt(), 5f)
            }
        }
        when (style) {
            DashboardStyle.TRACK -> {
                rpmBar(c, RectF(30f, 124f, 387f, 155f), rpm, colors, activeOnly = true)
                text(c, "${rpm?.roundToInt() ?: "—"} rpm", 204f, 194f, 27f, colors.ink)
                text(c, speed, 156f, 365f, 136f, colors.ink, racing, 265f, 1.12f)
                text(c, "km/h", 156f, 401f, 26f, colors.muted)
                text(c, temperature, 156f, 484f, 23f, colors.muted)
                music(c, RectF(1648f, 88f, 1886f, 400f))
            }
            DashboardStyle.OBSIDIAN -> {
                text(c, speed, 194f, 285f, 96f, colors.ink, medium, 166f)
                text(c, "km/h", 194f, 321f, 26f, colors.muted)
                text(c, temperature, 160f, 459f, 23f, colors.muted)
                music(c, RectF(1604f, 87f, 1856f, 350f))
                text(c, "${rpm?.roundToInt() ?: "—"} rpm", 1730f, 411f, 33f, colors.ink)
                rpmBar(c, RectF(1594f, 431f, 1866f, 444f), rpm, colors, labelsAbove = false, activeOnly = true)
            }
            else -> {
                c.save();c.scale(DashboardLayout.CONCEPT_DIAL_SCALE,DashboardLayout.CONCEPT_DIAL_SCALE,266f,266f)
                text(c, speed, 266f, 301f, 124f, colors.ink, if(style==DashboardStyle.POLAR) regular else medium, 280f)
                text(c, "km/h", 266f, 344f, 29f, colors.muted)
                text(c, temperature, 266f, 456f, 25f, colors.muted)
                c.restore()
                c.save();c.scale(DashboardLayout.CONCEPT_DIAL_SCALE,DashboardLayout.CONCEPT_DIAL_SCALE,1654f,266f)
                music(c, RectF(1542f, 151f, 1766f, 404f))
                text(c, "${rpm?.roundToInt() ?: "—"} rpm", 1654f, 456f, 32f, colors.ink)
                c.restore()
            }
        }
    }
    private fun rpmBar(c: Canvas, r: RectF, value: Double?, colors: Palette, labelsAbove: Boolean = true, activeOnly: Boolean = false) {
        if (!labelsAbove) {
            if (activeOnly && value != null) {
                val end = r.left + r.width() * (value / 8000.0).coerceIn(0.0,1.0).toFloat()
                fill(colors.accent); c.drawRoundRect(RectF(r.left,r.top,end,r.bottom),2f,2f,p)
            }
            for(i in 0..40) {
                val x=r.left+i*r.width()/40
                if(activeOnly && (value==null || i*200>value)) continue
                val color=if(activeOnly) colors.face else if(i>=35) 0xffe43c3c.toInt() else colors.muted
                line(c,x,r.top+if(i%5==0) 0f else 3f,x,r.bottom-if(i%5==0) 0f else 3f,color,if(i%5==0) 1.8f else 1f)
            }
        } else for (i in 0 until 40) {
            if (activeOnly && (value == null || !value.isFinite() || i >= value.coerceIn(0.0, 8000.0) / 200)) continue
            val x = r.left + i * r.width() / 40
            val color = if (i >= 35) 0xffcb3430.toInt() else if (activeOnly) colors.accent else Color.argb(105, Color.red(colors.muted), Color.green(colors.muted), Color.blue(colors.muted))
            if(activeOnly && Color.red(colors.ink)>200 && i<35) {
                line(c,x,r.bottom,x+5f,r.top,0x32ff7309,9f)
                stroke(Color.WHITE,6f,trackMeterReflection)
                c.drawLine(x,r.bottom,x+5f,r.top,p)
            } else line(c, x, r.bottom, x + 5f, r.top, color, 6f)
        }
        if (!activeOnly) for (i in 0..8) text(c, i.toString(), r.left + i * r.width() / 8,
            if (labelsAbove) r.top - 10 else r.bottom + 20, 18f, if (i >= 7) 0xffcf3631.toInt() else colors.muted)
    }
    /** Navigation card structure is part of each concept, not the shared wide classic card. */
    fun navigation(c: Canvas, r: RectF, style: DashboardStyle, day: Boolean, glyph: Drawable?, icon: Int, distance: String, road: String, remaining: String) {
        val colors = palette(style, day)
        fun card(rect: RectF) = lighting.card(c, rect, style, day)
        card(r)
        val color=when(style) { DashboardStyle.TRACK -> 0xff00bfdd.toInt(); DashboardStyle.OBSIDIAN -> colors.accent; else -> colors.ink }
        val arrow=if(style==DashboardStyle.OBSIDIAN) RectF(r.left+22,r.top+19,r.left+80,r.top+78)
            else RectF(r.left+20,r.top+12,r.left+78,r.top+70)
        guidanceGlyph.draw(c,arrow,icon,color,glyph)
        when (style) {
            DashboardStyle.TRACK -> {
                text(c, distance, r.left + 184, r.top + 38, 33f, colors.ink, medium, 170f)
                text(c, road, r.left + 184, r.top + 64, 22f, colors.muted, width = 174f)
                if (remaining.isNotEmpty()) {
                    line(c, r.left + 20, r.top + 79, r.right - 20, r.top + 79, colors.edge)
                    text(c, remaining, r.centerX(), r.bottom - 15, 21f, colors.muted, width = r.width() - 32)
                }
            }
            DashboardStyle.OBSIDIAN -> {
                text(c, distance, r.left + 180, r.top + 58, 36f, colors.ink, medium, 170f)
                line(c, r.left + 281, r.top + 20, r.left + 281, r.bottom - 20, colors.edge)
                text(c, road, r.left + 397, r.top + 40, 28f, colors.ink, width = 190f)
                text(c, remaining, r.left + 397, r.top + 70, 21f, colors.muted, width = 194f)
            }
            else -> {
                line(c, r.left + 96, r.top + 14, r.left + 96, r.bottom - 14, colors.edge)
                text(c, distance, r.left + 220, r.top + 37, 33f, colors.ink, medium, 210f)
                text(c, road, r.left + 220, r.bottom - 16, 23f, colors.muted, width = 210f)
                if (remaining.isNotEmpty()) {
                    val summary = RectF(784f, 424f, 1136f, 470f); card(summary)
                    text(c, remaining, 960f, 454f, 23f, colors.ink, width = 320f)
                }
            }
        }
    }
}
