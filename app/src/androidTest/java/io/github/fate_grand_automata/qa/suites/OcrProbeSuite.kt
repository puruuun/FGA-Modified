package io.github.fate_grand_automata.qa.suites

import android.os.Debug
import android.util.Log
import io.github.fate_grand_automata.imaging.DroidCvPattern
import io.github.fate_grand_automata.imaging.TesseractOcrService
import io.github.fate_grand_automata.qa.NativeProbe
import io.github.fate_grand_automata.qa.SupportDeviceTestRunner
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreReader
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.ceil

/**
 * ocrProbe suite (issue #530): the PP-OCRv6 recogniser through libfga_ffi.so (tract backend) and,
 * for comparison, the Tesseract engine the app uses today, on the 370 labelled support-score crops.
 * Issue #535 adds the same recogniser with ONNX Runtime (`ort-*` cases), which needs a library
 * built with `-Pfga.native.ort=true`; without it those cases report themselves skipped.
 *
 * The model and the samples are local-only data (docs/agents/rust-native.md, "Test data"), so
 * they are pushed to the device before the run; the case file names their device paths. All cases
 * run in one process, so the case file puts each engine's memory case before its other cases.
 */
internal fun runOcrProbeCase(runner: SupportDeviceTestRunner, case: JSONObject): JSONObject {
    val test = case.getString("test")
    if (!NativeProbe.loaded) {
        val reason = NativeProbe.skipReason()
        Log.w(TAG, "$test: skipped, $reason")
        return JSONObject().put("test", test).put("skipped", reason)
    }
    val actual = when (test) {
        "tesseract-baseline" -> tesseractBaseline(runner, case)
        "tract-memory" -> engineMemory(runner, case, TRACT)
        "tract-recognise", "tract-recognise-allowed" -> engineRecognise(runner, case, TRACT)
        "ort-memory", "ort-recognise", "ort-recognise-allowed", "ort-recognise-1thread" -> {
            if (!ortPackaged()) return ortSkipped(test)
            val engine = ortEngine(case.optInt("threads", 0))
            if (test == "ort-memory") engineMemory(runner, case, engine) else engineRecognise(runner, case, engine)
        }
        else -> error("Unknown ocrProbe test $test")
    }
    Log.i(TAG, "$test: $actual")
    return actual.put("test", test)
}

private const val TAG = "FgaOcrProbe"

/**
 * tools/device-qa/run.py reads `requires`: with `--native` alone an ONNX Runtime case may be
 * skipped, with `--native-ort` it may not.
 */
private fun ortSkipped(test: String): JSONObject = JSONObject().put("test", test).put("requires", "ort")
    .put("skipped", "libfga_ffi.so was built without the ort-probe feature; build with -Pfga.native=true -Pfga.native.ort=true")

/** One recogniser backend behind libfga_ffi.so: its three calls and what its report adds. */
private class Engine(
    val loadCall: String,
    val load: (String) -> String,
    val read: (ByteArray, Int, Int, String) -> String,
    val unload: () -> String,
    val memoryNote: String = HWM_NOTE,
    val extra: (load: JSONObject, threadsAround: List<Int>) -> Map<String, Any?> = { _, _ -> emptyMap() },
)

private const val HWM_NOTE =
    "vmHwmKb is the process-wide peak; an earlier case (tesseract-baseline) may already have set it, so it moves only when this case goes higher"

private val TRACT = Engine("ocrLoad", { NativeProbe.ocrLoad(it) }, NativeProbe::ocrRead, NativeProbe::ocrUnload)

/**
 * [threads] 0 is ONNX Runtime's default, which by its documentation is one intra-op thread per
 * physical core; the API cannot report the number it picked, so the report gives the processor
 * count and the process's thread count before loading, after loading and after reading.
 */
