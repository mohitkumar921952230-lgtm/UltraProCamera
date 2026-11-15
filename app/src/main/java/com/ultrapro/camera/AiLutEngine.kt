package com.ultrapro.camera

import android.graphics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

object AiLutEngine {

    enum class LutPreset(val title: String) {
        AI_AUTO("🤖 AI Ultra Auto"),
        IPHONE_SKIN("🍎 iPhone Natural Skin"),
        GOLDEN_SUNSET("🌅 Golden Hour LUT"),
        VIVID_ROSE("🌹 Deep Vivid Red/Green"),
        MIDNIGHT_MOON("🌙 Midnight Low-Glare"),
        SAMSUNG_ULTRA("🌌 Ultra HDR Vivid"),
        CINEMA_MOODY("🎬 Moody Teal & Orange")
    }

    suspend fun enhanceImageOffline(
        source: Bitmap,
        lut: LutPreset,
        aiUltraEnabled: Boolean,
        isNightMode: Boolean
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val colorMatrix = getLutColorMatrix(lut, aiUltraEnabled, isNightMode)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(source, 0f, 0f, paint)

        if (aiUltraEnabled) {
            applySmartSharpenAndDetailBoost(output)
        }

        return@withContext output
    }

    private fun getLutColorMatrix(
        lut: LutPreset,
        aiEnabled: Boolean,
        isNight: Boolean
    ): ColorMatrix {
        val cm = ColorMatrix()

        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(if (aiEnabled) 1.22f else 1.05f)
        cm.postConcat(satMatrix)

        val custom = when (lut) {
            LutPreset.IPHONE_SKIN -> floatArrayOf(
                1.08f, 0.03f, 0.00f, 0f, 6f,
                0.02f, 1.04f, 0.01f, 0f, 4f,
                0.00f, 0.01f, 0.96f, 0f, 2f,
                0f,    0f,    0f,    1f, 0f
            )
            LutPreset.GOLDEN_SUNSET -> floatArrayOf(
                1.18f, 0.06f, 0.00f, 0f, 10f,
                0.03f, 1.10f, 0.00f, 0f, 5f,
                0.00f, 0.00f, 0.85f, 0f, -4f,
                0f,    0f,    0f,    1f, 0f
            )
            LutPreset.VIVID_ROSE -> floatArrayOf(
                1.24f, 0.00f, 0.02f, 0f, -4f,
                0.00f, 1.16f, 0.00f, 0f, -2f,
                0.02f, 0.00f, 1.12f, 0f, -4f,
                0f,    0f,    0f,    1f, 0f
            )
            LutPreset.MIDNIGHT_MOON -> floatArrayOf(
                0.95f, 0.02f, 0.05f, 0f, -6f,
                0.02f, 1.02f, 0.06f, 0f, -4f,
                0.04f, 0.06f, 1.18f, 0f, 4f,
                0f,    0f,    0f,    1f, 0f
            )
            LutPreset.SAMSUNG_ULTRA -> floatArrayOf(
                1.16f, 0.02f, 0.02f, 0f, 4f,
                0.02f, 1.18f, 0.02f, 0f, 4f,
                0.02f, 0.02f, 1.20f, 0f, 6f,
                0f,    0f,    0f,    1f, 0f
            )
            LutPreset.CINEMA_MOODY -> floatArrayOf(
                1.15f, 0.05f, -0.05f, 0f, 2f,
                0.00f, 1.05f, 0.04f,  0f, 0f,
                -0.04f, 0.06f, 1.14f, 0f, 6f,
                0f,    0f,    0f,     1f, 0f
            )
            LutPreset.AI_AUTO -> floatArrayOf(
                1.12f, 0.02f, 0.01f, 0f, 3f,
                0.01f, 1.12f, 0.01f, 0f, 3f,
                0.01f, 0.02f, 1.12f, 0f, 3f,
                0f,    0f,    0f,    1f, 0f
            )
        }
        cm.postConcat(ColorMatrix(custom))

        if (isNight) {
            val nightMatrix = ColorMatrix(
                floatArrayOf(
                    0.96f, 0f,    0f,    0f, 8f,
                    0f,    0.96f, 0f,    0f, 8f,
                    0f,    0f,    0.98f, 0f, 10f,
                    0f,    0f,    0f,    1f, 0f
                )
            )
            cm.postConcat(nightMatrix)
        }

        return cm
    }

    private fun applySmartSharpenAndDetailBoost(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = pixels.copyOf()

        val strength = 0.28f
        for (y in 1 until h - 1) {
            val rowOffset = y * w
            for (x in 1 until w - 1) {
                val idx = rowOffset + x
                val c = pixels[idx]
                val top = pixels[idx - w]
                val bottom = pixels[idx + w]
                val left = pixels[idx - 1]
                val right = pixels[idx + 1]

                val rC = (c shr 16) and 0xFF
                val gC = (c shr 8) and 0xFF
                val bC = c and 0xFF

                val rAvg = (((top shr 16) and 0xFF) + ((bottom shr 16) and 0xFF) +
                        ((left shr 16) and 0xFF) + ((right shr 16) and 0xFF)) shr 2
                val gAvg = (((top shr 8) and 0xFF) + ((bottom shr 8) and 0xFF) +
                        ((left shr 8) and 0xFF) + ((right shr 8) and 0xFF)) shr 2
                val bAvg = ((top and 0xFF) + (bottom and 0xFF) +
                        (left and 0xFF) + (right and 0xFF)) shr 2

                val rNew = min(255, max(0, (rC + (rC - rAvg) * strength).toInt()))
                val gNew = min(255, max(0, (gC + (gC - gAvg) * strength).toInt()))
                val bNew = min(255, max(0, (bC + (bC - bAvg) * strength).toInt()))

                result[idx] = (0xFF shl 24) or (rNew shl 16) or (gNew shl 8) or bNew
            }
        }
        bitmap.setPixels(result, 0, w, 0, 0, w, h)
    }
}
