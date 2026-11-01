package com.shilapi.xcertplay

import android.content.Context
import android.graphics.*
import com.shilapi.xcertplay.host.R
import java.util.Locale

/** Wire IDs stay compatible with existing saved commands; visual order is NAV, MAP, MUSIC. */
internal object Esp32Pages {
    fun position(page:Int):Int=when(page) { 0->0;2->1;1->2;else->error("Invalid page") }
}

internal object Esp32MusicTime {
    fun elapsed(elapsed:Long?,duration:Long?):Long? = elapsed?.takeIf { it>=0 }?.let {
        if(duration!=null && duration>0)minOf(it,duration) else it
    }
    fun label(millis:Long?):String {
        if(millis==null || millis<0)return "--:--"
        val seconds=millis/1000
        return if(seconds>=3600)String.format(Locale.US,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
        else String.format(Locale.US,"%02d:%02d",seconds/60,seconds%60)
    }
}

internal object Esp32FrameRenderer {
    fun render(context:Context,s:DashboardSnapshot,page:Int,navigationTransform:Int=0,carPlay:Bitmap?=null):Bitmap {
        require(page in 0..2 && navigationTransform in 0..3)
        val b=Bitmap.createBitmap(466,466,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(Color.BLACK)
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(value:String,x:Float,y:Float,size:Float,width:Float,color:Int=Color.WHITE,bold:Boolean=false,align:Paint.Align=Paint.Align.CENTER) {
            p.reset();p.isAntiAlias=true;p.color=color;p.textSize=size;p.textAlign=align
            p.typeface=if(bold)Typeface.create("sans-serif",Typeface.BOLD) else Typeface.create("sans-serif",Typeface.NORMAL)
            val safe=value.replace('\n',' ').take(150)
            val clipped=android.text.TextUtils.ellipsize(safe,android.text.TextPaint(p),width,android.text.TextUtils.TruncateAt.END).toString()
            c.drawText(clipped,x,y,p)
        }
        if(page!=2) {
            // A restrained metallic edge from the concept; never masks the full-screen map.
            p.style=Paint.Style.STROKE;p.strokeWidth=7f
            p.shader=LinearGradient(40f,0f,420f,466f,intArrayOf(Color.rgb(165,171,176),Color.rgb(25,29,32),Color.rgb(75,80,84)),floatArrayOf(0f,.46f,1f),Shader.TileMode.CLAMP)
            c.drawCircle(233f,233f,229f,p)
            p.strokeWidth=1f;p.shader=LinearGradient(70f,0f,400f,466f,Color.rgb(225,228,230),Color.rgb(25,28,31),Shader.TileMode.CLAMP)
            c.drawCircle(233f,233f,232f,p)
            p.shader=null;p.color=Color.rgb(16,18,20);p.strokeWidth=1f;c.drawCircle(233f,233f,224.5f,p)
        }
        if(page==0) {
            val n=s.navigation
            val heading=when(n?.icon) {
                1->context.getString(R.string.esp32_instruction_straight)
                2->context.getString(R.string.esp32_instruction_left)
                3->context.getString(R.string.esp32_instruction_right)
                else->n?.road?.takeIf { it.isNotBlank() } ?: context.getString(R.string.esp32_no_navigation)
            }
            text(heading,233f,79f,29f,300f,bold=true)
            if(n!=null) {
                val drawable=androidx.core.content.ContextCompat.getDrawable(context,when(n.icon) { 2->R.drawable.ic_turn_card_turn_left;3->R.drawable.ic_turn_card_turn_right;4->R.drawable.ic_turn_card_turn_slight_left;5->R.drawable.ic_turn_card_turn_slight_right;6->R.drawable.ic_turn_card_turn_sharp_left;7->R.drawable.ic_turn_card_turn_sharp_right;8->R.drawable.ic_turn_card_u_turn_left;19->R.drawable.ic_turn_card_u_turn_right;11,12,17,18->R.drawable.ic_turn_card_roundabout_right;15->R.drawable.ic_turn_card_sports_score;else->R.drawable.ic_turn_card_straight })
                DashboardGuidanceGlyph().draw(c,RectF(102f,100f,272f,280f),n.icon,Color.WHITE,drawable)
                val distance=when { n.distanceMeters<0->"—";n.distanceMeters<1000->n.distanceMeters.toString();else->String.format(Locale.US,"%.1f",n.distanceMeters/1000.0) }
                val unit=if(n.distanceMeters<0)"" else if(n.distanceMeters<1000)"m" else "km"
                p.typeface=Typeface.create("sans-serif",Typeface.BOLD);p.textSize=40f
                val numberWidth=p.measureText(distance);p.typeface=Typeface.DEFAULT;p.textSize=24f;val unitWidth=p.measureText(unit)
                val left=233f-(numberWidth+if(unit.isEmpty())0f else unitWidth+8f)/2f
                text(distance,left,337f,40f,230f,bold=true,align=Paint.Align.LEFT)
                if(unit.isNotEmpty())text(unit,left+numberWidth+8f,337f,24f,60f,align=Paint.Align.LEFT)
                text(listOfNotNull(n.remainingMeters?.takeIf { it>=0 }?.let { String.format(Locale.US,"%.1f km",it/1000.0) },n.remainingSeconds?.takeIf { it>=0 }?.let { context.getString(R.string.widget_minutes,(it+59)/60) }).joinToString(" · "),233f,389f,24f,310f,Color.LTGRAY)
            }
            p.reset();p.isAntiAlias=true;p.color=Color.WHITE;p.style=Paint.Style.STROKE;p.strokeWidth=3f;c.drawCircle(371f,218f,43f,p)
            text(s.speedKph?.takeIf { it.isFinite() && it>=0 }?.toInt()?.toString() ?: "—",371f,224f,36f,74f,bold=true)
            text("km/h",371f,244f,15f,72f)
        } else if(page==1) {
            val cover=RectF(137f,54f,329f,246f)
            c.save();c.clipPath(Path().apply { addRoundRect(cover,15f,15f,Path.Direction.CW) })
            p.reset();p.isAntiAlias=true;p.isFilterBitmap=true
            val art=s.music.artwork?.takeUnless { it.isRecycled }
            if(art!=null)c.drawBitmap(art,Esp32CarPlayFrames.centerCrop(art.width,art.height),cover,p)
            else { p.color=Color.rgb(28,34,40);c.drawRect(cover,p);text("♫",233f,179f,75f,150f,Color.WHITE) }
            c.restore()
            text(s.music.info.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.esp32_no_music),233f,290f,32f,340f,bold=true)
            text(s.music.info.artist.orEmpty(),233f,324f,23f,330f)
            val duration=s.music.info.durationMillis?.takeIf { it>0 }
            val elapsed=Esp32MusicTime.elapsed(s.music.info.elapsedMillis,duration)
            p.reset();p.isAntiAlias=true;p.strokeWidth=6f;p.strokeCap=Paint.Cap.ROUND;p.color=Color.argb(72,255,255,255)
            c.drawLine(102f,354f,364f,354f,p)
            if(duration!=null && elapsed!=null) {
                val x=102f+262f*(elapsed.toDouble()/duration).toFloat()
                p.color=Color.WHITE;c.drawLine(102f,354f,x,354f,p)
                p.color=Color.WHITE;p.style=Paint.Style.FILL;c.drawCircle(x,354f,7f,p)
            }
            text(Esp32MusicTime.label(elapsed),102f,382f,19f,126f,align=Paint.Align.LEFT)
            text(Esp32MusicTime.label(duration),364f,382f,19f,126f,align=Paint.Align.RIGHT)
        } else {
            if(carPlay!=null && !carPlay.isRecycled) {
                p.reset();p.isFilterBitmap=true;c.drawBitmap(carPlay,Esp32CarPlayFrames.contentCrop(carPlay),RectF(0f,0f,466f,466f),p)
            } else text(context.getString(R.string.esp32_no_carplay),233f,233f,24f,340f)
        }
        if(page!=2)for(i in 0..2) {
            p.reset();p.isAntiAlias=true;p.color=if(i==Esp32Pages.position(page))Color.WHITE else Color.rgb(74,87,98)
            c.drawCircle(205f+i*28f,416f,6f,p)
        }
        if(navigationTransform==0)return b
        val matrix=Matrix().apply { when(navigationTransform) { 1->setScale(1f,-1f);2->setScale(-1f,1f);3->setScale(-1f,-1f) } }
        return Bitmap.createBitmap(b,0,0,b.width,b.height,matrix,false).also { if(it!==b)b.recycle() }
    }
}
