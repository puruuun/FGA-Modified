package io.github.fate_grand_automata.imaging

import android.graphics.BitmapFactory
import io.github.fate_grand_automata.scripts.weekly.IPatternFileLoader
import io.github.fate_grand_automata.scripts.weekly.MapOverlay
import io.github.lib_automata.Pattern
import io.github.lib_automata.Size
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.File
import javax.inject.Inject

/** Weekly route chapter art on disk, as OpenCV images. */
class PatternFileLoader @Inject constructor() : IPatternFileLoader {
    override fun load(file: File, color: Boolean, tag: String): Pattern =
        file.inputStream().buffered().use { DroidCvPattern(it, color, tag) }

    override fun loadOverlay(file: File): MapOverlay {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath)) { "invalid map gimmick image" }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return MapOverlay(bitmap.width, bitmap.height, pixels)
        } finally { bitmap.recycle() }
    }

    override fun fromGray(pixels: ByteArray, size: Size): Pattern {
        val mat = Mat(size.height, size.width, CvType.CV_8UC1)
        mat.put(0, 0, pixels)
        return DroidCvPattern(mat, tag = "composited-map")
    }

    override fun save(pattern: Pattern, file: File) {
        val temp = File(file.parentFile, file.name + ".part")
        temp.outputStream().buffered().use { pattern.save(it) }
        check(temp.renameTo(file)) { "cannot store prepared Atlas image" }
    }
}
