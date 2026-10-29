package io.github.fate_grand_automata.scripts.weekly

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Decoded, unpremultiplied ARGB pixels. Kept independent of Android and OpenCV for offline tests. */
data class MapOverlay(val width: Int, val height: Int, val argb: IntArray)

object MapGimmickCompositor {
    /** Draw in depth order over a display-sized grayscale map. Atlas positions refer to each layer's centre. */
    fun composite(gray: ByteArray, width: Int, height: Int, layers: List<Pair<MapGimmick, MapOverlay>>) {
        require(gray.size == width * height)
        for ((gimmick, image) in layers.sortedBy { it.first.depthOffset }) {
            require(image.width > 0 && image.height > 0 && image.argb.size == image.width * image.height)
            if (gimmick.scale <= 0 || !gimmick.x.isFinite() || !gimmick.y.isFinite()) continue
            val scale = gimmick.scale / 1000.0
            val left = gimmick.x - image.width * scale / 2
            val top = gimmick.y - image.height * scale / 2
            for (y in maxOf(0, floor(top).toInt()) until minOf(height, ceil(top + image.height * scale).toInt())) {
                val sy = floor((y + 0.5 - top) / scale).toInt().coerceIn(0, image.height - 1)
                for (x in maxOf(0, floor(left).toInt()) until minOf(width, ceil(left + image.width * scale).toInt())) {
                    val sx = floor((x + 0.5 - left) / scale).toInt().coerceIn(0, image.width - 1)
                    val pixel = image.argb[sy * image.width + sx]
                    val alpha = (pixel ushr 24) / 255.0
                    if (alpha == 0.0) continue
                    val value = 0.299 * (pixel ushr 16 and 255) + 0.587 * (pixel ushr 8 and 255) + 0.114 * (pixel and 255)
                    val i = y * width + x
                    gray[i] = (value * alpha + (gray[i].toInt() and 255) * (1 - alpha)).roundToInt().toByte()
                }
            }
        }
    }
}
