package io.github.fate_grand_automata.qa

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import io.github.fate_grand_automata.qa.suites.verifyBond
import io.github.fate_grand_automata.qa.suites.verifyNoblePhantasm
import io.github.fate_grand_automata.qa.suites.verifyQuestItems
import io.github.fate_grand_automata.qa.suites.verifySupport
import io.github.fate_grand_automata.qa.suites.verifyFpSell
import io.github.fate_grand_automata.qa.suites.verifySkillLevel
import io.github.fate_grand_automata.qa.suites.verifyWeeklyRoute
import io.github.fate_grand_automata.qa.suites.verifyRankUp
import io.github.fate_grand_automata.qa.suites.verifySkillLevelPanel
import io.github.fate_grand_automata.qa.suites.verifyHakunoShuffle
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader

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
                val started = SystemClock.elapsedRealtime()
                var api: FixtureApi? = null
                try {
                    api = FixtureApi(this, case, input.optJSONObject("imports") ?: JSONObject())
                    result.put("actual", when (case.optString("suite", "support")) {
                        "support" -> api.verifySupport()
                        "bond" -> api.verifyBond()
                        "questItems" -> api.verifyQuestItems()
                        "noblePhantasm" -> api.verifyNoblePhantasm()
                        "fpSell" -> api.verifyFpSell()
                        "skillLevel" -> api.verifySkillLevel()
                        "weeklyRoute" -> api.verifyWeeklyRoute()
                        "rankUp" -> api.verifyRankUp()
                        "skillLevelPanel" -> api.verifySkillLevelPanel()
                        "hakunoShuffle" -> api.verifyHakunoShuffle()
                        else -> error("Unknown suite")
                    })
                    api.checkCoverage()
                    result.put("passed", true)
                } catch (error: Throwable) { result.put("passed", false).put("error", error.stackTraceToString()) }
                finally {
                    result.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    api?.let { result.put("ocrCalls", it.ocr.calls).put("ocrMs", it.ocr.totalMs); it.close() }
                }
                results.put(result)
            }
            report.put("results", results).put("passed", (0 until results.length()).all { results.getJSONObject(it).getBoolean("passed") })
        } catch (error: Throwable) { report.put("passed", false).put("error", error.stackTraceToString()) }
        finish(Activity.RESULT_OK, Bundle().apply { putString("report", report.toString()) })
    }
}
