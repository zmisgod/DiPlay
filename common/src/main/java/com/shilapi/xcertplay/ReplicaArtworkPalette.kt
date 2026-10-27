package com.shilapi.xcertplay

import android.graphics.Color

/** Derived once per cover. Vehicle telltales deliberately do not use these accent colors. */
internal data class ReplicaArtworkPalette(val dominant: Int, val light: Boolean) {
    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a)+(Color.red(b)-Color.red(a))*t).toInt(),
        (Color.green(a)+(Color.green(b)-Color.green(a))*t).toInt(),
        (Color.blue(a)+(Color.blue(b)-Color.blue(a))*t).toInt())
    val text = if(light) Color.rgb(36,34,31) else Color.rgb(255,247,233)
    val secondary = if(light) Color.rgb(91,86,79) else Color.rgb(215,208,195)
    val surface = mix(dominant, if(light) Color.WHITE else Color.BLACK, if(light) .93f else .84f)
    val dialBase = if(light) intArrayOf(mix(dominant,Color.WHITE,.96f),mix(dominant,Color.WHITE,.85f),mix(dominant,Color.WHITE,.73f))
        else intArrayOf(mix(dominant,Color.BLACK,.77f),mix(dominant,Color.BLACK,.87f),mix(dominant,Color.BLACK,.67f))
    val accent = mix(dominant,if(light) Color.BLACK else Color.WHITE,if(light) .32f else .38f)
    val bezel = intArrayOf(mix(dominant,Color.WHITE,.78f),mix(dominant,Color.BLACK,.35f),mix(dominant,Color.WHITE,.64f))
    val sweep = intArrayOf(mix(dominant,Color.WHITE,.86f),mix(dominant,Color.WHITE,.53f),mix(dominant,Color.BLACK,.10f),mix(dominant,Color.BLACK,.34f),mix(dominant,Color.BLACK,.57f))
    val bottomBand = intArrayOf(dialBase[1],dialBase[2])
    fun progressColor(fraction: Float): Int {
        val position=fraction.coerceIn(0f,1f)*4
        val i=position.toInt().coerceAtMost(3)
        return mix(sweep[i],sweep[i+1],position-i)
    }
    fun progressTextColor(fraction: Float): Int {
        val lum=Color.luminance(progressColor(fraction))
        return if(1.05f/(lum+.05f) > (lum+.05f)/.055f) Color.WHITE else Color.rgb(18,18,18)
    }
    companion object {
        fun fromPixels(pixels: IntArray): ReplicaArtworkPalette {
            // Hue buckets avoid averaging complementary colors into muddy gray. Neutral covers
            // still use their average rather than inventing a saturated color.
            val weights=DoubleArray(12);val r=DoubleArray(12);val g=DoubleArray(12);val b=DoubleArray(12)
            val hsv=FloatArray(3);var luminance=0.0;var saturation=0.0;var sumR=0L;var sumG=0L;var sumB=0L
            pixels.forEach { color ->
                luminance+=Color.luminance(color);sumR+=Color.red(color);sumG+=Color.green(color);sumB+=Color.blue(color)
                Color.colorToHSV(color,hsv);saturation+=hsv[1]
                if(hsv[1]>.16f && hsv[2]>.12f) {
                    val i=(hsv[0]/30).toInt().coerceIn(0,11)
                    val weight=(hsv[1]*hsv[1]*hsv[2]).toDouble()
                    weights[i]+=weight;r[i]+=Color.red(color)*weight;g[i]+=Color.green(color)*weight;b[i]+=Color.blue(color)*weight
                }
            }
            val strongest=weights.indices.maxByOrNull { weights[it] } ?: 0
            val color=if(weights[strongest]>pixels.size*.015) Color.rgb((r[strongest]/weights[strongest]).toInt(),(g[strongest]/weights[strongest]).toInt(),(b[strongest]/weights[strongest]).toInt())
                else Color.rgb((sumR/pixels.size).toInt(),(sumG/pixels.size).toInt(),(sumB/pixels.size).toInt())
            return ReplicaArtworkPalette(color,luminance/pixels.size>.38 && saturation/pixels.size<.28)
        }
    }
}
