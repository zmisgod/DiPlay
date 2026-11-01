package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Color
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="zh-rCN")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class Esp32DisplayTest {
    @Test fun conceptPageIndicatorsAndTimeLabelsHandleMissingAndOutOfRangeMetadata() {
        assertEquals(listOf(0,1,2),listOf(0,2,1).map(Esp32Pages::position))
        assertEquals("01:24",Esp32MusicTime.label(84000))
        assertEquals("03:56",Esp32MusicTime.label(236000))
        assertEquals("1:02:03",Esp32MusicTime.label(3723000))
        assertEquals("--:--",Esp32MusicTime.label(null))
        assertEquals("--:--",Esp32MusicTime.label(-1))
        assertEquals(236000L,Esp32MusicTime.elapsed(999000,236000))
        assertNull(Esp32MusicTime.elapsed(-1,236000))
        val c=RuntimeEnvironment.getApplication()
        val empty=Esp32FrameRenderer.render(c,DashboardSnapshot(),1)
        val timed=Esp32FrameRenderer.render(c,DashboardSnapshot(music=ClusterMusicState.Snapshot(CarPlayNowPlaying(durationMillis=236000,elapsedMillis=84000),null)),1)
        fun changed(left:Int,right:Int)= (left..right).any { x->(367..384).any { y->empty.getPixel(x,y)!=timed.getPixel(x,y) } }
        assertTrue("Elapsed time is rendered",changed(102,190));assertTrue("Total time is rendered",changed(285,364))
        assertEquals(Color.WHITE,timed.getPixel(120,354))
        assertEquals(Color.WHITE,timed.getPixel(261,416))
        assertEquals(Color.rgb(74,87,98),timed.getPixel(233,416))
        empty.recycle();timed.recycle()
    }
    @Test fun musicLabelsAndProgressStayWhiteWithoutTintingArtwork() {
        val art=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val snapshot=DashboardSnapshot(music=ClusterMusicState.Snapshot(
            CarPlayNowPlaying(title="白色歌名",artist="白色歌手",elapsedMillis=84000,durationMillis=236000),art))
        val frame=Esp32FrameRenderer.render(RuntimeEnvironment.getApplication(),snapshot,1)
        try {
            assertEquals(Color.RED,frame.getPixel(233,150))
            for((top,bottom) in listOf(260 to 293,300 to 327,346 to 385)) {
                var whites=0
                for(y in top..bottom)for(x in 100..366) {
                    val color=frame.getPixel(x,y)
                    assertEquals(Color.red(color),Color.green(color))
                    assertEquals(Color.red(color),Color.blue(color))
                    if(color==Color.WHITE)whites++
                }
                assertTrue("Visible white music content at $top",whites>10)
            }
        } finally {frame.recycle();art.recycle()}
    }

    @Test fun usbMapCadenceIsBoundedAndDoesNotAccelerateWifiOrMetadataPages() {
        assertEquals(250L,Esp32FramePacing.delay(1,2,0))
        assertEquals(75L,Esp32FramePacing.delay(1,2,175))
        assertEquals(40L,Esp32FramePacing.delay(1,2,800))
        for(page in 0..2)assertEquals(1000L,Esp32FramePacing.delay(0,page,175))
        for(page in 0..1)assertEquals(1000L,Esp32FramePacing.delay(1,page,175))
    }
    @Test fun credentialsAndCommandsAreIndependentOfDashboardAndCarPlaySettings() {
        val c=RuntimeEnvironment.getApplication()
        val before=NativeDashboardSettings.mode(c)
        assertFalse(Esp32Settings.enabled(c))
        assertEquals(0,Esp32Settings.transport(c));Esp32Settings.setTransport(c,1);assertEquals(1,Esp32Settings.transport(c))
        Esp32Settings.command(c,0);val first=Esp32Settings.commandId(c)
        Esp32Settings.command(c,-1)
        assertEquals(first+1,Esp32Settings.commandId(c));assertEquals(-1,Esp32Settings.command(c))
        assertEquals(listOf(0,-1),Esp32Settings.pending(c).map { it.page })
        Esp32Settings.acknowledge(c,first)
        assertEquals(listOf(-1),Esp32Settings.pending(c).map { it.page })
        assertTrue(Esp32Settings.commandId(c)>Esp32Settings.ack(c))
        assertEquals(before,NativeDashboardSettings.mode(c))
        assertEquals(Esp32Settings.client(c),Esp32Settings.client(c))
        assertEquals(SettingsCategory.ADVANCED,SettingsInformationArchitecture.sectionsByCategory.entries.single { SettingsSection.ESP32_DISPLAY in it.value }.key)
    }
    @Test fun thirdPageAndHudDirectionArePersistedWithoutChangingDashboard() {
        val c=RuntimeEnvironment.getApplication();val before=NativeDashboardSettings.mode(c)
        assertEquals(0,Esp32Settings.navigationTransform(c))
        Esp32Settings.command(c,2)
        assertEquals(listOf(2),Esp32Settings.pending(c).map { it.page })
        Esp32Settings.setNavigationTransform(c,1)
        assertEquals(1,Esp32Settings.navigationTransform(c));assertEquals(before,NativeDashboardSettings.mode(c))
        assertThrows(IllegalArgumentException::class.java) { Esp32Settings.command(c,3) }
    }
    @Test fun hudMirrorsNavigationMusicAndMapWithoutChangingSource() {
        val c=RuntimeEnvironment.getApplication()
        val map=Bitmap.createBitmap(932,466,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        android.graphics.Canvas(map).drawRect(233f,0f,699f,160f,android.graphics.Paint().apply { color=Color.CYAN })
        val snapshot=DashboardSnapshot(music=ClusterMusicState.Snapshot(CarPlayNowPlaying(title="星河",artist="测试歌手"),null),navigation=ClusterTurnGuidance(2,0,300,"人民路"))
        for(page in 0..2) {
            val normal=Esp32FrameRenderer.render(c,snapshot,page,0,map)
            val vertical=Esp32FrameRenderer.render(c,snapshot,page,1,map)
            val horizontal=Esp32FrameRenderer.render(c,snapshot,page,2,map)
            val rotated=Esp32FrameRenderer.render(c,snapshot,page,3,map)
            var differences=0
            for(y in 30..430 step 10) for(x in 30..430 step 10) {
                assertEquals(normal.getPixel(x,y),vertical.getPixel(x,465-y))
                assertEquals(normal.getPixel(x,y),horizontal.getPixel(465-x,y))
                assertEquals(normal.getPixel(x,y),rotated.getPixel(465-x,465-y))
                if(normal.getPixel(x,y)!=vertical.getPixel(x,y))differences++
            }
            assertTrue("Page $page must visibly flip",differences>0)
            File("build/reports/esp32-display/hud-$page.png").apply { parentFile?.mkdirs() }.outputStream().use { vertical.compress(Bitmap.CompressFormat.PNG,100,it) }
            listOf(normal,vertical,horizontal,rotated).forEach { it.recycle() }
        }
        assertFalse(map.isRecycled);map.recycle()
    }
    @Test fun mapFillsPanelWithCentralCropAndExcludesSideContent() {
        val c=RuntimeEnvironment.getApplication()
        for((w,h) in listOf(932 to 466,466 to 932,467 to 466)) {
            val bounds=Esp32CarPlayFrames.centerCrop(w,h)
            assertEquals(minOf(w,h),bounds.width());assertEquals(bounds.width(),bounds.height())
            assertEquals((w-minOf(w,h))/2,bounds.left);assertEquals((h-minOf(w,h))/2,bounds.top)
            val source=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            android.graphics.Canvas(source).drawRect(bounds,android.graphics.Paint().apply { color=Color.CYAN })
            val frame=Esp32FrameRenderer.render(c,DashboardSnapshot(),2,1,source)
            for((x,y) in listOf(233 to 233,30 to 233,233 to 30,430 to 233))assertEquals(Color.CYAN,frame.getPixel(x,y))
            assertFalse(source.isRecycled)
            File("build/reports/esp32-display/cluster-map-$w-$h.png").apply { parentFile?.mkdirs() }.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) }
            source.recycle();frame.recycle()
        }
    }
    @Test fun fullBleedMapRemovesSourceBottomMatteAndNeverDrawsPageDots() {
        val c=RuntimeEnvironment.getApplication()
        val source=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        android.graphics.Canvas(source).drawRect(0f,0f,1000f,500f,android.graphics.Paint().apply { color=Color.CYAN })
        assertEquals(android.graphics.Rect(250,0,750,500),Esp32CarPlayFrames.contentCrop(source))
        for(transform in 0..3) {
            val result=Esp32FrameRenderer.render(c,DashboardSnapshot(),2,transform,source)
            // Corners, bottom and previous page-dot positions all belong to the map.
            for((x,y) in listOf(0 to 0,465 to 465,233 to 465,209 to 416,233 to 416,257 to 416))assertEquals(Color.CYAN,result.getPixel(x,y))
            result.recycle()
        }
        source.recycle()
    }
    @Test fun darkRoadsAndEntirelyBlackFramesAreNotMistakenForLetterboxing() {
        val source=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888)
        for(color in listOf(Color.BLACK,Color.rgb(16,16,16))) {
            source.eraseColor(color)
            assertEquals(Esp32CarPlayFrames.centerCrop(1000,600),Esp32CarPlayFrames.contentCrop(source))
        }
        source.eraseColor(Color.CYAN)
        android.graphics.Canvas(source).drawRect(0f,200f,1000f,400f,android.graphics.Paint().apply { color=Color.BLACK })
        assertEquals(Esp32CarPlayFrames.centerCrop(1000,600),Esp32CarPlayFrames.contentCrop(source))
        source.recycle()
    }
    @Test fun timedOutCaptureRecyclesLateBitmapAndSuccessfulCaptureTransfersOwnership() {
        val timeout=PendingBitmap();assertNull(timeout.await(0))
        val late=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
        timeout.complete(late);assertTrue(late.isRecycled)
        val request=PendingBitmap();val frame=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
        request.complete(frame);assertSame(frame,request.await(0));assertFalse(frame.isRecycled);frame.recycle()
    }
    @Test fun endpointCannotInjectPathPortOrCredentials() {
        for(host in listOf("192.168.4.1","diplay.local","10.0.0.15"))assertTrue(host,Esp32Settings.validHost(host))
        for(host in listOf("http://x","x:80","x/y","user@x","x\nX-Secret","", ".x"))assertFalse(host,Esp32Settings.validHost(host))
    }
    @Test fun renderingUsesSnapshotWithoutRecyclingSharedCoverAndExportsRoundPreviews() {
        val c=RuntimeEnvironment.getApplication()
        val art=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(28,74,110)) }
        // Deterministic sample cover; real pages use the iPhone's artwork without editing it.
        val ac=android.graphics.Canvas(art);val ap=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        ap.shader=android.graphics.LinearGradient(0f,0f,0f,192f,Color.rgb(255,168,95),Color.rgb(24,69,99),android.graphics.Shader.TileMode.CLAMP)
        ac.drawRect(0f,0f,192f,192f,ap);ap.shader=null;ap.color=Color.rgb(255,225,151);ac.drawCircle(132f,89f,15f,ap)
        for(i in 0..2) {
            ap.color=listOf(Color.rgb(86,105,125),Color.rgb(44,76,104),Color.rgb(18,52,80))[i]
            val ridge=android.graphics.Path().apply { moveTo(0f,120f+i*22f);lineTo(36f,104f+i*23f);lineTo(88f,128f+i*18f);lineTo(141f,108f+i*25f);lineTo(192f,122f+i*23f);lineTo(192f,192f);lineTo(0f,192f);close() };ac.drawPath(ridge,ap)
        }
        val snapshot=DashboardSnapshot(vehicle=mapOf(com.shilapi.xcertplay.ultra.VehicleSignal.INDICATED_SPEED_KPH to com.shilapi.xcertplay.ultra.VehicleReading(48,0,0)),music=ClusterMusicState.Snapshot(CarPlayNowPlaying(title="日落之后",artist="远方乐队",durationMillis=236000,elapsedMillis=84000,playing=true),art),navigation=ClusterTurnGuidance(2,0,300,"人民路",remainingMeters=5200,remainingSeconds=780))
        val dir=File("build/reports/esp32-display").apply { mkdirs() }
        for(page in 0..1) {
            val frame=Esp32FrameRenderer.render(c,snapshot,page)
            assertEquals(466,frame.width);assertEquals(466,frame.height)
            assertEquals(Color.BLACK,frame.getPixel(0,0));assertEquals(Color.BLACK,frame.getPixel(465,465))
            File(dir,if(page==0)"navigation.png" else "music.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) }
            frame.recycle();assertFalse(art.isRecycled)
        }
        val empty=Esp32FrameRenderer.render(c,DashboardSnapshot(),0)
        assertEquals(Color.BLACK,empty.getPixel(140,200));empty.recycle();art.recycle()
    }
}
