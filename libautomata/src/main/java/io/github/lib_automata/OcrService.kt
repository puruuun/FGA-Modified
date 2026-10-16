package io.github.lib_automata

/**
 * OCR engines hold native resources, so callers must [close] the service when they're done
 * with it instead of waiting for the garbage collector to get around to it.
 */
interface OcrService : AutoCloseable {
    fun detectText(pattern: Pattern): String

    /** Independent single-line profile; must not alter the ordinary OCR context. */
    fun detectTextLine(pattern: Pattern, options: TextLineOcrOptions): OcrText
}

data class OcrText(val text: String, val confidence: Int)

data class TextLineOcrOptions(
    val scale: Int = 1,
    val threshold: Double = 0.5,
    val fillOutline: Boolean = false,
    val padding: Int = 0,
    val allowedCharacters: String = "",
    val invert: Boolean = false,
    val rawGrayscale: Boolean = false,
    /** Additional width correction for narrow game fonts; ordinary OCR keeps its aspect ratio. */
    val horizontalScale: Double = 1.0
) {
    init {
        require(scale > 0)
        require(horizontalScale.isFinite() && horizontalScale > 0.0)
        require(threshold in 0.0..1.0)
        require(padding >= 0)
        require(!rawGrayscale || !fillOutline)
    }
}
