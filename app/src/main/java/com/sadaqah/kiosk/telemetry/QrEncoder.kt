package com.sadaqah.kiosk.telemetry

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Encodes a URL to a QR matrix the screen draws itself.
 *
 * A QR code is the only way an operator reaches a link from this kiosk: lock
 * task mode ([MainActivity] calls `startLockTask`) means no browser can be
 * launched, so a tappable link is inert and the operator's own phone is the
 * only reader.
 */
object QrEncoder {

    /** Error correction M, not L: these codes are read off a kiosk screen at an
     *  angle, often under shop lighting, and M tolerates that for about 10%
     *  more modules. Not H, which would inflate the symbol for no gain at this
     *  payload size. */
    private val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        // Four modules, which is the quiet zone the QR specification requires.
        // Less than that still decodes in a test harness reading a clean
        // matrix and fails against a phone camera pointed at a lit screen —
        // a defect no unit test on this machine can catch.
        EncodeHintType.MARGIN to 4,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )

    /**
     * Returns the symbol at **module resolution** — roughly 25x25 to 60x60 —
     * not at pixel size. The caller scales it when drawing.
     *
     * Asking ZXing for 512x512 would return a 512x512 matrix, and a Canvas
     * drawing one rect per dark module would then issue ~130,000 draw calls
     * per code, twice per frame. Passing 0 lets the writer pick the smallest
     * version that fits, and the screen scales ~45 rects per side instead.
     *
     * Null rather than a throw: these URLs are never validated anywhere
     * (`TelemetryUrl` checks only the Supabase endpoint), so the input can be
     * empty or too long for any QR version, and `QRCodeWriter.encode` throws
     * on both. A throw during composition would take down a kiosk in lock
     * task mode, which cannot be dismissed. The screen renders the URL as
     * text and omits the code.
     */
    fun encode(text: String): BitMatrix? = try {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    } catch (_: Exception) {
        null
    }
}
