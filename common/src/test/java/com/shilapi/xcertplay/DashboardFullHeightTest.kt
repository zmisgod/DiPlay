package com.shilapi.xcertplay

import android.graphics.*
import android.view.View
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="zh-rCN-w1920dp-h532dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardFullHeightTest {
    @Test fun enlargedDialPagesKeepCentralPanelsAndExportActualDayNightCanvas() {
        val state=DashboardSnapshot(vehicle=mapOf(
            VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(88,0,0),VehicleSignal.ENGINE_RPM to VehicleReading(2400,0,0),
            VehicleSignal.COOLANT_GAUGE_PERCENT to VehicleReading(50,0,0),VehicleSignal.INDICATED_RANGE_KM to VehicleReading(111,0,0),
            VehicleSignal.GEAR to VehicleReading("D",0,0)),music=ClusterMusicState.Snapshot(CarPlayNowPlaying(title="晴天",artist="周杰伦"),null))
        val out=File("build/reports/dashboard-full-height").apply { mkdirs() }
        for(style in listOf(DashboardStyle.REPLICA,DashboardStyle.POLAR,DashboardStyle.STARFIELD,DashboardStyle.MIDNIGHT)) {
            for(day in listOf(false,true)) {
                if(day && !style.supportsAppearance)continue
                val v=DashboardCanvas(RuntimeEnvironment.getApplication()).apply {
                    this.style=style;mode=DashboardMode.MUSIC;snapshot=state
                    appearance=if(day) DashboardAppearance.DAY else DashboardAppearance.NIGHT
                }
                v.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY));v.layout(0,0,1920,532)
                val b=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
                assertEquals(1920,b.width);assertEquals(532,b.height)
                // New full-height dial masks remain clear of the central music/tires regions.
                val rightEdge=if(style==DashboardStyle.MIDNIGHT)474f else 530f
                assertTrue(v.geometry.music!!.left>rightEdge)
                File(out,"${style.name.lowercase()}-${if(day) "day" else "night"}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
            }
        }
        assertTrue(DashboardLayout.REPLICA_DIAL_Y-DashboardLayout.REPLICA_DIAL_RADIUS in 1f..4f)
        assertTrue(532f-DashboardLayout.REPLICA_DIAL_Y-DashboardLayout.REPLICA_DIAL_RADIUS in 1f..4f)
    }
}
