package com.sadaqah.kiosk.telemetry

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
     * second dependency this phase does not take. `decode` above rebuilds the
     * bridge from `core` alone: RGBLuminanceSource wraps a pixel array, and
     * HybridBinarizer turns that into the BinaryBitmap a reader needs.
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

    // A round trip alone stays green even if the encoder regresses to pixel
    // resolution or drops the quiet zone — decode() only checks the text comes
    // back out. These two pin the properties a round trip cannot see.

    @Test
    fun `matrix stays at module resolution, not pixel size`() {
        val matrix = QrEncoder.encode("https://example.org/privacy?v=2")!!
        // Real QR symbols top out at 177 modules per side (version 40); asking
        // for 512x512 pixel output instead would blow past this by orders of
        // magnitude.
        assertTrue(matrix.width < 100)
        assertTrue(matrix.height < 100)
    }

    @Test
    fun `quiet zone is baked into the matrix border`() {
        val matrix = QrEncoder.encode("https://example.org/privacy?v=2")!!
        val size = matrix.width
        for (i in 0 until size) {
            for (offset in 0 until 4) {
                assertFalse("column $offset, row $i should be light", matrix.get(offset, i))
                assertFalse("column ${size - 1 - offset}, row $i should be light", matrix.get(size - 1 - offset, i))
                assertFalse("row $offset, column $i should be light", matrix.get(i, offset))
                assertFalse("row ${size - 1 - offset}, column $i should be light", matrix.get(i, size - 1 - offset))
            }
        }
    }
}
