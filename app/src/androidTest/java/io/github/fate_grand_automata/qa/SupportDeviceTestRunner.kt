package io.github.fate_grand_automata.qa

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import io.github.fate_grand_automata.IStorageProvider
import io.github.fate_grand_automata.imaging.DroidCvPattern
import io.github.fate_grand_automata.imaging.TesseractOcrService
import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.enums.*
import io.github.fate_grand_automata.scripts.locations.*
import io.github.fate_grand_automata.scripts.prefs.*
import io.github.fate_grand_automata.scripts.supportSelection.*
import io.github.fate_grand_automata.util.ImageLoader
import io.github.lib_automata.*
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlin.time.Duration

/** Manual Android tests using the production readers, selectors, ImageLoader and OCR.
 * Live reader cases can only tap the skill-display switch. An explicit inputText
 * fixture can paste Unicode into a focused field for manual name-search tests.
 * The 16:9 capture/coordinate adapter is test code; no test controls ship in the app.
 */
class SupportDeviceTestRunner : Instrumentation() {
    private lateinit var arguments: Bundle
    override fun onCreate(arguments: Bundle) { super.onCreate(arguments); this.arguments = arguments; start() }
    internal fun fixture(path: String): ByteArray {
        require(Regex("/sdcard/Download/fga-device-qa/[A-Za-z0-9._/-]+").matches(path) && ".." !in path)
        return ParcelFileDescriptor.AutoCloseInputStream(
            // UiAutomation tokenizes a command directly; shell quotes would become part of the path.
            uiAutomation.executeShellCommand("cat $path")
        ).use { it.readBytes() }
    }
    override fun onStart() {
        val report = JSONObject()
        try {
            check(OpenCVLoader.initLocal())
            val input = JSONObject(fixture(arguments.getString("cases")!!).toString(Charsets.UTF_8))
            // Unicode test input through Android's normal paste shortcut; no IME or OS setting changes.
            if (input.has("inputText")) {
                (targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("FGA device test", input.getString("inputText")))
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    val now = SystemClock.uptimeMillis()
                    check(uiAutomation.injectInputEvent(KeyEvent(now, now, action, KeyEvent.KEYCODE_V, 0,
                        KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON), true))
                }
                report.put("passed", true).put("textPasted", true)
                finish(Activity.RESULT_OK, Bundle().apply { putString("report", report.toString()) })
                return
            }
            val cases = input.getJSONArray("cases"); val results = JSONArray()
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val result = JSONObject().put("name", case.getString("name"))
                try {
                    DeviceApi(this, case, input.optJSONObject("imports") ?: JSONObject()).use { result.put("actual", it.verify()) }
                    result.put("passed", true)
                } catch (error: Throwable) { result.put("passed", false).put("error", error.stackTraceToString()) }
                results.put(result)
            }
            report.put("results", results).put("passed", (0 until results.length()).all { results.getJSONObject(it).getBoolean("passed") })
        } catch (error: Throwable) { report.put("passed", false).put("error", error.stackTraceToString()) }
        finish(Activity.RESULT_OK, Bundle().apply { putString("report", report.toString()) })
    }
}

private inline fun <reified T> properties(crossinline value: (String) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> value(method.name) } as T

