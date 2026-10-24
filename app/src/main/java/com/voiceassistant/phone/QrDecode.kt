package com.voiceassistant.phone

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

object QrDecode {
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    /**
     * Reads a QR code from one camera frame. [luminance] is the frame's brightness plane; [rowStride] is the distance between rows in it,
     * which can be wider than [width]. A QR code reads the same at any rotation, so the frame needs no turning.
     */
    fun fromLuminance(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? = try {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (e: ReaderException) {
        null // no code in this frame: normal, the camera keeps trying
    }
}
