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

    /**
     * 把任意来源的箭头 PNG 规整成 256×256、透明底、保持原比例。
     *
     * 两处调用：CarLife `0x10030` 的 `turnIconData`（引擎给的原图），以及
     * [ManeuverArrow] 启动时从 `res/raw` 读出来的官方图标。**必须走同一套缩放**，
     * 否则同一辆车上切数据源时箭头大小会跳一下（CarLife 原图 240×240，
     * 我们不发原尺寸而是统一到 [NavigationIconLayout.SIZE]）。
     */
    internal fun render(bytes: ByteArray): ByteArray {
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

    /**
     * 媒体卡片（`0x5001` 封面位）用的字节：把箭头 PNG 压成**深底白箭头 JPEG**。
     *
     * **由「转向箭头加深色底」开关控制，默认关闭**（`DisplayPrefs.arrowDarkTile`）。
     * 为什么需要它（2026-11-24 测试台实测：卡片左侧整块空白）：
     *
     * 1. 官方箭头是**纯白**（实测 14372 个不透明像素平均 RGB=255,255,255），
     *    而媒体卡片在测试台/部分车机上是**浅色**底——白画在白上等于没有图；
     * 2. 现阶段唯一被证明能显示的封面格式是 JPEG（歌曲封面走的就是这条），
     *    发 PNG 没有成功案例。
     *
     * JPEG 不支持透明通道，`compress` 会把透明区写成黑色——正好成了白箭头的底，
     * 浅色卡片上是一块深色图标，深色卡片上只剩白箭头，两种底都看得见。
     * 默认关是因为深色卡片的车上原样发更好看，不该为浅色场景默认改观感。
     *
     * @return JPEG 字节；输入是全透明（没有箭头）时返回空数组，调用方回退原图。
     */
    internal fun toCardJpeg(png: ByteArray): ByteArray {
        val source = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return ByteArray(0)
        try {
            if (isFullyTransparent(source)) return ByteArray(0)
            val size = NavigationIconLayout.SIZE
            val tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                // 留 6% 边距：箭头贴着卡片边缘会显得挤，也避免车机方图裁切吃掉笔画。
                val inset = size * 0.06f
                tile.eraseColor(TILE_BACKGROUND)
                Canvas(tile).drawBitmap(
                    source,
                    null,
                    RectF(inset, inset, size - inset, size - inset),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                return ByteArrayOutputStream().use { out ->
                    if (tile.compress(Bitmap.CompressFormat.JPEG, 92, out)) out.toByteArray() else ByteArray(0)
                }
            } finally {
                tile.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    /** 箭头图标的底色（近黑的中性灰）。浅底卡片上成块、深底卡片上不吃掉白箭头。 */
    private val TILE_BACKGROUND = 0xFF16181D.toInt()

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
