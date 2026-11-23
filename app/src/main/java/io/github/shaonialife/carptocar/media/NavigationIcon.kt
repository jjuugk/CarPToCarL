package io.github.shaonialife.carptocar.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object NavigationIconLayout {
    const val SIZE = 256

    data class Placement(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    fun fit(
        width: Int,
        height: Int,
    ): Placement {
        require(width > 0 && height > 0)
        val scale = minOf(1f, SIZE.toFloat() / width, SIZE.toFloat() / height)
        val w = width * scale
        val h = height * scale
        return Placement((SIZE - w) / 2f, (SIZE - h) / 2f, w, h)
    }
}

/** 保留原图比例与透明通道。没有可用箭头时发送透明封面以清除上一张。 */
internal object NavigationIcon {
    val blank: ByteArray =
        java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAE0lEQVR4nGNgGAWjYBSMAgYwAAAEEAABsax5zAAAAABJRU5ErkJggg==",
        )

    private val worker =
        ThreadPoolExecutor(
            1,
            1,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(1),
            { task -> Thread(task, "navigation-artwork").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }

    fun prepare(
        display: NavigationDisplay,
        request: NavigationDisplay.ArtworkRequest,
    ) {
        worker.execute {
            val artwork = runCatching { render(request.bytes) }.getOrDefault(blank)
            display.prepared(request, artwork)
        }
    }

    private fun render(bytes: ByteArray): ByteArray {
        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inScaled = false
            }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType != "image/png" || bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) {
            return blank
        }
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return blank
        try {
            if (isFullyTransparent(source)) return blank
            val size = NavigationIconLayout.SIZE
            val placement = NavigationIconLayout.fit(source.width, source.height)
            val canvasImage = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                canvasImage.eraseColor(Color.TRANSPARENT)
                val destination =
                    RectF(placement.left, placement.top, placement.left + placement.width, placement.top + placement.height)
                Canvas(canvasImage).drawBitmap(source, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
                return ByteArrayOutputStream().use { out ->
                    if (canvasImage.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else blank
                }
            } finally {
                canvasImage.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun isFullyTransparent(image: Bitmap): Boolean {
        if (!image.hasAlpha()) return false
        // Scan one row at a time to avoid allocating an extra full-resolution pixel buffer.
        val row = IntArray(image.width)
        for (y in 0 until image.height) {
            image.getPixels(row, 0, image.width, 0, y, image.width, 1)
            if (row.any { it ushr 24 != 0 }) return false
        }
        return true
    }
}
