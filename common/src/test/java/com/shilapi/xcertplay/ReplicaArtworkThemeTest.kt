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
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="zh-rCN-w1920dp-h532dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplicaArtworkThemeTest {
    private fun view()=DashboardCanvas(RuntimeEnvironment.getApplication()).apply {
        style=DashboardStyle.REPLICA;appearance=DashboardAppearance.DAY;mode=DashboardMode.MUSIC
        snapshot=DashboardSnapshot(mapOf(
            VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(68,0,0),VehicleSignal.ENGINE_RPM to VehicleReading(1600,0,0),
            VehicleSignal.ENGINE_COOLANT_C to VehicleReading(90,0,0),VehicleSignal.INDICATED_AMBIENT_C to VehicleReading(23,0,0),
            VehicleSignal.INDICATED_RANGE_KM to VehicleReading(111,0,0),VehicleSignal.GEAR to VehicleReading("D",0,0),
            VehicleSignal.AUTO_HOLD_ACTIVE to VehicleReading(true,0,32),VehicleSignal.PARKING_BRAKE_ON to VehicleReading(false,0,0),
            VehicleSignal.ASSIST_MODE to VehicleReading("GPILOT",0,0),VehicleSignal.ACC_STATE to VehicleReading(2,0,0),
            VehicleSignal.ACC_SET_SPEED_KPH to VehicleReading(80,0,0),VehicleSignal.FOLLOW_GAP to VehicleReading(2,0,0),
            VehicleSignal.LOW_BEAM to VehicleReading(true,0,0),VehicleSignal.AUTO_HEADLIGHTS to VehicleReading(true,0,0)))
        measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY));layout(0,0,1920,532)
    }
    private fun frame(v:DashboardCanvas)=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    private fun cover(color:Int)=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun music(v:DashboardCanvas,art:Bitmap?,title:String="一些风景",artist:String="五条人",playing:Boolean=true) {
        v.snapshot=v.snapshot.copy(music=ClusterMusicState.Snapshot(CarPlayNowPlaying(title=title,artist=artist,playing=playing,durationMillis=240000,elapsedMillis=85000),art))
    }
    @Test fun coverSwitchRecolorsBothGaugesReusesBlurAndClearsOnPauseOrMissingCover() {
        val v=view();val art=cover(Color.rgb(220,48,30));music(v,art)
        val red=frame(v);assertEquals(1,v.artworkBlurBuildCount);val count=v.dialFaceBuildCount
        repeat(4) { frame(v).recycle() }
        assertEquals(1,v.artworkBlurBuildCount);assertEquals(count,v.dialFaceBuildCount)
        art.eraseColor(Color.rgb(30,70,210));val blue=frame(v)
        assertEquals(2,v.artworkBlurBuildCount);assertEquals(count+2,v.dialFaceBuildCount)
        for((x,y) in listOf(12 to 12,170 to 250,1550 to 150)) {
            val a=red.getPixel(x,y);val b=blue.getPixel(x,y)
            assertTrue(Color.red(a)>Color.blue(a));assertTrue(Color.blue(b)>Color.red(b))
        }
        music(v,art,playing=false);val paused=frame(v);assertEquals(Color.WHITE,paused.getPixel(12,12))
        music(v,null);val missing=frame(v);assertEquals(Color.WHITE,missing.getPixel(12,12))
        music(v,art);art.recycle();val recycled=frame(v);assertEquals(Color.WHITE,recycled.getPixel(12,12))
        listOf(red,blue,paused,missing,recycled).forEach(Bitmap::recycle)
    }
    @Test fun albumPaletteDoesNotTintMapVideoOrChangeTelltaleMeaning() {
        val v=view();val art=cover(Color.rgb(220,48,30));music(v,art)
        v.mode=DashboardMode.NAVIGATION;v.mapActive=true;v.mapWindow=v.geometry.map!!
        val raw=Color.rgb(30,110,190)
        val image=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).apply { eraseColor(raw) };v.draw(Canvas(image))
        assertEquals(raw,image.getPixel(960,270))
        fun count(color:Int,x0:Int,x1:Int,y0:Int,y1:Int)=(x0..x1).sumOf { x -> (y0..y1).count { y -> image.getPixel(x,y)==color } }
        assertTrue(count(Color.rgb(100,224,166),1080,1150,478,531)>0)
        assertEquals(0,count(Color.rgb(255,105,104),1170,1242,478,531))
        assertTrue("RPM redline endpoint keeps its warning color",count(Color.rgb(255,73,68),1770,1810,385,425)>0)
        image.recycle();art.recycle()
    }
    @Test fun speedHasThreeDotsPerTwentyAndNoDenseInnerTickRing() {
        val dial=DashboardDial.CLASSIC_SPEED
        assertEquals((0..240 step 20).map(Int::toDouble),(0..dial.divisions).filter { it%dial.majorEvery==0 }.map(dial::valueAtTick))
        for(start in 0 until 240 step 20) assertEquals(listOf(start+5.0,start+10.0,start+15.0),
            (0..dial.divisions).map(dial::valueAtTick).filter { it>start && it<start+20 })
        val v=view();v.snapshot=DashboardSnapshot();val bitmap=frame(v)
        val scale=DashboardLayout.REPLICA_DIAL_RADIUS/208f
        fun pixel(value:Double,r:Float):Int { val a=Math.toRadians(dial.angle(value).toDouble());return bitmap.getPixel((266+cos(a)*r*scale).roundToInt(),(266+sin(a)*r*scale).roundToInt()) }
        // 85/90/95 are dots; off-grid 82/88/92/98 must not have the old dense tick strokes.
        for(value in listOf(85.0,90.0,95.0)) assertTrue(Color.luminance(pixel(value,187.5f))<.25f)
        for(value in listOf(82.0,88.0,92.0,98.0)) assertTrue(Color.luminance(pixel(value,187.5f))>.65f)
        bitmap.recycle()
    }
    @Test fun neutralAndLightCoversUseReadablePalettesAndGaussianKeepsColorMasses() {
        val art=cover(Color.WHITE);val c=Canvas(art);val p=Paint().apply { color=Color.rgb(215,35,30) }
        c.drawRect(0f,0f,32f,64f,p)
        val backdrop=ReplicaMusicBackdrop();backdrop.update(art)
        val image=Bitmap.createBitmap(192,64,Bitmap.Config.ARGB_8888);backdrop.drawAlbum(Canvas(image),RectF(0f,0f,192f,64f))
        assertNotEquals(image.getPixel(10,30),image.getPixel(180,30))
        assertTrue("Gaussian transition must be smooth",Color.green(image.getPixel(90,30))<Color.green(image.getPixel(100,30)))
        val light=ReplicaArtworkPalette.fromPixels(IntArray(64) { Color.rgb(245,243,238) })
        assertTrue(light.light);assertTrue((Color.luminance(light.surface)+.05f)/(Color.luminance(light.text)+.05f)>4.5f)
        image.recycle();art.recycle();backdrop.clear()
    }
    @Test fun renderLocalCoverPreviewsWhenProvided() {
        val directory=System.getenv("DIPLAY_PREVIEW_COVERS_DIR")?.let(::File) ?: return
        val out=File("build/reports/dashboard-v52").apply { mkdirs() }
        val v=view()
        fun save(name:String) { val b=frame(v);File(out,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
        save("00-white-default")
        directory.listFiles()?.filter { it.extension.lowercase() in listOf("png","jpg","jpeg","webp") }?.sortedBy { it.name }?.forEach { file ->
            val art=BitmapFactory.decodeFile(file.absolutePath) ?: return@forEach
            music(v,art,file.nameWithoutExtension.substringAfter('-'),if(file.name.startsWith("08")) "五条人" else "周杰伦")
            save(file.nameWithoutExtension);art.recycle()
        }
    }
}
