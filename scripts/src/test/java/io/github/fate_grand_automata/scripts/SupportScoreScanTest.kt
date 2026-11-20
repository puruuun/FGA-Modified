package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.scripts.modules.SupportScreen
import io.github.fate_grand_automata.scripts.supportSelection.ApiScoreScanFrames
import io.github.fate_grand_automata.scripts.supportSelection.DirectoryScoreScanStorage
import io.github.fate_grand_automata.scripts.supportSelection.EngineDisplay
import io.github.fate_grand_automata.scripts.supportSelection.EngineField
import io.github.fate_grand_automata.scripts.supportSelection.EngineScore
import io.github.fate_grand_automata.scripts.supportSelection.EngineUnknownReason
import io.github.fate_grand_automata.scripts.supportSelection.ISupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanDevice
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanEndReason
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanFrames
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanLog
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanResult
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.fate_grand_automata.scripts.supportSelection.SupportBoundsFinder
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreDisplay
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreScanner
import io.github.fate_grand_automata.scripts.supportSelection.SupportScores
import io.github.lib_automata.ExitManager
import io.github.lib_automata.Location
import io.github.lib_automata.Match
import io.github.lib_automata.MeanColor
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.Size
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class SupportScoreScanTest {
    @TempDir
    lateinit var dir: File

    /** A 60x30 gray field; save writes its pixels, as a lossless encoder keeps them. */
    private class FieldPattern(private val pixels: ByteArray) : Pattern {
        override val width = 60
        override val height = 30
        override var tag = ""
        var closed = false
        override fun resize(size: Size): Pattern = throw UnsupportedOperationException()
        override fun resize(target: Pattern, size: Size) = throw UnsupportedOperationException()
        override fun findMatches(template: Pattern, similarity: Double): Sequence<Match> = emptySequence()
        override fun crop(region: Region): Pattern = throw UnsupportedOperationException()
        override fun save(stream: OutputStream) = stream.write(pixels)
        override fun copy(): Pattern = FieldPattern(pixels.copyOf())
        override fun threshold(value: Double): Pattern = throw UnsupportedOperationException()
        override fun isWhite() = false
        override fun isBlack() = false
        override fun floodFill(x: Double, y: Double, maxDiff: Double, newValue: Double): Pattern = throw UnsupportedOperationException()
        override fun fillText(): Pattern = throw UnsupportedOperationException()
        override fun meanColor(): MeanColor? = null
        override fun grayPixels(): ByteArray = pixels.copyOf()
        override fun close() { closed = true }

        companion object {
            fun of(id: Int) = FieldPattern(ByteArray(60 * 30) { (id * 7 + it % 13).toByte() })
        }
    }

    /**
     * A support list of [total] rows, [perScreen] on screen; each scroll moves it by two rows until
     * the last row is on screen, after which a scroll changes nothing (the bottom).
     */
    private class World(val total: Int, val perScreen: Int = 3) {
        val exitManager = ExitManager()
        var offset = 0
        var dialogOpen = false
        var listShown = true
        var listLoaded = true
        var snaps = 0
        var inSnap = false
        var currentSnap = -1
        val gestures = mutableListOf<String>()
        val fieldSnaps = mutableListOf<Int>()
        val patterns = mutableListOf<FieldPattern>()
        var onScroll: (Int) -> Unit = {}
        var onDelay: () -> Unit = {}

        fun visible() = (offset until minOf(total, offset + perScreen)).toList()

        val screen = object : SupportScreen {
            override fun scrollDown() {
                gestures += "scrollDown"
                offset = minOf(offset + 2, maxOf(0, total - perScreen))
                onScroll(gestures.size)
            }
            override fun scrollToTop() { gestures += "scrollToTop" }
            override fun click(supportClass: SupportClass) { gestures += "click" }
            override fun delay(duration: Duration) {
                onDelay()
                exitManager.checkExitRequested()
            }
            override fun refresh() { gestures += "refresh" }
            override fun refreshAvailable() = true
            override fun isAnyDialogOpen() = dialogOpen
            override fun noSupportsPresent() = listLoaded && listShown && total == 0
            override fun someSupportsPresent() = listLoaded && listShown && total > 0
            override fun isListLoaded() = listLoaded && listShown
        }

        val rowsBounds = mutableMapOf<Int, Int>()

        val frames = object : ScoreScanFrames {
            override fun <T> sameSnap(block: () -> T): T {
                currentSnap = snaps++
                inSnap = true
                try { return block() } finally { inSnap = false }
            }
            override fun rows() = visible().mapIndexed { position, id ->
                val bounds = SupportBounds(Region(76, 200 + 440 * position, 2356, 428))
                rowsBounds[bounds.region.y] = id
                bounds
            }
            override fun field(bounds: SupportBounds): Pattern {
                check(inSnap) { "field outside a snapshot" }
                fieldSnaps += currentSnap
                return FieldPattern.of(rowsBounds.getValue(bounds.region.y)).also { patterns += it }
            }
        }
    }

    private class Reader(val world: World, val time: TestTimeSource, val score: (Int) -> SupportScores) : ISupportScoreReader {
        val calls = mutableListOf<Triple<Int, Boolean, Int>>()
        var onRead: (Int) -> Unit = {}
        override fun read(bounds: SupportBounds, checkClass: Boolean, checkGrand: Boolean, screen: Int, row: Int): SupportScores {
            check(checkClass)
            check(world.inSnap) { "Kotlin read outside the snapshot" }
            val id = world.rowsBounds.getValue(bounds.region.y)
            calls += Triple(id, checkGrand, world.currentSnap)
            time += 1500.microseconds
            onRead(calls.size)
            return score(id)
        }
    }

    private class Engine(val world: World, val score: (Int) -> EngineScore) : SupportScoreEngine {
        var reason: String? = null
        val reads = mutableListOf<Pair<ByteArray, Int>>()
        var onRead: (Int) -> Unit = {}
        override val unavailableReason get() = reason
        override val loadTime: Duration? = 420.milliseconds
        override fun read(field: Pattern): EngineScore {
            if (reason != null) return EngineScore.unknown(EngineUnknownReason.Unavailable, reason)
            val pixels = field.grayPixels()!!
            reads += pixels to world.currentSnap
            onRead(reads.size)
            val id = (0 until 1000).first { FieldPattern.of(it).grayPixels().contentEquals(pixels) }
            return score(id)
        }
    }

    private fun kotlinScore(id: Int) = SupportScores(classScore = 50 + id, display = SupportScoreDisplay.Ordinary)

    private fun rustScore(id: Int) = EngineScore(
        classScore = EngineField.Value(50 + id),
        grandScore = EngineField.Unknown(EngineUnknownReason.NotShown),
        display = EngineDisplay.Ordinary,
        text = "+${50 + id}",
        confidence = 0.97f,
        minCharConfidence = 0.9f,
        duration = 5.milliseconds,
        engineTime = 4000.microseconds,
    )

    private inner class Scan(
        total: Int,
        perScreen: Int = 3,
        val server: GameServer = GameServer.Kr,
        var free: Long = Long.MAX_VALUE,
        val maxScreens: Int = SupportScoreScanner.MAX_SCREENS,
        kotlin: (Int) -> SupportScores = ::kotlinScore,
        rust: (Int) -> EngineScore = ::rustScore,
    ) {
        val world = World(total, perScreen)
        val time = TestTimeSource()
        val reader = Reader(world, time, kotlin)
        val engine = Engine(world, rust)
        val root = File(dir, ScoreScanLog.ROOT)
        val storage = DirectoryScoreScanStorage(root) { free }
        var now = 1_790_000_000_000L
        val device = ScoreScanDevice("1.9.0-beta.1-rust.1", "Test Phone", "15 (API 35)")

        fun run(): ScoreScanResult = SupportScoreScanner(
            screen = world.screen,
            frames = world.frames,
            reader = reader,
            engine = engine,
            storage = storage,
            device = device,
            exitManager = world.exitManager,
            server = server,
            clock = { now.also { now += 10 } },
            timeSource = time,
            maxScreens = maxScreens,
        ).run()

        fun folder(result: ScoreScanResult) = File(root, result.folder!!)
        fun lines(result: ScoreScanResult) = File(folder(result), ScoreScanLog.LINES).readLines().map { Json.parseToJsonElement(it).jsonObject }
        fun summary(result: ScoreScanResult) = Json.parseToJsonElement(File(folder(result), ScoreScanLog.SUMMARY).readText()).jsonObject
    }

    private fun JsonObject.str(key: String) = (get(key) as JsonPrimitive).content
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.int(key: String) = str(key).toInt()

    @Test
    fun `a whole scan issues only scroll gestures and ends at the bottom of the list`() {
        val scan = Scan(total = 7)
        val result = scan.run()

        assertThat(result.reason).isEqualTo(ScoreScanEndReason.Bottom)
        // Offsets 0, 2, 4; the fourth snapshot shows offset 4 again.
        assertThat(result.screens).isEqualTo(3)
        assertThat(result.rows).isEqualTo(9)
        assertThat(scan.world.gestures).containsExactly("scrollDown", "scrollDown", "scrollDown")
        assertThat(scan.lines(result).size).isEqualTo(9)
        assertThat(scan.summary(result).str("endReason")).isEqualTo("bottom")
    }

    @Test
    fun `the scan never reaches a click on the automation api`() {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val finder = mockk<SupportBoundsFinder>()
        val bounds = listOf(SupportBounds(Region(76, 200, 2356, 428)), SupportBounds(Region(76, 640, 2356, 428)))
        every { finder.all() } returns bounds
        every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        every { with(api) { any<Region>().getPattern(any()) } } answers { FieldPattern.of(firstArg<Region>().y) }
        val world = World(total = 2)
        val engine = Engine(world) { rustScore(it) }
        val reader = object : ISupportScoreReader {
            override fun read(bounds: SupportBounds, checkClass: Boolean, checkGrand: Boolean, screen: Int, row: Int) = SupportScores()
        }
        val result = SupportScoreScanner(
            world.screen, ApiScoreScanFrames(api, finder), reader, engine,
            DirectoryScoreScanStorage(File(dir, "api")) { Long.MAX_VALUE },
            ScoreScanDevice("v", "m", "a"), world.exitManager, GameServer.Kr,
        ).run()

        assertThat(result.reason).isEqualTo(ScoreScanEndReason.Bottom)
        assertThat(world.gestures).containsExactly("scrollDown")
        verify(exactly = 0) { with(api) { any<Location>().click(any()) } }
        verify(exactly = 0) { with(api) { any<Region>().click(any()) } }
        // The field is the score number region of each row.
        verify(exactly = 2) { with(api) { Region(76 + 1960, 200 + 310, 120, 60).getPattern(any()) } }
    }

    @Test
    fun `one snapshot per screen, and both readers read each row once on that snapshot`() {
        val scan = Scan(total = 5)
        val result = scan.run()

        assertThat(result.screens).isEqualTo(2)
        // Two screens read, one more that showed the bottom.
        assertThat(scan.world.snaps).isEqualTo(3)
        assertThat(scan.reader.calls.map { it.first }).containsExactly(0, 1, 2, 2, 3, 4)
        assertThat(scan.reader.calls.map { it.third }).containsExactly(0, 0, 0, 1, 1, 1)
        assertThat(scan.engine.reads.map { it.second }).containsExactly(0, 0, 0, 1, 1, 1)
        val lines = scan.lines(result)
        for ((i, line) in lines.withIndex()) {
            val crop = File(scan.folder(result), line.str("crop")).readBytes()
            assertThat(crop.contentEquals(scan.engine.reads[i].first)).isTrue()
            assertThat(line.str("cropSha256")).isEqualTo(ScoreScanLog.sha256(crop))
        }
        assertThat(scan.world.patterns.all { it.closed }).isTrue()
    }

    @Test
    fun `Grand is read where the server shows it`() {
        val kr = Scan(total = 1).also { it.run() }
        assertThat(kr.reader.calls.map { it.second }).containsExactly(false)
        val jp = Scan(total = 1, server = GameServer.Jp.Original).also { it.run() }
        assertThat(jp.reader.calls.map { it.second }).containsExactly(true)
    }

    private fun assertNotStarted(scan: Scan, result: ScoreScanResult, reason: ScoreScanEndReason) {
        assertThat(result.reason).isEqualTo(reason)
        assertThat(result.folder).isNull()
        assertThat(scan.world.gestures.size).isEqualTo(0)
        assertThat(scan.reader.calls.size).isEqualTo(0)
        assertThat(scan.engine.reads.size).isEqualTo(0)
        assertThat(scan.root.exists()).isFalse()
    }

    @Test
    fun `a dialog at the start ends the scan before anything is written`() {
        val scan = Scan(total = 5).apply { world.dialogOpen = true }
        assertNotStarted(scan, scan.run(), ScoreScanEndReason.DialogOpen)
    }

    @Test
    fun `a list that is not loaded ends the scan at once`() {
        val scan = Scan(total = 5).apply { world.listLoaded = false }
        assertNotStarted(scan, scan.run(), ScoreScanEndReason.NotSupportList)
    }

    @Test
    fun `a loaded list without supports ends the scan at once`() {
        val scan = Scan(total = 0)
        assertNotStarted(scan, scan.run(), ScoreScanEndReason.NoRows)
    }

    @Test
    fun `an unavailable engine ends the scan with its reason, never with one reader only`() {
        val scan = Scan(total = 5).apply { engine.reason = "this build has no support-score engine" }
        val result = scan.run()
        assertNotStarted(scan, result, ScoreScanEndReason.EngineUnavailable)
        assertThat(result.detail).isEqualTo("this build has no support-score engine")
    }

    @Test
    fun `free space below the floor ends the scan before writing`() {
        val scan = Scan(total = 5, free = ScoreScanLog.MIN_FREE_BYTES - 1)
        assertNotStarted(scan, scan.run(), ScoreScanEndReason.LowSpace)
    }

    @Test
    fun `free space that drops below the floor during the scan ends it with a summary`() {
        val scan = Scan(total = 9)
        scan.world.onScroll = { scan.free = ScoreScanLog.MIN_FREE_BYTES - 1 }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.LowSpace)
        assertThat(result.screens).isEqualTo(1)
        assertThat(scan.summary(result).str("endReason")).isEqualTo("low_space")
    }

    @Test
    fun `a list shorter than one screen is read once`() {
        val scan = Scan(total = 2)
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.Bottom)
        assertThat(result.screens).isEqualTo(1)
        assertThat(result.rows).isEqualTo(2)
        assertThat(scan.world.gestures).containsExactly("scrollDown")
    }

    @Test
    fun `the screen bound ends a long scan`() {
        val scan = Scan(total = 100, maxScreens = 4)
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.ScreenLimit)
        assertThat(result.screens).isEqualTo(4)
        assertThat(scan.world.gestures.size).isEqualTo(3)
        assertThat(SupportScoreScanner.MAX_SCREENS).isEqualTo(80)
    }

    @Test
    fun `leaving the support list ends the scan without another gesture`() {
        val scan = Scan(total = 20)
        scan.world.onScroll = { if (it == 2) scan.world.listShown = false }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.ScreenLost)
        assertThat(result.screens).isEqualTo(2)
        assertThat(scan.world.gestures.size).isEqualTo(2)
        assertThat(scan.summary(result).str("endReason")).isEqualTo("screen_lost")
    }

    @Test
    fun `a dialog that opens during the scan ends it without another gesture`() {
        val scan = Scan(total = 20)
        scan.world.onScroll = { scan.world.dialogOpen = true }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.DialogOpen)
        assertThat(result.screens).isEqualTo(1)
        assertThat(scan.world.gestures.size).isEqualTo(1)
    }

    @Test
    fun `a stop between rows ends the scan with the rows read so far and a summary`() {
        val scan = Scan(total = 20)
        scan.reader.onRead = { if (it == 4) scan.world.exitManager.exit() }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.UserStopped)
        assertThat(result.rows).isEqualTo(4)
        assertThat(scan.lines(result).size).isEqualTo(4)
        assertThat(scan.summary(result).str("endReason")).isEqualTo("user_stopped")
        assertThat(scan.summary(result).int("rows")).isEqualTo(4)
        assertThat(scan.world.gestures.size).isEqualTo(1)
    }

    @Test
    fun `a stop between screens ends the scan before the next screen`() {
        val scan = Scan(total = 20)
        scan.world.onDelay = { scan.world.exitManager.exit() }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.UserStopped)
        assertThat(result.screens).isEqualTo(1)
        assertThat(scan.world.snaps).isEqualTo(1)
    }

    @Test
    fun `an engine that becomes unavailable ends the scan with its reason`() {
        val scan = Scan(total = 20)
        scan.engine.onRead = { if (it == 2) scan.engine.reason = "closed" }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.EngineUnavailable)
        assertThat(result.rows).isEqualTo(2)
        assertThat(result.detail).isEqualTo("closed")
        assertThat(scan.summary(result).str("engineUnavailableReason")).isEqualTo("closed")
    }

    @Test
    fun `a read the engine answers as unavailable is logged and ends the scan`() {
        val scan = Scan(total = 20)
        var reads = 0
        scan.reader.onRead = { reads = it }
        val unavailable = object : SupportScoreEngine by scan.engine {
            override fun read(field: Pattern) = if (reads == 3) EngineScore.unknown(EngineUnknownReason.Unavailable, "gone") else scan.engine.read(field)
        }
        val result = SupportScoreScanner(
            scan.world.screen, scan.world.frames, scan.reader, unavailable, scan.storage, scan.device,
            scan.world.exitManager, GameServer.Kr,
        ).run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.EngineUnavailable)
        assertThat(result.rows).isEqualTo(3)
        assertThat(scan.lines(result)[2].obj("rust").str("status")).isEqualTo("unavailable")
    }

    @Test
    fun `each line is on disk before the next row is read`() {
        val scan = Scan(total = 5)
        val seen = mutableListOf<Int>()
        scan.reader.onRead = {
            val file = scan.root.listFiles()!!.single().resolve(ScoreScanLog.LINES)
            seen += if (file.exists()) file.readLines().size else 0
        }
        scan.run()
        assertThat(seen).containsExactly(0, 1, 2, 3, 4, 5)
    }

    @Test
    fun `an unexpected failure leaves the lines read so far and a summary, and is rethrown`() {
        val scan = Scan(total = 20)
        scan.engine.onRead = { if (it == 3) throw IllegalStateException("boom") }
        assertFailsWith<IllegalStateException> { scan.run() }
        val folder = scan.root.listFiles()!!.single()
        assertThat(folder.resolve(ScoreScanLog.LINES).readLines().size).isEqualTo(2)
        val summary = Json.parseToJsonElement(folder.resolve(ScoreScanLog.SUMMARY).readText()).jsonObject
        assertThat(summary.str("endReason")).isEqualTo("error")
        assertThat(summary.int("rows")).isEqualTo(2)
        assertThat(summary.str("detail")).isEqualTo("java.lang.IllegalStateException: boom")
    }

    @Test
    fun `the lines and the summary of a small scan, field by field`() {
        val scan = Scan(
            total = 2,
            server = GameServer.Jp.Original,
            kotlin = { id -> if (id == 0) SupportScores(62, 16, SupportScoreDisplay.Compound) else SupportScores() },
            rust = { id ->
                if (id == 0) EngineScore(
                    EngineField.Value(62), EngineField.Value(16), EngineDisplay.Compound, "+62/+16", 0.98f, 0.91f,
                    5.milliseconds, 3500.microseconds
                ) else EngineScore(
                    EngineField.Value(41), EngineField.Unknown(EngineUnknownReason.NotShown), EngineDisplay.Ordinary, "+41", 0.95f, 0.9f,
                    6.milliseconds, 4500.microseconds
                )
            },
        )
        val result = scan.run()
        val lines = scan.lines(result)
        assertThat(lines.size).isEqualTo(2)
        val crop0 = File(scan.folder(result), "crops/s000-r0.png").readBytes()

        fun json(text: String): JsonElement = Json.parseToJsonElement(text)
        assertThat(lines[0]).isEqualTo(json("""
            {"schema":1,"time":1790000000010,"server":"Jp","screen":0,"row":0,
             "bounds":{"x":76,"y":200,"w":2356,"h":428},"crop":"crops/s000-r0.png","cropSha256":"${ScoreScanLog.sha256(crop0)}",
             "kotlin":{"classScore":62,"grandScore":16,"display":"compound","checkGrand":true,"micros":1500},
             "rust":{"status":"read","classScore":62,"grandScore":16,"display":"compound","classReason":null,"grandReason":null,
                     "confidence":0.98,"minCharConfidence":0.91,"text":"+62/+16","detail":null,"micros":3500,"callMicros":5000},
             "agree":true}
        """))
        assertThat(lines[1].obj("kotlin")).isEqualTo(json("""{"classScore":null,"grandScore":null,"display":"unknown","checkGrand":true,"micros":1500}"""))
        assertThat(lines[1].obj("rust").str("grandReason")).isEqualTo("not_shown")
        assertThat(lines[1].obj("rust").str("classScore")).isEqualTo("41")
        assertThat(lines[1].str("agree")).isEqualTo("false")
        assertThat(lines[1].str("crop")).isEqualTo("crops/s000-r1.png")

        assertThat(scan.summary(result)).isEqualTo(json("""
            {"schema":1,"appVersion":"1.9.0-beta.1-rust.1","deviceModel":"Test Phone","androidVersion":"15 (API 35)",
             "server":"Jp","folder":"support-score-scan/${result.folder}","startTime":1790000000000,"endTime":1790000000030,
             "endReason":"bottom","detail":null,"screens":1,"rows":2,
             "counts":{"agree":1,"differ":1,"kotlinOnly":0,"rustOnly":1,"bothUnknown":0,
                       "kotlinClass62":1,"rustClass62":1,"kotlinGrand16":1,"rustGrand16":1},
             "engineLoadMicros":420000,
             "kotlinMicros":{"count":2,"median":1500,"max":1500},
             "rustMicros":{"count":2,"median":3500,"max":4500},
             "engineUnavailableReason":null}
        """))
        assertThat(result.folder).isEqualTo(SupportScoreScanner.stamp(1_790_000_000_000L))
    }

    @Test
    fun `agreement treats an unknown as equal only to an unknown`() {
        val unknownRust = EngineScore.unknown(EngineUnknownReason.LowConfidence, null)
        assertThat(ScoreScanLog.agree(SupportScores(), unknownRust)).isTrue()
        assertThat(ScoreScanLog.agree(SupportScores(0, display = SupportScoreDisplay.Unknown), unknownRust)).isFalse()
        assertThat(ScoreScanLog.agree(SupportScores(62, display = SupportScoreDisplay.Ordinary), rustScore(12))).isTrue()
        assertThat(ScoreScanLog.agree(SupportScores(62, display = SupportScoreDisplay.Compound), rustScore(12))).isFalse()
    }

    @Test
    fun `at most twenty scan folders are kept, the newest, and nothing else is touched`() {
        val scan = Scan(total = 1)
        val old = (1..25).map { "20200101-0000%02d".format(it) }
        old.forEach { File(scan.root, it).mkdirs() }
        File(scan.root, "notes").mkdirs()
        File(scan.root, "readme.txt").writeText("x")
        val result = scan.run()

        val scans = scan.root.listFiles()!!.filter { it.isDirectory && ScoreScanLog.isScanName(it.name) }.map { it.name }.sorted()
        assertThat(scans).isEqualTo(old.takeLast(19) + result.folder!!)
        assertThat(File(scan.root, "notes").isDirectory).isTrue()
        assertThat(File(scan.root, "readme.txt").exists()).isTrue()
    }

    @Test
    fun `a second scan in the same second gets its own folder`() {
        assertThat(ScoreScanLog.newName("20261120-101010", listOf("20261120-101010"))).isEqualTo("20261120-101010-2")
        assertThat(ScoreScanLog.newName("20261120-101010", emptyList())).isEqualTo("20261120-101010")
    }

    @Test
    fun `the end reasons in the summary have their documented names`() {
        assertThat(ScoreScanEndReason.entries.map { it.wireName }).isEqualTo(listOf(
            "not_support_list", "no_rows", "dialog_open", "engine_unavailable", "low_space", "user_stopped",
            "bottom", "screen_limit", "screen_lost", "error",
        ))
    }
}