private class DeviceApi(private val runner: SupportDeviceTestRunner, private val case: JSONObject, imports: JSONObject) : IFgoAutomataApi, AutoCloseable {
    private val live = case.optBoolean("live", false)
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
    private val ocr = TesseractOcrService(runner.targetContext)
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
    private fun supportPrefs(minimum: Int = 2) = properties<ISupportPreferences> {
        when (it) {
            "getMinimumNpLevel" -> minimum
            "getNpLevelCheck", "getAppendSkillCheck" -> true
            "getAppendSkillsMax" -> listOf(false, true, false, false, false)
            "getPreferredServants" -> listOf("device-test")
            "getBondCEEffect" -> BondCEEffectEnum.Ignore
            else -> false
        }
    }
    fun verify(): JSONObject {
        val result = JSONObject(); val display = SupportSkillDisplay(this)
        if (live) display.ensureNormal()
        val view = display.read(); result.put("view", view.name)
        if (case.has("view")) check(view.name == case.getString("view")) { "View $view != ${case.getString("view")}" }
        val bounds = useSameSnapIn { SupportBoundsFinder(this).all() }
        result.put("bounds", JSONArray(bounds.map { it.region.y / 2.0 }))
        val expected = case.getJSONArray("rows"); val rows = JSONArray()
        val np = SupportNpLevelReader(this, ocr); val score = SupportScoreReader(this, ocr); val skills = SupportSkillLevelReader(this)
        for (i in 0 until expected.length()) {
            val row = expected.getJSONObject(i); val y = row.getInt("y")
            val bound = checkNotNull(bounds.minByOrNull { abs(it.region.y / 2 - y) })
            check(abs(bound.region.y / 2 - y) <= 2) { "Missing row $y: $bounds" }
            val values = useSameSnapIn {
                val n = np.read(bound); val s = score.read(bound, true, true); val k = skills.read(bound, view == SupportSkillDisplay.View.Append)
                JSONObject().put("np", n ?: JSONObject.NULL).put("class", s.classScore ?: JSONObject.NULL)
                    .put("grand", s.grandScore ?: JSONObject.NULL).put("display", s.display.name).put("skills", JSONArray(k))
            }
            rows.put(values)
            for (key in listOf("np", "class", "grand")) if (row.has(key))
                check(values.get(key).toString() == row.get(key).toString()) { "$key at $y: $values != $row" }
            if (row.has("skills")) check(values.getJSONArray("skills").toString() == row.getJSONArray("skills").toString()) { "Skills at $y: $values != $row" }
            val npChecks = JSONArray()
            if (row.has("np") && !row.isNull("np")) for (minimum in 1..5) {
                val pass = useSameSnapIn { SupportNpSelection(this, supportPrefs(minimum), np).check(bound) }
                check(pass == (row.getInt("np") >= minimum)) { "NP minimum $minimum at $y" }; npChecks.put(pass)
            }
            values.put("npMinimumChecks", npChecks)
            val scores = SupportScores(if (values.isNull("class")) null else values.getInt("class"),
                if (values.isNull("grand")) null else values.getInt("grand"), SupportScoreDisplay.valueOf(values.getString("display")))
            values.put("classOnly", SupportScoreSelection.matches(scores, true, false))
                .put("grandOnly", SupportScoreSelection.matches(scores, false, true)).put("bothScores", SupportScoreSelection.matches(scores, true, true))
            if (live && case.optBoolean("appendCycle")) {
                val pass = AppendSkillSelection(this, supportPrefs(), display, skills).check(bound)
                values.put("appendSlot2", pass).put("restoredView", display.read().name)
                if (row.has("appendSlot2")) check(pass == row.getBoolean("appendSlot2"))
                check(display.read() == SupportSkillDisplay.View.Normal) { "Normal display was not restored" }
            }
            val items = row.optJSONArray("matches") ?: JSONArray(); val checks = JSONArray()
            val star = SupportSelectionStarChecker(this); val grand = SupportSelectionGrandChecker(this)
            for (j in 0 until items.length()) {
                val item = items.getJSONObject(j); val name = item.getString("name")
                val pass = useSameSnapIn {
                    if (item.getString("kind") == "Servant") ServantSelection(this, supportPrefs(), star, grand, skills).check(listOf(name), bound)
                    else CESelection(this, supportPrefs(), star, grand).check(listOf(name), bound)
                }
                checks.put(JSONObject().put("name", name).put("kind", item.getString("kind")).put("matched", pass))
                check(pass == item.getBoolean("expected")) { "Imported image mismatch at $y: $item -> $pass" }
            }
            values.put("importMatches", checks)
        }
        return result.put("rows", rows)
    }
    override fun close() { clearFrame(); images.clearImageCache(); ocr.close() }
}
