package com.shilapi.xcertplay

import android.graphics.*
import com.shilapi.xcertplay.ultra.VehicleReading
import com.shilapi.xcertplay.ultra.VehicleSignal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardLightingTest {
    private fun frame(style: DashboardStyle, day: Boolean = false): Bitmap {
        val renderer=DashboardConceptRenderer()
        return Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).also {
            val c=Canvas(it);renderer.background(c,style,day)
            renderer.instruments(c,style,day,DashboardSnapshot()) { _,_ -> }
        }
    }
    private fun sample(image: Bitmap,x: Int,y: Int): List<Int> {
        val pixels=(x-3..x+3).flatMap { xx -> (y-3..y+3).map { yy -> image.getPixel(xx,yy) } }
        return listOf(pixels.map(Color::red).sorted()[24],pixels.map(Color::green).sorted()[24],pixels.map(Color::blue).sorted()[24])
    }
    @Test fun blankSurfacesStayWithinMeasuredReferenceColorTolerance() {
        // 7×7 medians from the original PNGs, mapped relative to the dial center/radius.
        // These protect material colors only; they are not a whole-screen similarity score.
        val expected=mapOf(
            (DashboardStyle.POLAR to false) to listOf(listOf(2,7,10),listOf(2,6,11),listOf(2,6,11),listOf(1,6,10)),
            (DashboardStyle.STARFIELD to false) to listOf(listOf(28,26,48),listOf(28,26,48),listOf(28,26,48),listOf(29,28,51)),
            (DashboardStyle.STARFIELD to true) to listOf(listOf(222,222,228),listOf(222,222,228),listOf(222,222,228),listOf(219,219,226)))
        val points=listOf(266 to 186,346 to 206,186 to 206,346 to 346)
        for((key,colors) in expected) {
            val image=frame(key.first,key.second)
            for((i,point) in points.withIndex()) {
                val actual=sample(image,point.first,point.second)
                for(channel in 0..2) assertTrue("$key/$point expected ${colors[i]} got $actual",
                    abs(actual[channel]-colors[i][channel])<=4)
            }
            image.recycle()
        }
    }
    @Test fun metalHasDirectionalReflectionAndTrackGlowIsLocal() {
        val polar=frame(DashboardStyle.POLAR)
        fun luma(radius: Float, degrees: Double): Float {
            val a=Math.toRadians(degrees)
            return Color.luminance(polar.getPixel((266+cos(a)*radius*DashboardLayout.CONCEPT_DIAL_SCALE).roundToInt(),(266+sin(a)*radius*DashboardLayout.CONCEPT_DIAL_SCALE).roundToInt()))
        }
        // Sample across the ridge width: a single rounded diagonal pixel may fall into its groove.
        fun ridge(angle: Double) = (0..12).maxOf { luma(245f+it*.25f,angle) }
        fun groove(angle: Double) = (0..8).minOf { luma(243.5f+it*.25f,angle) }
        assertTrue("Silver ridge highlights must vary with angle",ridge(225.0)>ridge(75.0)*1.6f)
        assertTrue("Dark groove must separate the metallic ridges",ridge(225.0)>groove(225.0)*1.5f)
        polar.recycle()
        val track=frame(DashboardStyle.TRACK)
        assertTrue("Orange light falls off away from its source",Color.red(track.getPixel(45,214))>Color.red(track.getPixel(350,214))+8)
        track.recycle()
    }
    @Test fun surfaceCacheIsBoundedAndMapFadeDoesNotCoverTheCenter() {
        val renderer=DashboardConceptRenderer()
        val image=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888)
        val c=Canvas(image)
        for(style in listOf(DashboardStyle.STARFIELD,DashboardStyle.TRACK,DashboardStyle.POLAR,DashboardStyle.OBSIDIAN)) {
            renderer.background(c,style,false)
            assertTrue(renderer.surfaceBytes<=1920*532*4*3)
        }
        renderer.background(c,DashboardStyle.STARFIELD,false)
        val edgeColor=image.getPixel(266,10)
        image.eraseColor(Color.MAGENTA)
        renderer.videoEdges(c,DashboardStyle.STARFIELD,false)
        assertEquals(edgeColor,image.getPixel(266,10))
        assertEquals(Color.MAGENTA,image.getPixel(960,290))
        assertEquals(Color.MAGENTA,image.getPixel(200,10))
        val count=renderer.buildCount
        renderer.release();assertEquals(0,renderer.surfaceBytes)
        renderer.background(c,DashboardStyle.STARFIELD,false)
        assertEquals(count+1,renderer.buildCount)
        image.recycle()
    }

    @Test fun starCoresSurviveHalfSizeAndObsidianKeepsItsTallSourceCurve() {
        val image=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888)
        DashboardConceptRenderer().background(Canvas(image),DashboardStyle.STARFIELD,false)
        val small=Bitmap.createScaledBitmap(image,960,266,true)
        val visible=(0 until small.width).sumOf { x -> (0 until small.height).count { y ->
            val pixel=small.getPixel(x,y)
            Color.red(pixel)>135 && Color.green(pixel)>135 && Color.blue(pixel)>150
        } }
        assertTrue("Star cores must remain visible when the panel is previewed at half size: $visible",visible>60)
        image.recycle();small.recycle()
        val scale=ObsidianSpeedScale();val bounds=RectF();scale.path.computeBounds(bounds,true)
        assertTrue("Do not stretch the narrow source curve into a wide C",bounds.height()/bounds.width()>1.75f)
        val part=Path();scale.progress(60.0,part)
        val fullLength=PathMeasure(scale.path,false).length
        assertEquals(fullLength*.25f,PathMeasure(part,false).length,.5f)
        scale.progress(0.0,part);assertTrue(part.isEmpty)
        scale.progress(500.0,part);assertEquals(fullLength,PathMeasure(part,false).length,.5f)
    }
    @Test fun trackCoarseRpmBarsRemainReadableAndKeepTheSevenThousandRedline() {
        val renderer=DashboardConceptRenderer()
        fun render(rpm: Double?)=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).also {
            val c=Canvas(it);renderer.background(c,DashboardStyle.TRACK,false)
            val readings=if(rpm==null) emptyMap() else mapOf(VehicleSignal.ENGINE_RPM to VehicleReading(rpm,0,0))
            renderer.instruments(c,DashboardStyle.TRACK,false,DashboardSnapshot(readings)) { _,_ -> }
        }
        fun runs(b: Bitmap, red: Boolean): List<Int> {
            val widths=mutableListOf<Int>();var width=0
            for(x in 24..398) {
                val v=b.getPixel(x,140)
                val lit=if(red) Color.red(v)>150 && Color.green(v)<90 else
                    Color.red(v)>190 && Color.green(v)>100 && Color.blue(v)<170
                if(lit) width++ else if(width>0) { widths+=width;width=0 }
            }
            if(width>0) widths+=width
            return widths
        }
        for((rpm,lit) in listOf(null to 0,0.0 to 0,1600.0 to 8,7000.0 to 35,8000.0 to 35)) {
            val b=render(rpm)
            val bars=runs(b,false)
            assertEquals("Active bar count at $rpm rpm",lit,bars.size)
            assertTrue("Thicker bars must survive a horizontal scan",bars.all { it>=5 })
            // The redline stays marked even before RPM reaches it.
            assertEquals("Redline stays at 7,000 rpm",5,runs(b,true).size)
            b.recycle()
        }
    }

    @Test fun trackReflectionKeepsAPaleNarrowCoreAndAsymmetricFalloff() {
        val b=frame(DashboardStyle.TRACK)
        // Original horizontal edge: a single pale-gold core with a darker incident side.
        // A broad symmetric orange blur can be bright overall but must fail this profile check.
        val core=b.getPixel(60,218)
        assertTrue("Pale reflected core, not saturated orange",Color.green(core)>200 && Color.blue(core)>115)
        val shoulder=b.getPixel(60,222)
        assertTrue("Core must separate from the surrounding glow",Color.green(core)>Color.green(shoulder)*3)
        val incident=b.getPixel(60,216);val reflected=b.getPixel(60,220)
        assertTrue("Surface reflection is asymmetric",Color.red(reflected)>Color.red(incident)*1.1f)
        b.recycle()
    }

    @Test fun starfieldProgressNeverFillsTheDialAndPolarMarkersReflectAtBothRims() {
        fun render(style: DashboardStyle, day: Boolean, speed: Double?, rpm: Double?): Bitmap {
            val readings=mutableMapOf<VehicleSignal,VehicleReading>()
            speed?.let { readings[VehicleSignal.INDICATED_SPEED_KPH]=VehicleReading(it,0,0) }
            rpm?.let { readings[VehicleSignal.ENGINE_RPM]=VehicleReading(it,0,0) }
            return Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).also {
                val renderer=DashboardConceptRenderer();val c=Canvas(it)
                renderer.background(c,style,day)
                renderer.instruments(c,style,day,DashboardSnapshot(readings)) { _,_ -> }
            }
        }
        for(day in listOf(false,true)) {
            val zero=render(DashboardStyle.STARFIELD,day,0.0,0.0)
            val live=render(DashboardStyle.STARFIELD,day,60.0,1600.0)
            for(cx in listOf(266,1654)) for(angle in 145..165 step 2) for(radius in 178..190) {
                val a=Math.toRadians(angle.toDouble());val x=(cx+cos(a)*radius*DashboardLayout.CONCEPT_DIAL_SCALE).roundToInt();val y=(266+sin(a)*radius*DashboardLayout.CONCEPT_DIAL_SCALE).roundToInt()
                assertEquals("Starfield must not fill the progress sector",zero.getPixel(x,y),live.getPixel(x,y))
            }
            zero.recycle();live.recycle()
        }
        val inactive=render(DashboardStyle.POLAR,false,0.0,0.0)
        val live=render(DashboardStyle.POLAR,false,60.0,1600.0)
        for((cx,angle) in listOf(266 to 202.5,1654 to 189.0)) for(radius in listOf(158,240)) {
            // Off-axis shoulders of each hot point must catch reflected cyan light too.
            val a=Math.toRadians(angle+1.5);val x=(cx+cos(a)*radius*DashboardLayout.CONCEPT_DIAL_SCALE).roundToInt();val y=(266+sin(a)*radius*DashboardLayout.CONCEPT_DIAL_SCALE).roundToInt()
            assertTrue("Specular shoulder at $cx/$radius",Color.green(live.getPixel(x,y))>Color.green(inactive.getPixel(x,y))+25)
        }
        inactive.recycle();live.recycle()
    }

    @Test fun obsidianMarkerAndProgressUseTheSamePositionAndInvalidReadingsHaveNoMarker() {
        val scale=ObsidianSpeedScale();val position=FloatArray(2);val tangent=FloatArray(2)
        val progress=Path()
        for(speed in listOf(0.0,60.0,120.0,240.0,300.0)) {
            assertTrue(scale.marker(speed,position,tangent))
            if(speed>0) {
                scale.progress(speed,progress);val measured=PathMeasure(progress,false);val end=FloatArray(2)
                measured.getPosTan(measured.length,end,null)
                assertArrayEquals(position,end,.2f)
            }
        }
        for(speed in listOf(-1.0,Double.NaN,Double.POSITIVE_INFINITY)) assertFalse(scale.marker(speed,position,tangent))
    }

}
