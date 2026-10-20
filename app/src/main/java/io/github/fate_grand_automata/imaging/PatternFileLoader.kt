package io.github.fate_grand_automata.imaging

import io.github.fate_grand_automata.scripts.weekly.IPatternFileLoader
import io.github.lib_automata.Pattern
import java.io.File
import javax.inject.Inject

/** Weekly route chapter art on disk, as OpenCV images. */
class PatternFileLoader @Inject constructor() : IPatternFileLoader {
    override fun load(file: File, color: Boolean, tag: String): Pattern =
        file.inputStream().buffered().use { DroidCvPattern(it, color, tag) }

    override fun save(pattern: Pattern, file: File) {
        val temp = File(file.parentFile, file.name + ".part")
        temp.outputStream().buffered().use { pattern.save(it) }
        check(temp.renameTo(file)) { "cannot store prepared Atlas image" }
    }
}
