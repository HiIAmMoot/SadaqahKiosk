package com.sadaqah.kiosk.telemetry

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrEncoderTest {

    /**
     * Rebuilds a decodable image from a module-resolution [BitMatrix] using only
     * classes in zxing's `core` artifact — `javase`'s BufferedImageLuminanceSource
     * is off limits (see QrEncoder's doc comment). Each module is scaled up before
     * decoding because a binarizer given a raw 25x25-ish image has too little
     * signal to threshold against.
     */
    private fun decode(matrix: BitMatrix?): String? {
        if (matrix == null) return null
        val scale = 8
        val width = matrix.width * scale
        val height = matrix.height * scale
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dark = matrix.get(x / scale, y / scale)
                pixels[y * width + x] = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val source = RGBLuminanceSource(width, height, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return QRCodeReader().decode(bitmap).text
    }

    /**
     * A real round trip rather than a golden-image comparison, which would break
     * on any rendering change without saying anything about correctness.
     *
     * core only: the usual bridge to a BinaryBitmap is
     * BufferedImageLuminanceSource, which lives in zxing's javase artifact — a
     * second dependency this phase does not take. core can back a BinaryBitmap
     * from the BitMatrix directly.
     */
    @Test
    fun `an encoded url decodes back to itself`() {
        val url = "https://example.org/privacy?v=2"
        val decoded = decode(QrEncoder.encode(url))
        assertEquals(url, decoded)
    }

    @Test
    fun `a long url still round trips`() {
        // QR version selection has to grow with the payload; a short-only
        // encoder passes the test above and fails in the field.
        val url = "https://example.org/" + "a".repeat(180)
        assertEquals(url, decode(QrEncoder.encode(url)))
    }

    @Test
    fun `an empty url encodes to null`() {
        assertNull(QrEncoder.encode(""))
    }
}