private fun ortEngine(threads: Int) = Engine(
    "ocrLoadOrt",
    { NativeProbe.ocrLoadOrt(it, threads) },
    NativeProbe::ocrReadOrt,
    NativeProbe::ocrUnloadOrt,
    memoryNote = "vmHwmKb is the process-wide peak; the tesseract-baseline and tract cases run first and may already have set it, so it moves only when this case goes higher. " +
        "libonnxruntime.so is mapped when libfga_ffi.so loads, before beforeLoad, so the library itself is in every reading. " +
        "The ONNX Runtime environment is built on the first load and stays for the life of the process, so afterUnloadAndGc cannot return to beforeLoad",
) { load, threadsAround ->
    mapOf(
        "threads" to threads,
        "intraThreads" to load.getInt("intraThreads"),
        "availableProcessors" to Runtime.getRuntime().availableProcessors(),
        "processThreads" to JSONObject().put("beforeLoad", threadsAround.getOrNull(0)).put("afterLoad", threadsAround.getOrNull(1))
            .put("afterReading", threadsAround.getOrNull(2)),
        // Reported, not checked: by ONNX Runtime's threading documentation an intra-op pool of N
        // creates N - 1 threads, so 1 should give 0, but ART may start or end its own threads
        // during the load, so a failing rule on this count could not be justified.
        "threadsCreatedByLoad" to threadsAround.getOrNull(1)?.let { after -> threadsAround.getOrNull(0)?.let { after - it } },
        "threadNote" to if (threads == 0) {
            "threads 0 is ONNX Runtime's default (one intra-op thread per physical core, calling thread included); processThreads shows the pool it created"
        } else {
            "intra-op pool of $threads, calling thread included; by ONNX Runtime's documentation the load creates ${threads - 1} pool threads (threadsCreatedByLoad, reported, not checked). The tract cases run on one thread: native/ builds tract-linalg without its multithread-mm feature, so tract's matrix products run on the calling thread"
        },
    )
}

/** The ONNX Runtime functions exist only in a library built with the ort-probe feature. */
private fun ortPackaged(): Boolean = try {
    NativeProbe.ocrUnloadOrt()
    true
} catch (_: UnsatisfiedLinkError) {
    false
}

/** Threads of this process, from the kernel. */
private fun processThreads(): Int = File("/proc/self/task").list()?.size ?: -1
/** Lists in a report stay short so the whole report stays under the runner's 256 KB limit. */
private const val MAX_LISTED = 20

private class Sample(val file: String, val pyText: String, val gray: ByteArray, val width: Int, val height: Int)

/** Decoded once per process: every case reads the same crops, and decoding is not engine cost. */
private var loadedSamples: Pair<String, List<Sample>>? = null
private var copiedModel: Pair<String, File>? = null

private fun samples(runner: SupportDeviceTestRunner, dir: String): List<Sample> {
    loadedSamples?.let { (loadedDir, list) -> if (loadedDir == dir) return list }
    val indexBytes = runner.fixture("$dir/index.json")
    check(indexBytes.isNotEmpty()) { "$dir/index.json is missing on the device; push the OCR samples first (docs/agents/rust-native.md)" }
    val index = JSONArray(indexBytes.toString(Charsets.UTF_8))
    val list = (0 until index.length()).map { i ->
        val row = index.getJSONObject(i)
        val file = row.getString("file")
        val png = runner.fixture("$dir/$file")
        check(png.isNotEmpty()) { "$dir/$file is missing on the device; push the OCR samples first" }
        // A null py_text means Python read nothing, as on the PC side.
        decodeGray(file, if (row.isNull("py_text")) "" else row.getString("py_text"), png)
    }
    loadedSamples = dir to list
    return list
}

/**
 * The PNG's first channel (red), as the PC tool and the Rust host test take it. The samples are the
 * gray crops the Kotlin reader gets, stored with equal R, G and B, so any channel is the gray value;
 * that is checked here instead of assumed. OpenCV decodes without the colour management that
 * BitmapFactory may apply.
 */
private fun decodeGray(file: String, pyText: String, png: ByteArray): Sample {
    val encoded = MatOfByte(*png)
    val mat = try { Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_UNCHANGED) } finally { encoded.release() }
    try {
        check(!mat.empty() && mat.depth() == CvType.CV_8U) { "$file is not an 8-bit PNG" }
        val channels = mat.channels()
        check(channels == 1 || channels == 3 || channels == 4) { "$file has $channels channels; expected 1, 3 or 4" }
        val raw = ByteArray((mat.total() * channels).toInt())
        mat.get(0, 0, raw)
        val gray = ByteArray(mat.rows() * mat.cols()) { i ->
            if (channels == 1) raw[i] else {
                // OpenCV orders colour channels B, G, R(, A): index 2 is the PNG's first channel.
                val b = raw[i * channels]; val g = raw[i * channels + 1]; val r = raw[i * channels + 2]
                check(r == g && g == b) {
                    "$file pixel $i is not gray (R ${r.toInt() and 0xFF}, G ${g.toInt() and 0xFF}, B ${b.toInt() and 0xFF})"
                }
                r
            }
        }
        return Sample(file, pyText, gray, mat.cols(), mat.rows())
    } finally { mat.release() }
}

private fun copyDir(runner: SupportDeviceTestRunner) = File(runner.targetContext.cacheDir, "ocr-probe")

/**
 * Rust opens the model by path, and the app process cannot read /sdcard/Download directly. The
 * copy is shared by the tract and ONNX Runtime cases of one run and removed by [removeOcrProbeCopies].
 */
