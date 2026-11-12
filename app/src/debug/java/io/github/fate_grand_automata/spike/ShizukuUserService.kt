package io.github.fate_grand_automata.spike

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.StructStat
import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.system.exitProcess

@Keep
class ShizukuUserService : IShizukuSpikeService.Stub {

    companion object {
        private const val TAG = "ShizukuSpike"
        private const val CHALDEA_USER_DIR = "/sdcard/Android/data/cc.narumi.chaldea/files/user"
        private const val FILE_USERDATA = "userdata.json"
        private const val FILE_USERDATA_BAK = "userdata.json.bak"
    }

    private data class FileSnapshot(
        val exists: Boolean,
        val size: Long = 0,
        val sha256: String = "",
        val uid: Int = -1,
        val gid: Int = -1,
        val modeOctal: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("exists", exists)
            put("size", size)
            put("sha256", sha256)
            put("uid", uid)
            put("gid", gid)
            put("mode", modeOctal)
        }
    }

    // Cache of the exact bytes and snapshot read in E
    private var cachedUserdataBytes: ByteArray? = null
    private var cachedUserdataSnapshot: FileSnapshot? = null

    private var cachedBakBytes: ByteArray? = null
    private var cachedBakSnapshot: FileSnapshot? = null

    constructor() : super() {
        initLogging()
    }

    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super() {
        initLogging()
    }

    private fun initLogging() {
        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.tag(TAG).i("ShizukuUserService initialized. PID=${Process.myPid()}, UID=${Process.myUid()}")
    }

    override fun destroy() {
        Timber.tag(TAG).i("ShizukuUserService destroy() called, exiting process")
        exitProcess(0)
    }

    override fun getUid(): Int = Process.myUid()

    override fun getPid(): Int = Process.myPid()

    private data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runShell(command: String): ShellResult {
        return try {
            val process = ProcessBuilder("sh", "-c", command).start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            ShellResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error executing shell command: %s", command)
            ShellResult(-1, "", e.message ?: "Execution error")
        }
    }

    override fun getChaldeaState(): String {
        Timber.tag(TAG).i("Querying Chaldea state...")

        // 1. pidof cc.narumi.chaldea
        val pidResult = runShell("pidof cc.narumi.chaldea")
        val pids = pidResult.stdout.trim()
        val isRunning = pidResult.exitCode == 0 && pids.isNotEmpty()

        // 2. dumpsys activity activities
        val dumpsysActivity = runShell("dumpsys activity activities")
        val activityLines = dumpsysActivity.stdout.lines()

        var isTopResumed = false
        val matchingActivityLines = mutableListOf<String>()

        for (line in activityLines) {
            val trimmed = line.trim()
            if (trimmed.contains("topResumedActivity", ignoreCase = true) && trimmed.contains("cc.narumi.chaldea")) {
                isTopResumed = true
            }
            if (trimmed.contains("cc.narumi.chaldea")) {
                val hasKeyword = listOf("visible", "mVisible", "Resumed", "windowingMode").any {
                    trimmed.contains(it, ignoreCase = true)
                }
                if (hasKeyword) {
                    matchingActivityLines.add(trimmed)
                }
            }
        }

        // 3. dumpsys window windows (grep -E "cc.narumi.chaldea|mCurrentFocus")
        val dumpsysWindow = runShell("dumpsys window windows")
        val windowLines = dumpsysWindow.stdout.lines()
        val matchingWindowLines = mutableListOf<String>()

        for (line in windowLines) {
            val trimmed = line.trim()
            if (trimmed.contains("cc.narumi.chaldea") || trimmed.contains("mCurrentFocus")) {
                matchingWindowLines.add(trimmed)
            }
        }

        val resultJson = JSONObject().apply {
            put("running", isRunning)
            put("pids", pids)
            put("topResumedActivity", isTopResumed)
            put("activityMatchingLines", JSONArray(matchingActivityLines))
            put("windowMatchingLines", JSONArray(matchingWindowLines))
        }

        val resultStr = resultJson.toString(2)
        Timber.tag(TAG).i(
            "Chaldea state: running=%s, pids=%s, topResumed=%s, activityMatches=%d, windowMatches=%d",
            isRunning,
            pids,
            isTopResumed,
            matchingActivityLines.size,
            matchingWindowLines.size
        )
        return resultStr
    }

    private fun takeSnapshot(file: File): Pair<FileSnapshot, ByteArray?> {
        if (!file.exists()) {
            return Pair(FileSnapshot(exists = false), null)
        }
        val bytes = file.readBytes()
        val stat: StructStat = Os.stat(file.absolutePath)
        val modeStr = "0" + Integer.toOctalString(stat.st_mode)
        val sha256 = calculateSha256(bytes)
        val snapshot = FileSnapshot(
            exists = true,
            size = bytes.size.toLong(),
            sha256 = sha256,
            uid = stat.st_uid,
            gid = stat.st_gid,
            modeOctal = modeStr,
        )
        return Pair(snapshot, bytes)
    }

    private fun calculateSha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun maskImportId(id: String): String {
        val trimmed = id.trim()
        return if (trimmed.length > 3) {
            "*".repeat(trimmed.length - 3) + trimmed.takeLast(3)
        } else if (trimmed.isNotEmpty()) {
            "***"
        } else {
            "(none)"
        }
    }

    override fun readUserDataInfo(): String {
        Timber.tag(TAG).i("Reading Chaldea userdata files...")

        val userdataFile = File(CHALDEA_USER_DIR, FILE_USERDATA)
        val bakFile = File(CHALDEA_USER_DIR, FILE_USERDATA_BAK)

        val (uSnapshot, uBytes) = takeSnapshot(userdataFile)
        val (bSnapshot, bBytes) = takeSnapshot(bakFile)

        cachedUserdataSnapshot = uSnapshot
        cachedUserdataBytes = uBytes

        cachedBakSnapshot = bSnapshot
        cachedBakBytes = bBytes

        val resultJson = JSONObject()
        resultJson.put("userdata", uSnapshot.toJson())
        resultJson.put("userdata_bak", bSnapshot.toJson())

        if (uBytes != null) {
            try {
                val json = JSONObject(String(uBytes, Charsets.UTF_8))
                val version = json.opt("version")?.toString() ?: "unknown"
                resultJson.put("json_version", version)

                val usersArray = json.optJSONArray("users")
                val userCount = usersArray?.length() ?: 0
                resultJson.put("user_count", userCount)

                val usersInfo = JSONArray()
                if (usersArray != null) {
                    for (i in 0 until usersArray.length()) {
                        val userObj = usersArray.optJSONObject(i)
                        if (userObj != null) {
                            val lastImportId = userObj.optString("lastImportId", "")
                            val region = userObj.opt("region")?.toString() ?: "unknown"
                            val uInfo = JSONObject().apply {
                                put("index", i)
                                put("lastImportIdMasked", maskImportId(lastImportId))
                                put("region", region)
                            }
                            usersInfo.put(uInfo)
                        }
                    }
                }
                resultJson.put("users", usersInfo)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error parsing userdata.json content as JSON")
                resultJson.put("json_parse_error", e.message)
            }
        }

        val resultStr = resultJson.toString(2)
        Timber.tag(TAG).i(
            "Read userdata complete: userdataExists=%s, size=%d, sha=%s, bakExists=%s, size=%d",
            uSnapshot.exists,
            uSnapshot.size,
            uSnapshot.sha256.take(8),
            bSnapshot.exists,
            bSnapshot.size
        )
        return resultStr
    }

    override fun openUserDataPipe(fileName: String): ParcelFileDescriptor? {
        val safeName = if (fileName == FILE_USERDATA_BAK) FILE_USERDATA_BAK else FILE_USERDATA
        val file = File(CHALDEA_USER_DIR, safeName)
        if (!file.exists() || !file.canRead()) {
            Timber.tag(TAG).e("Cannot stream %s: file not readable or missing", safeName)
            return null
        }
        val bytes = file.readBytes()
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { os ->
                    os.write(bytes)
                    os.flush()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error writing to pipe for %s", safeName)
            }
        }, "UserDataPipeWriter").start()

        Timber.tag(TAG).i("Opened ParcelFileDescriptor pipe for %s (%d bytes)", safeName, bytes.size)
        return readSide
    }

    override fun readUserDataChunk(fileName: String, offset: Int, length: Int): ByteArray? {
        val safeName = if (fileName == FILE_USERDATA_BAK) FILE_USERDATA_BAK else FILE_USERDATA
        val file = File(CHALDEA_USER_DIR, safeName)
        if (!file.exists() || !file.canRead()) {
            return null
        }
        val fileSize = file.length().toInt()
        if (offset < 0 || offset >= fileSize) {
            return ByteArray(0)
        }
        val readLen = minOf(length, fileSize - offset)
        val buffer = ByteArray(readLen)
        FileInputStream(file).use { fis ->
            fis.skip(offset.toLong())
            var total = 0
            while (total < readLen) {
                val r = fis.read(buffer, total, readLen - total)
                if (r == -1) break
                total += r
            }
        }
        return buffer
    }

    override fun rewriteIdentically(): String {
        Timber.tag(TAG).i("Initiating rewriteIdentically...")

        // 1. Verify Chaldea is NOT running
        val pidResult = runShell("pidof cc.narumi.chaldea")
        val pids = pidResult.stdout.trim()
        if (pidResult.exitCode == 0 && pids.isNotEmpty()) {
            val msg = "Refused: Chaldea is currently running (PID $pids). Force-stop Chaldea before rewriting."
            Timber.tag(TAG).w(msg)
            return JSONObject().apply {
                put("success", false)
                put("refusalReason", msg)
            }.toString(2)
        }

        // 2. Verify bytes were cached from E
        val uBytes = cachedUserdataBytes
        val preUSnapshot = cachedUserdataSnapshot
        if (uBytes == null || preUSnapshot == null || !preUSnapshot.exists) {
            val msg = "Refused: userdata.json was not read yet. Perform Read (Action E) first."
            Timber.tag(TAG).w(msg)
            return JSONObject().apply {
                put("success", false)
                put("refusalReason", msg)
            }.toString(2)
        }

        val userdataFile = File(CHALDEA_USER_DIR, FILE_USERDATA)
        if (!userdataFile.exists()) {
            val msg = "Refused: userdata.json does not exist on disk."
            Timber.tag(TAG).w(msg)
            return JSONObject().apply {
                put("success", false)
                put("refusalReason", msg)
            }.toString(2)
        }

        // 3. Overwrite userdata.json in place (open existing, truncate, write)
        try {
            Files.newOutputStream(
                userdataFile.toPath(),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { os ->
                os.write(uBytes)
                os.flush()
            }
            Timber.tag(TAG).i("Overwrote userdata.json in-place with %d bytes", uBytes.size)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to overwrite userdata.json in place")
            return JSONObject().apply {
                put("success", false)
                put("error", "Failed to write userdata.json: ${e.message}")
            }.toString(2)
        }

        // 4. Overwrite userdata.json.bak in place if it existed in E
        val bBytes = cachedBakBytes
        val preBSnapshot = cachedBakSnapshot
        val bakFile = File(CHALDEA_USER_DIR, FILE_USERDATA_BAK)
        var bakOverwritten = false

        if (bBytes != null && preBSnapshot != null && preBSnapshot.exists && bakFile.exists()) {
            try {
                Files.newOutputStream(
                    bakFile.toPath(),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { os ->
                    os.write(bBytes)
                    os.flush()
                }
                bakOverwritten = true
                Timber.tag(TAG).i("Overwrote userdata.json.bak in-place with %d bytes", bBytes.size)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to overwrite userdata.json.bak in place")
                return JSONObject().apply {
                    put("success", false)
                    put("error", "Failed to write userdata.json.bak: ${e.message}")
                }.toString(2)
            }
        }

        // 5. Re-read and verify stats match E identically
        val (postUSnapshot, _) = takeSnapshot(userdataFile)
        val (postBSnapshot, _) = takeSnapshot(bakFile)

        val uMatches = preUSnapshot == postUSnapshot
        val bMatches = if (bakOverwritten) preBSnapshot == postBSnapshot else true

        val allMatch = uMatches && bMatches

        val resultJson = JSONObject().apply {
            put("success", allMatch)
            put("userdataIdentical", uMatches)
            put("bakIdentical", bMatches)
            put("preUserdata", preUSnapshot.toJson())
            put("postUserdata", postUSnapshot.toJson())
            if (preBSnapshot != null && preBSnapshot.exists) {
                put("preBak", preBSnapshot.toJson())
                put("postBak", postBSnapshot.toJson())
            }
        }

        val resultStr = resultJson.toString(2)
        Timber.tag(TAG).i(
            "Rewrite result: success=%s, uMatches=%s, bMatches=%s",
            allMatch,
            uMatches,
            bMatches
        )
        return resultStr
    }

    override fun forceStopChaldea(): String {
        Timber.tag(TAG).i("Force-stopping Chaldea (am force-stop cc.narumi.chaldea)...")
        val res = runShell("am force-stop cc.narumi.chaldea")
        val output = "exitCode=${res.exitCode}, stdout='${res.stdout.trim()}', stderr='${res.stderr.trim()}'"
        Timber.tag(TAG).i("am force-stop completed: %s", output)
        return output
    }
}
