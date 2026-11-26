package com.example.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.widget.ImageView
import coil.dispose
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared helper for uploading device photos (via zero-permission Android Photo Picker)
 * as compact JPEG Data URIs (`data:image/jpeg;base64,...`) and rendering both uploaded
 * Data URIs and remote `https://` URLs reliably across popups, ads, banners, and admin previews.
 */
object PopupImageHelper {

    /**
     * Kept safely under Android's IPC Bundle threshold (~500 KB) and well within
     * Firestore's 1 MiB document limit so passing images to DialogFragments never throws
     * TransactionTooLargeException and syncs rapidly across RTDB & Firestore.
     */
    const val MAX_BASE64_DATA_URI_CHARS = 160_000
    const val MAX_IMAGE_SOURCE_CHARS = 250_000
    private const val DATA_URI_PREFIX = "data:image/jpeg;base64,"
    private const val DEFAULT_MAX_EDGE_PX = 900

    fun isDataImageUri(source: String?): Boolean {
        val clean = source?.trim().orEmpty()
        return clean.startsWith("data:image/", ignoreCase = true) && clean.contains(";base64,")
    }

    fun approxKilobytes(source: String?): Int {
        val clean = source?.trim().orEmpty()
        val idx = clean.indexOf(";base64,")
        if (idx < 0) return 0
        val base64Len = (clean.length - (idx + 8)).coerceAtLeast(0)
        val decodedBytes = (base64Len * 3L) / 4L
        return ((decodedBytes + 1023L) / 1024L).toInt().coerceAtLeast(1)
    }

    fun decodeDataUriToBitmap(source: String): Bitmap? {
        return runCatching {
            val idx = source.indexOf(";base64,")
            if (idx < 0) return null
            val base64Payload = source.substring(idx + 8)
            val bytes = Base64.decode(base64Payload, Base64.DEFAULT)
            if (bytes.isEmpty()) return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /**
     * Loads either a `data:image/...;base64,...` uploaded photo OR a standard `http(s)://` URL
     * into [imageView], invoking [onSuccess] or [onError] cleanly.
     */
    fun loadPopupImage(
        imageView: ImageView,
        rawSource: String?,
        onSuccess: () -> Unit = {},
        onError: () -> Unit = {}
    ) {
        val source = rawSource?.trim().orEmpty()
        if (source.isEmpty()) {
            imageView.dispose()
            imageView.setImageDrawable(null)
            onError()
            return
        }

        if (isDataImageUri(source)) {
            imageView.dispose()
            val bitmap = decodeDataUriToBitmap(source)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                onSuccess()
            } else {
                imageView.setImageDrawable(null)
                onError()
            }
            return
        }

        imageView.load(source) {
            crossfade(true)
            listener(
                onSuccess = { _, _ -> onSuccess() },
                onError = { _, _ -> onError() }
            )
        }
    }

    /**
     * Decodes a Photo Picker [uri], scales it cleanly to [maxEdgePx], and compresses as JPEG
     * until the resulting `data:image/jpeg;base64,...` fits within [maxChars].
     */
    suspend fun encodeImageUriToDataUri(
        context: Context,
        uri: Uri,
        maxEdgePx: Int = DEFAULT_MAX_EDGE_PX,
        maxChars: Int = MAX_BASE64_DATA_URI_CHARS
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val decoded = decodeScaledBitmap(context, uri, maxEdgePx) ?: return@withContext null
            compressBitmapToDataUri(decoded, maxChars)
        }.getOrNull()
    }

    private fun decodeScaledBitmap(context: Context, uri: Uri, maxEdgePx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val decoded = runCatching {
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val srcW = info.size.width.coerceAtLeast(1)
                    val srcH = info.size.height.coerceAtLeast(1)
                    val largest = max(srcW, srcH)
                    if (largest > maxEdgePx) {
                        val ratio = maxEdgePx.toFloat() / largest.toFloat()
                        val targetW = (srcW * ratio).roundToInt().coerceAtLeast(1)
                        val targetH = (srcH * ratio).roundToInt().coerceAtLeast(1)
                        decoder.setTargetSize(targetW, targetH)
                    }
                }
            }.getOrNull()
            if (decoded != null) return decoded
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var sampleSize = 1
        while (max(srcW / sampleSize, srcH / sampleSize) > maxEdgePx * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return null

        val largest = max(rawBitmap.width, rawBitmap.height)
        if (largest <= maxEdgePx) return rawBitmap

        val ratio = maxEdgePx.toFloat() / largest.toFloat()
        val targetW = (rawBitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val targetH = (rawBitmap.height * ratio).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(rawBitmap, targetW, targetH, true)
        if (scaled !== rawBitmap) {
            rawBitmap.recycle()
        }
        return scaled
    }

    private fun compressBitmapToDataUri(initialBitmap: Bitmap, maxChars: Int): String? {
        var working = initialBitmap
        val qualities = intArrayOf(82, 72, 62, 52, 42, 34)
        val baos = ByteArrayOutputStream()
        var bestResult: String? = null

        try {
            repeat(3) { pass ->
                for (quality in qualities) {
                    baos.reset()
                    working.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                    val encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    val dataUri = DATA_URI_PREFIX + encoded
                    bestResult = dataUri
                    if (dataUri.length <= maxChars) {
                        return dataUri
                    }
                }
                if (pass < 2) {
                    val nextW = (working.width * 0.72f).roundToInt().coerceAtLeast(160)
                    val nextH = (working.height * 0.72f).roundToInt().coerceAtLeast(160)
                    if (nextW == working.width && nextH == working.height) {
                        return bestResult?.takeIf { it.length <= maxChars }
                    }
                    val smaller = Bitmap.createScaledBitmap(working, nextW, nextH, true)
                    if (smaller !== working) {
                        working.recycle()
                        working = smaller
                    }
                }
            }
            return bestResult?.takeIf { it.length <= maxChars }
        } finally {
            if (!working.isRecycled) {
                working.recycle()
            }
        }
    }
}
