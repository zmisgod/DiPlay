package com.shilapi.xcertplay

import android.graphics.*
import kotlin.math.*

/** Native vector rendering: no mockup images, fake map roads or simulated vehicle readings. */
internal class DashboardConceptRenderer {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var backdrop: Picture? = null
    private var backdropKey: Pair<DashboardStyle, Boolean>? = null
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val italic = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
    private fun fill(c: Canvas, r: RectF, color: Int, radius: Float = 0f) {
        p.shader=null; p.color=color; p.style=Paint.Style.FILL
        c.drawRoundRect(r,radius,radius,p)
    }
    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, face: Typeface = regular) {
        p.shader=null; p.style=Paint.Style.FILL; p.color=color; p.textSize=size; p.typeface=face; p.textAlign=Paint.Align.CENTER
        c.drawText(value,x,y,p)
    }
    private fun line(c: Canvas, x: Float, y: Float, xx: Float, yy: Float, color: Int, width: Float) {
        p.shader=null;p.color=color;p.style=Paint.Style.STROKE;p.strokeWidth=width;c.drawLine(x,y,xx,yy,p)
    }
    fun background(c: Canvas, style: DashboardStyle, day: Boolean) {
        val key=style to day
        if(backdropKey != key) {
            backdrop=Picture().apply {
                val b=beginRecording(1920,532)
                p.style=Paint.Style.FILL
                val colors = when {
                    day && style == DashboardStyle.STARFIELD -> intArrayOf(0xffefece9.toInt(),0xffdcdce9.toInt(),0xffebe6dc.toInt())
                    day -> intArrayOf(0xfff5f1e9.toInt(),0xffe4e9ed.toInt(),0xfff0e8dd.toInt())
                    style == DashboardStyle.STARFIELD -> intArrayOf(0xff252039.toInt(),0xff101b30.toInt(),0xff31223e.toInt())
                    else -> intArrayOf(0xff03070a.toInt(),0xff07131c.toInt(),0xff020509.toInt())
                }
                p.alpha=255;p.shader=LinearGradient(0f,0f,1920f,532f,colors,null,Shader.TileMode.CLAMP)
                b.drawRect(0f,0f,1920f,532f,p);p.shader=null
                if(style==DashboardStyle.STARFIELD) {
                    // Static nebulous light and varied stars, recorded once. No animated wallpaper work.
                    for ((x,y,tint) in listOf(Triple(180f,60f,0x555e3986),Triple(1350f,410f,0x44364682),Triple(1780f,30f,0x555e426c))) {
                        p.alpha=255;p.shader=RadialGradient(x,y,650f,if(day) 0x33ffffff else tint,Color.TRANSPARENT,Shader.TileMode.CLAMP)
                        b.drawRect(0f,0f,1920f,532f,p)
                    }
                    p.shader=null
                    val random=java.util.Random(4711)
                    repeat(180) { i ->
                        val x=random.nextFloat()*1920;val y=random.nextFloat()*532
                        val alpha=if(day) 95 else 70+random.nextInt(115)
                        p.color=Color.argb(alpha,236,228,255)
                        b.drawCircle(x,y,if(i%17==0) 1.5f else .45f+random.nextFloat()*.6f,p)
                        if(i%31==0) {
                            p.color=Color.argb(alpha/3,236,228,255)
                            b.drawCircle(x,y,3f,p)
                        }
                    }
                }
                endRecording()
            }
            backdropKey=key
        }
        backdrop?.let(c::drawPicture)
    }
    /** Instruments keep fixed geometry; only the shared status glyphs respond to icon scale. */
    fun instruments(c: Canvas, style: DashboardStyle, day: Boolean, state: DashboardSnapshot,
        music: (Canvas, RectF) -> Unit) {
        val ink=if(day) 0xff28303e.toInt() else 0xfff1f4fa.toInt()
        val secondary=if(day) 0xff586171.toInt() else 0xffaebdd1.toInt()
        val surface=if(day) 0xfff3f1ee.toInt() else if(style==DashboardStyle.STARFIELD) 0xff201e34.toInt() else 0xff050d13.toInt()
        val accent=if(style==DashboardStyle.TRACK) 0xffff960d.toInt() else if(day) 0xff008e91.toInt() else 0xff32d9df.toInt()
        val top=12f
        val bottom=520f
        val cy=(top+bottom)/2;val radius=(bottom-top)/2
        val speed=state.speedKph?.roundToInt()?.toString() ?: "—"
        val rpm=state.number(com.shilapi.xcertplay.ultra.VehicleSignal.ENGINE_RPM)
        val temp=(state.coolantC ?: state.frontCompartmentC)?.roundToInt()?.toString() ?: "—"
        if(style==DashboardStyle.TRACK) {
            val left=Path().apply { moveTo(0f,top);lineTo(550f,top);lineTo(445f,bottom);lineTo(0f,bottom);close() }
            p.style=Paint.Style.FILL
            p.alpha=255;p.shader=LinearGradient(0f,top,550f,bottom,if(day) 0xfffaf9f5.toInt() else 0xff141313.toInt(),surface,Shader.TileMode.CLAMP)
            c.drawPath(left,p);p.shader=null
            c.save();c.clipPath(left)
            p.color=if(day) 0x10946b32 else 0x2478521e
            for (yy in 365..520 step 8) for(xx in 0..550 step 8) c.drawCircle(xx.toFloat()+(yy%16)/2,yy.toFloat(),.8f,p)
            c.restore()
            for ((width,alpha) in listOf(10f to 15,5f to 40,1.5f to 190)) {
                p.style=Paint.Style.STROKE;p.strokeWidth=width;p.color=Color.argb(alpha,255,150,13);c.drawPath(left,p)
            }
            // Layered speed plate and fine orange horizon reproduce the track silhouette.
            val speedPlate=Path().apply { moveTo(0f,top+195);lineTo(505f,top+195);lineTo(440f,bottom-88);lineTo(0f,bottom-88);close() }
            p.style=Paint.Style.FILL;p.alpha=255;p.shader=LinearGradient(0f,top+195,480f,bottom,if(day) 0xffede7dc.toInt() else 0xff25180c.toInt(),surface,Shader.TileMode.CLAMP)
            c.drawPath(speedPlate,p);p.shader=null
            line(c,0f,top+195,505f,top+195,accent,1.2f)
            rpmBar(c,RectF(48f,top+30,450f,top+60),rpm,accent,secondary,ink)
            text(c,"${rpm?.roundToInt() ?: "—"} rpm",245f,top+94,27f,ink)
            text(c,speed,220f,top+(bottom-top)*.66f,min(130f,(bottom-top)*.29f),ink,italic)
            text(c,"km/h",220f,top+(bottom-top)*.80f,28f,secondary)
            text(c,"${if(state.coolantC==null) "前舱" else "水温"}  $temp°C",220f,bottom-20,25f,secondary)
            val right=RectF(1510f,top,1920f,bottom)
            panel(c,right,day,surface,accent)
            music(c,RectF(1570f,top+24,1860f,bottom-26))
        } else if(style==DashboardStyle.OBSIDIAN) {
            panel(c,RectF(16f,top,410f,bottom),day,surface,secondary)
            // A single open arc leaves the map dominant, rather than a second full dial.
            p.style=Paint.Style.STROKE;p.strokeWidth=3.5f;p.color=secondary
            c.drawArc(RectF(50f,top+24,390f,bottom-82),80f,200f,false,p)
            state.speedKph?.let {
                p.strokeWidth=5f;p.color=accent;c.drawArc(RectF(50f,top+24,390f,bottom-82),80f,(it/240*200).coerceIn(0.0,200.0).toFloat(),false,p)
            }
            val arcCy=(top+24+bottom-82)/2;val ry=(bottom-82-top-24)/2
            for(i in 0..24) {
                val a=Math.toRadians(80.0+i*200.0/24)
                val co=cos(a).toFloat();val si=sin(a).toFloat()
                line(c,220+co*170,arcCy+si*ry,220+co*161,arcCy+si*(ry-8),secondary,1.5f)
                if(i%4==0) text(c,(i*10).toString(),220+co*143,arcCy+si*(ry-21)+6,16f,secondary)
            }
            text(c,speed,252f,cy+8,min(98f,(bottom-top)*.28f),ink,bold);text(c,"km/h",252f,cy+46,25f,secondary)
            text(c,"${if(state.coolantC==null) "前舱" else "水温"}  $temp°C",215f,bottom-25,25f,secondary)
            panel(c,RectF(1510f,top,1904f,bottom),day,surface,secondary)
            music(c,RectF(1574f,top+24,1840f,bottom-126))
            line(c,1540f,bottom-104,1874f,bottom-104,if(day) 0xffc4ccce.toInt() else 0xff23333b.toInt(),1f)
            text(c,"${rpm?.roundToInt() ?: "—"} rpm",1707f,bottom-61,31f,ink)
            rpmBar(c,RectF(1550f,bottom-40,1860f,bottom-26),rpm,accent,secondary,ink,labels=false)
        } else {
            for((cx,isSpeed) in listOf(266f to true,1654f to false)) {
                c.save();c.translate(cx,cy);c.scale(radius/254f,radius/254f)
                p.alpha=255;p.shader=RadialGradient(-50f,-90f,380f,
                    if(style==DashboardStyle.STARFIELD) { if(day) 0xffe9e5ed.toInt() else 0xff29213c.toInt() }
                    else if(day) 0xfff4f6f5.toInt() else 0xff0d1c27.toInt(),
                    if(day) surface else 0xff02090e.toInt(),Shader.TileMode.CLAMP)
                p.style=Paint.Style.FILL;c.drawCircle(0f,0f,254f,p);p.shader=null
                if(style==DashboardStyle.STARFIELD) {
                    p.color=if(day) 0x45ffffff else 0x426f789a
                    val random=java.util.Random(if(isSpeed) 26 else 16)
                    repeat(20){ val x=random.nextFloat()*440-220;val y=random.nextFloat()*440-220
                        if(x*x+y*y<235*235 && (abs(x)>100 || abs(y)>90)) c.drawCircle(x,y,.8f,p) }
                }
                val dial=if(isSpeed) DashboardDial(240.0,48,4) else DashboardDial.RPM
                val value=if(isSpeed) state.speedKph else rpm
                p.style=Paint.Style.STROKE
                if(style==DashboardStyle.POLAR) {
                    // Metallic bezel: multiple narrow tonal bands instead of one flat blue outline.
                    p.alpha=255;p.shader=LinearGradient(-210f,-250f,170f,250f,intArrayOf(0xffbdd2e0.toInt(),0xff254455.toInt(),0xff8cabbf.toInt(),0xff112d3c.toInt()),null,Shader.TileMode.CLAMP)
                    p.strokeWidth=5f;c.drawCircle(0f,0f,250f,p);p.shader=null
                    p.strokeWidth=1f;p.color=0xff718b9e.toInt();c.drawCircle(0f,0f,254f,p)
                    p.color=0xff182d3c.toInt();c.drawCircle(0f,0f,244f,p)
                    p.style=Paint.Style.FILL;p.alpha=255;p.shader=RadialGradient(0f,0f,168f,0xff020609.toInt(),0xff071722.toInt(),Shader.TileMode.CLAMP)
                    c.drawCircle(0f,0f,168f,p);p.shader=null;p.style=Paint.Style.STROKE
                    p.strokeWidth=2f;p.color=0xff28748a.toInt();c.drawCircle(0f,0f,170f,p)
                    for(i in 0..100) {
                        val a=Math.toRadians(135.0+i*2.7);val co=cos(a).toFloat();val si=sin(a).toFloat()
                        line(c,co*175,si*175,co*181,si*181,0xff335266.toInt(),1f)
                    }
                } else {
                    p.strokeWidth=1.5f;p.color=secondary;c.drawCircle(0f,0f,250f,p)
                }
                for(i in 0..dial.divisions) {
                    val v=dial.valueAtTick(i);val a=Math.toRadians(dial.angle(v).toDouble());val major=i%dial.majorEvery==0
                    val col=if(!isSpeed && v>=7000) 0xffda414b.toInt() else secondary
                    line(c,cos(a).toFloat()*(if(major) 228 else 236),sin(a).toFloat()*(if(major) 228 else 236),cos(a).toFloat()*244,sin(a).toFloat()*244,col,if(major) 2.8f else 1.2f)
                    if(major) text(c,if(isSpeed) v.roundToInt().toString() else (v/1000).roundToInt().toString(),cos(a).toFloat()*207,sin(a).toFloat()*207+8,22f,col)
                }
                if(value!=null) {
                    val sweep=dial.sweep(value)
                    if(style==DashboardStyle.POLAR) {
                        p.style=Paint.Style.STROKE;p.strokeWidth=62f
                        p.alpha=255;p.shader=SweepGradient(0f,0f,intArrayOf(0x1041dbea,0x3034dbea,0x6641dbea,0x1041dbea),null)
                        c.drawArc(RectF(-211f,-211f,211f,211f),135f,sweep,false,p);p.shader=null
                        val a=Math.toRadians((135f+sweep).toDouble())
                        line(c,cos(a).toFloat()*174,sin(a).toFloat()*174,cos(a).toFloat()*243,sin(a).toFloat()*243,accent,3f)
                    }
                    p.style=Paint.Style.STROKE;p.strokeWidth=4f;p.color=accent
                    c.drawArc(RectF(-246f,-246f,246f,246f),135f,sweep,false,p)
                }
                if(isSpeed){text(c,speed,0f,36f,110f,ink);text(c,"km/h",0f,78f,28f,secondary)
                    text(c,"${if(state.coolantC==null) "前舱" else "水温"}  $temp°C",0f,190f,24f,secondary)
                } else {music(c,RectF(-120f,-128f,120f,127f));text(c,"${rpm?.roundToInt() ?: "—"} rpm",0f,196f,29f,ink)}
                c.restore()
            }
        }
    }
    private fun panel(c: Canvas,r: RectF,day: Boolean,surface: Int,edge: Int) {
        p.style=Paint.Style.FILL
        p.alpha=255;p.shader=LinearGradient(r.left,r.top,r.right,r.bottom,if(day) 0xfffdfcf8.toInt() else 0xff0e1920.toInt(),surface,Shader.TileMode.CLAMP)
        c.drawRoundRect(r,24f,24f,p);p.shader=null
        p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=Color.argb(70,Color.red(edge),Color.green(edge),Color.blue(edge))
        c.drawRoundRect(r,24f,24f,p)
    }
    private fun rpmBar(c: Canvas, r: RectF, value: Double?, accent: Int, muted: Int, ink: Int, labels: Boolean=true) {
        for(i in 0..63) {
            val x=r.left+i*r.width()/64
            line(c,x,r.bottom,x+3,r.top,if(i>=56) 0xffdc4852.toInt() else if(value!=null && i<value/125) accent else muted,3f)
        }
        if(labels) for(i in 0..8) text(c,i.toString(),r.left+i*r.width()/8,r.top-10,19f,if(i>=7) 0xffdc4852.toInt() else ink)
    }
}
