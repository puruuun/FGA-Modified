package io.github.fate_grand_automata.scripts.weekly

import io.github.fate_grand_automata.scripts.util.replaceFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Official Atlas Academy data only (no Chaldea data): the regional mission sets and the main-story war and quest phase
 * data (design 0009 D-1, D-2, D-11). The raw downloads are about 50 MB, so only the compact computed dataset is kept
 * per server and week; the raw files are deleted once it is written. KR uses the KR quest list with the enemy
 * composition of the JP quest of the same ID (D-11); quests missing in JP or with a different AP are dropped and logged.
 */
class AtlasRouteRepository(
    private val cache: File,
    /** Writes the response body of an Atlas path into the target file. */
    private val fetch: (path: String, target: File) -> Unit = ::download,
    private val log: (String) -> Unit = {},
    private val progress: (stage: String, done: Int, total: Int) -> Unit = { _, _, _ -> }
) {
    data class Dataset(val server: String, val week: AtlasWeekly.Week, val quests: List<RouteQuest>,
        val downloadMillis: Long, val mismatched: List<Int>, val missing: List<Int>, val wars: Map<Int, WarInfo> = emptyMap(), val questNames: Set<String> = emptySet())

    @Serializable
    private class Saved(val week: Int, val quests: List<RouteQuest>, val millis: Long, val mismatched: List<Int>, val missing: List<Int>, val wars: Map<Int, WarInfo> = emptyMap(), val questNames: Set<String> = emptySet())

    private val json = Json { ignoreUnknownKeys = true }

    fun cached(server: String, now: Long = System.currentTimeMillis() / 1000): Dataset? = runCatching {
        val week = AtlasWeekly.activeWeek(File(cache, "$server-missions.json").readText(), now)
        val it = json.decodeFromString<Saved>(File(cache, "$server-dataset7-${week.id}.json").readText())
        Dataset(server, week, it.quests, it.millis, it.mismatched, it.missing, it.wars, it.questNames)
    }.getOrNull()

    @Serializable private class SideWeek(val id: Int, val start: Long, val end: Long, val missionIds: List<Int>)

    /**
     * What [weekAt] has learned from one state of `<server>-missions.json`, keyed by that file's size and mtime. The
     * master is a few MB of JSON (about 2 MB for KR and 4 MB for JP) that parses into a tree about ten times that size, so it is parsed only when this file cannot answer. [missed] holds snapshot times no
     * weekly set covers.
     */
    @Serializable private class Side(val size: Long, val mtime: Long, val weeks: List<SideWeek> = emptyList(), val missed: List<Long> = emptyList())

    /** A download that failed or did not cover [snapshot], so the same snapshot is not tried again for a day. */
    @Serializable private class Attempt(val at: Long, val snapshot: Long)

    private fun SideWeek.toWeek() = AtlasWeekly.Week(id, start, end, missionIds.map { RouteMission(it, "", 1, MissionKind.Unsupported) })

    private fun readSide(file: File, master: File): Side {
        val fresh = Side(master.length(), master.lastModified())
        val side = runCatching { json.decodeFromString<Side>(file.readText()) }.getOrNull() ?: return fresh
        return if (side.size == fresh.size && side.mtime == fresh.mtime) side else fresh
    }

    private fun writeSmall(target: File, text: String) = runCatching {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text)
        replace(temp, target)
    }

    /** Answers from the side file when it can and parses the master once otherwise. */
    private fun weekFromMaster(server: String, master: File, at: Long): AtlasWeekly.Week? {
        val sideFile = File(cache, "$server-missions-view.json")
        val side = readSide(sideFile, master)
        side.weeks.firstOrNull { at >= it.start && at < it.end }?.let { return it.toWeek() }
        if (at in side.missed) return null
        val week = runCatching { AtlasWeekly.activeWeek(master.readText(), at) }.getOrNull()
        val updated = if (week != null) {
            side.weeks + SideWeek(week.id, week.start, week.end, week.missions.map { it.id })
        } else side.weeks
        val missed = if (week == null) side.missed + at else side.missed
        writeSmall(sideFile, json.encodeToString(Side(side.size, side.mtime, updated.takeLast(4), missed.takeLast(8))))
        return week
    }

    /**
     * The weekly mission set that was active at [at], for display only: no quest data is loaded. Uses the same
     * `<server>-missions.json` cache as [load]; a missing or outdated file is downloaded only when [download] is set,
     * and not again for the same [at] within a day after an attempt that failed or did not cover it. The download goes
     * to its own temporary file because [load] may be downloading the same list at the same time.
     * Returns null when the list cannot be read or no unique weekly set covers [at].
     */
    fun weekAt(server: String, at: Long, download: Boolean, now: Long = System.currentTimeMillis() / 1000): AtlasWeekly.Week? {
        require(server in setOf("JP", "KR")) { "unsupported weekly server" }
        val missionFile = File(cache, "$server-missions.json")
        if (missionFile.isFile) weekFromMaster(server, missionFile, at)?.let { return it }
        if (!download) return null
        // Two dashboard passes can overlap (leaving the screen and coming back), and both would write the same part file.
        return synchronized(downloadLock(server)) { downloadWeek(server, at, now, missionFile) }
    }

    private fun downloadWeek(server: String, at: Long, now: Long, missionFile: File): AtlasWeekly.Week? {
        // A pass that waited for the lock finds the list the first one stored.
        if (missionFile.isFile) weekFromMaster(server, missionFile, at)?.let { return it }
        val attemptFile = File(cache, "$server-missions-attempt.json")
        val last = runCatching { json.decodeFromString<Attempt>(attemptFile.readText()) }.getOrNull()
        if (last != null && last.snapshot == at && now - last.at in 0 until RETRY_SECONDS) return null
        cache.mkdirs()
        val fresh = File(cache, "$server-missions.dashboard.part")
        return try {
            fetch("export/$server/nice_master_mission.json", fresh)
            val week = AtlasWeekly.activeWeek(fresh.readText(), at)
            replace(fresh, missionFile)
            attemptFile.delete()
            // The new file is already parsed, so its side file is written from that result.
            writeSmall(File(cache, "$server-missions-view.json"), json.encodeToString(
                Side(missionFile.length(), missionFile.lastModified(), listOf(SideWeek(week.id, week.start, week.end, week.missions.map { it.id })))))
            week
        } catch (_: Exception) {
            writeSmall(attemptFile, json.encodeToString(Attempt(now, at)))
            null
        } finally {
            fresh.delete()
        }
    }

    fun load(server: String, now: Long = System.currentTimeMillis() / 1000): Dataset {
        require(server in setOf("JP", "KR")) { "unsupported weekly server" }
        progress("missions", 0, 1)
        val start = System.nanoTime()
        cache.mkdirs()
        val missionFile = File(cache, "$server-missions.json")
        val cached = missionFile.takeIf { it.exists() }?.let {
            runCatching { AtlasWeekly.activeWeek(it.readText(), now) }.getOrNull()
        }
        val week = cached ?: run {
            val fresh = File(cache, "$server-missions.download")
            fetch("export/$server/nice_master_mission.json", fresh)
            val raw = fresh.readText()
            AtlasWeekly.activeWeek(raw, now).also { replace(fresh, missionFile) }
        }

        val saved = File(cache, "$server-dataset7-${week.id}.json")
        saved.takeIf { it.exists() }?.let { file ->
            runCatching { json.decodeFromString<Saved>(file.readText()) }.getOrNull()?.let {
                log("load server=$server week=${week.id} cached quests=${it.quests.size} mismatched=${it.mismatched.size} missing=${it.missing.size}")
                return Dataset(server, week, it.quests, it.millis, it.mismatched, it.missing, it.wars, it.questNames)
            }
        }

        val dir = File(cache, "$server-raw-${week.id}").apply { mkdirs() }
        try {
            fun file(path: String): File = File(dir, path.replace('/', '_')).also { if (!it.exists()) fetchAtomic(path, it) }
            fun wars(region: String) = file("export/$region/nice_war.json").inputStream().buffered().use { AtlasWeekly.wars(it, now, log) }
            progress("chapters", 0, 1)
            val regionalWars = wars(server)
            val regional = regionalWars.quests
            val jpById = (if (server == "JP") regional else wars("JP").quests).associateBy { it.id }
            val mismatch = mutableListOf<Int>()
            val missing = mutableListOf<Int>()
            val pool = Executors.newFixedThreadPool(8)
            val quests = try {
                val candidates = regional.filter { it.warId !in WeeklyRouteSolver.EXCLUDED_WARS }
                val completed = java.util.concurrent.atomic.AtomicInteger()
                progress("quests", 0, candidates.size)
                val futures = candidates.map { q ->
                    pool.submit(Callable {
                        val source = jpById[q.id]
                        val parsedQuest = when {
                            source == null -> { synchronized(missing) { missing += q.id }; log("drop quest=${q.id} reason=jpMissing"); null }
                            source.ap != q.ap -> {
                                synchronized(mismatch) { mismatch += q.id }
                                log("drop quest=${q.id} reason=apMismatch regional=${q.ap} jp=${source.ap}"); null
                            }
                            else -> {
                                require(source.repeatPhase > 0) { "missing repeat phase ${q.id}" }
                                val parsed = AtlasWeekly.phase(q, file("nice/JP/quest/${q.id}/${source.repeatPhase}").readText())
                                if (!parsed.enemyDataAvailable) {
                                    synchronized(missing) { missing += q.id }; log("drop quest=${q.id} reason=enemyDataMissing"); null
                                } else parsed
                            }
                        }
                        progress("quests", completed.incrementAndGet(), candidates.size)
                        parsedQuest
                    })
                }
                futures.mapNotNull { it.get() }
            } finally { pool.shutdownNow() }
            val millis = (System.nanoTime() - start) / 1_000_000
            log("load server=$server week=${week.id} quests=${quests.size} mismatched=${mismatch.size} missing=${missing.size} ms=$millis")
            val warInfo = regionalWars.wars.filterKeys { id -> quests.any { it.warId == id } }
            val result = Dataset(server, week, quests, millis, mismatch.sorted(), missing.sorted(), warInfo, regional.map { it.name }.toSet())
            val text = json.encodeToString(Saved(week.id, quests, millis, result.mismatched, result.missing, warInfo, result.questNames))
            val temp = File(cache, saved.name + ".tmp").apply { writeText(text) }
            replace(temp, saved)
            // Only the current week's compact dataset is kept.
            cache.listFiles { f -> f.name.startsWith("$server-dataset") && f != saved }?.forEach { it.delete() }
            return result
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun fetchAtomic(path: String, target: File) {
        val temp = File(target.parentFile, target.name + ".part")
        fetch(path, temp)
        replace(temp, target)
    }

    companion object {
        private const val RETRY_SECONDS = 24L * 3600
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private fun downloadLock(server: String): Any = locks.getOrPut(server) { Any() }

        private fun replace(temp: File, target: File) {
            replaceFile(temp, target)
        }

        private fun download(path: String, target: File) {
            require(!path.contains("..") && Regex("[A-Za-z0-9_./]+").matches(path))
            val connection = URL("https://api.atlasacademy.io/$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "FGA-Modified weekly-route/1")
            // The war export is 25 MB plain and 1.5 MB gzipped; asking for gzip is what makes this feasible on a phone.
            connection.setRequestProperty("Accept-Encoding", "gzip")
            try {
                check(connection.responseCode == 200) { "Atlas HTTP ${connection.responseCode}" }
                val gzip = connection.contentEncoding.equals("gzip", ignoreCase = true)
                val source = if (gzip) GZIPInputStream(connection.inputStream) else connection.inputStream
                source.use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= 64L * 1024 * 1024) { "Atlas response too large" }
                            out.write(buffer, 0, n)
                        }
                    }
                }
            } finally { connection.disconnect() }
        }
    }
}
