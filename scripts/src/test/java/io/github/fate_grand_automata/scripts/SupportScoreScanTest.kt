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
import io.github.fate_grand_automata.scripts.modules.SupportSelectionLoop
import io.github.fate_grand_automata.scripts.supportSelection.ApiScoreScanFrames
import io.github.fate_grand_automata.scripts.supportSelection.DirectoryScoreScanStorage
import io.github.fate_grand_automata.scripts.supportSelection.EngineDisplay
import io.github.fate_grand_automata.scripts.supportSelection.EngineField
import io.github.fate_grand_automata.scripts.supportSelection.EngineScore
import io.github.fate_grand_automata.scripts.supportSelection.EngineUnknownReason
import io.github.fate_grand_automata.scripts.supportSelection.ISupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanDevice
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanEndReason
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanFolder
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanFrames
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanLog
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanRecord
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanResult
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanStorage
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanTally
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
import kotlinx.serialization.json.JsonNull
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
     *
     * The checks answer as RealSupportScreen's do. isAnyDialogOpen is "the SupportExtra tab is not in
     * its region", so it is true on any other screen ([onSupportScreen] false) as well as under a
     * dialog. someSupportsPresent is a confirm-setup button or a guest in the friend region; a
     * [guestOnly] list has the latter without a confirm-setup button, so it has no rows to find.
     * noSupportsPresent is the list's "not found" text.
     */
    private class World(val total: Int, val perScreen: Int = 3, var guestOnly: Boolean = false) {
        val exitManager = ExitManager()
        var offset = 0
        var onSupportScreen = true
        var dialogOpen = false
        var listLoaded = true
        var snaps = 0
        var inSnap = false
        var currentSnap = -1
        val gestures = mutableListOf<String>()
        val delays = mutableListOf<Duration>()
        val patterns = mutableListOf<FieldPattern>()
        /** 1-based scroll numbers that the game drops: the list does not move. */
        val droppedScrolls = mutableSetOf<Int>()
        var onScroll: (Int) -> Unit = {}
        var onDelay: () -> Unit = {}

        fun visible() = if (guestOnly) emptyList() else (offset until minOf(total, offset + perScreen)).toList()

        private val listShown get() = onSupportScreen && listLoaded

        val screen = object : SupportScreen {
            override fun scrollDown() {
                gestures += "scrollDown"
                val n = gestures.count { it == "scrollDown" }
                if (n !in droppedScrolls) offset = minOf(offset + 2, maxOf(0, total - perScreen))
                onScroll(n)
            }
            override fun scrollToTop() { gestures += "scrollToTop" }
            override fun click(supportClass: SupportClass) { gestures += "click" }
            override fun delay(duration: Duration) {
                delays += duration
                onDelay()
                exitManager.checkExitRequested()
            }
            override fun refresh() { gestures += "refresh" }
            override fun refreshAvailable() = true
            override fun isAnyDialogOpen() = dialogOpen || !onSupportScreen
            override fun noSupportsPresent() = listShown && total == 0 && !guestOnly
            override fun someSupportsPresent() = listShown && (total > 0 || guestOnly)
            override fun isListLoaded() = noSupportsPresent() || someSupportsPresent()
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

    /** Like the real engine, it answers Unavailable from read only once unavailableReason says so. */
    private class Engine(val world: World, val score: (Int) -> EngineScore) : SupportScoreEngine {
        var reason: String? = null
        var asked = 0
        val reads = mutableListOf<Pair<ByteArray, Int>>()
        var onRead: (Int) -> Unit = {}
        var onAsked: () -> Unit = {}
        /** Runs after a read has returned its score: the engine closes between two reads. */
        var onReturned: (Int) -> Unit = {}
        override val unavailableReason: String? get() {
            asked++
            onAsked()
            return reason
        }
        override val loadTime: Duration? = 420.milliseconds
        override fun read(field: Pattern): EngineScore {
            if (reason != null) return EngineScore.unknown(EngineUnknownReason.Unavailable, reason)
            val pixels = field.grayPixels()!!
            reads += pixels to world.currentSnap
            onRead(reads.size)
            if (reason != null) return EngineScore.unknown(EngineUnknownReason.Unavailable, reason)
            val id = (0 until 1000).first { FieldPattern.of(it).grayPixels().contentEquals(pixels) }
            return score(id).also { onReturned(reads.size) }
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
        guestOnly: Boolean = false,
        val server: GameServer = GameServer.Kr,
        var free: Long = Long.MAX_VALUE,
        val maxScreens: Int = SupportScoreScanner.MAX_SCREENS,
        kotlin: (Int) -> SupportScores = ::kotlinScore,
        rust: (Int) -> EngineScore = ::rustScore,
    ) {
        val world = World(total, perScreen, guestOnly)
        val time = TestTimeSource()
        val reader = Reader(world, time, kotlin)
        val engine = Engine(world, rust)
        val root = File(dir, ScoreScanLog.ROOT)
        var storage: ScoreScanStorage = DirectoryScoreScanStorage(root) { free }
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

        val scrolls get() = world.gestures.count { it == "scrollDown" }
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
        // Offsets 0, 2, 4, then two scrolls that leave offset 4 as it was.
        assertThat(result.screens).isEqualTo(3)
        assertThat(result.rows).isEqualTo(9)
        assertThat(scan.world.gestures).containsExactly("scrollDown", "scrollDown", "scrollDown", "scrollDown")
        assertThat(scan.lines(result).size).isEqualTo(9)
        assertThat(scan.summary(result).str("endReason")).isEqualTo("bottom")
    }

    @Test
    fun `every scroll is followed by the settle wait of the support selection loop`() {
        val scan = Scan(total = 7)
        scan.run()
        assertThat(scan.world.delays.size).isEqualTo(scan.scrolls)
        assertThat(scan.world.delays.all { it == SupportSelectionLoop.SCROLL_SETTLE }).isTrue()
        assertThat(SupportSelectionLoop.SCROLL_SETTLE).isEqualTo(300.milliseconds)
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
        assertThat(world.gestures).containsExactly("scrollDown", "scrollDown")
        verify(exactly = 0) { with(api) { any<Location>().click(any()) } }
        verify(exactly = 0) { with(api) { any<Region>().click(any()) } }
        // The field is the score number region of each row: one read screen and two unchanged ones.
        verify(exactly = 3) { with(api) { Region(76 + 1960, 200 + 310, 120, 60).getPattern(any()) } }
    }

    @Test
    fun `both readers read each row once on the snapshot of its screen`() {
        val scan = Scan(total = 5)
        val result = scan.run()

        assertThat(result.screens).isEqualTo(2)
        // Per loop: the screen's snapshot and the look before the scroll. Screens 0 and 2 are read, 4 and 6 unchanged.
        assertThat(scan.world.snaps).isEqualTo(7)
        assertThat(scan.reader.calls.map { it.first }).containsExactly(0, 1, 2, 2, 3, 4)
        assertThat(scan.reader.calls.map { it.third }).containsExactly(0, 0, 0, 2, 2, 2)
        assertThat(scan.engine.reads.map { it.second }).containsExactly(0, 0, 0, 2, 2, 2)
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
    fun `a dialog over the list at the start ends the scan before anything is written`() {
        val scan = Scan(total = 5).apply { world.dialogOpen = true }
        assertNotStarted(scan, scan.run(), ScoreScanEndReason.NotSupportListOrDialog)
    }

    @Test
    fun `another screen at the start reads as the same reason as a dialog`() {
        val scan = Scan(total = 5).apply { world.onSupportScreen = false }
        assertNotStarted(scan, scan.run(), ScoreScanEndReason.NotSupportListOrDialog)
    }

    @Test
    fun `a list that is not loaded ends the scan at once`() {
        val scan = Scan(total = 5).apply { world.listLoaded = false }
        assertNotStarted(scan, scan.run(), ScoreScanEndReason.ListNotLoaded)
    }

    @Test
    fun `a loaded list without supports ends the scan at once`() {
        val scan = Scan(total = 0)
        assertNotStarted(scan, scan.run(), ScoreScanEndReason.NoRows)
    }

    @Test
    fun `a list with only a guest row ends without any gesture`() {
        val scan = Scan(total = 0, guestOnly = true)
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.NoRows)
        assertThat(scan.world.gestures.size).isEqualTo(0)
        assertThat(result.rows).isEqualTo(0)
        assertThat(scan.summary(result).str("endReason")).isEqualTo("no_rows")
    }

    @Test
    fun `an unavailable engine ends the scan with its reason, never with one reader only`() {
        val scan = Scan(total = 5).apply { engine.reason = "this build has no support-score engine" }
        val result = scan.run()
        assertNotStarted(scan, result, ScoreScanEndReason.EngineUnavailable)
        assertThat(result.detail).isEqualTo("this build has no support-score engine")
    }

    @Test
    fun `a stop during the engine's first load ends as the user's stop`() {
        val scan = Scan(total = 5)
        scan.engine.onAsked = { scan.world.exitManager.exit(); scan.engine.reason = "stopped" }
        assertNotStarted(scan, scan.run(), ScoreScanEndReason.UserStopped)
    }

    @Test
    fun `free space below the floor ends the scan before the engine is asked`() {
        val scan = Scan(total = 5, free = ScoreScanLog.MIN_FREE_BYTES - 1)
        assertNotStarted(scan, scan.run(), ScoreScanEndReason.LowSpace)
        assertThat(scan.engine.asked).isEqualTo(0)
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
    fun `a list shorter than one screen is read once and ends after two unchanged screens`() {
        val scan = Scan(total = 2)
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.Bottom)
        assertThat(result.screens).isEqualTo(1)
        assertThat(result.rows).isEqualTo(2)
        assertThat(scan.scrolls).isEqualTo(2)
    }

    @Test
    fun `the real bottom ends after exactly two extra scrolls`() {
        val scan = Scan(total = 9)
        val result = scan.run()
        // Offsets 0, 2, 4, 6 read with three scrolls, then two scrolls that change nothing.
        assertThat(result.screens).isEqualTo(4)
        assertThat(scan.scrolls).isEqualTo(3 + 2)
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.Bottom)
    }

    @Test
    fun `a dropped scroll in the middle of the list neither ends the scan nor logs a row twice`() {
        val scan = Scan(total = 9)
        scan.world.droppedScrolls += 2
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.Bottom)
        assertThat(result.screens).isEqualTo(4)
        assertThat(scan.lines(result).map { it.int("screen") to it.int("row") }.distinct().size).isEqualTo(result.rows)
        assertThat(scan.reader.calls.map { it.first }).containsExactly(0, 1, 2, 2, 3, 4, 4, 5, 6, 6, 7, 8)
    }

    @Test
    fun `the screen bound ends a long scan`() {
        val scan = Scan(total = 100, maxScreens = 4)
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.ScreenLimit)
        assertThat(result.screens).isEqualTo(4)
        assertThat(scan.scrolls).isEqualTo(3)
        assertThat(SupportScoreScanner.MAX_SCREENS).isEqualTo(80)
    }

    @Test
    fun `leaving the support list after a scroll ends the scan without another gesture`() {
        val scan = Scan(total = 20)
        scan.world.onScroll = { if (it == 2) scan.world.onSupportScreen = false }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.NotSupportListOrDialog)
        assertThat(result.screens).isEqualTo(2)
        assertThat(scan.scrolls).isEqualTo(2)
        assertThat(scan.summary(result).str("endReason")).isEqualTo("not_support_list_or_dialog")
    }

    @Test
    fun `supports that vanish from the list after a scroll end the scan without another gesture`() {
        val scan = Scan(total = 20)
        scan.world.onScroll = { scan.world.listLoaded = false }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.RowsLost)
        assertThat(scan.scrolls).isEqualTo(1)
    }

    @Test
    fun `a dialog that opens after a scroll ends the scan without another gesture`() {
        val scan = Scan(total = 20)
        scan.world.onScroll = { scan.world.dialogOpen = true }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.NotSupportListOrDialog)
        assertThat(result.screens).isEqualTo(1)
        assertThat(scan.scrolls).isEqualTo(1)
    }

    @Test
    fun `a stop during the last row of a screen issues no further scroll`() {
        val scan = Scan(total = 20)
        scan.reader.onRead = { if (it == 3) scan.world.exitManager.exit() }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.UserStopped)
        assertThat(result.rows).isEqualTo(3)
        assertThat(scan.scrolls).isEqualTo(0)
    }

    @Test
    fun `leaving the support list while rows are read issues no further scroll`() {
        val scan = Scan(total = 20)
        scan.reader.onRead = { if (it == 2) scan.world.onSupportScreen = false }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.NotSupportListOrDialog)
        assertThat(result.rows).isEqualTo(3)
        assertThat(scan.scrolls).isEqualTo(0)
    }

    @Test
    fun `a dialog that opens while rows are read issues no further scroll`() {
        val scan = Scan(total = 20)
        scan.reader.onRead = { if (it == 3) scan.world.dialogOpen = true }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.NotSupportListOrDialog)
        assertThat(scan.scrolls).isEqualTo(0)
    }

    @Test
    fun `supports that vanish while rows are read issue no further scroll`() {
        val scan = Scan(total = 20)
        scan.reader.onRead = { if (it == 3) scan.world.listLoaded = false }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.RowsLost)
        assertThat(scan.scrolls).isEqualTo(0)
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
        assertThat(scan.scrolls).isEqualTo(1)
    }

    @Test
    fun `a stop in the wait between screens ends the scan before the next screen`() {
        val scan = Scan(total = 20)
        scan.world.onDelay = { scan.world.exitManager.exit() }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.UserStopped)
        assertThat(result.screens).isEqualTo(1)
        // The first screen and the look before its scroll.
        assertThat(scan.world.snaps).isEqualTo(2)
    }

    @Test
    fun `an engine that becomes unavailable ends the scan with its reason`() {
        val scan = Scan(total = 20)
        scan.engine.onReturned = { if (it == 2) scan.engine.reason = "closed" }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.EngineUnavailable)
        assertThat(result.rows).isEqualTo(2)
        assertThat(scan.lines(result).map { it.obj("rust").str("status") }).containsExactly("read", "read")
        assertThat(result.detail).isEqualTo("closed")
        assertThat(scan.summary(result).str("engineUnavailableReason")).isEqualTo("closed")
    }

    @Test
    fun `an engine closed during a read is logged and ends the scan`() {
        val scan = Scan(total = 20)
        scan.engine.onRead = { if (it == 3) scan.engine.reason = "closed" }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.EngineUnavailable)
        assertThat(result.rows).isEqualTo(3)
        assertThat(scan.lines(result)[2].obj("rust").str("status")).isEqualTo("unavailable")
        assertThat(scan.scrolls).isEqualTo(0)
    }

    @Test
    fun `a stop that makes the engine unavailable during a read ends as the user's stop`() {
        val scan = Scan(total = 20)
        scan.engine.onRead = { if (it == 3) { scan.world.exitManager.exit(); scan.engine.reason = "stopped" } }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.UserStopped)
        assertThat(result.rows).isEqualTo(3)
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
    fun `an unexpected failure leaves the lines and crops read so far and a summary, and is rethrown`() {
        val scan = Scan(total = 20)
        scan.engine.onRead = { if (it == 3) throw IllegalStateException("boom") }
        assertFailsWith<IllegalStateException> { scan.run() }
        val folder = scan.root.listFiles()!!.single()
        assertThat(folder.resolve(ScoreScanLog.LINES).readLines().size).isEqualTo(2)
        assertThat(folder.resolve(ScoreScanLog.CROPS).list()!!.sorted()).containsExactly("s000-r0.png", "s000-r1.png")
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

    private fun record(kotlin: SupportScores, rust: EngineScore) = ScoreScanRecord(
        time = 1, server = "Kr", screen = 0, row = 0, bounds = SupportBounds(Region(1, 2, 3, 4)),
        crop = "s000-r0.png", cropSha256 = "00", kotlin = kotlin, kotlinCheckGrand = false, kotlinMicros = 7, rust = rust,
    )

    @Test
    fun `text and detail are escaped and a NaN confidence is written as null`() {
        val text = "a\"b\\c\nd\u0001e 한글"
        val rust = EngineScore(
            EngineField.Unknown(EngineUnknownReason.Format), EngineField.Unknown(EngineUnknownReason.Format),
            EngineDisplay.Unknown, text, Float.NaN, Float.POSITIVE_INFINITY, 1.milliseconds, 900.microseconds, detail = text,
        )
        val line = ScoreScanLog.line(record(SupportScores(), rust))
        assertThat(line.contains('\n')).isFalse()
        val parsed = Json.parseToJsonElement(line).jsonObject.obj("rust")
        assertThat(parsed.str("text")).isEqualTo(text)
        assertThat(parsed.str("detail")).isEqualTo(text)
        assertThat(parsed.getValue("confidence")).isEqualTo(JsonNull)
        assertThat(parsed.getValue("minCharConfidence")).isEqualTo(JsonNull)
    }

    @Test
    fun `an engine error is recorded with its status`() {
        val rust = EngineScore.unknown(EngineUnknownReason.EngineError, "malformed engine reply: magic 0")
        assertThat(ScoreScanLog.status(rust)).isEqualTo("engine_error")
        val parsed = Json.parseToJsonElement(ScoreScanLog.line(record(SupportScores(), rust))).jsonObject.obj("rust")
        assertThat(parsed.str("status")).isEqualTo("engine_error")
        assertThat(parsed.str("classReason")).isEqualTo("engine_error")
        assertThat(parsed.str("detail")).isEqualTo("malformed engine reply: magic 0")
        assertThat(parsed.getValue("micros")).isEqualTo(JsonNull)
    }

    @Test
    fun `the tally counts reads that only one reader or neither made`() {
        val tally = ScoreScanTally()
        val unknown = EngineScore.unknown(EngineUnknownReason.LowConfidence, null)
        tally.add(record(SupportScores(62, display = SupportScoreDisplay.Ordinary), unknown))
        tally.add(record(SupportScores(), unknown))
        tally.add(record(SupportScores(), unknown))
        tally.add(record(SupportScores(), rustScore(12)))
        tally.add(record(SupportScores(62, display = SupportScoreDisplay.Ordinary), rustScore(12)))
        assertThat(listOf(tally.kotlinOnly, tally.rustOnly, tally.bothUnknown, tally.agree, tally.differ))
            .containsExactly(1, 1, 2, 3, 2)
        assertThat(listOf(tally.kotlinClass62, tally.rustClass62)).containsExactly(2, 2)
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
    fun `a folder that cannot be created deletes no older scan`() {
        val scan = Scan(total = 1)
        val old = (1..25).map { "20200101-0000%02d".format(it) }
        old.forEach { File(scan.root, it).mkdirs() }
        val real = scan.storage
        scan.storage = object : ScoreScanStorage by real {
            override fun create(name: String): ScoreScanFolder = throw java.io.IOException("read-only")
        }
        assertFailsWith<java.io.IOException> { scan.run() }
        assertThat(scan.root.list()!!.sorted()).isEqualTo(old)
        assertThat(scan.reader.calls.size).isEqualTo(0)
    }

    @Test
    fun `the storage refuses names that are not scan folders`() {
        val root = File(dir, "refuse")
        File(root, "notes").mkdirs()
        val storage = DirectoryScoreScanStorage(root) { Long.MAX_VALUE }
        assertFailsWith<IllegalArgumentException> { storage.delete("notes") }
        assertFailsWith<IllegalArgumentException> { storage.delete("../refuse") }
        assertFailsWith<IllegalArgumentException> { storage.create("notes") }
        assertThat(File(root, "notes").isDirectory).isTrue()
        assertThat(storage.scanNames()).isEqualTo(emptyList())
    }

    @Test
    fun `a second scan in the same second gets its own folder`() {
        assertThat(ScoreScanLog.newName("20261120-101010", listOf("20261120-101010"))).isEqualTo("20261120-101010-2")
        assertThat(ScoreScanLog.newName("20261120-101010", emptyList())).isEqualTo("20261120-101010")
    }

    @Test
    fun `the end reasons in the summary have their documented names`() {
        assertThat(ScoreScanEndReason.entries.map { it.wireName }).isEqualTo(listOf(
            "not_support_list_or_dialog", "list_not_loaded", "no_rows", "rows_lost",
            "engine_unavailable", "low_space", "user_stopped", "bottom", "screen_limit",
        ))
        assertThat(ScoreScanLog.ERROR).isEqualTo("error")
    }
}
