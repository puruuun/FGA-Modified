package io.github.fate_grand_automata.scripts.capture.chaldea

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

class ChaldeaIoException(val kind: Kind, cause: Throwable? = null) : IOException(kind.name, cause) {
    enum class Kind { NotFound, NoAccess, Unreadable, WriteFailed }
}

/** The files of one Chaldea user folder. Abstract so the write sequence is tested without a device. */
interface ChaldeaFileAccess {
    fun exists(name: String): Boolean
    fun length(name: String): Long
    fun lastModified(name: String): Long

    /** @throws ChaldeaIoException */
    fun read(name: String): ByteArray

    /** Replaces the content of an existing file in place. Never creates a file. @throws ChaldeaIoException */
    fun overwrite(name: String, bytes: ByteArray)
}

/** java.io implementation (also valid on Android API 24, unlike java.nio.file). */
class JavaChaldeaFileAccess(private val dir: File) : ChaldeaFileAccess {
    private fun file(name: String) = File(dir, name)

    override fun exists(name: String) = file(name).isFile
    override fun length(name: String) = file(name).length()
    override fun lastModified(name: String) = file(name).lastModified()

    override fun read(name: String): ByteArray {
        val f = file(name)
        try {
            if (!f.exists()) throw ChaldeaIoException(ChaldeaIoException.Kind.NotFound)
            FileInputStream(f).use { return it.readBytes() }
        } catch (e: ChaldeaIoException) {
            throw e
        } catch (e: FileNotFoundException) {
            throw ChaldeaIoException(kindOf(e, ChaldeaIoException.Kind.Unreadable), e)
        } catch (e: IOException) {
            throw ChaldeaIoException(ChaldeaIoException.Kind.Unreadable, e)
        } catch (e: SecurityException) {
            throw ChaldeaIoException(ChaldeaIoException.Kind.NoAccess, e)
        }
    }

    override fun overwrite(name: String, bytes: ByteArray) {
        val f = file(name)
        // The existence check is the only guard against creating a file: FileOutputStream opens with O_CREAT.
        if (!f.isFile) throw ChaldeaIoException(ChaldeaIoException.Kind.NotFound)
        try {
            FileOutputStream(f, false).use {
                it.write(bytes)
                it.flush()
                it.fd.sync()
            }
        } catch (e: FileNotFoundException) {
            throw ChaldeaIoException(kindOf(e, ChaldeaIoException.Kind.WriteFailed), e)
        } catch (e: IOException) {
            throw ChaldeaIoException(ChaldeaIoException.Kind.WriteFailed, e)
        } catch (e: SecurityException) {
            throw ChaldeaIoException(ChaldeaIoException.Kind.NoAccess, e)
        }
    }

    private fun kindOf(e: FileNotFoundException, fallback: ChaldeaIoException.Kind): ChaldeaIoException.Kind {
        val m = e.message.orEmpty()
        return when {
            m.contains("EACCES") || m.contains("Permission denied", ignoreCase = true) -> ChaldeaIoException.Kind.NoAccess
            m.contains("ENOENT") || m.contains("No such file", ignoreCase = true) -> ChaldeaIoException.Kind.NotFound
            else -> fallback
        }
    }
}

/** Picks the Chaldea package whose userdata.json is the newest (amendment O). */
object ChaldeaPackages {
    const val PLAY = "cc.narumi.chaldea"
    const val FDROID = "cc.narumi.chaldea.fdroid"
    val all = listOf(PLAY, FDROID)

    fun userDir(pkg: String) = "/sdcard/Android/data/$pkg/files/user"

    fun gameDir(pkg: String) = "/sdcard/Android/data/$pkg/files/game"

    /**
     * @param modified last-modified time of each package's userdata.json, null when that package has no such file
     * @return the package with the newest file; the Play package when neither has one (ties go to Play)
     */
    fun choose(modified: Map<String, Long?>): String =
        all.filter { modified[it] != null }.maxByOrNull { modified.getValue(it)!! } ?: PLAY
}
