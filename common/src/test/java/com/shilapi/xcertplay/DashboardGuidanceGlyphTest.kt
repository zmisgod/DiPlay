package com.shilapi.xcertplay

import android.graphics.*
import android.graphics.drawable.Drawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardGuidanceGlyphTest {
    private fun frame(icon: Int) = Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888).also {
        DashboardGuidanceGlyph().draw(Canvas(it),RectF(0f,0f,96f,96f),icon,Color.CYAN,null)
    }
    @Test fun solidTurnsPreserveDirectionAndStraightHasNoTurn() {
        val left=frame(2);val right=frame(3);val straight=frame(1)
        // Skia can rasterize a mirrored curve with slightly different edge alpha.
        // Compare total coverage, while exact interior points below protect maneuver direction.
        var coverageError=0L
        for(y in 0..95) for(x in 0..95) coverageError+=kotlin.math.abs(
            Color.alpha(right.getPixel(x,y))-Color.alpha(left.getPixel(95-x,y)))
        assertTrue("Mirrored silhouette coverage differs: $coverageError",coverageError < 96*96*255*.005)

        assertEquals(Color.CYAN,right.getPixel(88,32))
        assertEquals(Color.TRANSPARENT,left.getPixel(88,32))
        assertEquals(Color.CYAN,straight.getPixel(48,8))
        assertEquals(Color.TRANSPARENT,straight.getPixel(8,48))
    }
    @Test fun roundaboutsAndUnknownManeuversKeepTheirSuppliedDrawable() {
        var draws=0
        val fallback=object: Drawable() {
            override fun draw(canvas: Canvas) { draws++ }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(filter: ColorFilter?) {}
            @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
        }
        val image=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888)
        val renderer=DashboardGuidanceGlyph()
        for(icon in listOf(0,4,5,6,7,8,11,12,15,17,18,19,999)) {
            renderer.draw(Canvas(image),RectF(12f,12f,84f,84f),icon,Color.CYAN,fallback)
        }
        assertEquals(13,draws)
        assertEquals(Color.TRANSPARENT,image.getPixel(48,48))
    }
}
