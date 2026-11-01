package com.shilapi.xcertplay

import android.graphics.*
import android.view.View
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.CarPlayNowPlaying
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

/** Real Canvas renders, with a deliberately schematic video fixture confined to the test source set. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h532dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardFidelityTest {
    private val styles = listOf(DashboardStyle.MIDNIGHT, DashboardStyle.PANORAMA,
        DashboardStyle.TRACK, DashboardStyle.POLAR, DashboardStyle.OBSIDIAN, DashboardStyle.STARFIELD)

    private fun state() = DashboardSnapshot(mapOf(
        VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(60, 0, 0),
        VehicleSignal.ENGINE_RPM to VehicleReading(1600, 0, 0),
        VehicleSignal.FRONT_COMPARTMENT_C to VehicleReading(24, 0, 0),
        VehicleSignal.INDICATED_AMBIENT_C to VehicleReading(23, 0, 0),
        VehicleSignal.INDICATED_RANGE_KM to VehicleReading(111, 0, 0),
        VehicleSignal.GEAR to VehicleReading("D", 0, 0),
        VehicleSignal.ASSIST_MODE to VehicleReading("ACC", 0, 0),
        VehicleSignal.ACC_SET_SPEED_KPH to VehicleReading(80, 0, 0),
        VehicleSignal.FOLLOW_GAP to VehicleReading(2, 0, 0),
        VehicleSignal.AUTO_HEADLIGHTS to VehicleReading(true, 0, 0),
        VehicleSignal.LOW_BEAM to VehicleReading(true, 0, 0),
        VehicleSignal.TIRE_FL_BAR to VehicleReading(2.3, 0, 0),
        VehicleSignal.TIRE_FR_BAR to VehicleReading(2.3, 0, 0),
        VehicleSignal.TIRE_RL_BAR to VehicleReading(2.3, 0, 0),
        VehicleSignal.TIRE_RR_BAR to VehicleReading(2.3, 0, 0)),
        music = ClusterMusicState.Snapshot(CarPlayNowPlaying(title = "星河", artist = "示例歌手",
            elapsedMillis = 85000, durationMillis = 240000)),
        navigation = ClusterTurnGuidance(3, 0, 300, "人民路", remainingSeconds = 1080, remainingMeters = 12000))

    private fun view(style: DashboardStyle, mode: DashboardMode, appearance: DashboardAppearance, snapshot: DashboardSnapshot) =
        DashboardCanvas(RuntimeEnvironment.getApplication()).apply {
            this.style = style; this.mode = mode; this.appearance = appearance; this.snapshot = snapshot
            mapWindow = DashboardLayout.of(style, mode).map ?: RectF()
            mapActive = !mapWindow.isEmpty
            val clock = DashboardCanvas::class.java.getDeclaredField("clockFormat").apply { isAccessible = true }
            (clock.get(this) as java.text.SimpleDateFormat).applyPattern("'10:09'")
            measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(532, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1920, 532)
        }

    @Test fun cachedConceptMaterialsSurviveTelemetryChangesAndRefreshOnlyForStyleOrAppearance() {
        val renderer = DashboardConceptRenderer()
        val image = Bitmap.createBitmap(1920, 532, Bitmap.Config.ARGB_8888)
        val c = Canvas(image)
        var expected = 0
        for (style in styles.filter { it.concept }) {
            for (day in listOf(false, true)) {
                renderer.background(c, style, day)
                assertEquals(++expected, renderer.buildCount)
                renderer.instruments(c, style, day, state()) { _, _ -> }
                renderer.instruments(c, style, day, DashboardSnapshot()) { _, _ -> }
                renderer.instruments(c, style, day, state().copy(vehicle = mapOf(
                    VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(240, 10, 0),
                    VehicleSignal.ENGINE_RPM to VehicleReading(8000, 10, 0)))) { _, _ -> }
                assertEquals("Live values must not rebuild thousands of static material/tick commands", expected, renderer.buildCount)
            }
        }
        image.recycle()
    }

    @Test fun guidanceClearsWhileVideoAndInstrumentsStayVisible() {
        val underlay = Color.rgb(133, 51, 117)
        for (style in styles) {
            val v = view(style, DashboardMode.NAVIGATION, DashboardAppearance.NIGHT, state())
            fun frame() = Bitmap.createBitmap(1920, 532, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(underlay); v.draw(Canvas(it))
            }
            val active = frame()
            val box = v.geometry.navigation!!
            assertTrue("$style guidance must clear the 60px header", box.top >= 60)
            assertTrue("$style guidance must clear the 54px footer", box.bottom < 478)
            assertEquals("$style center exposes the video", underlay, active.getPixel(960, 290))
            assertNotEquals("$style speed is opaque above the video", underlay, active.getPixel(200, 250))
            v.snapshot = v.snapshot.copy(navigation = null)
            val cleared = frame()
            assertEquals("$style has no empty navigation panel", underlay,
                cleared.getPixel(box.centerX().toInt(), box.centerY().toInt()))
            for (x in 30..350 step 4) for (y in 90..400 step 4)
                assertEquals("$style route end must not alter the speed display", active.getPixel(x, y), cleared.getPixel(x, y))
            active.recycle(); cleared.recycle()
        }
    }

    @Test fun exportsEveryRetainedNonClassicAppearanceAndContentPage() {
        val out = File("build/reports/dashboard-fidelity").apply { mkdirs() }
        val art = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val ac = Canvas(art)
        p.shader = LinearGradient(0f, 0f, 256f, 256f, 0xff162b59.toInt(), 0xff8c6293.toInt(), Shader.TileMode.CLAMP)
        ac.drawPaint(p); p.shader = null
        p.color = 0xffece5be.toInt(); ac.drawCircle(170f, 90f, 54f, p)
        p.color = 0xff25365c.toInt(); ac.drawCircle(188f, 74f, 54f, p)
        val snapshot = state().let { it.copy(music = it.music.copy(artwork = art)) }
        for (style in styles) for (appearance in listOf(DashboardAppearance.NIGHT, DashboardAppearance.DAY)) {
            if (appearance == DashboardAppearance.DAY && !style.supportsAppearance) continue
            for (mode in DashboardMode.choices(style)) {
                val v = view(style, mode, appearance, snapshot)
                val image = Bitmap.createBitmap(1920, 532, Bitmap.Config.ARGB_8888)
                val c = Canvas(image)
                if (v.mapActive) mapFixture(c, v.mapWindow, appearance == DashboardAppearance.DAY)
                v.draw(c)
                File(out, "${style.name.lowercase()}-${appearance.name.lowercase()}-${mode.name.lowercase()}.png")
                    .outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                image.recycle()
            }
        }
        art.recycle()
    }

    @Test fun midnightAndPanoramaVehicleInformationLivesOnlyAboveContent() {
        for(style in listOf(DashboardStyle.MIDNIGHT,DashboardStyle.PANORAMA)) {
            for(mode in DashboardMode.choices(style)) {
                val v=view(style,mode,DashboardAppearance.NIGHT,state()).apply { panelScale=1.5f }
                fun frame()=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.MAGENTA);v.draw(Canvas(it))
                }
                val before=frame()
                v.snapshot=v.snapshot.copy(vehicle=v.snapshot.vehicle+mapOf(
                    VehicleSignal.INDICATED_RANGE_KM to VehicleReading(321,0,0),
                    VehicleSignal.INDICATED_AMBIENT_C to VehicleReading(18,0,0),
                    VehicleSignal.GEAR to VehicleReading("P",0,0),
                    VehicleSignal.AUTO_HOLD_ACTIVE to VehicleReading(true,0,0),
                    VehicleSignal.PARKING_BRAKE_ON to VehicleReading(true,0,0)))
                val after=frame()
                for(x in 0 until 1920 step 3) for(y in 60 until 532 step 3)
                    assertEquals("$style/$mode vehicle info leaked into content",before.getPixel(x,y),after.getPixel(x,y))
                for(r in listOf(Rect(16,0,482,60),Rect(1438,0,1904,60))) {
                    val differences=(r.left until r.right).sumOf { x -> (0 until 60).count { y -> before.getPixel(x,y)!=after.getPixel(x,y) } }
                    assertTrue("$style live values must update in the top wings",differences>100)
                }
                if(v.mapActive) assertEquals("Bottom map area is reclaimed",Color.MAGENTA,after.getPixel(960,500))
                before.recycle();after.recycle()
            }
        }
    }

    private fun mapFixture(c: Canvas, bounds: RectF, day: Boolean) {
        c.save(); c.clipRect(bounds)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (day) 0xffdce2e5.toInt() else 0xff141e30.toInt(); c.drawPaint(p)
        p.style = Paint.Style.STROKE; p.color = if (day) 0xffbbc7d0.toInt() else 0xff2e3a52.toInt()
        p.strokeWidth = 12f
        for (x in -900..2800 step 250) c.drawLine(960f, 100f, x.toFloat(), 532f, p)
        for (y in listOf(210f, 260f, 335f, 435f, 520f)) c.drawLine(0f, y, 1920f, y, p)
        p.color = if (day) 0xff009e9e.toInt() else 0xff13cfd3.toInt(); p.strokeWidth = 10f
        c.drawPath(Path().apply { moveTo(960f, 532f); lineTo(960f, 305f); quadTo(960f, 265f, 1020f, 265f); lineTo(1260f, 265f) }, p)
        p.color = Color.WHITE; p.style = Paint.Style.FILL
        c.drawPath(Path().apply { moveTo(960f, 330f); lineTo(943f, 371f); lineTo(960f, 363f); lineTo(977f, 371f); close() }, p)
        c.restore()
    }
}
