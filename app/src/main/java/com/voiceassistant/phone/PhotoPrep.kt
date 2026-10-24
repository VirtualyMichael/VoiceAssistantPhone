package com.voiceassistant.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/** Gets a camera photo ready to send: upright, no bigger than needed to read a sign, and small enough for the link. */
object PhotoPrep {
    const val MAX_SIDE = 1600
    const val MAX_BYTES = 900_000

    /** Size after scaling so the longer side is at most [max]. */
    fun scaledSize(width: Int, height: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= max) return width to height
        val scale = max.toFloat() / longest
        return maxOf(1, Math.round(width * scale)) to maxOf(1, Math.round(height * scale))
    }

    /** Power-of-two decode shrink that keeps the picture at least [max] pixels on its long side, so a 50 MP photo is not decoded in full. */
    fun sampleSize(width: Int, height: Int, max: Int = MAX_SIDE): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= max) sample *= 2
        return sample
    }

    fun prepare(jpeg: ByteArray, rotationDegrees: Int, maxBytes: Int = MAX_BYTES): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return jpeg
        val (w, h) = scaledSize(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (w != decoded.width) postScale(w.toFloat() / decoded.width, h.toFloat() / decoded.height)
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (upright !== decoded) decoded.recycle()
        try {
            var out = ByteArray(0)
            for (quality in intArrayOf(85, 75, 65, 55, 45)) {
                val stream = ByteArrayOutputStream()
                upright.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                out = stream.toByteArray()
                if (out.size <= maxBytes) break
            }
            return out
        } finally {
            upright.recycle()
        }
    }
}
