package io.github.shaonialife.carptocar.carplus

internal object CarPlusVideo {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FPS = 30

    data class Size(val width: Int, val height: Int)

    data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int)

    val defaultSize = Size(WIDTH, HEIGHT)

    fun displaySize(width: Int, height: Int): Size? =
        if (width in 320..4096 && height in 180..2304) Size(width, height) else null

    fun manualSize(width: Int, height: Int): Size? =
        if (width % 2 == 0 && height % 2 == 0) displaySize(width, height) else null

    /** Coordinates are relative to the top-left of the output. Leave bars if the source declines our aspect ratio. */
    fun fit(source: Size, output: Size): Viewport {
        val width = minOf(output.width, (output.height.toLong() * source.width / source.height).toInt())
        val height = minOf(output.height, (output.width.toLong() * source.height / source.width).toInt())
        return Viewport((output.width - width) / 2, (output.height - height) / 2, width, height)
    }
}
