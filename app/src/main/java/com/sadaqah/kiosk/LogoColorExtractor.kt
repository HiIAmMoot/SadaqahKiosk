package com.sadaqah.kiosk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Extracts dominant colours from the configured logo image and exposes them
 * as a list of ARGB longs for the color picker's "From logo" swatch row.
 *
 * Behaviour:
 *  - Results are cached by logo URI; subsequent calls with the same URI are a no-op.
 *  - All decode + Palette work runs off the main thread.
 *  - Very-grey / near-white / near-black candidates are filtered out so the
 *     row doesn't get spammed with anti-aliasing edges or background pixels.
 *  - Anything that fails (URI gone, image corrupt, decoder OOM) leaves the
 *     state untouched and just logs — never crashes the kiosk.
 */
object LogoColorExtractor {
    private const val TAG = "LogoColorExtractor"

    /** Max swatches surfaced in the picker row. */
    private const val MAX_SWATCHES = 6

    /** Min HSV saturation to count as a "real" colour vs noise. */
    private const val MIN_SATURATION = 0.18f

    /** Avoid near-black and near-white edges of the picture. */
    private const val MIN_VALUE = 0.10f
    private const val MAX_VALUE = 0.95f

    private val _colors = MutableStateFlow<List<Long>>(emptyList())
    val colors: StateFlow<List<Long>> = _colors

    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastUri: String? = null

    /**
     * Triggers extraction for [logoUri]. Idempotent — repeated calls with the
     * same URI don't reprocess. Pass null/blank to clear.
     */
    fun refresh(context: Context, logoUri: String?) {
        if (logoUri.isNullOrBlank()) {
            lastUri = null
            _colors.value = emptyList()
            return
        }
        if (logoUri == lastUri) return
        lastUri = logoUri
        scope.launch {
            try {
                val bitmap = decode(context, logoUri.toUri())
                if (bitmap == null) {
                    Log.w(TAG, "Could not decode $logoUri")
                    _colors.value = emptyList()
                    return@launch
                }
                val palette = withContext(Dispatchers.Default) {
                    Palette.Builder(bitmap).maximumColorCount(24).generate()
                }
                bitmap.recycle()
                _colors.value = palette.swatches
                    .asSequence()
                    .sortedByDescending { it.population }
                    .filter(::passesFilter)
                    .map { (it.rgb.toLong() or 0xFF000000L) and 0xFFFFFFFFL }
                    .distinct()
                    .take(MAX_SWATCHES)
                    .toList()
                Log.d(TAG, "Extracted ${_colors.value.size} swatches from $logoUri")
            } catch (e: Exception) {
                Log.e(TAG, "Extraction failed for $logoUri: ${e.message}")
                _colors.value = emptyList()
            }
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        // Inline-sample the image so we never hand Palette a multi-megapixel
        // bitmap. Palette is plenty accurate at ~300px on the long edge.
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            val targetEdge = 300
            val sampleSize = if (longEdge > targetEdge) {
                var s = 1
                while (longEdge / (s * 2) >= targetEdge) s *= 2
                s
            } else 1
            val decode = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "decode failed: ${e.message}")
            null
        }
    }

    private fun passesFilter(swatch: Palette.Swatch): Boolean {
        val hsl = swatch.hsl
        // hsl = [hue 0..360, saturation 0..1, lightness 0..1]
        val saturation = hsl[1]
        val lightness = hsl[2]
        if (saturation < MIN_SATURATION) return false
        if (lightness < MIN_VALUE || lightness > MAX_VALUE) return false
        return true
    }
}
