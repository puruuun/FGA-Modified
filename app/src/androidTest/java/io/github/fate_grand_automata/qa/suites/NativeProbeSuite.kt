package io.github.fate_grand_automata.qa.suites

import org.json.JSONObject
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

/**
 * nativeProbe suite: runs one [NativeProbeTest] method, named by the case's "test", and reports
 * the outcome. A skipped test (no libfga_ffi.so in the APK) is reported as skipped, not failed.
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
    return JSONObject().put("test", test).apply { skipped?.let { put("skipped", it) } }
}
