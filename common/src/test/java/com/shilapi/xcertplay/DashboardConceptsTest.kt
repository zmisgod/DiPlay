package com.shilapi.xcertplay

import android.content.Context
import android.graphics.*
import android.view.View
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.ultra.VehicleSignal
import com.shilapi.xcertplay.ultra.VehicleReading
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="zh-rCN-w1920dp-h532dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardConceptsTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Test fun storedStyleOrdinalsAndNewPageCyclesRemainStable() {
        assertEquals(listOf("OFF","MIDNIGHT","PANORAMA","REPLICA","OEM_OVERLAY"),DashboardStyle.entries.take(5).map { it.name })
        for(style in DashboardStyle.entries.filter { it.concept }) {
            NativeDashboardSettings.setStyle(context,style)
            assertEquals(DashboardMode.NAVIGATION,NativeDashboardSettings.mode(context))
            NativeDashboardSettings.setMode(context,DashboardMode.NAVIGATION)
            assertEquals(DashboardMode.TIRES,NativeDashboardSettings.cycle(context))
            assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.cycle(context))
            assertEquals(DashboardMode.NAVIGATION,NativeDashboardSettings.cycle(context))
        }
        assertFalse(DashboardStyle.POLAR.supportsAppearance)
    }
    @Test fun portraitMirrorFitsThenZoomsAndPansWithoutStretch() {
        val box=RectF(266f,0f,1654f,532f)
        val portrait=DashboardMirrorViewport.fit(box,9.0/16,100,0,0)
        assertEquals(532f,portrait.height(),.01f)
        assertEquals(960f,portrait.centerX(),.01f)
        assertEquals(9f/16,portrait.width()/portrait.height(),.001f)
        val zoom=DashboardMirrorViewport.fit(box,9.0/16,200,25,-10)
        assertEquals(portrait.width()*2,zoom.width(),.01f)
        assertEquals(960f+box.width()*.25f,zoom.centerX(),.01f)
        assertEquals(266f-53.2f,zoom.centerY(),.01f)
        NativeDashboardSettings.setMirrorTransform(context,230,-35,42)
        assertEquals(230,NativeDashboardSettings.mirrorZoom(context))
        assertEquals(-35,NativeDashboardSettings.mirrorX(context))
        assertEquals(42,NativeDashboardSettings.mirrorY(context))
    }
    @Test fun primaryMirrorRegistryDoesNotTouchClusterStreamAndReappliesOnReconnect() {
        val primaryCalls=mutableListOf<Pair<String,android.view.Surface?>>()
        val clusterCalls=mutableListOf<Pair<String,android.view.Surface?>>()
        val oldMain=MainScreenMirrors.sink
        val oldCluster=MapMirrors.sink
        val st=android.graphics.SurfaceTexture(0)
        val surface=android.view.Surface(st)
        val key="test-primary-isolation"
        try {
            MainScreenMirrors.sink={ k,s -> primaryCalls.add(k to s) }
            MapMirrors.sink={ k,s -> clusterCalls.add(k to s) }
            MainScreenMirrors.set(key,surface)
            MainScreenMirrors.set(key,surface)
            assertEquals(1,primaryCalls.count { it.first==key })
            assertTrue(clusterCalls.isEmpty())
            MainScreenMirrors.reapply()
            assertEquals(2,primaryCalls.count { it.first==key })
            assertSame(surface,primaryCalls.last { it.first==key }.second)
            MainScreenMirrors.set(key,null)
            assertNull(primaryCalls.last { it.first==key }.second)
            assertTrue(clusterCalls.isEmpty())
        } finally {
            MainScreenMirrors.set(key,null)
            MainScreenMirrors.sink=oldMain;MapMirrors.sink=oldCluster
            surface.release();st.release()
        }
    }
    @Test fun modulesAreSharedAndDaytimeBrightAtAllSizes() {
        val cover=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply {
            val c=Canvas(this); val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader=LinearGradient(0f,0f,256f,256f,0xff173459.toInt(),0xff8c6293.toInt(),Shader.TileMode.CLAMP)
            c.drawRect(0f,0f,256f,256f,paint);paint.shader=null
            paint.color=0xffe9dcb9.toInt();c.drawCircle(166f,94f,54f,paint)
            paint.color=0xff162f47.toInt();c.drawCircle(183f,78f,54f,paint)
        }
        val state=DashboardSnapshot(vehicle=mapOf(
            VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(60,0,0),
            VehicleSignal.ENGINE_RPM to VehicleReading(1600,0,0),
            VehicleSignal.FRONT_COMPARTMENT_C to VehicleReading(24,0,0),
            VehicleSignal.INDICATED_AMBIENT_C to VehicleReading(23,0,0),
            VehicleSignal.INDICATED_RANGE_KM to VehicleReading(111,0,0),
            VehicleSignal.GEAR to VehicleReading("D",0,0),
            VehicleSignal.ASSIST_MODE to VehicleReading("ACC",0,0),
            VehicleSignal.ACC_SET_SPEED_KPH to VehicleReading(80,0,0),
            VehicleSignal.AUTO_HEADLIGHTS to VehicleReading(true,0,0),
            VehicleSignal.LOW_BEAM to VehicleReading(true,0,0),
            VehicleSignal.TIRE_FL_BAR to VehicleReading(2.3,0,0),
            VehicleSignal.TIRE_FR_BAR to VehicleReading(2.3,0,0),
            VehicleSignal.TIRE_RL_BAR to VehicleReading(2.3,0,0),
            VehicleSignal.TIRE_RR_BAR to VehicleReading(2.3,0,0)),
            music=ClusterMusicState.Snapshot(CarPlayNowPlaying(title="星河 · 很长的歌曲名称测试",artist="示例歌手",durationMillis=240000,elapsedMillis=85000),cover),
            navigation=ClusterTurnGuidance(3,0,300,"人民路",remainingSeconds=1080,remainingMeters=12000))
        val out=File("build/reports/dashboard-concepts").apply { mkdirs() }
        for(scale in NativeDashboardSettings.iconScales) for(appearance in listOf(DashboardAppearance.NIGHT,DashboardAppearance.DAY)) {
            var reference:Bitmap?=null
            for(style in DashboardStyle.entries.filter { it.concept && (appearance!=DashboardAppearance.DAY || it.supportsAppearance) }) {
                val view=DashboardCanvas(context).apply { this.style=style;this.appearance=appearance;panelScale=scale;mode=DashboardMode.NAVIGATION;snapshot=state }
                // Keep the real wall clock from changing module pixels across a minute boundary.
                val formatterField = DashboardCanvas::class.java.getDeclaredField("clockFormat").apply { isAccessible = true }
                (formatterField.get(view) as java.text.SimpleDateFormat).applyPattern("'12:00'")
                view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY));view.layout(0,0,1920,532)
                for(page in listOf(DashboardMode.NAVIGATION,DashboardMode.MUSIC,DashboardMode.TIRES)) {
                    view.mode=page
                    val image=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(image))
                    if(appearance==DashboardAppearance.DAY) {
                        assertTrue(Color.luminance(image.getPixel(960,0))>.5f)
                        assertTrue(Color.luminance(image.getPixel(960,531))>.5f)
                    }
                    // Every concept shares glyphs/readouts/geometry; the surface belongs to its material.
                    if(page==DashboardMode.NAVIGATION) {
                        if(reference==null) reference=image.copy(Bitmap.Config.ARGB_8888,false) else {
                            val foreground=if(appearance==DashboardAppearance.DAY) setOf(ReplicaDayPalette.text,
                                ReplicaDayPalette.secondary,ReplicaDayPalette.inactive,ReplicaDayPalette.green,
                                ReplicaDayPalette.red,ReplicaDayPalette.amber,ReplicaDayPalette.blue)
                            else setOf(Color.rgb(243,248,255),Color.rgb(141,162,182),Color.rgb(38,57,74),
                                Color.rgb(100,224,166),Color.rgb(255,105,104),Color.rgb(255,193,90),Color.rgb(79,165,255))
                            var compared=0
                            for(r in listOf(Rect(520,2,1400,58),Rect(610,486,1310,527)))
                                for(x in r.left until r.right) for(y in r.top until r.bottom) {
                                    val pixel=reference.getPixel(x,y)
                                    // Exclude antialias blending against different surface colors, not opaque icon pixels.
                                    if(pixel in foreground) {
                                        assertEquals("$style $scale $appearance shared foreground at $x/$y",pixel,image.getPixel(x,y))
                                        compared++
                                    }
                                }
                            assertTrue("Thousands of actual foreground pixels must be checked",compared>3000)
                        }
                    }
                    File(out,"${style.name.lowercase()}-${appearance.name.lowercase()}-${scale}x-${page.name.lowercase()}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                    image.recycle()
                }
            }
            reference?.recycle()
        }
    }
    @Test fun vehicleTimestampOnlyUpdatesDoNotRedrawAndModuleResizeDoes() {
        val v=DashboardCanvas(context)
        assertTrue(v.invalidateIfContentChanged());assertFalse(v.invalidateIfContentChanged())
        v.panelScale=1.5f;assertTrue(v.invalidateIfContentChanged());assertFalse(v.invalidateIfContentChanged())
    }
}
