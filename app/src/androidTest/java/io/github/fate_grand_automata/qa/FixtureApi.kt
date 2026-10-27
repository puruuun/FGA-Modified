package io.github.fate_grand_automata.qa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import io.github.fate_grand_automata.IStorageProvider
import io.github.fate_grand_automata.imaging.DroidCvPattern
import io.github.fate_grand_automata.imaging.TesseractOcrService
import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.enums.*
import io.github.fate_grand_automata.scripts.locations.*
import io.github.fate_grand_automata.scripts.prefs.*
import io.github.fate_grand_automata.util.ImageLoader
import io.github.lib_automata.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import kotlin.time.Duration

/** Serves one fixture case to the production readers: a screenshot scaled to 720 high (aspect kept), Region coordinates from the product transform formulas, and a cloned OCR. */
internal class FixtureApi(
    private val runner: SupportDeviceTestRunner,
    internal val case: JSONObject,
    internal val imports: JSONObject,
) : IFgoAutomataApi, AutoCloseable {
    internal val live = case.optBoolean("live", false)
    /** Crop fixtures are placed on a black 1280x720 canvas at region720; they must cover every region the reader touches. */
    internal val isCrop = case.optString("frame") == "crop"
    private val region720 = case.optJSONArray("region720")?.let { it.toRegion() }
    /** Reads may extend this many 720p pixels past the right edge of a single crop (black canvas there): the score region is 5 px wider than the stored 55x30 crops. */
    private val bleedRight720 = case.optInt("bleedRight720", 0)
    private val masked720 = case.optJSONArray("masked720")?.let { list -> (0 until list.length()).map { list.getJSONArray(it).toRegion() } } ?: emptyList()
    /** 720p rectangles of every pixel read through the fixture, used for the crop coverage check. */
    private val readRegions = mutableListOf<Region>()
    private val colors = ColorManager()
    private var sameDepth = 0
    private var frame: ByteArray? = null
    private var gray: Pattern? = null
    private var color: Pattern? = null
    private val server = GameServers.deserialize(case.optString("server", "Kr"))!!
    private val common = properties<ISupportPreferencesCommon> { if (it == "getMlbSimilarity") 0.8 else error("Unexpected preference $it") }
    override val prefs = properties<IPreferences> { when (it) { "getGameServer" -> server; "getSupport" -> common; else -> error("Unexpected preference $it") } }
    internal val supportCeLogs = mutableListOf<ScriptLog.SupportCe>()
    override val messages = object : IScriptMessages {
        override val supportCeDiagnosticsEnabled = case.optString("view") == "CESelection"
        override fun notify(action: ScriptNotify) = Unit
        override fun log(item: ScriptLog) {
            if (item is ScriptLog.SupportCe) {
                supportCeLogs += item
                // Instrumentation has its own Timber class loader; mirror the product line into logcat.
                android.util.Log.i("FGA.SupportCe", item.message)
            }
        }
    }
    private val storage = Proxy.newProxyInstance(IStorageProvider::class.java.classLoader, arrayOf(IStorageProvider::class.java)) { _, method, args ->
        check(method.name == "readSupportImage") { "Unexpected storage operation ${method.name}" }
        val builtInCes = case.optJSONArray("builtInCes")
        if (args!![0] == io.github.fate_grand_automata.SupportImageKind.CE && builtInCes != null &&
            (0 until builtInCes.length()).any { builtInCes.getString(it) == args[1] }) {
            return@newProxyInstance listOf(runner.targetContext.assets.open("Support/ce/${args[1]}.png"))
        }
        val files = imports.getJSONArray("${args[0]}:${args[1]}")
        (0 until files.length()).map { ByteArrayInputStream(runner.fixture(files.getString(it))) }
    } as IStorageProvider
    override val images = ImageLoader(storage, prefs, runner.targetContext, colors)
    /** Width of the 720p-high frame, keeping the source aspect ratio. Crops and live captures use 16:9. */
    private val width720: Int = if (live || isCrop) 1280 else {
        val bytes = runner.fixture(case.getString("file"))
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        check(options.outWidth > 0 && options.outHeight > 0) { "fixture is not an image" }
        options.outWidth * 720 / options.outHeight
    }
    // Same formulas as ScriptAreaTransforms: the script area is 1440 tall and as wide as the frame.
    private val area = Region(0, 0, width720 * 2, 1440)
    private val transforms = object : IScriptAreaTransforms {
        override val scriptArea = area
        override val isWide = area.size.isWide()
        override val gameServer = server
        override val canLongSwipe = false
        override fun Location.xFromCenter() = this + Location(area.center.x, 0)
        override fun Region.xFromCenter() = this + Location(area.center.x, 0)
        override fun Location.xFromRight() = this + Location(area.right, 0)
        override fun Region.xFromRight() = this + Location(area.right, 0)
        override fun Location.yFromBottom() = this + Location(0, area.bottom)
        override fun Region.yFromBottom() = this + Location(0, area.bottom)
    }
    override val locations = Locations(transforms, FPLocations(transforms), LotteryLocations(transforms), SupportScreenLocations(transforms),
        AttackScreenLocations(transforms), BattleScreenLocations(transforms, MasterLocations(transforms, images, this, messages)),
        ServantLevelLocations(transforms), QuestItemLocations(transforms), BondLocations(transforms))
    internal val ocr = MeasuredOcr(TesseractOcrService(runner.targetContext))
    internal fun fixtureText(file: String): String = runner.fixture(file).toString(Charsets.UTF_8)

    internal fun fixturePattern(file: String, color: Boolean = false): Pattern =
        ByteArrayInputStream(runner.fixture(file)).use { DroidCvPattern(it, color) }

    private fun clearFrame() { gray?.close(); color?.close(); gray = null; color = null; frame = null }
    private fun screenshot(): Pattern {
        if (frame == null) {
            frame = if (isCrop) composeCrop() else if (!live) runner.fixture(case.getString("file")) else {
                val bitmap = checkNotNull(runner.uiAutomation.takeScreenshot())
                try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
                finally { bitmap.recycle() }
            }
        }
        (if (colors.isColor) color else gray)?.let { return it }
        val picture = ByteArrayInputStream(frame!!).use { DroidCvPattern(it, colors.isColor) }
        // Scale to 720 high and keep the aspect ratio, so 1440p coordinates map the same way as in the app.
        val targetWidth = picture.width * 720 / picture.height
        val resized = if (picture.width == targetWidth && picture.height == 720) picture
            else picture.use { it.resize(Size(targetWidth, 720)) }
        if (colors.isColor) color = resized else gray = resized
        return resized
    }
    override fun <T> useSameSnapIn(block: () -> T): T {
        if (sameDepth == 0) clearFrame()
        sameDepth++
        return try { block() } finally { sameDepth--; if (sameDepth == 0) clearFrame() }
    }
    override fun <T> useColor(block: () -> T): T = colors.useColor(block)
    override fun Duration.wait(applyMultiplier: Boolean) { if (live) Thread.sleep(inWholeMilliseconds) }
    override fun Location.click(times: Int) {
        check(live && this == locations.support.skillDisplaySwitchClick && times == 1) { "Gameplay click blocked: $this" }
        val bitmap = checkNotNull(runner.uiAutomation.takeScreenshot())
        val screenX = x * bitmap.width / 2560f; val screenY = y * bitmap.height / 1440f
        bitmap.recycle()
        // Android's tap command supplies a touchscreen source and a real press interval;
        // zero-duration synthetic MotionEvents can be dropped by Unity's frame polling.
        ParcelFileDescriptor.AutoCloseInputStream(runner.uiAutomation.executeShellCommand(
            "input tap ${screenX.toInt()} ${screenY.toInt()}"
        )).use { it.readBytes() }
        clearFrame()
    }
    /** Crops the 720p frame for reads; the crop is recorded so coverage can be checked after the case. */
    private fun Region.cropFrame(): Pattern {
        readRegions += this * 0.5
        return screenshot().crop(this * 0.5)
    }
    override fun Region.getPattern(tag: String): Pattern = cropFrame().use { it.copy() }.also { it.tag = tag }
    override fun Region.findAll(pattern: Pattern, similarity: Double?): List<Match> = cropFrame().use { cropped ->
        cropped.findMatches(pattern, similarity ?: 0.8).map { Match(it.region * 2.0 + location, it.score) }.toList()
    }
    override fun Region.exists(image: Pattern, timeout: Duration, similarity: Double?) = findAll(image, similarity).isNotEmpty()
    override fun Region.waitVanish(image: Pattern, timeout: Duration, similarity: Double?) = !exists(image, timeout, similarity)
    override fun Region.isWhite() = getPattern().use { it.isWhite() }
    override fun Region.isBlack() = getPattern().use { it.isBlack() }
    override fun Region.detectText(outlinedText: Boolean) = error("Not a support-reader operation")
    override fun Map<Pattern, Region>.exists(timeout: Duration, similarity: Double?, requireAll: Boolean) =
        if (requireAll) all { (p, r) -> r.exists(p, timeout, similarity) } else any { (p, r) -> r.exists(p, timeout, similarity) }
    /**
     * Places each crop at its region720 origin on a black 1280x720 canvas. A case with "pieces" composes several
     * crops of one screen; a single crop uses "file" and "region720". Reads outside every piece fail in [checkCoverage].
     */
    private fun composeCrop(): ByteArray {
        val canvas = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        Canvas(canvas).drawColor(Color.BLACK)
        for ((file, region) in cropPieces()) {
            val bytes = runner.fixture(file)
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "crop image unreadable: $file" }
            // Multi-piece screens are reference ROIs that must match their region exactly; single crops are checked by coverage.
            if (case.has("pieces")) check(bitmap.width == region.width && bitmap.height == region.height) {
                "crop $file is ${bitmap.width}x${bitmap.height}, region720 is ${region.width}x${region.height}"
            }
            Canvas(canvas).drawBitmap(bitmap, region.x.toFloat(), region.y.toFloat(), null)
            bitmap.recycle()
        }
        try { return ByteArrayOutputStream().use { canvas.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { canvas.recycle() }
    }

    /** (device-relative repo path, region720) for every crop piece of this case. */
    private fun cropPieces(): List<Pair<String, Region>> =
        case.optJSONArray("pieces")?.let { list ->
            (0 until list.length()).map { list.getJSONObject(it).let { p -> p.getString("file") to p.getJSONArray("region720").toRegion() } }
        } ?: listOf(case.getString("file") to checkNotNull(region720) { "crop case needs region720" })

    /** Fails a crop case that read outside every crop region or touched a masked area (D-4). */
    fun checkCoverage() {
        if (!isCrop) return
        val regions = cropPieces().map { it.second }
        for (read in readRegions) {
            check(regions.any { it.copy(width = it.width + bleedRight720).contains(read) }) {
                "fixture does not cover region: read $read outside ${regions.joinToString()}"
            }
            for (mask in masked720) check(!read.overlaps(mask)) { "read $read overlaps masked area $mask" }
        }
    }

    private fun Region.contains(other: Region) =
        other.x >= x && other.y >= y && other.x + other.width <= x + width && other.y + other.height <= y + height

    private fun Region.overlaps(other: Region) =
        x < other.x + other.width && other.x < x + width && y < other.y + other.height && other.y < y + height

    private fun JSONArray.toRegion() = Region(getInt(0), getInt(1), getInt(2), getInt(3))

    override fun close() { clearFrame(); images.clearImageCache(); ocr.close() }
}

/** Counts OCR calls and their wall time so each fixture case reports its reader cost. */
internal class MeasuredOcr(private val delegate: OcrService) : OcrService {
    var calls = 0; private set
    var totalMs = 0L; private set

    override fun detectText(pattern: Pattern): String = measure { delegate.detectText(pattern) }
    override fun detectTextLine(pattern: Pattern, options: TextLineOcrOptions): OcrText = measure { delegate.detectTextLine(pattern, options) }
    override fun close() = delegate.close()

    private inline fun <T> measure(block: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        try { return block() } finally { calls++; totalMs += SystemClock.elapsedRealtime() - started }
    }
}

internal inline fun <reified T> properties(crossinline value: (String) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> value(method.name) } as T
