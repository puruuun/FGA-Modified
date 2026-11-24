package io.github.fate_grand_automata.qa.suites

import org.json.JSONObject
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

/**
 * nativeProbe suite: runs one [NativeProbeTest] method, named by the case's "test", and reports
 * the outcome. A skipped test (libfga_ffi.so not packaged, or packaged but not loadable; the
 * reason says which) is reported as skipped, not failed.
 */
internal fun runNativeProbeCase(case: JSONObject): JSONObject {
    val test = case.getString("test")
    var skipped: String? = null
    val core = JUnitCore()
    core.addListener(object : RunListener() {
        override fun testAssumptionFailure(failure: Failure) {
            skipped = failure.message
        }
    })
    val outcome = core.run(Request.method(NativeProbeTest::class.java, test))
    outcome.failures.firstOrNull()?.let { throw AssertionError("$test: ${it.message}", it.exception) }
    // tools/device-qa/run.py reads `requires` to tell a missing feature from a missing library.
    return JSONObject().put("test", test).apply {
        skipped?.let {
            put("skipped", it)
            case.optString("requires").takeIf(String::isNotEmpty)?.let { feature -> put("requires", feature) }
        }
    }
}