private fun modelFile(runner: SupportDeviceTestRunner, devicePath: String): File {
    copiedModel?.let { (path, file) -> if (path == devicePath && file.isFile) return file }
    val target = File(copyDir(runner), devicePath.substringAfterLast('/'))
    checkNotNull(target.parentFile).mkdirs()
    val copied = try { runner.copyFixture(devicePath, target) } catch (error: Throwable) { target.delete(); throw error }
    if (copied == 0L) {
        target.delete()
        error("$devicePath is missing on the device; push the OCR model first (docs/agents/rust-native.md)")
    }
    copiedModel = devicePath to target
    return target
}

/**
 * Files copied for a check are removed afterwards (docs/agents/devices-and-safety.md). The runner
 * calls this once when its whole run ends, pass, fail or exception, so the tract and ONNX Runtime cases share one
 * copy; the fixtures under /sdcard/Download/fga-device-qa/ stay. `deleteRecursively` reports a
 * failure only by returning false (and returns true for a missing folder), so a failure throws
 * here and the runner records it as `cleanupError`.
 */
internal fun removeOcrProbeCopies(runner: SupportDeviceTestRunner) {
    copiedModel = null
    val dir = copyDir(runner)
    check(dir.deleteRecursively()) { "could not delete ${dir.path}" }
}

private fun okJson(call: String, result: String): JSONObject {
    check(result.startsWith("OK: ")) { "$call: $result" }
    return JSONObject(result.removePrefix("OK: "))
}

private fun collectGarbage() {
    repeat(2) { Runtime.getRuntime().gc(); System.runFinalization() }
}

/** One reading: the kernel's counters from Rust, and Android's PSS split by heap. */
private fun memory(point: String): JSONObject {
    val reading = JSONObject().put("point", point)
    val status = NativeProbe.memoryStatus()
    if (status.startsWith("OK: ")) {
        val values = JSONObject(status.removePrefix("OK: "))
        for (key in values.keys()) reading.put(key, values.getLong(key))
    } else reading.put("procStatus", status)
    val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
    return reading.put("pssKb", info.totalPss).put("nativePssKb", info.nativePss)
        .put("dalvikPssKb", info.dalvikPss).put("otherPssKb", info.otherPss)
}

private val MEMORY_KEYS = listOf("vmRssKb", "vmHwmKb", "vmSizeKb", "pssKb", "nativePssKb", "dalvikPssKb", "otherPssKb")

/** Each reading with its change since the first reading and since the one before. */
private fun memoryReport(readings: List<JSONObject>): JSONArray {
    fun diff(a: JSONObject, b: JSONObject) = JSONObject().apply {
        for (key in MEMORY_KEYS) if (a.has(key) && b.has(key)) put(key, a.getLong(key) - b.getLong(key))
    }
    return JSONArray().apply {
        readings.forEachIndexed { i, reading ->
            if (i > 0) reading.put("sinceFirst", diff(reading, readings[0])).put("sincePrevious", diff(reading, readings[i - 1]))
            put(reading)
        }
    }
}

