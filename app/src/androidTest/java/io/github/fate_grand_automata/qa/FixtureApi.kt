package io.github.fate_grand_automata.qa

import android.graphics.Bitmap
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
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import kotlin.time.Duration

/** Serves one fixture case to the production readers: a 16:9 screenshot, fixed Region coordinates and a cloned OCR. */
internal class FixtureApi(
    private val runner: SupportDeviceTestRunner,
    internal val case: JSONObject,
    imports: JSONObject,
) : IFgoAutomataApi, AutoCloseable {
    internal val live = case.optBoolean("live", false)
    private val colors = ColorManager()
    private var sameDepth = 0
    private var frame: ByteArray? = null
    private var gray: Pattern? = null
    private var color: Pattern? = null
    private val server = GameServers.deserialize(case.optString("server", "Kr"))!!
    private val common = properties<ISupportPreferencesCommon> { if (it == "getMlbSimilarity") 0.8 else error("Unexpected preference $it") }
    override val prefs = properties<IPreferences> { when (it) { "getGameServer" -> server; "getSupport" -> common; else -> error("Unexpected preference $it") } }
    override val messages = properties<IScriptMessages> { Unit }
    private val storage = Proxy.newProxyInstance(IStorageProvider::class.java.classLoader, arrayOf(IStorageProvider::class.java)) { _, method, args ->
        check(method.name == "readSupportImage") { "Unexpected storage operation ${method.name}" }
        val files = imports.getJSONArray("${args!![0]}:${args[1]}")
        (0 until files.length()).map { ByteArrayInputStream(runner.fixture(files.getString(it))) }
    } as IStorageProvider
    override val images = ImageLoader(storage, prefs, runner.targetContext, colors)
    private val transforms = object : IScriptAreaTransforms {
        override val scriptArea = Region(0, 0, 2560, 1440)
        override val isWide = false
        override val gameServer = server
        override val canLongSwipe = false
        override fun Location.xFromCenter() = this + Location(1280, 0)
        override fun Region.xFromCenter() = this + Location(1280, 0)
        override fun Location.xFromRight() = this + Location(2560, 0)
        override fun Region.xFromRight() = this + Location(2560, 0)
        override fun Location.yFromBottom() = this + Location(0, 1440)
        override fun Region.yFromBottom() = this + Location(0, 1440)
    }
    override val locations = Locations(transforms, FPLocations(transforms), LotteryLocations(transforms), SupportScreenLocations(transforms),
        AttackScreenLocations(transforms), BattleScreenLocations(transforms, MasterLocations(transforms, images, this, messages)),
        ServantLevelLocations(transforms), QuestItemLocations(transforms), BondLocations(transforms))
    internal val ocr = MeasuredOcr(TesseractOcrService(runner.targetContext))
    private fun clearFrame() { gray?.close(); color?.close(); gray = null; color = null; frame = null }
    private fun screenshot(): Pattern {
        if (frame == null) {
            frame = if (!live) runner.fixture(case.getString("file")) else {
                val bitmap = checkNotNull(runner.uiAutomation.takeScreenshot())
                try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
                finally { bitmap.recycle() }
            }
        }
        (if (colors.isColor) color else gray)?.let { return it }
        val picture = ByteArrayInputStream(frame!!).use { DroidCvPattern(it, colors.isColor) }
        check(picture.width * 9 == picture.height * 16) { "Tests require a 16:9 viewport" }
        val resized = if (picture.width == 1280) picture else picture.use { it.resize(Size(1280, 720)) }
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
    override fun Region.getPattern(tag: String): Pattern = screenshot().crop(this * 0.5).use { it.copy() }.also { it.tag = tag }
    override fun Region.findAll(pattern: Pattern, similarity: Double?): List<Match> = screenshot().crop(this * 0.5).use { cropped ->
        cropped.findMatches(pattern, similarity ?: 0.8).map { Match(it.region * 2.0 + location, it.score) }.toList()
    }
    override fun Region.exists(image: Pattern, timeout: Duration, similarity: Double?) = findAll(image, similarity).isNotEmpty()
    override fun Region.waitVanish(image: Pattern, timeout: Duration, similarity: Double?) = !exists(image, timeout, similarity)
    override fun Region.isWhite() = getPattern().use { it.isWhite() }
    override fun Region.isBlack() = getPattern().use { it.isBlack() }
    override fun Region.detectText(outlinedText: Boolean) = error("Not a support-reader operation")
    override fun Map<Pattern, Region>.exists(timeout: Duration, similarity: Double?, requireAll: Boolean) =
        if (requireAll) all { (p, r) -> r.exists(p, timeout, similarity) } else any { (p, r) -> r.exists(p, timeout, similarity) }
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
