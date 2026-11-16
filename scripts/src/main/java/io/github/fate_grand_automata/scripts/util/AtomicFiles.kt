package io.github.fate_grand_automata.scripts.util

import java.io.File
import java.io.IOException

/**
 * Moves [temp] over [target] using `java.io` only so callers remain compatible with Android API 24+.
 * On Android/Linux, `rename(2)` atomically replaces an existing [target] on the same filesystem.
 */
fun replaceFile(temp: File, target: File) {
    if (temp.renameTo(target)) return
    // On Windows, File.renameTo wraps MoveFileW, which refuses to overwrite an existing target.
    // Move the existing target aside first only on Windows so JVM tests running on Windows behave
    // like Android/Linux without changing Android/Linux's single-rename(2) path.
    if (isWindows() && temp.exists() && target.isFile) {
        val backup = File(target.parentFile, "${target.name}.bak.${System.nanoTime()}")
        if (!backup.exists() && target.renameTo(backup)) {
            if (temp.renameTo(target)) {
                backup.delete()
                return
            }
            backup.renameTo(target)
        }
    }
    throw IOException("Failed to move $temp to $target")
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
