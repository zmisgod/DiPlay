package com.shilapi.xcertplay

import android.content.Context
import android.graphics.*
import android.view.View
import com.shilapi.xcertplay.ultra.VehicleReading
import com.shilapi.xcertplay.ultra.VehicleSignal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], qualifiers="zh-rCN-w1920dp-h532dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardIconSizingTest {
    private val app: Context get()=RuntimeEnvironment.getApplication()
    private fun view(style: DashboardStyle) = DashboardCanvas(app).apply {
        this.style=style; mode=DashboardMode.NAVIGATION
        // Repeated image comparisons can span a real wall-clock minute; keep fixture time fixed.
        val clockField=DashboardCanvas::class.java.getDeclaredField("clockFormat").apply { isAccessible=true }
        (clockField.get(this) as java.text.SimpleDateFormat).applyPattern("'12:00'")
        snapshot=DashboardSnapshot(mapOf(
            VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(60,0,0),
            VehicleSignal.ENGINE_RPM to VehicleReading(1600,0,0),
            VehicleSignal.ASSIST_MODE to VehicleReading("ACC",0,0),
            VehicleSignal.ACC_SET_SPEED_KPH to VehicleReading(80,0,0),
            VehicleSignal.FOLLOW_GAP to VehicleReading(2,0,0),
            VehicleSignal.INDICATED_RANGE_KM to VehicleReading(111,0,0),
            VehicleSignal.INDICATED_AMBIENT_C to VehicleReading(23,0,0),
            VehicleSignal.AUTO_HOLD_ACTIVE to VehicleReading(true,0,0),
            VehicleSignal.PARKING_BRAKE_ON to VehicleReading(true,0,0),
            VehicleSignal.GEAR to VehicleReading("D",0,0)))
        measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
        layout(0,0,1920,532)
    }
    private fun frame(v:DashboardCanvas)=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

    @Test fun supportedStepsPersistAndOldTwoTimesSettingMigrates() {
        assertEquals(listOf(1f,1.1f,1.2f,1.3f,1.4f,1.5f),NativeDashboardSettings.iconScales)
        for(scale in NativeDashboardSettings.iconScales) {
            NativeDashboardSettings.setPanelScale(app,scale)
            assertEquals(scale,NativeDashboardSettings.panelScale(app))
        }
        app.getSharedPreferences("native_dashboard",Context.MODE_PRIVATE).edit().putFloat("panel_scale",2f).commit()
        assertEquals(1.5f,NativeDashboardSettings.panelScale(app))
        for(invalid in listOf(Float.NaN,0f,3f)) {
            app.getSharedPreferences("native_dashboard",Context.MODE_PRIVATE).edit().putFloat("panel_scale",invalid).commit()
            assertEquals(1f,NativeDashboardSettings.panelScale(app))
        }
    }
    @Test fun onlyGlyphsGrowWhileDialsMapTextAndPanelEdgesStayFixed() {
        val out=File("build/reports/dashboard-v50").apply { mkdirs() }
        for(style in DashboardStyle.entries.filter { it!=DashboardStyle.OFF && it!=DashboardStyle.OEM_OVERLAY }) {
            val v=view(style);v.panelScale=1f
            val geometry=v.geometry
            val base=frame(v)
            for(scale in NativeDashboardSettings.iconScales.drop(1)) {
                v.panelScale=scale;assertEquals(geometry,v.geometry)
                val changed=frame(v)
                // Entire instrument and content band must remain identical, not shrink with icons.
                for(x in 0 until 1920 step 3) for(y in 64..476 step 3)
                    assertEquals("$style/$scale changed content",base.getPixel(x,y),changed.getPixel(x,y))
                // Time, temperature, range, gear and ACC speed are text, not icon scaling targets.
                for(r in listOf(Rect(626,488,696,523),Rect(743,488,824,523),Rect(915,488,1040,523),Rect(1274,486,1305,523),Rect(996,10,1220,53)))
                    for(x in r.left until r.right) for(y in r.top until r.bottom)
                        assertEquals("$style/$scale changed text",base.getPixel(x,y),changed.getPixel(x,y))
                for(point in listOf(Point(960,0),Point(506,5),Point(1414,5),Point(960,531),Point(589,531),Point(1330,531)))
                    assertEquals(base.getPixel(point.x,point.y),changed.getPixel(point.x,point.y))
                var differences=0
                for(x in 518..574) for(y in 1..58) if(base.getPixel(x,y)!=changed.getPixel(x,y)) differences++
                assertTrue("Turn glyph must grow",differences>10)
                if(style==DashboardStyle.REPLICA) {
                    File(out,"classic-icons-${scale}x.png").outputStream().use { changed.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
                changed.recycle()
            }
            base.recycle()
        }
    }
    @Test fun newStyleNavigationCardAndSummaryRequireGuidanceAndSurviveRepeatedDraws() {
        for(style in DashboardStyle.entries.filter { it.concept }) {
            val v=view(style)
            v.snapshot=v.snapshot.copy(navigation=com.shilapi.xcertplay.hud.ClusterTurnGuidance(
                3,0,300,"人民路",remainingSeconds=1080,remainingMeters=12000))
            val first=frame(v);val repeated=frame(v)
            for(x in 600..1320 step 4) for(y in 70..470 step 4)
                assertEquals("Repeated render loses content: $style",first.getPixel(x,y),repeated.getPixel(x,y))
            // Track and Obsidian put arrival information inside their instruction card;
            // the two ring designs keep a separate lower summary.
            val summary = when(style) {
                DashboardStyle.TRACK -> Rect(830,156,1090,195)
                DashboardStyle.OBSIDIAN -> Rect(1000,125,1205,165)
                else -> Rect(784,424,1136,470)
            }
            v.snapshot=v.snapshot.copy(navigation=v.snapshot.navigation!!.copy(remainingSeconds=null,remainingMeters=null))
            val noSummary=frame(v)
            assertTrue("Live summary must disappear when remaining data is absent: $style",
                (summary.left until summary.right).sumOf { x -> (summary.top until summary.bottom).count { y ->
                    first.getPixel(x,y)!=noSummary.getPixel(x,y)
                } }>20)
            v.snapshot=v.snapshot.copy(navigation=null)
            val empty=frame(v)
            val card=v.geometry.navigation!!
            assertNotEquals("Instruction card must appear only with guidance",first.getPixel(960,card.top.toInt()+5),empty.getPixel(960,card.top.toInt()+5))
            first.recycle();repeated.recycle();noSummary.recycle();empty.recycle()
        }
    }
    @Test fun followCarUsesItsActual48UnitViewportAndFooterSeparatesEveryGroup() {
        val v=view(DashboardStyle.REPLICA);v.panelScale=1f
        val image=frame(v);val white=Color.rgb(243,248,255)
        val pixels=mutableListOf<Point>()
        for(x in 1228..1278) for(y in 1..58) if(image.getPixel(x,y)==white) pixels.add(Point(x,y))
        assertTrue(pixels.isNotEmpty())
        assertTrue("Following car body must not exceed lamp size",pixels.maxOf { it.x }-pixels.minOf { it.x }<=26)
        for(x in listOf(721,849,1071,1163,1255)) for(y in 493..517)
            assertEquals("Stable vertical separator",Color.rgb(141,162,182),image.getPixel(x,y))
        image.recycle()
    }
    @Test fun confirmedBrakeIndicatorsBecomeHeavierAndReturnToThinGray() {
        val out=File("build/reports/dashboard-v51").apply { mkdirs() }
        for (appearance in listOf(DashboardAppearance.DAY, DashboardAppearance.NIGHT)) {
            for (scale in NativeDashboardSettings.iconScales) {
                val v=view(DashboardStyle.REPLICA).apply { this.appearance=appearance; panelScale=scale }
                val active=frame(v)
                v.snapshot=v.snapshot.copy(vehicle=v.snapshot.vehicle + mapOf(
                    VehicleSignal.AUTO_HOLD_ACTIVE to VehicleReading(false,0,32),
                    VehicleSignal.PARKING_BRAKE_ON to VehicleReading(false,0,0)))
                val inactive=frame(v)
                v.snapshot=v.snapshot.copy(vehicle=v.snapshot.vehicle - VehicleSignal.AUTO_HOLD_ACTIVE - VehicleSignal.PARKING_BRAKE_ON)
                val unknown=frame(v)
                val gray=if(appearance==DashboardAppearance.DAY) ReplicaDayPalette.secondary else Color.rgb(141,162,182)
                val colors=if(appearance==DashboardAppearance.DAY) listOf(ReplicaDayPalette.green,ReplicaDayPalette.red)
                    else listOf(Color.rgb(100,224,166),Color.rgb(255,105,104))
                for ((index,cx) in listOf(1117,1209).withIndex()) {
                    var thick=0;var thin=0
                    for(x in cx-33..cx+33) for(y in 473..531) {
                        if(active.getPixel(x,y)==colors[index]) thick++
                        if(inactive.getPixel(x,y)==gray) thin++
                        assertEquals("Missing feedback stays thin gray",inactive.getPixel(x,y),unknown.getPixel(x,y))
                    }
                    assertTrue("Active $index at $scale must have visibly heavier strokes: $thick vs $thin",thick>thin*1.35)
                }
                if(scale==1f || scale==1.5f) {
                    for((name,image) in listOf("active" to active,"inactive" to inactive))
                        File(out,"${appearance.name.lowercase()}-${scale}x-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
                active.recycle();inactive.recycle();unknown.recycle()
            }
        }
    }

}
