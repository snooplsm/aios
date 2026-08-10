package com.aios.messaging.mms.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class EncodedPhoto(val bytes: ByteArray, val width: Int, val height: Int)

/** Bounds both decoded memory and encoded carrier payload before constructing an MMS. */
internal object MmsPhotoTranscoder {
    fun encode(
        context: Context,
        uri: Uri,
        carrierWidth: Int,
        carrierHeight: Int,
        byteBudget: Int,
    ): EncodedPhoto {
        val mime = context.contentResolver.getType(uri).orEmpty()
        require(mime.startsWith("image/")) { "The selected item is not a supported photo" }
        require(byteBudget >= MIN_BUDGET) { "Carrier MMS size limit is too small for a photo" }
        val maxWidth = carrierWidth.takeIf { it > 0 }?.coerceIn(MIN_DIMENSION, MAX_DIMENSION)
            ?: DEFAULT_DIMENSION
        val maxHeight = carrierHeight.takeIf { it > 0 }?.coerceIn(MIN_DIMENSION, MAX_DIMENSION)
            ?: DEFAULT_DIMENSION
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        var decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val widthScale = maxWidth.toDouble() / info.size.width.coerceAtLeast(1)
            val heightScale = maxHeight.toDouble() / info.size.height.coerceAtLeast(1)
            val scale = min(1.0, min(widthScale, heightScale))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            if (scale < 1.0) {
                decoder.setTargetSize(
                    max(1, (info.size.width * scale).roundToInt()),
                    max(1, (info.size.height * scale).roundToInt()),
                )
            }
        }
        try {
            repeat(MAX_RESIZE_PASSES) {
                val opaque = opaque(decoded)
                try {
                    for (quality in QUALITIES) {
                        val output = ByteArrayOutputStream(min(byteBudget, 256 * 1_024))
                        check(opaque.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                            "Photo encoder failed"
                        }
                        val bytes = output.toByteArray()
                        if (bytes.size <= byteBudget) {
                            return EncodedPhoto(bytes, opaque.width, opaque.height)
                        }
                    }
                } finally {
                    if (opaque !== decoded) opaque.recycle()
                }
                val nextWidth = max(1, (decoded.width * RESIZE_FACTOR).roundToInt())
                val nextHeight = max(1, (decoded.height * RESIZE_FACTOR).roundToInt())
                check(nextWidth < decoded.width || nextHeight < decoded.height) {
                    "Photo cannot fit the carrier MMS size limit"
                }
                val resized = Bitmap.createScaledBitmap(decoded, nextWidth, nextHeight, true)
                if (resized !== decoded) decoded.recycle()
                decoded = resized
            }
            error("Photo cannot fit the carrier MMS size limit")
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun opaque(source: Bitmap): Bitmap {
        if (!source.hasAlpha()) return source
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(source, 0f, 0f, null)
            it.setHasAlpha(false)
        }
    }

    private const val MIN_BUDGET = 32 * 1_024
    private const val MIN_DIMENSION = 320
    private const val MAX_DIMENSION = 4_096
    private const val DEFAULT_DIMENSION = 1_280
    private const val MAX_RESIZE_PASSES = 7
    private const val RESIZE_FACTOR = 0.78
    private val QUALITIES = intArrayOf(90, 82, 72, 60, 48, 36)
}
