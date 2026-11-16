package io.github.fate_grand_automata.capture

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Synchronous file flag under `noBackupFilesDir` tracking whether root `FGA_CAP` rules
 * or the root watchdog may be active (R5, B8).
 *
 * Set before `apply` runs; cleared only after `FGA_CLEANUP_OK` confirms the chains are gone.
 * When clear, startup cleanup never invokes `su`.
 */
internal class RootRulesActiveFlag(private val dir: File) {
    private val file: File
        get() = File(dir, FILE_NAME)

    @Synchronized
    fun isSet(): Boolean = file.exists()

    @Synchronized
    fun markMaybeActive() {
        dir.mkdirs()
        FileOutputStream(file).use { out ->
            out.write("1\n".toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    @Synchronized
    fun clear() {
        if (file.exists()) {
            file.delete()
        }
    }

    companion object {
        const val FILE_NAME = "capture_root_rules_maybe_active"

        fun from(context: Context): RootRulesActiveFlag =
            RootRulesActiveFlag(context.noBackupFilesDir)
    }
}