/** Nearest-rank percentiles, in microseconds. */
private fun timing(micros: List<Long>): JSONObject {
    if (micros.isEmpty()) return JSONObject().put("calls", 0)
    val sorted = micros.sorted()
    fun rank(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    return JSONObject().put("calls", sorted.size).put("medianUs", rank(0.5)).put("p90Us", rank(0.9)).put("maxUs", sorted.last())
}

private fun tesseractBaseline(runner: SupportDeviceTestRunner, case: JSONObject): JSONObject {
    val samples = samples(runner, case.getString("samples"))
    val readings = mutableListOf<JSONObject>()
    val micros = mutableListOf<Long>()
    collectGarbage()
    readings += memory("beforeCreate")
    // The same construction FixtureApi uses for every reader case.
    val ocr = TesseractOcrService(runner.targetContext)
    readings += memory("afterCreate")
    try {
        for (sample in samples) {
            val mat = Mat(sample.height, sample.width, CvType.CV_8UC1).apply { put(0, 0, sample.gray) }
            DroidCvPattern(mat).use { pattern ->
                // The support score reader's first profile, the one it always runs (OCR_PROFILES[0]).
                val started = System.nanoTime()
                ocr.detectTextLine(pattern, SupportScoreReader.OCR_OPTIONS)
                micros += (System.nanoTime() - started) / 1000
            }
        }
        readings += memory("afterReadingAll")
    } finally {
        ocr.close()
    }
    collectGarbage()
    readings += memory("afterCloseAndGc")
    return JSONObject()
        .put("samples", samples.size)
        .put("note", "TesseractOcrService creates its engine on the first call, so afterCreate holds no engine and firstCallUs includes the engine start")
        .put("firstCallUs", micros.firstOrNull())
        .put("perCall", timing(micros))
        .put("perCallAfterFirst", timing(micros.drop(1)))
        .put("memory", memoryReport(readings))
}

private fun engineMemory(runner: SupportDeviceTestRunner, case: JSONObject, engine: Engine): JSONObject {
    val samples = samples(runner, case.getString("samples"))
    val model = modelFile(runner, case.getString("model"))
    val readings = mutableListOf<JSONObject>()
    val threadsAround = mutableListOf<Int>()
    var errors = 0
    var firstError: String? = null
    collectGarbage()
    readings += memory("beforeLoad")
    threadsAround += processThreads()
    val load = try {
        okJson(engine.loadCall, engine.load(model.path)).also {
            readings += memory("afterLoad")
            threadsAround += processThreads()
            for (sample in samples) {
                val result = engine.read(sample.gray, sample.width, sample.height, "")
                if (!result.startsWith("OK: ")) { errors++; firstError = firstError ?: "${sample.file}: $result" }
            }
            readings += memory("afterReadingAll")
            threadsAround += processThreads()
        }
    } finally {
        engine.unload()
    }
    collectGarbage()
    readings += memory("afterUnloadAndGc")
    return JSONObject()
        .put("note", engine.memoryNote)
        .put("samples", samples.size)
        .put("loadMs", load.getLong("loadMs"))
        .put("readErrors", errors)
        .put("firstReadError", firstError)
        .put("memory", memoryReport(readings))
        .apply { for ((key, value) in engine.extra(load, threadsAround)) put(key, value) }
}

private fun engineRecognise(runner: SupportDeviceTestRunner, case: JSONObject, engine: Engine): JSONObject {
    val test = case.getString("test")
    val allowed = case.optString("allowed", "")
    val minEqual = case.getInt("minEqual")
    val samples = samples(runner, case.getString("samples"))
    val model = modelFile(runner, case.getString("model"))
    val engineMicros = mutableListOf<Long>()
    val callMicros = mutableListOf<Long>()
    val differing = JSONArray()
    val errors = JSONArray()
    var differingCount = 0
    var errorCount = 0
    var equal = 0
    val threadsAround = mutableListOf(processThreads())
    val load = try {
        okJson(engine.loadCall, engine.load(model.path)).also {
            threadsAround += processThreads()
            for (sample in samples) {
                val started = System.nanoTime()
                val result = engine.read(sample.gray, sample.width, sample.height, allowed)
                callMicros += (System.nanoTime() - started) / 1000
                if (!result.startsWith("OK: ")) {
                    errorCount++
                    if (errors.length() < MAX_LISTED) errors.put(JSONObject().put("file", sample.file).put("result", result))
                    continue
                }
                val read = JSONObject(result.removePrefix("OK: "))
                engineMicros += read.getLong("micros")
                val text = read.getString("text")
                if (text == sample.pyText) equal++ else {
                    differingCount++
                    if (differing.length() < MAX_LISTED) {
                        differing.put(JSONObject().put("file", sample.file).put("pyText", sample.pyText).put("text", text))
                    }
                }
            }
            threadsAround += processThreads()
        }
    } finally {
        engine.unload()
    }
    val report = JSONObject()
        .put("note", "perCrop is timed inside Rust around recognize only; perJniCall is the whole call from Kotlin and is the one to compare with tesseract-baseline perCall (whole detectTextLine with its preprocessing)" +
            if (allowed.isEmpty()) "" else ". The allowed set is rebuilt on every call, outside the perCrop span but inside perJniCall")
        .put("allowed", allowed)
        .put("samples", samples.size)
        .put("equalToPyText", equal)
        .put("minEqual", minEqual)
        .put("differingCount", differingCount)
        .put("differing", differing)
        .put("errorCount", errorCount)
        .put("errors", errors)
        .put("loadMs", load.getLong("loadMs"))
        .put("classes", load.getInt("classes"))
        // Inside Rust around recognize only; the JNI figure adds the array copy and the string round trip.
        .put("perCrop", timing(engineMicros))
        .put("perJniCall", timing(callMicros))
    for ((key, value) in engine.extra(load, threadsAround)) report.put(key, value)
    // summary-ocrProbe.md lists only failed cases, so the count is logged for passing ones too.
    Log.i(TAG, "$test: equalToPyText $equal of ${samples.size} (minEqual $minEqual, ERR $errorCount)")
    if (errorCount > 0 || equal < minEqual) {
        throw AssertionError("$test: $equal of ${samples.size} texts equal py_text (need $minEqual), $errorCount ERR. $report")
    }
    return report
}
