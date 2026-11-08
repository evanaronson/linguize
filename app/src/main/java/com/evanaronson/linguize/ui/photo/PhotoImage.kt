package com.evanaronson.linguize.ui.photo

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import com.evanaronson.linguize.llm.lab.ImageInput
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a picked or shared image into a JPEG small enough to send: at most [MAX_SIDE] pixels
 * on its long side, re-encoded until it fits [MAX_BYTES]. Everything stays in memory; the
 * original is read once through the content resolver and never copied to storage.
 */
internal object PhotoImage {
    /** Enough for small print on an A4 letter photographed whole; providers downscale beyond this anyway. */
    const val MAX_SIDE = 1600

    /** Base64 grows this by a third; both providers accept far more, but slow networks don't. */
    const val MAX_BYTES = 1_500_000

    /** Qualities tried in turn, best first. */
    val QUALITIES = listOf(85, 75, 65, 50)

    /** The size to decode to: [width] by [height] scaled so the long side is at most [maxSide], never enlarged. */
    fun scaledSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Empty image" }
        val longSide = max(width, height)
        if (longSide <= maxSide) return width to height
        val scale = maxSide.toDouble() / longSide
        // At least one pixel on the short side, even for a very long, thin image.
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** The first of [QUALITIES] whose encoding fits, or the last one tried; [encode] gives the bytes at a quality. */
    fun fitting(maxBytes: Int = MAX_BYTES, encode: (quality: Int) -> ByteArray): ByteArray {
        var last = ByteArray(0)
        for (quality in QUALITIES) {
            last = encode(quality)
            if (last.size <= maxBytes) return last
        }
        return last
    }

    /** Decodes, rotates per EXIF (ImageDecoder does this), downscales and compresses. Call off the main thread. */
    fun prepare(resolver: ContentResolver, uri: Uri): ImageInput {
        val source = ImageDecoder.createSource(resolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (width, height) = scaledSize(info.size.width, info.size.height)
            decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val opaque = if (decoded.hasAlpha()) onWhite(decoded).also { decoded.recycle() } else decoded
        try {
            val bytes = fitting { quality ->
                ByteArrayOutputStream().use { out ->
                    check(opaque.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG encoding failed" }
                    out.toByteArray()
                }
            }
            return ImageInput("image/jpeg", bytes)
        } finally {
            opaque.recycle()
        }
    }

    /** JPEG has no transparency: screenshots and stickers would turn black behind their text. */
    private fun onWhite(bitmap: Bitmap): Bitmap {
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return flat
    }
}
