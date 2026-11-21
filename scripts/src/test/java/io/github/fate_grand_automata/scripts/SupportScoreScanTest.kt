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
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNative
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeCapture
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeFrames
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeMap
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
import io.github.lib_automata.Transformer
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
            override fun fieldImageRegion(bounds: SupportBounds) =
                if (position(bounds) == outsidePosition) Region(1250, 0, 60, 30) else fieldImage(position(bounds))
        }

        /** A row position whose field the frames place partly outside the display, as a wrong geometry would. */
        var outsidePosition: Int? = null

        fun position(bounds: SupportBounds) = (bounds.region.y - 200) / 440

        /**
         * What the display shows now at 720p: a textured background with each visible row's field where
         * [fieldImage] says, so a 2x frame of it area-averages back to the pixels the engine read.
         */
        fun screen720(): ByteArray {
            val pixels = ByteArray(1280 * 720) { ((it % 1280) * 3 + (it / 1280) * 5).mod(251).toByte() }
            for ((position, id) in visible().withIndex()) {
                val field = FieldPattern.of(id).grayPixels()
                val at = fieldImage(position)
                for (y in 0 until 30) for (x in 0 until 60) {
                    pixels[(at.y + y) * 1280 + at.x + x] = field[y * 60 + x]
                }
            }
            return pixels
        }

        companion object {
            /** 720p image coordinates inside the game area; a 2560x1440 display, so the frame is 2x. */
            fun fieldImage(position: Int) = Region(1018, 100 + 200 * position, 60, 30)
        }
    }

    /** A gray image whose crop, resize (area average), save and pixels behave like the app's. */
    private class GrayPattern(override val width: Int, override val height: Int, val pixels: ByteArray) : Pattern {
        override var tag = ""
        var closed = false
        override fun resize(size: Size): Pattern {
            val out = ByteArray(size.width * size.height)
            for (ty in 0 until size.height) for (tx in 0 until size.width) {
                val x0 = tx * width / size.width
                val x1 = maxOf(x0 + 1, (tx + 1) * width / size.width)
                val y0 = ty * height / size.height
                val y1 = maxOf(y0 + 1, (ty + 1) * height / size.height)
                var sum = 0
                for (y in y0 until y1) for (x in x0 until x1) sum += pixels[y * width + x].toInt() and 0xff
                out[ty * size.width + tx] = (sum / ((x1 - x0) * (y1 - y0))).toByte()
            }
            return GrayPattern(size.width, size.height, out)
        }
        override fun resize(target: Pattern, size: Size) = throw UnsupportedOperationException()
        override fun findMatches(template: Pattern, similarity: Double): Sequence<Match> = emptySequence()
        override fun crop(region: Region): Pattern {
            val clip = Region(0, 0, width, height).clip(region)
            val out = ByteArray(clip.width * clip.height)
            for (y in 0 until clip.height) System.arraycopy(pixels, (clip.y + y) * width + clip.x, out, y * clip.width, clip.width)
            return GrayPattern(clip.width, clip.height, out)
        }
        override fun save(stream: OutputStream) {
            stream.write(byteArrayOf((width shr 8).toByte(), width.toByte(), (height shr 8).toByte(), height.toByte()))
            stream.write(pixels)
        }
        override fun copy(): Pattern = GrayPattern(width, height, pixels.copyOf())
        override fun threshold(value: Double): Pattern = throw UnsupportedOperationException()
        override fun isWhite() = false
        override fun isBlack() = false
        override fun floodFill(x: Double, y: Double, maxDiff: Double, newValue: Double): Pattern = throw UnsupportedOperationException()
        override fun fillText(): Pattern = throw UnsupportedOperationException()
        override fun meanColor(): MeanColor? = null
        override fun grayPixels(): ByteArray = pixels.copyOf()
        override fun close() { closed = true }
    }

    /**
     * The full-display port as HolderScoreScanNativeFrames answers it: a 2560x1440 frame of what the
     * world shows when it is asked, or the failures that class returns. Every call is recorded with
     * what was on disk and how many snapshots and gestures had happened.
     */
    private class Native(val world: World, val time: TestTimeSource, val linesFile: () -> File?) : ScoreScanNativeFrames {
        data class Call(val rowLines: Int, val nativeLines: Int, val snaps: Int, val gestures: Int)
        val calls = mutableListOf<Call>()
        val frames = mutableListOf<GrayPattern>()
        /** Per 1-based call: what to answer instead of a frame. */
        var answer: (Int) -> ScoreScanNativeCapture? = { null }
        var took: Duration = 30.milliseconds
        /** Native pixels the frame is moved right by (content shifted since the snapshot). */
        var shift = 0
        var onCapture: (Int) -> Unit = {}

        override fun capture(): ScoreScanNativeCapture {
            onCapture(calls.size + 1)
            val lines = linesFile()?.takeIf { it.exists() }?.readLines() ?: emptyList()
            calls += Call(
                lines.count { "\"kind\":\"row\"" in it }, lines.count { "\"kind\":\"native\"" in it },
                world.snaps, world.gestures.size,
            )
            time += took
            answer(calls.size)?.let { return it }
            val small = world.screen720()
            val big = ByteArray(2560 * 1440)
            for (y in 0 until 1440) for (x in 0 until 2560) {
                val sx = (x - shift).coerceIn(0, 2559) / 2
                big[y * 2560 + x] = small[(y / 2) * 1280 + sx]
            }
            val frame = GrayPattern(2560, 1440, big).also { frames += it }
            return ScoreScanNativeCapture.Frame(frame, ScoreScanNativeMap(Size(2560, 1440), Size(1280, 720), Location(0, 0)))
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
            loaded = true
            asked++
            onAsked()
            return reason
        }
        /** Null until the first ask or read, which loads the real engine, whether that load works or not. */
        private var loaded = false
        override val loadTime: Duration? get() = if (loaded) 420.milliseconds else null
        override fun read(field: Pattern): EngineScore {
            loaded = true
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
        val native = Native(world, time) { root.listFiles()?.singleOrNull()?.resolve(ScoreScanLog.LINES) }
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
            nativeFrames = native,
            clock = { now.also { now += 10 } },
            timeSource = time,
            maxScreens = maxScreens,
        ).run()

        val scrolls get() = world.gestures.count { it == "scrollDown" }
        fun folder(result: ScoreScanResult) = File(root, result.folder!!)
        fun allLines(result: ScoreScanResult) = File(folder(result), ScoreScanLog.LINES).readLines().map { Json.parseToJsonElement(it).jsonObject }
        fun lines(result: ScoreScanResult) = allLines(result).filter { (it["kind"] as JsonPrimitive).content == "row" }
        fun nativeLines(result: ScoreScanResult) = allLines(result).filter { (it["kind"] as JsonPrimitive).content == "native" }
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
        val transformer = mockk<Transformer>()
        every { transformer.toImage(any()) } answers { firstArg<Region>() * 0.5 }
        val native = object : ScoreScanNativeFrames {
            var calls = 0
            override fun capture(): ScoreScanNativeCapture {
                calls++
                return ScoreScanNativeCapture.Failed(ScoreScanNative.UNSUPPORTED)
            }
        }
        val result = SupportScoreScanner(
            world.screen, ApiScoreScanFrames(api, finder, transformer), reader, engine,
            DirectoryScoreScanStorage(File(dir, "api")) { Long.MAX_VALUE },
            ScoreScanDevice("v", "m", "a"), world.exitManager, GameServer.Kr, native,
        ).run()

        assertThat(result.reason).isEqualTo(ScoreScanEndReason.Bottom)
        assertThat(world.gestures).containsExactly("scrollDown", "scrollDown")
        assertThat(native.calls).isEqualTo(1)
        verify(exactly = 1) { transformer.toImage(Region(76 + 1960, 200 + 310, 120, 60)) }
        verify(exactly = 1) { transformer.toImage(Region(76 + 1960, 640 + 310, 120, 60)) }
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
        // The start's look at the rows, then per loop the screen's snapshot and the look before the scroll.
        // Screens 1 and 3 are read, 5 and 7 unchanged.
        assertThat(scan.world.snaps).isEqualTo(8)
        assertThat(scan.reader.calls.map { it.first }).containsExactly(0, 1, 2, 2, 3, 4)
        assertThat(scan.reader.calls.map { it.third }).containsExactly(1, 1, 1, 3, 3, 3)
        assertThat(scan.engine.reads.map { it.second }).containsExactly(1, 1, 1, 3, 3, 3)
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
    fun `a list with only a guest row at the start ends before anything is written`() {
        val scan = Scan(total = 0, guestOnly = true)
        assertNotStarted(scan, scan.run(), ScoreScanEndReason.NoRows)
    }

    @Test
    fun `a later screen with only a guest row ends the scan with the rows read so far and no gesture`() {
        val scan = Scan(total = 9)
        scan.world.onScroll = { if (it == 1) scan.world.guestOnly = true }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.NoRows)
        assertThat(scan.scrolls).isEqualTo(1)
        assertThat(result.rows).isEqualTo(3)
        assertThat(scan.lines(result).size).isEqualTo(3)
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
    fun `a stop that arrives while the engine loads fine creates and deletes nothing`() {
        val scan = Scan(total = 5)
        val old = (1..25).map { "20200101-0000%02d".format(it) }
        old.forEach { File(scan.root, it).mkdirs() }
        scan.engine.onAsked = { scan.world.exitManager.exit() }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.UserStopped)
        assertThat(result.folder).isNull()
        assertThat(scan.root.list()!!.sorted()).isEqualTo(old)
        assertThat(scan.world.gestures.size).isEqualTo(0)
        assertThat(scan.reader.calls.size).isEqualTo(0)
        assertThat(scan.engine.reads.size).isEqualTo(0)
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
    fun `two dropped scrolls apart from each other neither end the scan nor log a row twice`() {
        val scan = Scan(total = 13)
        scan.world.droppedScrolls += setOf(2, 4)
        val result = scan.run()
        // Offsets 0, 2, (2), 4, (4), 6, 8, 10, then two scrolls that leave offset 10 as it was.
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.Bottom)
        assertThat(result.screens).isEqualTo(6)
        assertThat(scan.scrolls).isEqualTo(9)
        assertThat(result.rows).isEqualTo(18)
        assertThat(scan.lines(result).size).isEqualTo(18)
        assertThat(scan.lines(result).map { it.int("screen") to it.int("row") }.distinct().size).isEqualTo(18)
        assertThat(scan.reader.calls.map { it.first })
            .containsExactly(0, 1, 2, 2, 3, 4, 4, 5, 6, 6, 7, 8, 8, 9, 10, 10, 11, 12)
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
    fun `a stop during the last row of a screen issues no further scroll and takes no native frame`() {
        val scan = Scan(total = 20)
        scan.reader.onRead = { if (it == 3) scan.world.exitManager.exit() }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.UserStopped)
        assertThat(result.rows).isEqualTo(3)
        assertThat(scan.scrolls).isEqualTo(0)
        assertThat(scan.native.calls.size).isEqualTo(0)
        assertThat(scan.nativeLines(result).size).isEqualTo(0)
        assertThat(File(scan.folder(result), "native").list()!!.size).isEqualTo(0)
    }

    @Test
    fun `a stop during the last row of the last allowed screen is the user's stop and takes no native frame`() {
        val scan = Scan(total = 100, maxScreens = 2)
        scan.reader.onRead = { if (it == 6) scan.world.exitManager.exit() }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.UserStopped)
        assertThat(result.rows).isEqualTo(6)
        assertThat(scan.native.calls.size).isEqualTo(1)
        assertThat(scan.nativeLines(result).map { it.int("screen") }.distinct()).containsExactly(0)
        assertThat(scan.summary(result).str("endReason")).isEqualTo("user_stopped")
    }

    @Test
    fun `a stop during the native capture of the last allowed screen is the user's stop`() {
        val scan = Scan(total = 100, maxScreens = 2)
        scan.native.onCapture = { if (it == 2) scan.world.exitManager.exit() }
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.UserStopped)
        assertThat(scan.native.calls.size).isEqualTo(2)
        assertThat(scan.scrolls).isEqualTo(1)
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
        // The start's look at the rows, the first screen and the look before its scroll.
        assertThat(scan.world.snaps).isEqualTo(3)
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
            seen += if (file.exists()) file.readLines().count { "\"kind\":\"row\"" in it } else 0
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
            {"schema":2,"kind":"row","time":1790000000010,"server":"Jp","screen":0,"row":0,
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

        val natives = scan.nativeLines(result)
        assertThat(natives.size).isEqualTo(2)
        val native0 = File(scan.folder(result), "native/s000-r0.png").readBytes()
        val crop1 = File(scan.folder(result), "crops/s000-r1.png").readBytes()
        val native1 = File(scan.folder(result), "native/s000-r1.png").readBytes()
        val matches = natives.map { it.obj("native").str("match").toDouble() }
        assertThat(matches.all { kotlin.math.abs(it - 1.0) < 1e-9 }).isTrue()
        fun withoutMatch(line: JsonObject) = JsonObject(line + ("native" to JsonObject(line.obj("native") - "match")))
        assertThat(withoutMatch(natives[0])).isEqualTo(json("""
            {"schema":2,"kind":"native","time":1790000000030,"server":"Jp","screen":0,"row":0,"cropSha256":"${ScoreScanLog.sha256(crop0)}",
             "native":{"crop":"native/s000-r0.png","cropSha256":"${ScoreScanLog.sha256(native0)}","frame":{"w":2560,"h":1440},
                       "scale":{"x":2.0,"y":2.0},"region":{"x":1996,"y":160,"w":200,"h":140},"field":{"x":40,"y":40,"w":120,"h":60},
                       "clamped":[],"captureMicros":30000},
             "reason":null,"detail":null,"captureMicros":30000}
        """))
        assertThat(withoutMatch(natives[1])).isEqualTo(json("""
            {"schema":2,"kind":"native","time":1790000000040,"server":"Jp","screen":0,"row":1,"cropSha256":"${ScoreScanLog.sha256(crop1)}",
             "native":{"crop":"native/s000-r1.png","cropSha256":"${ScoreScanLog.sha256(native1)}","frame":{"w":2560,"h":1440},
                       "scale":{"x":2.0,"y":2.0},"region":{"x":1996,"y":560,"w":200,"h":140},"field":{"x":40,"y":40,"w":120,"h":60},
                       "clamped":[],"captureMicros":30000},
             "reason":null,"detail":null,"captureMicros":30000}
        """))
        // 200x140 at 2x of the 100x70 region around the field: a 4-byte size header and the pixels.
        assertThat(native0.size).isEqualTo(4 + 200 * 140)

        val summary = scan.summary(result)
        val nativeSummary = summary.obj("native")
        assertThat(kotlin.math.abs(nativeSummary.obj("match").str("median").toDouble() - 1.0) < 1e-9).isTrue()
        assertThat(kotlin.math.abs(nativeSummary.obj("match").str("min").toDouble() - 1.0) < 1e-9).isTrue()
        assertThat(JsonObject(nativeSummary + ("match" to JsonObject(nativeSummary.obj("match") - "median" - "min")))).isEqualTo(json("""
            {"taken":1,"failed":{},"frame":{"w":2560,"h":1440},"scale":{"x":2.0,"y":2.0},
             "captureMicros":{"count":1,"median":30000,"max":30000},"match":{"count":2}}
        """))
        assertThat(JsonObject(summary - "native")).isEqualTo(json("""
            {"schema":2,"appVersion":"1.9.0-beta.1-rust.1","deviceModel":"Test Phone","androidVersion":"15 (API 35)",
             "server":"Jp","folder":"support-score-scan/${result.folder}","startTime":1790000000000,"endTime":1790000000050,
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
            "engine_unavailable", "low_space", "user_stopped", "bottom", "screen_limit", "native_restore_failed",
        ))
        assertThat(ScoreScanLog.ERROR).isEqualTo("error")
    }

    private fun Scan.rowLinesWithoutTime(result: ScoreScanResult) = lines(result).map { JsonObject(it - "time") }

    @Test
    fun `the native frame is taken after the rows are logged, before the pre-scroll check, and not on an unchanged screen`() {
        val scan = Scan(total = 7)
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.Bottom)
        // Three screens read (offsets 0, 2, 4), then two unchanged ones without a capture.
        // Snapshots: the start, then per screen its own one; the pre-scroll look comes after the capture.
        assertThat(scan.native.calls).containsExactly(
            Native.Call(rowLines = 3, nativeLines = 0, snaps = 2, gestures = 0),
            Native.Call(rowLines = 6, nativeLines = 3, snaps = 4, gestures = 1),
            Native.Call(rowLines = 9, nativeLines = 6, snaps = 6, gestures = 2),
        )
        assertThat(scan.world.gestures.all { it == "scrollDown" }).isTrue()
        assertThat(scan.nativeLines(result).map { it.int("screen") to it.int("row") })
            .isEqualTo(scan.lines(result).map { it.int("screen") to it.int("row") })
        assertThat(scan.native.frames.all { it.closed }).isTrue()
        assertThat(File(scan.folder(result), "native").list()!!.sorted())
            .isEqualTo((0..2).flatMap { screen -> (0..2).map { "s%03d-r%d.png".format(screen, it) } })
    }

    @Test
    fun `the native capture of the last screen comes before the screen limit ends the scan`() {
        val scan = Scan(total = 100, maxScreens = 2)
        val result = scan.run()
        assertThat(result.reason).isEqualTo(ScoreScanEndReason.ScreenLimit)
        assertThat(scan.native.calls.size).isEqualTo(2)
        assertThat(scan.nativeLines(result).size).isEqualTo(6)
    }

    private fun assertNativeFailureKeepsTheScan(answer: (Int) -> ScoreScanNativeCapture?, took: Duration, reason: String, detail: String?) {
        val reference = Scan(total = 7)
        val referenceResult = reference.run()
        val scan = Scan(total = 7)
        scan.native.answer = answer
        scan.native.took = took
        val result = scan.run()

        assertThat(result.reason).isEqualTo(ScoreScanEndReason.Bottom)
        assertThat(result.rows).isEqualTo(referenceResult.rows)
        assertThat(scan.world.gestures).isEqualTo(reference.world.gestures)
        assertThat(scan.world.gestures.all { it == "scrollDown" }).isTrue()
        assertThat(scan.rowLinesWithoutTime(result)).isEqualTo(reference.rowLinesWithoutTime(referenceResult))
        assertThat(scan.native.calls.size).isEqualTo(3)
        val natives = scan.nativeLines(result)
        assertThat(natives.size).isEqualTo(9)
        assertThat(natives.all { it.getValue("native") == JsonNull }).isTrue()
        assertThat(natives.map { it.str("reason") }.distinct()).containsExactly(reason)
        assertThat(natives.map { it.getValue("detail") }.distinct()).containsExactly(detail?.let { JsonPrimitive(it) } ?: JsonNull)
        assertThat(natives.map { it.int("captureMicros") }.distinct()).containsExactly(took.inWholeMicroseconds.toInt())
        assertThat(File(scan.folder(result), "native").list()!!.size).isEqualTo(0)
        assertThat(scan.native.frames.all { it.closed }).isTrue()
        val native = scan.summary(result).obj("native")
        assertThat(native.int("taken")).isEqualTo(0)
        assertThat(native.obj("failed")).isEqualTo(Json.parseToJsonElement("""{"$reason":3}"""))
        assertThat(native.getValue("frame")).isEqualTo(JsonNull)
        assertThat(native.obj("captureMicros").int("count")).isEqualTo(3)
        assertThat(native.obj("match").int("count")).isEqualTo(0)
    }

    @Test
    fun `a failing native capture leaves the 720p lines and the scan as they were`() =
        assertNativeFailureKeepsTheScan(
            { ScoreScanNativeCapture.Failed(ScoreScanNative.ERROR, "KnownException: NoScreenshotReceived") },
            40.milliseconds, "error", "KnownException: NoScreenshotReceived",
        )

    @Test
    fun `a null native frame leaves the 720p lines and the scan as they were`() =
        assertNativeFailureKeepsTheScan({ ScoreScanNativeCapture.Failed(ScoreScanNative.NULL_FRAME) }, 40.milliseconds, "null_frame", null)

    @Test
    fun `a native capture that throws is logged and the scan goes on`() =
        assertNativeFailureKeepsTheScan(
            { throw IllegalStateException("display gone") }, 40.milliseconds, "error", "java.lang.IllegalStateException: display gone",
        )

    @Test
    fun `a slow native capture is dropped, closed and logged, and the scan goes on`() =
        assertNativeFailureKeepsTheScan({ null }, ScoreScanNative.CAPTURE_BOUND + 1.milliseconds, "slow", null)

    @Test
    fun `a native capture at the bound is kept`() {
        val scan = Scan(total = 2)
        scan.native.took = ScoreScanNative.CAPTURE_BOUND
        val result = scan.run()
        assertThat(scan.nativeLines(result).all { it.getValue("native") is JsonObject }).isTrue()
        assertThat(ScoreScanNative.CAPTURE_BOUND).isEqualTo(1500.milliseconds)
    }

    @Test
    fun `a capture that could not restore the 720p capture ends the scan before the next look or gesture`() {
        val scan = Scan(total = 20)
        scan.native.answer = { if (it == 2) ScoreScanNativeCapture.RestoreFailed("before 1280x720, after 2560x1440") else null }
        val result = scan.run()

        assertThat(result.reason).isEqualTo(ScoreScanEndReason.NativeRestoreFailed)
        assertThat(result.detail).isEqualTo("before 1280x720, after 2560x1440")
        assertThat(result.screens).isEqualTo(2)
        assertThat(result.rows).isEqualTo(6)
        assertThat(scan.world.gestures).containsExactly("scrollDown")
        // The start, two screens and the look before the first scroll; none after the failed restore.
        assertThat(scan.world.snaps).isEqualTo(4)
        val natives = scan.nativeLines(result)
        assertThat(natives.map { it.getValue("native") is JsonObject }).containsExactly(true, true, true, false, false, false)
        assertThat(natives.drop(3).map { it.str("reason") }.distinct()).containsExactly("restore_failed")
        val summary = scan.summary(result)
        assertThat(summary.str("endReason")).isEqualTo("native_restore_failed")
        assertThat(summary.str("detail")).isEqualTo("before 1280x720, after 2560x1440")
        assertThat(summary.obj("native").obj("failed")).isEqualTo(Json.parseToJsonElement("""{"restore_failed":1}"""))
        assertThat(summary.obj("native").int("taken")).isEqualTo(1)
    }

    @Test
    fun `the native match is 1 for the frame the rows were read from and lower for a shifted one`() {
        val same = Scan(total = 2)
        val sameMatches = same.nativeLines(same.run()).map { it.obj("native").str("match").toDouble() }
        val shifted = Scan(total = 2).apply { native.shift = 6 }
        val shiftedMatches = shifted.nativeLines(shifted.run()).map { it.obj("native").str("match").toDouble() }

        assertThat(sameMatches.all { kotlin.math.abs(it - 1.0) < 1e-9 }).isTrue()
        assertThat(shiftedMatches.size).isEqualTo(2)
        assertThat(shiftedMatches.all { it < 0.9 }).isTrue()
    }

    @Test
    fun `the correlation is undefined for a flat field and ignores brightness and contrast`() {
        val a = ByteArray(60 * 30) { (it % 60 + it / 60).toByte() }
        val brighter = ByteArray(a.size) { (2 * a[it] + 10).toByte() }
        assertThat(ScoreScanNative.correlation(a, ByteArray(a.size) { 7 })).isNull()
        assertThat(kotlin.math.abs(ScoreScanNative.correlation(a, brighter)!! - 1.0) < 1e-9).isTrue()
        assertThat(ScoreScanNative.correlation(a, a.copyOf(10))).isNull()
    }

    @Test
    fun `a field outside the frame costs only its own row's native crop`() {
        val scan = Scan(total = 3)
        scan.world.outsidePosition = 1
        val result = scan.run()
        val natives = scan.nativeLines(result)
        assertThat(natives.map { it.getValue("native") is JsonObject }).containsExactly(true, false, true)
        assertThat(natives[1].str("reason")).isEqualTo("field_outside_frame")
        assertThat(File(scan.folder(result), "native").list()!!.sorted()).containsExactly("s000-r0.png", "s000-r2.png")
        assertThat(scan.summary(result).obj("native").int("taken")).isEqualTo(1)
        assertThat(scan.summary(result).obj("native").obj("match").int("count")).isEqualTo(2)
    }

    private fun nativeRecord(match: Double?, clamped: List<String>) = io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeRecord(
        time = 5, server = "Kr", screen = 1, row = 2, cropSha256 = "aa",
        native = io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeResult(
            crop = "s001-r2.png", cropSha256 = "bb", frame = Size(2560, 1440), scaleX = 2.0, scaleY = 2.0,
            region = Region(0, 0, 170, 110), field = Region(10, 10, 120, 60), clamped = clamped, match = match, captureMicros = 9,
        ),
        reason = null, detail = null, captureMicros = 9,
    )

    @Test
    fun `a native line with cut sides and no match, field by field`() {
        val expected = Json.parseToJsonElement("""
            {"schema":2,"kind":"native","time":5,"server":"Kr","screen":1,"row":2,"cropSha256":"aa",
             "native":{"crop":"native/s001-r2.png","cropSha256":"bb","frame":{"w":2560,"h":1440},"scale":{"x":2.0,"y":2.0},
                       "region":{"x":0,"y":0,"w":170,"h":110},"field":{"x":10,"y":10,"w":120,"h":60},
                       "clamped":["left","top"],"match":null,"captureMicros":9},
             "reason":null,"detail":null,"captureMicros":9}
        """)
        assertThat(Json.parseToJsonElement(ScoreScanLog.nativeLine(nativeRecord(null, listOf("left", "top"))))).isEqualTo(expected)
        assertThat(Json.parseToJsonElement(ScoreScanLog.nativeLine(nativeRecord(Double.NaN, listOf("left", "top"))))).isEqualTo(expected)
    }

    @Test
    fun `the summary's match statistics skip a missing or non-finite match`() {
        val native = io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeTally()
        native.taken(ScoreScanNativeMap(Size(2560, 1440), Size(1280, 720), Location(0, 0)), 10)
        native.match(null)
        native.match(Double.NaN)
        native.match(Double.POSITIVE_INFINITY)
        native.match(0.5)
        native.match(0.9)
        val summary = ScoreScanLog.summary(io.github.fate_grand_automata.scripts.supportSelection.ScoreScanSummary(
            device = ScoreScanDevice("v", "m", "a"), server = "Kr", folder = "f", startTime = 1, endTime = 2,
            reason = "bottom", detail = null, screens = 1, tally = ScoreScanTally(), engineLoadMicros = null,
            engineUnavailableReason = null, native = native,
        ))
        assertThat(summary.contains("NaN") || summary.contains("Infinity")).isFalse()
        assertThat(Json.parseToJsonElement(summary).jsonObject.obj("native").obj("match"))
            .isEqualTo(Json.parseToJsonElement("""{"count":2,"median":0.5,"min":0.5}"""))
    }
}
