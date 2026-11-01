package com.shilapi.xcertplay

import android.graphics.*
import kotlin.math.*

/** Analytic lighting, sampled against the retained mockups. Called only while recording static surfaces. */
internal class DashboardSurfaceLighting {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val grain by lazy {
        val random = java.util.Random(4207)
        val pixels = IntArray(128 * 128) {
            ((1 + random.nextInt(4)) shl 24) or (if (random.nextBoolean()) 0xffffff else 0)
        }
        BitmapShader(Bitmap.createBitmap(pixels, 128, 128, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private fun fill(color: Int, shader: Shader? = null) {
        p.color = color; p.shader = shader; p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
    }
    private fun stroke(color: Int, width: Float, shader: Shader? = null) {
        fill(color, shader); p.style = Paint.Style.STROKE; p.strokeWidth = width
    }
    private fun rgb(value: Long) = (0xff000000L or value).toInt()
    private fun gradient(x: Float, y: Float, xx: Float, yy: Float, vararg colors: Long) =
        LinearGradient(x, y, xx, yy, colors.map(::rgb).toIntArray(), null, Shader.TileMode.CLAMP)
    private fun texture(c: Canvas) { fill(Color.WHITE, grain); c.drawPaint(p) }
    private fun glow(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, color: Int) {
        c.save(); c.translate(x, y); c.scale(rx, ry)
        fill(Color.WHITE, RadialGradient(0f, 0f, 1f,
            intArrayOf(color, (color and 0x00ffffff) or ((Color.alpha(color) / 3) shl 24), color and 0x00ffffff),
            floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP))
        c.drawCircle(0f, 0f, 1f, p); c.restore()
    }
    private fun polygon(vararg xy: Float) = Path().apply {
        moveTo(xy[0], xy[1]); for(i in 2 until xy.size step 2) lineTo(xy[i], xy[i+1]); close()
    }
    fun background(c: Canvas, style: DashboardStyle, day: Boolean) {
        fill(Color.WHITE, when {
            style == DashboardStyle.STARFIELD && day -> gradient(0f,0f,0f,532f,0xcacfdc,0xd4d4df,0xd8d5da)
            style == DashboardStyle.STARFIELD -> gradient(0f,0f,0f,532f,0x1b1732,0x1c1c32,0x222443)
            day -> gradient(0f,0f,0f,532f,0xe8e6e3,0xf4f3f0,0xdedddd)
            else -> gradient(0f,0f,0f,532f,0x02070b,0x02090e,0x01070b)
        }); c.drawRect(0f,0f,1920f,532f,p)
        if(style == DashboardStyle.STARFIELD) {
            glow(c, 135f, 70f, 540f, 210f, if(day) 0x40ffffff else 0x194e346e)
            glow(c, 1710f, 40f, 510f, 210f, if(day) 0x40fff5e9 else 0x193e264a)
            glow(c, 960f, 350f, 910f, 270f, if(day) 0x20ffffff else 0x11070615)
        }
        texture(c)
    }
    fun starDial(c: Canvas, day: Boolean) {
        c.save(); c.clipPath(Path().apply { addCircle(0f,0f,246f,Path.Direction.CW) })
        fill(Color.WHITE, gradient(0f,-246f,0f,246f,
            if(day) 0xdfdfe5 else 0x1c1a30, if(day) 0xdbdce2 else 0x1d1c33))
        c.drawPaint(p)
        glow(c,-125f,-165f,350f,300f,if(day) 0x12ffffff else 0x053c2d62)
        texture(c); c.restore()
        stroke(if(day) rgb(0x555d71) else rgb(0xd2cede),1.6f);c.drawCircle(0f,0f,246f,p)
    }
    fun polarDial(c: Canvas) {
        glow(c,0f,0f,255f,255f,0x3012425a)
        fill(Color.WHITE, RadialGradient(0f,-50f,340f,
            intArrayOf(rgb(0x01060a),rgb(0x030b11),rgb(0x081b28)),floatArrayOf(0f,.68f,1f),Shader.TileMode.CLAMP))
        c.drawCircle(0f,0f,248f,p)
        val angular = intArrayOf(rgb(0x6e8fa5),rgb(0x1e3545),rgb(0x405d73),rgb(0xabc6d7),
            rgb(0x587389),rgb(0xf4faff),rgb(0x7791a6),rgb(0xd1e0ed),rgb(0x6e8fa5))
        val metal = SweepGradient(0f,0f,angular,floatArrayOf(0f,.14f,.26f,.39f,.51f,.64f,.76f,.88f,1f))
        // Convex silver ridge, dark groove and a separate inner blue reflection.
        for((r,w,col) in listOf(Triple(248f,1f,rgb(0x3d566a)),Triple(246.5f,2f,Color.WHITE),
            Triple(244.5f,1.8f,rgb(0x11202d)),Triple(242.5f,2.4f,Color.WHITE),
            Triple(240.6f,1.1f,rgb(0x314f63)))) {
            stroke(col,w,if(col==Color.WHITE) metal else null);c.drawCircle(0f,0f,r,p)
        }
        fill(Color.WHITE, RadialGradient(0f,-40f,205f,rgb(0x010508),rgb(0x02090e),Shader.TileMode.CLAMP))
        c.drawCircle(0f,0f,156f,p)
        for((width,alpha) in listOf(9f to 7,5f to 17,2f to 135,.8f to 230)) {
            stroke(Color.argb(alpha,29,117,154),width);c.drawCircle(0f,0f,157f,p)
        }
        stroke(rgb(0x01080b),1.5f);c.drawCircle(0f,0f,154.5f,p)
    }
    fun panel(c: Canvas, r: RectF, day: Boolean) {
        val shape=Path().apply { addRoundRect(r,26f,26f,Path.Direction.CW) }
        // Shadow stays outside the opaque face; no blur is rebuilt while driving.
        for((w,a) in listOf(14f to 6,8f to 9,3f to 18)) {
            stroke(Color.argb(if(day) a/2 else a,0,0,0),w);c.drawPath(shape,p)
        }
        fill(Color.WHITE,gradient(r.left,r.top,r.right,r.bottom,
            if(day) 0xf7f8f5 else 0x0a1116,if(day) 0xefefeb else 0x080e11,if(day) 0xf5f5f2 else 0x060d10))
        c.drawPath(shape,p)
        c.save();c.clipPath(shape)
        glow(c,r.right-70,r.top+60,240f,180f,if(day) 0x20ffffff else 0x12617180)
        texture(c);c.restore()
        stroke(Color.WHITE,1.2f,gradient(r.left,r.top,r.right,r.bottom,
            if(day) 0xc5cccd else 0x26333c,if(day) 0xe3e6e1 else 0x101c23));c.drawPath(shape,p)
    }
    private fun luminousEdge(c: Canvas, path: Path, day: Boolean, x: Float, y: Float, radius: Float) {
        for((w,a,blur) in listOf(Triple(4f,24,7f),Triple(2f,60,2f),Triple(.9f,210,0f))) {
            stroke(Color.WHITE,w,RadialGradient(x,y,radius,
                intArrayOf(Color.argb(if(day) a/2 else a,255,174,64),Color.argb(a/2,255,104,6),0x00ff6806),
                floatArrayOf(0f,.48f,1f),Shader.TileMode.CLAMP))
            p.maskFilter=if(blur>0) BlurMaskFilter(blur,BlurMaskFilter.Blur.NORMAL) else null
            c.drawPath(path,p)
        }
        p.maskFilter=null
    }
    /** Reference-sampled cross-section: narrow pale-gold core, weaker incident side,
     * stronger reflected side, and a long low-energy tail. Cached once per appearance. */
    private fun trackBeam(c: Canvas, x: Float, y: Float, endX: Float, endY: Float,
                          day: Boolean, peak: Float = .28f, spread: Float = 13f,
                          reflectedSide: Float = 1f) {
        val length=hypot(endX-x,endY-y)
        c.save();c.translate(x,y);c.rotate(Math.toDegrees(atan2(endY-y,endX-x).toDouble()).toFloat())
        c.scale(1f,reflectedSide)
        val scale=spread/13f
        // Positions are deliberately dense around the optical core, never a filled triangular beam.
        val distances=floatArrayOf(-20f,-12f,-8f,-4f,-1.2f,-.2f,.2f,.8f,1.2f,2f,4f,7f,13f,26f)
        val night=intArrayOf(0x00ff6908,0x1eff6908,0x37ff6908,0x5aff6908,0x7dff770c,
            0xe6ffd98a.toInt(),0xffffeb9b.toInt(),0xffffeb9b.toInt(),0xf5ffac35.toInt(),0xd2ff8213.toInt(),
            0x96ff6e10.toInt(),0x5aff6e10,0x28ff6e10,0x00ff6e10)
        val colors=if(day) night.mapIndexed { i,v ->
            if(i in 5..8) Color.argb(210,255,153,30)
            else Color.argb((Color.alpha(v)*.32f).roundToInt(),255,144,18)
        }.toIntArray() else night
        val normal=LinearGradient(0f,-20f*scale,0f,26f*scale,colors,
            distances.map { (it+20f)/46f }.toFloatArray(),Shader.TileMode.CLAMP)
        val along=LinearGradient(0f,0f,length,0f,
            intArrayOf(0x00ffffff,0x80ffffff.toInt(),Color.WHITE,0xc8ffffff.toInt(),0x40ffffff,0x00ffffff),
            floatArrayOf(0f,peak*.5f,peak,peak+(1f-peak)*.13f,.85f,1f),Shader.TileMode.CLAMP)
        fill(Color.WHITE,ComposeShader(normal,along,PorterDuff.Mode.DST_IN))
        c.drawRect(0f,-20f*scale,length,26f*scale,p)
        c.restore()
    }
    fun track(c: Canvas, day: Boolean) {
        c.save(); c.scale(.60f, 1f); trackLeft(c, day); c.restore()
        trackRight(c, day)
    }
    private fun trackLeft(c: Canvas, day: Boolean) {
        val left=polygon(0f,0f,772f,0f,739f,64f,575f,306f,539f,319f,442f,486f,492f,532f,0f,532f)
        fill(Color.WHITE,gradient(0f,0f,0f,532f,if(day) 0xe6e4e1 else 0x0a0908,
            if(day) 0xf0eeeb else 0x080706,if(day) 0xdedbd7 else 0x11100e));c.drawPath(left,p)
        // A broad recessed metal shoulder, with its highlight normal to the diagonal edge.
        // The old flat ochre stripe read as a line instead of a folded material face.
        val shoulder=polygon(739f,64f,711f,64f,546f,291f,510f,306f,411f,461f,442f,486f,539f,319f,575f,306f)
        fill(Color.WHITE,gradient(481f,266f,586f,336f,
            if(day) 0xe4e1dd else 0x080706,if(day) 0xb4b1ad else 0x171310,
            if(day) 0xf8f5f0 else 0x493b2d,if(day) 0x8f8c87 else 0x080808));c.drawPath(shoulder,p)
        c.save();c.clipPath(shoulder)
        if(!day) {
            fill(Color.WHITE,LinearGradient(0f,64f,0f,480f,
                intArrayOf(0xf0080706.toInt(),0xd0080706.toInt(),0x00080706,0xb0080706.toInt()),
                floatArrayOf(0f,.35f,.63f,1f),Shader.TileMode.CLAMP));c.drawPaint(p)
        }
        glow(c,566f,294f,72f,58f,if(day) 0x30ffffff else 0x45c4a27e)
        c.restore()
        val upper=polygon(0f,16f,490f,34f,0f,88f)
        fill(Color.WHITE,gradient(0f,16f,0f,88f,if(day) 0xdad8d5 else 0x111212,if(day) 0xf3f1ee else 0x191918));c.drawPath(upper,p)
        stroke(if(day) 0xc0ffffff.toInt() else 0x493a3938,1.4f)
        c.drawPath(Path().apply { moveTo(0f,16f);lineTo(490f,34f) },p)
        stroke(if(day) 0xffc5c2bd.toInt() else 0xff302a23.toInt(),1.2f)
        c.drawPath(Path().apply { moveTo(0f,88f);lineTo(490f,34f) },p)

        // Warm pools light the substrate before the raised speed face is placed over it.
        c.save();c.clipPath(left)
        glow(c,15f,243f,210f,104f,if(day) 0x29ff9c29 else 0x907b3109.toInt())
        glow(c,419f,437f,155f,102f,if(day) 0x26ffb958 else 0x885c3517.toInt())
        glow(c,494f,347f,90f,156f,if(day) 0x12ffbf78 else 0x464d290c)
        // Embossed scallops catch light on the lower lip and retain a dark upper recess.
        val random=java.util.Random(771)
        for(row in 0..42) for(col in 0..84) {
            val x=col*7f+(row%2)*3.5f+random.nextFloat()*1.2f
            val y=282f+row*6f+random.nextFloat()*.9f
            val light=max(exp(-(((x-414)/146).pow(2)+((y-454)/96).pow(2))),
                .6f*exp(-(((x-25)/115).pow(2)+((y-389)/66).pow(2))))
            val strength=(light*(.18f+random.nextFloat()*.82f)).toFloat()
            stroke(Color.argb((strength*if(day) 16 else 125).roundToInt(),if(day) 95 else 187,if(day) 82 else 116,if(day) 65 else 49),.85f)
            c.drawArc(RectF(x,y,x+4.8f,y+3.5f),5f,165f,false,p)
            stroke(Color.argb((strength*80).roundToInt(),0,0,0),.7f)
            c.drawArc(RectF(x,y-.8f,x+4.8f,y+2.7f),185f,150f,false,p)
        }
        c.restore()
        val plate=Path().apply {
            moveTo(92f,218f);lineTo(567f,205f);lineTo(433f,404f)
            quadTo(425f,416f,407f,418f);lineTo(40f,432f);lineTo(0f,432f);close()
        }
        c.save();c.clipPath(left)
        c.translate(6f,9f);fill(if(day) 0x70000000 else 0xb0000000.toInt())
        p.maskFilter=BlurMaskFilter(13f,BlurMaskFilter.Blur.NORMAL);c.drawPath(plate,p);p.maskFilter=null;c.restore()
        fill(Color.WHITE,gradient(120f,230f,460f,430f,if(day) 0xefedeb else 0x050504,
            if(day) 0xe9e6e2 else 0x080706,if(day) 0xf5f1eb else 0x21170e));c.drawPath(plate,p)
        c.save();c.clipPath(plate)
        glow(c,470f,355f,72f,146f,if(day) 0x20ffd395 else 0x76562e0e)
        texture(c);c.restore()
        val edge=Path().apply { moveTo(567f,205f);lineTo(433f,404f);quadTo(425f,416f,407f,418f);lineTo(40f,432f) }
        stroke(if(day) rgb(0xf7a34c) else rgb(0x704015),1f);c.drawPath(edge,p)
        luminousEdge(c,edge,day,474f,342f,184f)
        val top=Path().apply { moveTo(0f,218f);lineTo(465f,218f) }
        val bottom=Path().apply { moveTo(0f,432f);lineTo(380f,432f);quadTo(394f,432f,405f,444f);lineTo(491f,532f) }
        for(path in listOf(top,bottom)) {
            stroke(if(day) rgb(0xff9209) else rgb(0x9d4b0f),1.25f);c.drawPath(path,p)
        }
        luminousEdge(c,top,day,48f,218f,295f)
        luminousEdge(c,bottom,day,389f,436f,340f)
        // The lower fascia reflects a broad warm wash on only the material side of its lip.
        c.save();c.clipPath(polygon(0f,434f,380f,434f,405f,445f,491f,532f,0f,532f))
        glow(c,360f,439f,280f,79f,if(day) 0x21ffb964 else 0x646a421c)
        c.restore()
        trackBeam(c,0f,218f,465f,218f,day,.13f,9f)
        trackBeam(c,433f,404f,567f,205f,day,.40f,9f,-1f)
        trackBeam(c,430f,432f,65f,432f,day,.10f,9f,-1f)
        trackBeam(c,405f,444f,491f,532f,day,.18f,7f)
    }
    private fun trackRight(c: Canvas, day: Boolean) {
        val right=Path().apply {
            moveTo(1670f,60f);lineTo(1975f,42f);lineTo(1857f,500f)
            quadTo(1845f,500f,1834f,506f);lineTo(1520f,506f)
            quadTo(1512f,506f,1506f,499f);lineTo(1489f,477f);quadTo(1481f,469f,1486f,456f)
            lineTo(1654f,80f);quadTo(1660f,64f,1670f,60f);close()
        }
        // Separate the sloping music face from the textured outer backing, as in the reference.
        val rightBacking=polygon(1652f,54f,1920f,38f,1920f,532f,1473f,532f,1464f,474f)
        fill(Color.WHITE,gradient(1720f,60f,1920f,532f,
            if(day) 0xdfdcd8 else 0x090807,if(day) 0xd7d3cc else 0x17130d));c.drawPath(rightBacking,p)
        c.save();c.clipPath(rightBacking)
        glow(c,1905f,507f,152f,160f,if(day) 0x22ffac4b else 0x84633b12.toInt())
        for(row in 0..46) for(col in 0..55) {
            val xx=1530f+col*8f+(row%2)*4;val yy=260f+row*6f
            val light=exp(-(((xx-1890)/125).pow(2)+((yy-505)/100).pow(2)))
            stroke(Color.argb((light*if(day) 24 else 98).roundToInt(),if(day) 101 else 186,if(day) 91 else 111,if(day) 76 else 44),.8f)
            c.drawArc(RectF(xx,yy,xx+5f,yy+3.5f),5f,165f,false,p)
        }
        c.restore()
        // Upper-right folded fascia continues behind the lamp strip instead of exposing a blue video patch.
        val upperRight=polygon(1418f,0f,1920f,0f,1920f,38f,1670f,60f,1636f,87f,1598f,60f,1418f,60f)
        fill(Color.WHITE,gradient(0f,0f,0f,87f,if(day) 0xe0deda else 0x101010,
            if(day) 0xf7f5f1 else 0x191919));c.drawPath(upperRight,p)
        stroke(if(day) 0xc0ffffff.toInt() else 0x603e352b,1.4f)
        c.drawLine(1418f,60f,1920f,11f,p)
        fill(Color.WHITE,gradient(0f,0f,0f,532f,if(day) 0xe6e4e1 else 0x0a0908,
            if(day) 0xf0eeeb else 0x080706,if(day) 0xdedbd7 else 0x11100e));c.drawPath(right,p)
        // Music panel: dark bevel + long orange edge, avoiding isolated yellow corner bulbs.
        val musicBevel=Path().apply { moveTo(1670f,60f);quadTo(1650f,61f,1643f,79f)
            lineTo(1474f,458f);quadTo(1469f,470f,1480f,482f);lineTo(1510f,508f)
            lineTo(1520f,506f);lineTo(1484f,469f);lineTo(1654f,80f);close() }
        fill(Color.WHITE,gradient(1510f,350f,1542f,364f,if(day) 0x5b5046 else 0x100c08,
            if(day) 0x927c63 else 0x34281b,if(day) 0xf1e3d5 else 0x1a120b));c.drawPath(musicBevel,p)
        c.save();c.clipPath(right)
        fill(Color.WHITE,gradient(1640f,100f,1810f,500f,
            if(day) 0xf0eeea else 0x060707,if(day) 0xe9e6e1 else 0x050605,
            if(day) 0xf4f0e8 else 0x100c08));c.drawPaint(p)
        glow(c,1510f,457f,108f,170f,if(day) 0x22ffb65c else 0x80663b16.toInt())
        glow(c,1850f,493f,100f,100f,if(day) 0x22ffc475 else 0x80502d0f.toInt())
        // The face is smooth; embossed cells belong to the substrate outside it.
        c.restore()
        val musicEdge=Path().apply { moveTo(1670f,60f);quadTo(1660f,64f,1654f,80f)
            lineTo(1486f,456f);quadTo(1481f,469f,1489f,477f);lineTo(1506f,499f)
            quadTo(1512f,506f,1520f,506f);lineTo(1816f,506f)
            quadTo(1839f,506f,1857f,500f);lineTo(1975f,42f) }
        stroke(if(day) rgb(0xe4a05a) else rgb(0x6c3716),.9f);c.drawPath(musicEdge,p)
        luminousEdge(c,musicEdge,day,1642f,106f,140f)
        luminousEdge(c,musicEdge,day,1503f,472f,116f)
        luminousEdge(c,musicEdge,day,1858f,496f,138f)
        trackBeam(c,1486f,456f,1654f,80f,day,.72f,8f)
        trackBeam(c,1857f,500f,1920f,255f,day,.14f,7f,-1f)
        trackBeam(c,1857f,510f,1920f,510f,day,.28f,6f)
    }
    fun card(c: Canvas, r: RectF, style: DashboardStyle, day: Boolean) {
        val star=style==DashboardStyle.STARFIELD
        fill(Color.WHITE,gradient(r.left,r.top,r.right,r.bottom,
            if(day) { if(star) 0xcfd0dc else 0xe4e3df } else { if(star) 0x1e1c32 else 0x051015 },
            if(day) { if(star) 0xdad9e2 else 0xf0efea } else { if(star) 0x201f35 else 0x02090c }))
        c.drawRoundRect(r,18f,18f,p)
        stroke(Color.WHITE,1.1f,gradient(r.left,r.top,r.right,r.bottom,
            if(day) { if(star) 0xa7a9b8 else 0xc6c8c9 } else { if(star) 0x64637c else 0x263e4e },
            if(day) 0xbcbec7 else if(star) 0x484960 else 0x15232b));c.drawRoundRect(r,18f,18f,p)
    }

    fun bar(c: Canvas, r: RectF, roundTop: Boolean, style: DashboardStyle, day: Boolean) {
        val star=style==DashboardStyle.STARFIELD
        val radii=if(roundTop) floatArrayOf(20f,20f,20f,20f,0f,0f,0f,0f)
            else floatArrayOf(0f,0f,0f,0f,20f,20f,20f,20f)
        val path=Path().apply {
            if(style==DashboardStyle.TRACK) {
                // Shallow trapezoid ends from the racing design, within the shared module bounds.
                if(roundTop) {
                    moveTo(r.left,r.bottom);lineTo(r.left+22,r.top+10)
                    quadTo(r.left+27,r.top,r.left+40,r.top);lineTo(r.right-40,r.top)
                    quadTo(r.right-27,r.top,r.right-22,r.top+10);lineTo(r.right,r.bottom)
                } else {
                    moveTo(r.left,r.top);lineTo(r.left+22,r.bottom-10)
                    quadTo(r.left+27,r.bottom,r.left+40,r.bottom);lineTo(r.right-40,r.bottom)
                    quadTo(r.right-27,r.bottom,r.right-22,r.bottom-10);lineTo(r.right,r.top)
                }
                close()
            } else addRoundRect(r,radii,Path.Direction.CW)
        }
        fill(Color.WHITE,gradient(0f,r.top,0f,r.bottom,
            if(day) { if(star) 0xc6c7d4 else 0xe5e3df } else { if(star) 0x151529 else 0x04090d },
            if(day) { if(star) 0xd0cfd9 else 0xeae8e4 } else { if(star) 0x1b1b30 else 0x020609 }))
        c.drawPath(path,p)
        // Only decorate the content-facing edge, keeping the module flush with the display.
        c.save();c.clipRect(r.left-2,if(roundTop) r.top-2 else r.top+2,r.right+2,if(roundTop) r.bottom-2 else r.bottom+2)
        stroke(when { style==DashboardStyle.TRACK -> if(day) rgb(0xffa210) else rgb(0xa65411)
            star -> if(day) rgb(0xa7a8b7) else rgb(0x4b4c65)
            day -> rgb(0xc6cbca)
            else -> rgb(0x1b303d) },1.2f)
        c.drawPath(path,p);c.restore()
    }
}
