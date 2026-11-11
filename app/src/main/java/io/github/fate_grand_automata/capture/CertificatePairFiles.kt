package io.github.fate_grand_automata.capture

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Two-file transaction. Validated backups survive both renames and any interrupted recovery. */
internal class CertificatePairFiles(
    private val directory: File,
    private val valid: (File, File) -> Boolean,
    private val write: (File, ByteArray) -> Unit = { file, bytes ->
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    },
    private val rename: (File, File) -> Unit = { from, to ->
        Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        Unit
    },
) {
    private fun file(name: String) = File(directory, name)
    private val leaf get() = file("leaf.p12")
    private val ca get() = file("ca.cer")
    private val oldLeaf get() = file("leaf.p12.bak")
    private val oldCa get() = file("ca.cer.bak")
    private val tempLeaf get() = file("leaf.p12.tmp")
    private val tempCa get() = file("ca.cer.tmp")

    private fun remove(file: File) { check(!file.exists() || file.delete()) { "Certificate cleanup failed: ${file.name}" } }

    fun recover(): Boolean {
        val interrupted = tempLeaf.exists() || tempCa.exists()
        if ((interrupted || !valid(leaf, ca)) && valid(oldLeaf, oldCa)) {
            write(tempLeaf, oldLeaf.readBytes())
            write(tempCa, oldCa.readBytes())
            rename(tempLeaf, leaf)
            rename(tempCa, ca)
            check(valid(leaf, ca)) { "Certificate recovery failed" }
        }
        val complete = valid(leaf, ca)
        if (!complete) { remove(leaf); remove(ca) }
        remove(tempLeaf)
        remove(tempCa)
        return complete
    }

    fun commit(leafBytes: ByteArray, caBytes: ByteArray) {
        val hadPair = recover()
        // Backup writes precede the transaction. An interrupted backup leaves the live pair valid.
        if (hadPair) {
            write(oldLeaf, leaf.readBytes())
            write(oldCa, ca.readBytes())
            check(valid(oldLeaf, oldCa)) { "Certificate backup failed" }
        } else { remove(oldLeaf); remove(oldCa) }
        try {
            write(tempLeaf, leafBytes)
            write(tempCa, caBytes)
            check(valid(tempLeaf, tempCa)) { "Invalid certificate pair" }
            rename(tempLeaf, leaf)
            rename(tempCa, ca)
        } catch (failure: Exception) {
            try {
                if (hadPair) {
                    write(tempLeaf, oldLeaf.readBytes())
                    write(tempCa, oldCa.readBytes())
                    rename(tempLeaf, leaf)
                    rename(tempCa, ca)
                } else { remove(leaf); remove(ca) }
                remove(tempLeaf); remove(tempCa)
            } catch (rollback: Exception) {
                throw IllegalStateException("Certificate rollback failed", rollback).apply { addSuppressed(failure) }
            }
            throw failure
        }
    }
}
