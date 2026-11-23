package io.github.fate_grand_automata.qa.suites

import android.graphics.BitmapFactory
import android.os.Debug
import android.util.Log
import io.github.fate_grand_automata.imaging.DroidCvPattern
import io.github.fate_grand_automata.imaging.TesseractOcrService
import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.qa.MeasuredOcr
import io.github.fate_grand_automata.qa.NativeProbe
import io.github.fate_grand_automata.qa.SupportDeviceTestRunner
import io.github.fate_grand_automata.sensing.OcrModelPin
import io.github.fate_grand_automata.sensing.RustSupportScoreEngine
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.fate_grand_automata.scripts.supportSelection.EngineDisplay
import io.github.fate_grand_automata.scripts.supportSelection.EngineField
import io.github.fate_grand_automata.scripts.supportSelection.EngineScore
import io.github.fate_grand_automata.scripts.supportSelection.EngineUnknownReason
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.SupportScores
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
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
 * Issue #568's `rust-engine` case is the exception: it loads the model packaged in the APK through
 * the product binding, so it needs no pushed model, and it runs after `rust-reader`, whose reads it
 * must equal.
 */
internal fun runOcrProbeCase(runner: SupportDeviceTestRunner, case: JSONObject): JSONObject {
    val test = case.getString("test")
    // Issue #549: the current Kotlin reader needs no Rust library.
    if (test == "kotlin-reader") return kotlinReader(runner, case).also { Log.i(TAG, "$test: $it") }.put("test", test)
    // Design 0026, completion criterion 3: these use the product binding, which must answer
    // unavailable with a reason, not crash, also in an APK without libfga_ffi.so.
    when (test) {
        "rust-engine-damaged-model", "rust-engine-short-model" -> return damagedModelCase(runner, case, test).also { Log.i(TAG, "$test: $it") }.put("test", test)
        "rust-engine-after-close" -> return afterCloseCase(runner, case).also { Log.i(TAG, "$test: $it") }.put("test", test)
    }
    if (!NativeProbe.loaded) {
        val reason = NativeProbe.skipReason()
        Log.w(TAG, "$test: skipped, $reason")
        return JSONObject().put("test", test).put("skipped", reason)
    }
    val actual = when (test) {
        "tesseract-baseline" -> tesseractBaseline(runner, case)
        "tract-memory" -> engineMemory(runner, case, TRACT)
        "tract-recognise", "tract-recognise-allowed" -> engineRecognise(runner, case, TRACT)
        "rust-reader" -> rustReader(runner, case)
        "rust-engine" -> rustEngine(runner, case)
        "ort-memory", "ort-recognise", "ort-recognise-allowed", "ort-recognise-1thread" -> {
            if (!ortPackaged()) return ortSkipped(test)
            val engine = ortEngine(case.optInt("threads", 0))
            if (test == "ort-memory") engineMemory(runner, case, engine) else engineRecognise(runner, case, engine)
        }
        // Issue #549: tract with a chosen minimum width and kernel thread count.
        else -> if (test.startsWith("tract-width-") || test.startsWith("tract-threads-")) {
            val threads = case.optInt("threads", 1)
            if (threads > 1 && !tractThreadsBuilt()) return tractThreadsSkipped(test)
            engineRecognise(runner, case, tractEngine(case.getInt("minWidth"), threads))
        } else error("Unknown ocrProbe test $test")
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
    /** tract only: (minimum width, threads), so a thread case can be held to the one-thread texts. */
    val tract: Pair<Int, Int>? = null,
    val extra: (load: JSONObject, threadsAround: List<Int>) -> Map<String, Any?> = { _, _ -> emptyMap() },
)

/**
 * The texts of each one-thread tract case of this run, by minimum width and allowed set. A
 * multi-threaded case must read exactly these: the PC found no difference, and tract splits its
 * work without changing the order of any sum (tract-linalg's multithread.rs).
 */
private val oneThreadTexts = mutableMapOf<String, List<String?>>()

private const val HWM_NOTE =
    "vmHwmKb is the process-wide peak; an earlier case (tesseract-baseline) may already have set it, so it moves only when this case goes higher"

/**
 * The width the Python results (`py_text`, `py_conf`) were read at, RapidOCR's. The tract-memory
 * and tract-recognise cases load at it, as the thread cases at 320 do, so `minEqual` keeps
 * comparing with Python; ocrLoad plans the reader's width, which is 320 as well.
 */
private const val PYTHON_WIDTH = 320

private val TRACT = Engine(
    "ocrLoadTract",
    { NativeProbe.ocrLoadTract(it, PYTHON_WIDTH, 1) },
    NativeProbe::ocrRead,
    NativeProbe::ocrUnload,
    tract = PYTHON_WIDTH to 1,
)

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
    ) + threadReport(threadsAround) + mapOf(
        "threadNote" to if (threads == 0) {
            "threads 0 is ONNX Runtime's default (one intra-op thread per physical core, calling thread included); processThreads shows the pool it created"
        } else {
            "intra-op pool of $threads, calling thread included; by ONNX Runtime's documentation the load creates ${threads - 1} pool threads (threadsCreatedByLoad, reported, not checked). The tract-recognise cases run on one thread: tract's matrix products run on the calling thread unless a tract-threads case, in a library built with the tract-threads feature, gives them a pool"
        },
    )
}

/** The process's thread counts around a load (before it, after it, after reading). */
private fun threadReport(threadsAround: List<Int>): Map<String, Any?> = mapOf(
    "availableProcessors" to Runtime.getRuntime().availableProcessors(),
    // processThreads() is -1 when /proc/self/task cannot be read; such a reading is left out
    // (JSONObject.put drops a null), so no difference is made from it.
    "processThreads" to JSONObject().put("beforeLoad", threadsAround.known(0)).put("afterLoad", threadsAround.known(1))
        .put("afterReading", threadsAround.known(2)),
    // Reported, not checked: ART may start or end its own threads during the load, so a failing
    // rule on this count could not be justified.
    "threadsCreatedByLoad" to threadsAround.known(1)?.let { after -> threadsAround.known(0)?.let { after - it } },
)

/**
 * Issue #549: tract with [minWidth] (the input every short crop is padded to; the Python results used 320)
 * and [threads] for its matrix kernels. 0 and 1 run them on the calling thread; more builds a
 * rayon pool of that many threads at load, so the load should create [threads] threads.
 */
private fun tractEngine(minWidth: Int, threads: Int) = Engine(
    "ocrLoadTract",
    { NativeProbe.ocrLoadTract(it, minWidth, threads) },
    NativeProbe::ocrRead,
    NativeProbe::ocrUnload,
    tract = minWidth to threads,
) { load, threadsAround ->
    mapOf(
        "minWidth" to load.getInt("minWidth"),
        "threads" to threads,
        "tractThreads" to load.getInt("threads"),
    ) + threadReport(threadsAround) + mapOf(
        "threadNote" to if (load.getInt("threads") <= 1) {
            "tract's single-threaded kernels on the calling thread, as in the tract-recognise cases; the load should create no thread (threadsCreatedByLoad, reported, not checked)"
        } else {
            "a rayon pool of ${load.getInt("threads")} threads, built at load and dropped at unload; the calling thread waits while the pool works, so the load should create ${load.getInt("threads")} threads (threadsCreatedByLoad, reported, not checked). tract runs a matrix product on the pool only when it has at least 64 panels or 2^21 multiply-adds (tract-linalg's defaults, not changed here)"
        },
    )
}

/** Whether libfga_ffi.so was built with fga-ffi's tract-threads feature. */
private fun tractThreadsBuilt(): Boolean = okJson("ocrTractFeatures", NativeProbe.ocrTractFeatures()).getBoolean("threadsBuilt")

/** tools/device-qa/run.py reads `requires`, as for the ONNX Runtime cases, with `--native-threads`. */
private fun tractThreadsSkipped(test: String): JSONObject = JSONObject().put("test", test).put("requires", "tract-threads")
    .put("skipped", "libfga_ffi.so was built without the tract-threads feature; build with -Pfga.native=true -Pfga.native.threads=true")

/** The ONNX Runtime functions exist only in a library built with the ort-probe feature. */
private fun ortPackaged(): Boolean = try {
    NativeProbe.ocrUnloadOrt()
    true
} catch (_: UnsatisfiedLinkError) {
    false
}

/** Threads of this process, from the kernel; -1 when the kernel's list cannot be read. */
private fun processThreads(): Int = File("/proc/self/task").list()?.size ?: -1

/** The reading at [index], or null when it is missing or could not be taken. */
private fun List<Int>.known(index: Int): Int? = getOrNull(index)?.takeIf { it >= 0 }
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
    // The rust-engine case's copy of the packaged model: the product keeps it between runs, a
    // check run does not leave it behind.
    val engineCopy = RustSupportScoreEngine.modelDir(runner.targetContext)
    check(engineCopy.deleteRecursively()) { "could not delete ${engineCopy.path}" }
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
    val texts = mutableListOf<String?>()
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
                    texts += null
                    errorCount++
                    if (errors.length() < MAX_LISTED) errors.put(JSONObject().put("file", sample.file).put("result", result))
                    continue
                }
                val read = JSONObject(result.removePrefix("OK: "))
                engineMicros += read.getLong("micros")
                val text = read.getString("text")
                texts += text
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
    // Issue #549: an observation case confirms a PC count on the device. Its minEqual is that PC
    // count, and a different count is reported, not failed; an ERR still fails.
    // A multi-threaded tract case must read exactly what the one-thread case at the same width and
    // allowed set read in this run; without that reading, or with one of another length, it fails.
    var referenceProblem: String? = null
    val tract = engine.tract
    val referenceKey = tract?.let { (minWidth, _) -> "$minWidth|$allowed" }
    if (tract != null && tract.second > 1) {
        val (minWidth, _) = tract
        val oneThreadCase = if (minWidth == PYTHON_WIDTH) {
            if (allowed.isEmpty()) "tract-recognise" else "tract-recognise-allowed"
        } else "tract-width-$minWidth" + if (allowed.isEmpty()) "" else "-allowed"
        val reference = oneThreadTexts[referenceKey]
        referenceProblem = when {
            reference == null -> "no one-thread reference: $oneThreadCase (minWidth $minWidth, allowed \"$allowed\") must pass earlier in the same run"
            reference.size != texts.size -> "the one-thread reference from $oneThreadCase holds ${reference.size} texts, this case read ${texts.size}"
            else -> {
                val differing = samples.indices.filter { texts[it] != reference[it] }
                val listed = JSONArray()
                for (i in differing.take(MAX_LISTED)) {
                    listed.put(JSONObject().put("file", samples[i].file).put("oneThread", reference[i] ?: JSONObject.NULL).put("text", texts[i] ?: JSONObject.NULL))
                }
                report.put("oneThreadReference", JSONObject().put("case", oneThreadCase).put("compared", samples.size)
                    .put("differ", differing.size).put("differing", listed))
                if (differing.isEmpty()) null else "${differing.size} texts differ from $oneThreadCase"
            }
        }
        referenceProblem?.let { report.put("oneThreadProblem", it) }
    }
    val observation = case.optBoolean("observation", false)
    if (observation) {
        report.put("observation", true).put("observationNote", "minEqual is the PC count at this setting; a lower equalToPyText is reported here and does not fail the case")
            .put("belowMinEqual", equal < minEqual)
    }
    Log.i(TAG, "$test: equalToPyText $equal of ${samples.size} (minEqual $minEqual, ERR $errorCount${if (observation) ", observation" else ""})")
    if (errorCount > 0 || (equal < minEqual && !observation) || referenceProblem != null) {
        throw AssertionError("$test: $equal of ${samples.size} texts equal py_text (need $minEqual), $errorCount ERR${referenceProblem?.let { ", $it" } ?: ""}. $report")
    }
    // Only a one-thread case that passed its own checks becomes the reference.
    if (tract != null && tract.second <= 1 && referenceKey != null) oneThreadTexts[referenceKey] = texts.toList()
    return report
}

/** Where SupportSuite's crop cases (support-score-d2.json) put a score crop: the score region of a row at y 200 (720p). */
private val SCORE_CROP_REGION_720 = listOf(1018, 355, 60, 30)
private const val SCORE_CROP_ROW_Y_720 = 200

private fun JSONObject.intOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)
private fun JSONObject.boolOrNull(key: String): Boolean? = if (!has(key) || isNull(key)) null else getBoolean(key)

/** Nearest-rank median and maximum of whole counts. */
private fun counts(values: List<Int>): JSONObject {
    if (values.isEmpty()) return JSONObject().put("fields", 0)
    val sorted = values.sorted()
    return JSONObject().put("fields", sorted.size).put("median", sorted[(ceil(0.5 * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)])
        .put("max", sorted.last()).put("total", sorted.sum())
}

/** The decoded crop size the crop path needs (SupportScoreLayout's 120x60 score region at 720p). */
private const val SCORE_CROP_WIDTH = 60
private const val SCORE_CROP_HEIGHT = 30

/**
 * A sample for [kotlinReader] and [rustReader]: present, and exactly the score region's size. The crop path pads a
 * smaller crop with the black canvas, where the full-frame path would have refused it.
 */
private fun checkScoreCrop(runner: SupportDeviceTestRunner, path: String) {
    val png = runner.fixture(path)
    check(png.isNotEmpty()) { "$path is missing on the device; push the OCR samples first (docs/agents/rust-native.md)" }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(png, 0, png.size, options)
    check(options.outWidth == SCORE_CROP_WIDTH && options.outHeight == SCORE_CROP_HEIGHT) {
        "$path is ${options.outWidth}x${options.outHeight}; the reader cases need ${SCORE_CROP_WIDTH}x$SCORE_CROP_HEIGHT score crops"
    }
}

private class ReaderRun(val scores: SupportScores, val micros: Long, val ocrCalls: Int)

/**
 * One field through the crop path of SupportSuite: FixtureApi places the crop on a black 1280x720
 * frame at the score region of row y 200, and SupportScoreReader reads that row with the row
 * server's profile. Only read() is timed: the frame is composed and the asset templates are
 * loaded before the clock starts.
 */
private fun readField(runner: SupportDeviceTestRunner, ocr: MeasuredOcr, path: String, server: String): ReaderRun {
    val fixture = JSONObject().put("name", path).put("suite", "support").put("frame", "crop")
        .put("file", path).put("region720", JSONArray(SCORE_CROP_REGION_720)).put("server", server)
    val api = FixtureApi(runner, fixture, JSONObject())
    try {
        val run = with(api) {
            val bound = SupportBounds(locations.support.defaultBounds.copy(y = SCORE_CROP_ROW_Y_720 * 2))
            for (image in listOf(Images.SupportScore62) + SupportScoreReader.COMPACT_CLASS_TEMPLATES + SupportScoreReader.GRAND_TEMPLATES) {
                images[image]
            }
            val reader = SupportScoreReader(api, ocr)
            useSameSnapIn {
                SupportScoreLayout.numberRegion(bound.region).getPattern("frame-warm-up").close()
                val callsBefore = ocr.calls
                val started = System.nanoTime()
                val read = reader.read(bound, true, true)
                ReaderRun(read, (System.nanoTime() - started) / 1000, ocr.calls - callsBefore)
            }
        }
        api.checkCoverage()
        return run
    } finally {
        api.close()
    }
}

/**
 * Issue #549: the real cost of the current reader per field. Each sample is the 60x30 gray crop
 * the reader got in the stage 0 replay (docs/qa/0025-stage-0-baseline.md 4.1), read through
 * [readField] with every OCR profile, the dark retry and the template matches, as in production.
 *
 * Before the timed loop, one JP and one KR field are read untimed: they start the shared Tesseract
 * engine, load SupportScoreBlocks' templates and make the KR profile's first use, none of which
 * is a per-field cost.
 *
 * The case fails on what a correctly driven reader always does, and reports the rest:
 * - every field made at least one OCR call: the reader runs its primary OCR profiles on every
 *   score region that is not clipped, and a 60x30 crop at this region never is, so a field
 *   without a call means the crop did not reach the reader;
 * - no false accept on a labelled row: the stage 0 replay of these crops had none, and the reader
 *   is built to answer "unknown" rather than a wrong value, so one is a finding to look at.
 * Agreement with the stage 0 result is only reported. The crop path hands the reader the same
 * pixels, but stage 0 ran on MuMu, and Tesseract on another CPU may round differently; there is no
 * measurement that would justify any allowed number of differences.
 */
private fun kotlinReader(runner: SupportDeviceTestRunner, case: JSONObject): JSONObject {
    val dir = case.getString("samples")
    val indexBytes = runner.fixture("$dir/index.json")
    check(indexBytes.isNotEmpty()) { "$dir/index.json is missing on the device; push the OCR samples first (docs/agents/rust-native.md)" }
    val index = JSONArray(indexBytes.toString(Charsets.UTF_8))
    val rows = (0 until index.length()).map { index.getJSONObject(it) }
    for (row in rows) {
        val file = row.getString("file")
        // An explicit null is "stage 0 read nothing"; a missing key would read as null too and
        // count as agreement, so it is refused.
        check(row.has("kt_cls") && row.has("kt_grand")) { "$dir/index.json: $file has no kt_cls or kt_grand" }
        // Without is62 and is16 a row would never count as a false accept, so the rule would hold
        // vacuously; an explicit null is a row without a label.
        check(row.has("is62") && row.has("is16")) { "$dir/index.json: $file has no is62 or is16" }
        checkScoreCrop(runner, "$dir/$file")
    }
    val threadsBefore = processThreads()
    val ocr = MeasuredOcr(TesseractOcrService(runner.targetContext))
    val micros = mutableListOf<Long>()
    val ocrCalls = mutableListOf<Int>()
    val byServer = mutableMapOf<String, MutableList<Long>>()
    val differences = JSONArray()
    var differenceCount = 0
    var sameValue = 0
    var bothNothing = 0
    val noOcr = mutableListOf<String>()
    val falseAccepts = mutableListOf<String>()
    val tally = mutableMapOf<String, Int>()
    fun count(key: String) { tally[key] = (tally[key] ?: 0) + 1 }
    fun tallied(key: String) = tally[key] ?: 0
    // SupportScoreBlocks loads its templates (a gzip resource) only when a JP read reaches the
    // two-digit block path, which a +62 that stage 0 read as 62 certainly does; a KR row pays the
    // first use of the KR profile (dark retry). Both warm-up reads are untimed.
    val jpWarmUp = rows.firstOrNull { it.getString("server") == "Jp" && it.boolOrNull("is62") == true && it.intOrNull("kt_cls") == 62 }
    val warmUps = listOfNotNull(
        (jpWarmUp ?: rows.firstOrNull { it.getString("server") == "Jp" })?.let { it to if (jpWarmUp != null) "Jp row labelled +62 that stage 0 read as 62" else "first Jp row: no Jp row is labelled +62 and read as 62 in stage 0, so the block templates may still load inside the timed span" },
        rows.firstOrNull { it.getString("server") == "Kr" }?.let { it to "first Kr row" },
    )
    val warmUpReport = JSONArray()
    try {
        for ((row, why) in warmUps) {
            val run = readField(runner, ocr, "$dir/${row.getString("file")}", row.getString("server"))
            warmUpReport.put(JSONObject().put("file", row.getString("file")).put("server", row.getString("server"))
                .put("why", why).put("us", run.micros).put("ocrCalls", run.ocrCalls))
        }
        for (row in rows) {
            val file = row.getString("file")
            val server = row.getString("server")
            val run = readField(runner, ocr, "$dir/$file", server)
            micros += run.micros
            ocrCalls += run.ocrCalls
            byServer.getOrPut(server) { mutableListOf() } += run.micros
            if (run.ocrCalls == 0) noOcr += file
            val cls = run.scores.classScore
            val grand = run.scores.grandScore
            when (row.boolOrNull("is62")) {
                true -> { count("positives62"); if (cls == 62) count("read62") }
                false -> if (cls == 62) { count("falseAccepts62"); falseAccepts += "$file +62" }
                null -> Unit
            }
            when (row.boolOrNull("is16")) {
                true -> { count("positives16"); if (grand == 16) count("read16") }
                false -> if (grand == 16) { count("falseAccepts16"); falseAccepts += "$file +16" }
                null -> Unit
            }
            row.intOrNull("cls")?.let { label -> count("classLabels"); if (cls == label) count("classValueEqual") }
            // kt_cls and kt_grand are what the reader returned in the stage 0 replay the crops were cut from.
            val stage0Class = row.intOrNull("kt_cls")
            val stage0Grand = row.intOrNull("kt_grand")
            when {
                cls != stage0Class || grand != stage0Grand -> {
                    differenceCount++
                    if (differences.length() < MAX_LISTED) differences.put(JSONObject().put("file", file).put("case", row.optString("case"))
                        .put("stage0", JSONObject().put("class", stage0Class ?: JSONObject.NULL).put("grand", stage0Grand ?: JSONObject.NULL))
                        .put("now", JSONObject().put("class", cls ?: JSONObject.NULL).put("grand", grand ?: JSONObject.NULL)))
                }
                cls == null && grand == null -> bothNothing++
                else -> sameValue++
            }
        }
    } finally {
        ocr.close()
    }
    val perField = timing(micros)
    val calls = counts(ocrCalls)
    Log.i(TAG, "kotlin-reader: +62 read ${tallied("read62")} of ${tallied("positives62")} (stage 0: 74 of 86), " +
        "+16 read ${tallied("read16")} of ${tallied("positives16")} (stage 0: 142 of 167), " +
        "false accepts +62 ${tallied("falseAccepts62")} +16 ${tallied("falseAccepts16")}, " +
        "OCR calls per field median ${calls.opt("median")} max ${calls.opt("max")}, " +
        "time per field median ${perField.opt("medianUs")} us p90 ${perField.opt("p90Us")} us max ${perField.opt("maxUs")} us, " +
        "stage 0 same value $sameValue, both nothing $bothNothing, differ $differenceCount")
    val report = JSONObject()
        .put("note", "SupportScoreReader.read(bound, true, true) through the crop path of SupportSuite (FixtureApi frame crop, region720 $SCORE_CROP_REGION_720, row y720 $SCORE_CROP_ROW_Y_720) with the profile of the row server; perField times read() alone, after untimed warm-up reads of one JP and one KR field (Tesseract start, SupportScoreBlocks templates, first KR profile use; see warmUp) and with the frame composed and the asset templates loaded beforehand")
        .put("samples", rows.size)
        .put("warmUp", warmUpReport)
        .put("perField", perField)
        .put("perFieldByServer", JSONObject().apply { for ((server, list) in byServer) put(server, timing(list)) })
        .put("ocrCallsPerField", calls)
        .put("stage0", JSONObject().put("sameValue", sameValue).put("bothNothing", bothNothing).put("differ", differenceCount)
            .put("differences", differences)
            .put("note", "reported, not checked: the same pixels, but stage 0 ran on MuMu and Tesseract may round differently on another CPU"))
        .put("plus62", JSONObject().put("positives", tallied("positives62")).put("read", tallied("read62"))
            .put("falseAccepts", tallied("falseAccepts62")).put("stage0", "74 of 86 read, 0 false accepts"))
        .put("plus16", JSONObject().put("positives", tallied("positives16")).put("read", tallied("read16"))
            .put("falseAccepts", tallied("falseAccepts16")).put("stage0", "142 of 167 read, 0 false accepts"))
        .put("classValue", JSONObject().put("labelled", tallied("classLabels")).put("equal", tallied("classValueEqual")))
        .put("processThreads", JSONObject().put("before", threadsBefore.takeIf { it >= 0 }).put("after", processThreads().takeIf { it >= 0 }))
    if (noOcr.isNotEmpty() || falseAccepts.isNotEmpty()) {
        throw AssertionError("kotlin-reader: fields without an OCR call ${noOcr.take(MAX_LISTED)}, false accepts ${falseAccepts.take(MAX_LISTED)}. $report")
    }
    return report
}

/**
 * Issue #555: the Rust support-score reader (`supportScoreRead`: the PP-OCRv6 recogniser plus the
 * rules of fga-sensing's `SupportScoreReader`) on the same 370 crops as [kotlinReader], with the
 * same definitions: a field is read when the reader returns 62 (class) or 16 (Grand), missed
 * when a labelled positive is not, and a false accept when a row labelled not-62 or not-16 is
 * returned as 62 or 16. The labels and the stage 0 results (`kt_cls`, `kt_grand`) are the ones
 * [kotlinReader] uses.
 *
 * The case fails when a call returns `ERR`, on a false accept, and on a wrong value: a labelled
 * class or Grand value read as another number, and also an `is62` row that reads a class value
 * other than 62 or an `is16` row that reads a Grand value other than 16, whether or not the
 * number itself is labelled (111 rows have `is16` and no `grand`). It also fails when it reads
 * fewer +62 or +16 than `minRead62` and `minRead16` of the case file, the PC counts of the final
 * reader: the texts are the same on a device, so a lower count is a regression to investigate,
 * not a number to lower, and a reader that returns nothing cannot pass. Misses above those
 * floors are reported next to the Kotlin reader's, which is what the comparison is for.
 */
private fun rustReader(runner: SupportDeviceTestRunner, case: JSONObject): JSONObject {
    val dir = case.getString("samples")
    val indexBytes = runner.fixture("$dir/index.json")
    check(indexBytes.isNotEmpty()) { "$dir/index.json is missing on the device; push the OCR samples first (docs/agents/rust-native.md)" }
    val index = JSONArray(indexBytes.toString(Charsets.UTF_8))
    val rows = (0 until index.length()).map { index.getJSONObject(it) }
    // With no rows every tally is zero, so a lowered floor would pass an empty index.
    check(rows.isNotEmpty()) { "$dir/index.json has no rows" }
    for (row in rows) {
        val file = row.getString("file")
        check(row.has("kt_cls") && row.has("kt_grand")) { "$dir/index.json: $file has no kt_cls or kt_grand" }
        check(row.has("is62") && row.has("is16")) { "$dir/index.json: $file has no is62 or is16" }
        // A missing value label would read as "no label" and let a wrong value pass.
        check(row.has("cls") && row.has("grand")) { "$dir/index.json: $file has no cls or grand" }
        checkScoreCrop(runner, "$dir/$file")
    }
    val minRead62 = case.getInt("minRead62")
    val minRead16 = case.getInt("minRead16")
    val samples = samples(runner, dir)
    check(samples.size == rows.size) { "${samples.size} decoded samples for ${rows.size} index rows" }
    val model = modelFile(runner, case.getString("model"))
    val engineMicros = mutableListOf<Long>()
    val callMicros = mutableListOf<Long>()
    val byServer = mutableMapOf<String, MutableList<Long>>()
    val reasons = mutableMapOf<String, Int>()
    val errors = JSONArray()
    var errorCount = 0
    val wrong = mutableListOf<String>()
    val falseAccepts = mutableListOf<String>()
    val tally = mutableMapOf<String, Int>()
    fun count(key: String) { tally[key] = (tally[key] ?: 0) + 1 }
    fun tallied(key: String) = tally[key] ?: 0
    val differences = JSONArray()
    var differenceCount = 0
    var sameValue = 0
    var bothNothing = 0
    rustReaderReads.clear()
    rustReaderPassed = false
    val load = try {
        okJson("ocrLoad", NativeProbe.ocrLoad(model.path)).also {
            for ((row, sample) in rows.zip(samples)) {
                val file = row.getString("file")
                val started = System.nanoTime()
                val result = NativeProbe.supportScoreRead(sample.gray, sample.width, sample.height)
                val callUs = (System.nanoTime() - started) / 1000
                if (!result.startsWith("OK: ")) {
                    errorCount++
                    if (errors.length() < MAX_LISTED) errors.put(JSONObject().put("file", file).put("result", result))
                    continue
                }
                val read = JSONObject(result.removePrefix("OK: "))
                rustReaderReads[file] = RustReaderRead(read, sample.gray)
                engineMicros += read.getLong("micros")
                callMicros += callUs
                byServer.getOrPut(row.getString("server")) { mutableListOf() } += callUs
                val cls = read.intOrNull("class")
                val grand = read.intOrNull("grand")
                for (key in listOf("classReason", "grandReason")) {
                    if (!read.isNull(key)) {
                        val reasonKey = "$key.${read.getString(key)}"
                        reasons[reasonKey] = (reasons[reasonKey] ?: 0) + 1
                    }
                }
                when (row.boolOrNull("is62")) {
                    true -> {
                        count("positives62")
                        if (cls == 62) count("read62")
                        else if (cls != null && row.intOrNull("cls") == null) { count("classWrong"); wrong += "$file class $cls, is62" }
                    }
                    false -> if (cls == 62) { count("falseAccepts62"); falseAccepts += "$file +62" }
                    null -> Unit
                }
                when (row.boolOrNull("is16")) {
                    true -> {
                        count("positives16")
                        if (grand == 16) count("read16")
                        else if (grand != null && row.intOrNull("grand") == null) { count("grandWrong"); wrong += "$file grand $grand, is16" }
                    }
                    false -> if (grand == 16) { count("falseAccepts16"); falseAccepts += "$file +16" }
                    null -> Unit
                }
                row.intOrNull("cls")?.let { label ->
                    count("classLabels")
                    when (cls) {
                        label -> count("classValueEqual")
                        null -> count("classUnknown")
                        else -> { count("classWrong"); wrong += "$file class $cls, label $label" }
                    }
                }
                row.intOrNull("grand")?.let { label ->
                    count("grandLabels")
                    when (grand) {
                        label -> count("grandValueEqual")
                        null -> count("grandUnknown")
                        else -> { count("grandWrong"); wrong += "$file grand $grand, label $label" }
                    }
                }
                val stage0Class = row.intOrNull("kt_cls")
                val stage0Grand = row.intOrNull("kt_grand")
                when {
                    cls != stage0Class || grand != stage0Grand -> {
                        differenceCount++
                        if (differences.length() < MAX_LISTED) differences.put(JSONObject().put("file", file).put("case", row.optString("case"))
                            .put("kotlin", JSONObject().put("class", stage0Class ?: JSONObject.NULL).put("grand", stage0Grand ?: JSONObject.NULL))
                            .put("rust", JSONObject().put("class", cls ?: JSONObject.NULL).put("grand", grand ?: JSONObject.NULL)))
                    }
                    cls == null && grand == null -> bothNothing++
                    else -> sameValue++
                }
            }
        }
    } finally {
        NativeProbe.ocrUnload()
    }
    val perField = timing(callMicros)
    Log.i(TAG, "rust-reader: +62 read ${tallied("read62")} of ${tallied("positives62")} (Kotlin stage 0: 74 of 86), " +
        "+16 read ${tallied("read16")} of ${tallied("positives16")} (Kotlin stage 0: 142 of 167), " +
        "false accepts +62 ${tallied("falseAccepts62")} +16 ${tallied("falseAccepts16")}, " +
        "class value equal ${tallied("classValueEqual")} of ${tallied("classLabels")} wrong ${tallied("classWrong")}, " +
        "time per field median ${perField.opt("medianUs")} us p90 ${perField.opt("p90Us")} us max ${perField.opt("maxUs")} us, " +
        "Kotlin stage 0 same value $sameValue, both nothing $bothNothing, differ $differenceCount, ERR $errorCount")
    val report = JSONObject()
        .put("note", "supportScoreRead on the recogniser ocrLoad loaded (the reader width minWidth, one thread, as the product engine) with the default ScoreConfig; perField is the whole JNI call from Kotlin, perFieldRust is inside Rust around recognise, pixel checks and rules")
        .put("samples", rows.size)
        .put("loadMs", load.getLong("loadMs"))
        .put("minWidth", load.getInt("minWidth"))
        .put("perField", perField)
        .put("perFieldRust", timing(engineMicros))
        .put("perFieldByServer", JSONObject().apply { for ((server, list) in byServer) put(server, timing(list)) })
        .put("plus62", JSONObject().put("positives", tallied("positives62")).put("read", tallied("read62"))
            .put("missed", tallied("positives62") - tallied("read62")).put("falseAccepts", tallied("falseAccepts62"))
            .put("kotlinStage0", "74 of 86 read, 12 missed, 0 false accepts"))
        .put("plus16", JSONObject().put("positives", tallied("positives16")).put("read", tallied("read16"))
            .put("missed", tallied("positives16") - tallied("read16")).put("falseAccepts", tallied("falseAccepts16"))
            .put("kotlinStage0", "142 of 167 read, 25 missed, 0 false accepts"))
        .put("classValue", JSONObject().put("labelled", tallied("classLabels")).put("equal", tallied("classValueEqual"))
            .put("unknown", tallied("classUnknown")).put("wrong", tallied("classWrong")))
        .put("grandValue", JSONObject().put("labelled", tallied("grandLabels")).put("equal", tallied("grandValueEqual"))
            .put("unknown", tallied("grandUnknown")).put("wrong", tallied("grandWrong")))
        .put("unknownReasons", JSONObject().apply { for ((key, value) in reasons.toSortedMap()) put(key, value) })
        .put("kotlinStage0", JSONObject().put("sameValue", sameValue).put("bothNothing", bothNothing).put("differ", differenceCount)
            .put("differences", differences)
            .put("note", "rows where the Rust result differs from kt_cls/kt_grand; the Kotlin reader only returns the 62 and 16 its policy asks for, so most rows differ when Rust returns another class value"))
        .put("errorCount", errorCount)
        .put("errors", errors)
    val belowFloor = tallied("read62") < minRead62 || tallied("read16") < minRead16
    report.put("minRead62", minRead62).put("minRead16", minRead16)
    if (errorCount > 0 || falseAccepts.isNotEmpty() || wrong.isNotEmpty() || belowFloor) {
        throw AssertionError("rust-reader: $errorCount ERR ${errors.toString().take(300)}, false accepts ${falseAccepts.take(MAX_LISTED)}, wrong values ${wrong.take(MAX_LISTED)}, +62 read ${tallied("read62")} (need $minRead62), +16 read ${tallied("read16")} (need $minRead16). $report")
    }
    rustReaderPassed = true
    return report
}

/** What rust-reader read for one sample in this run, and the bytes it fed, for [rustEngine]. */
private class RustReaderRead(val json: JSONObject, val gray: ByteArray)

private val rustReaderReads = mutableMapOf<String, RustReaderRead>()
private var rustReaderPassed = false

/** fga-sensing's reason names, as supportScoreRead writes them. */
private fun EngineUnknownReason.wireName(): String? = when (this) {
    EngineUnknownReason.Blank -> "blank"
    EngineUnknownReason.LowConfidence -> "low_confidence"
    EngineUnknownReason.Format -> "format"
    EngineUnknownReason.Range -> "range"
    EngineUnknownReason.Clipped -> "clipped"
    EngineUnknownReason.NotShown -> "not_shown"
    EngineUnknownReason.CropSize -> "crop_size"
    EngineUnknownReason.EngineError, EngineUnknownReason.Unavailable -> null
}

private fun EngineField.reasonName(): String? = (this as? EngineField.Unknown)?.reason?.wireName()

private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
private fun JSONObject.floatOrNull(key: String): Float? = if (!has(key) || isNull(key)) null else getDouble(key).toFloat()

/** The fields of [score] that differ from rust-reader's JSON for the same sample. */
private fun differencesFrom(score: EngineScore, probe: JSONObject): List<String> {
    val pairs = listOf(
        "class" to (score.classScore.valueOrNull to probe.intOrNull("class")),
        "grand" to (score.grandScore.valueOrNull to probe.intOrNull("grand")),
        "display" to (score.display.name.lowercase() to probe.getString("display")),
        "classReason" to (score.classScore.reasonName() to probe.stringOrNull("classReason")),
        "grandReason" to (score.grandScore.reasonName() to probe.stringOrNull("grandReason")),
        "text" to (score.text to probe.getString("text")),
        // The engine sends f32; the probe's JSON holds the f64 the f32 came from.
        "confidence" to (score.confidence to probe.floatOrNull("confidence")),
        "minCharConfidence" to (score.minCharConfidence to probe.floatOrNull("minCharConfidence")),
    )
    return pairs.filter { (_, values) -> values.first != values.second }.map { (key, values) -> "$key ${values.first} != ${values.second}" }
}

/** VmRSS and VmHWM of this process in kB, from the kernel; -1 when a line is missing. */
private fun procStatus(): Map<String, Long> {
    val text = File("/proc/self/status").readText()
    return listOf("VmRSS", "VmHWM").associateWith { key ->
        Regex("""(?m)^$key:\s+(\d+) kB""").find(text)?.groupValues?.get(1)?.toLong() ?: -1L
    }
}

private fun javaHeapKb(): Long = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1024 }

private fun engineMemory(point: String): JSONObject {
    val status = procStatus()
    return JSONObject().put("point", point).put("vmRssKb", status["VmRSS"]).put("vmHwmKb", status["VmHWM"])
        .put("javaHeapKb", javaHeapKb()).put("nativeHeapKb", Debug.getNativeHeapAllocatedSize() / 1024)
}

private class PeakSampler : Thread() {
    @Volatile var running = true
    @Volatile var maxHeapKb = javaHeapKb()
    @Volatile var maxRssKb = procStatus()["VmRSS"] ?: -1L
    @Volatile var samples = 0

    override fun run() {
        while (running) {
            maxHeapKb = maxOf(maxHeapKb, javaHeapKb())
            maxRssKb = maxOf(maxRssKb, procStatus()["VmRSS"] ?: -1L)
            samples++
            sleep(5)
        }
    }
}

/**
 * Samples the Java heap and VmRSS every few milliseconds while [block] runs, so the report has the
 * highest values during the load and not only the values after it.
 */
private fun <T> sampledPeaks(block: () -> T): Pair<T, JSONObject> {
    val sampler = PeakSampler().apply { isDaemon = true; start() }
    val result = try { block() } finally { sampler.running = false; sampler.join() }
    return result to JSONObject().put("maxJavaHeapKb", sampler.maxHeapKb).put("maxVmRssKb", sampler.maxRssKb).put("samples", sampler.samples)
}

/**
 * Issue #568: the product binding (RustSupportScoreEngine, as the rust build type's Hilt provider
 * builds it) on the model packaged in this APK's assets, reading the 370 crops through the Pattern
 * path a script uses: FixtureApi's crop frame, SupportScoreLayout.numberRegion of a row at y 200,
 * getPattern. The case needs no model on the device.
 *
 * It fails when the engine is not available (a library that is loaded but a model asset that is
 * missing or does not load is a failure, not a skip), when any read is an engine error, when the
 * bytes the Pattern holds differ from the bytes rust-reader fed for the same sample (the two
 * paths must give the reader the same pixels), when any field differs from rust-reader's read of
 * the same sample, which must have passed earlier in this run, and on rust-reader's own rules: a
 * false accept, a wrong value, or fewer than minRead62 / minRead16 reads.
 */
private fun rustEngine(runner: SupportDeviceTestRunner, case: JSONObject): JSONObject {
    val dir = case.getString("samples")
    check(rustReaderPassed) { "rust-engine compares with rust-reader, which must pass earlier in the same run" }
    val index = JSONArray(runner.fixture("$dir/index.json").toString(Charsets.UTF_8))
    val rows = (0 until index.length()).map { index.getJSONObject(it) }
    check(rows.isNotEmpty()) { "$dir/index.json has no rows" }
    check(rows.size == rustReaderReads.size) { "rust-reader read ${rustReaderReads.size} samples, the index has ${rows.size}" }
    val minRead62 = case.getInt("minRead62")
    val minRead16 = case.getInt("minRead16")

    val context = runner.targetContext
    val copyDir = RustSupportScoreEngine.modelDir(context)
    val copyBefore = copyDir.listFiles()?.map { it.name } ?: emptyList()
    // VmHWM is the process peak since start; writing 5 to clear_refs resets it to the current RSS
    // (Linux 4.0+), so the value after the load is the load's own peak. Reported if it is refused.
    val hwmReset = try { File("/proc/self/clear_refs").writeText("5"); true } catch (_: Exception) { false }
    val readings = mutableListOf<JSONObject>()
    collectGarbage()
    readings += engineMemory("beforeLoad")
    val engine = RustSupportScoreEngine.forApp(context)
    val reads = EngineReads()
    var loadPeaks = JSONObject()
    try {
        val (available, peaks) = sampledPeaks { engine.available }
        loadPeaks = peaks
        readings += engineMemory("afterLoad")
        if (!available) {
            val why = Mat(30, 60, CvType.CV_8UC1).let { mat -> DroidCvPattern(mat).use { engine.read(it).detail } }
            throw AssertionError("rust-engine: the engine is not available: $why")
        }
        reads.readSamples(runner, dir, rows, engine, "rust-engine")
        readings += engineMemory("afterReadingAll")
    } finally {
        engine.close()
    }
    collectGarbage()
    readings += engineMemory("afterCloseAndGc")
    val copyAfter = copyDir.listFiles()?.map { it.name } ?: emptyList()
    val timingReport = timing(reads.perField)
    val report = JSONObject()
        .put("note", "RustSupportScoreEngine.forApp on the packaged model; perField is engine.read() as a script sees it (gray copy, JNI, reply parsing); rust-reader's Rust-side time for the same samples is beside it. Memory: vmHwmKb after the load is the load's own peak when hwmReset is true; peaksDuringLoad samples the Java heap and VmRSS every 5 ms during the load, the model copy included")
        .put("samples", rows.size)
        .put("loadMs", engine.loadMillis)
        .put("modelCopy", JSONObject().put("before", JSONArray(copyBefore)).put("after", JSONArray(copyAfter)))
        .put("hwmReset", hwmReset)
        .put("peaksDuringLoad", loadPeaks)
        .put("memory", JSONArray(readings))
    reads.putInto(report, minRead62, minRead16)
    Log.i(TAG, "rust-engine: ${reads.summary()}, load ${engine.loadMillis} ms, per field median ${timingReport.opt("medianUs")} us p90 ${timingReport.opt("p90Us")} us max ${timingReport.opt("maxUs")} us, " +
        "memory ${readings.joinToString { "${it.getString("point")} rss ${it.opt("vmRssKb")} hwm ${it.opt("vmHwmKb")} heap ${it.opt("javaHeapKb")} native ${it.opt("nativeHeapKb")}" }}, " +
        "peaks during load $loadPeaks, hwmReset $hwmReset, model copy before $copyBefore after $copyAfter")
    reads.failure(rows.size, minRead62, minRead16)?.let { throw AssertionError("rust-engine: $it. $report") }
    return report
}

/** What [readSamples] found, with rust-engine's rules for a pass. */
private class EngineReads {
    val tally = mutableMapOf<String, Int>()
    fun count(key: String) { tally[key] = (tally[key] ?: 0) + 1 }
    fun tallied(key: String) = tally[key] ?: 0
    val wrong = mutableListOf<String>()
    val falseAccepts = mutableListOf<String>()
    val engineErrors = mutableListOf<String>()
    val pixelMismatch = mutableListOf<String>()
    val fieldMismatch = mutableListOf<String>()
    val perField = mutableListOf<Long>()
    val perFieldRust = mutableListOf<Long>()
    val reasons = mutableMapOf<String, Int>()
    var compared = 0

    fun putInto(report: JSONObject, minRead62: Int, minRead16: Int) {
        report.put("compared", compared)
            .put("perField", timing(perField))
            .put("rustReaderPerFieldRust", timing(perFieldRust))
            .put("plus62", JSONObject().put("positives", tallied("positives62")).put("read", tallied("read62")).put("minRead62", minRead62))
            .put("plus16", JSONObject().put("positives", tallied("positives16")).put("read", tallied("read16")).put("minRead16", minRead16))
            .put("unknownReasons", JSONObject().apply { for ((key, value) in reasons.toSortedMap()) put(key, value) })
            .put("pixelMismatchCount", pixelMismatch.size).put("pixelMismatch", JSONArray(pixelMismatch.take(MAX_LISTED)))
            .put("fieldMismatchCount", fieldMismatch.size).put("fieldMismatch", JSONArray(fieldMismatch.take(MAX_LISTED)))
            .put("engineErrorCount", engineErrors.size).put("engineErrors", JSONArray(engineErrors.take(MAX_LISTED)))
            .put("falseAccepts", JSONArray(falseAccepts.take(MAX_LISTED))).put("wrong", JSONArray(wrong.take(MAX_LISTED)))
    }

    fun summary() = "+62 read ${tallied("read62")} of ${tallied("positives62")}, +16 read ${tallied("read16")} of ${tallied("positives16")}, " +
        "false accepts ${falseAccepts.size}, wrong ${wrong.size}, pixel mismatch ${pixelMismatch.size}, field mismatch ${fieldMismatch.size} of $compared, " +
        "engine errors ${engineErrors.size}"

    /** Null when every rule of rust-engine holds. */
    fun failure(rows: Int, minRead62: Int, minRead16: Int): String? {
        val belowFloor = tallied("read62") < minRead62 || tallied("read16") < minRead16
        if (engineErrors.isEmpty() && pixelMismatch.isEmpty() && fieldMismatch.isEmpty() && compared == rows &&
            falseAccepts.isEmpty() && wrong.isEmpty() && !belowFloor
        ) return null
        return "engine errors ${engineErrors.take(5)}, pixel mismatch ${pixelMismatch.take(5)}, field mismatch ${fieldMismatch.take(5)}, compared $compared of $rows, false accepts ${falseAccepts.take(5)}, wrong ${wrong.take(5)}, +62 read ${tallied("read62")} (need $minRead62), +16 read ${tallied("read16")} (need $minRead16)"
    }
}

/**
 * Reads every sample of [rows] with [engine] through the Pattern path a script uses and compares
 * each with rust-reader's read of the same sample, as rust-engine does.
 */
private fun EngineReads.readSamples(runner: SupportDeviceTestRunner, dir: String, rows: List<JSONObject>, engine: RustSupportScoreEngine, tag: String) {
    for (row in rows) {
        val file = row.getString("file")
        val path = "$dir/$file"
        checkScoreCrop(runner, path)
        val reference = checkNotNull(rustReaderReads[file]) { "rust-reader has no read of $file" }
        val fixture = JSONObject().put("name", path).put("suite", "support").put("frame", "crop")
            .put("file", path).put("region720", JSONArray(SCORE_CROP_REGION_720)).put("server", row.getString("server"))
        val api = FixtureApi(runner, fixture, JSONObject())
        val score = try {
            with(api) {
                val bound = SupportBounds(locations.support.defaultBounds.copy(y = SCORE_CROP_ROW_Y_720 * 2))
                useSameSnapIn {
                    SupportScoreLayout.numberRegion(bound.region).getPattern(tag).use { pattern ->
                        val gray = pattern.grayPixels()
                        if (gray == null || pattern.width != SCORE_CROP_WIDTH || pattern.height != SCORE_CROP_HEIGHT || !gray.contentEquals(reference.gray)) {
                            val first = gray?.indices?.firstOrNull { it >= reference.gray.size || gray[it] != reference.gray[it] }
                            pixelMismatch += "$file ${pattern.width}x${pattern.height}" + if (gray == null) " not gray" else " first differing byte $first"
                        }
                        engine.read(pattern)
                    }
                }
            }.also { api.checkCoverage() }
        } finally {
            api.close()
        }
        perField += score.duration.inWholeMicroseconds
        val classScore = score.classScore
        val grandScore = score.grandScore
        val failed = listOf(EngineUnknownReason.EngineError, EngineUnknownReason.Unavailable).map { EngineField.Unknown(it) }
        if (classScore in failed || grandScore in failed) {
            engineErrors += "$file ${score.detail}"
            continue
        }
        for ((key, field) in listOf("classReason" to classScore, "grandReason" to grandScore)) {
            field.reasonName()?.let { reasons["$key.$it"] = (reasons["$key.$it"] ?: 0) + 1 }
        }
        compared++
        differencesFrom(score, reference.json).takeIf { it.isNotEmpty() }?.let { fieldMismatch += "$file: ${it.joinToString()}" }
        perFieldRust += reference.json.getLong("micros")
        val cls = classScore.valueOrNull
        val grand = grandScore.valueOrNull
        when (row.boolOrNull("is62")) {
            true -> {
                count("positives62")
                if (cls == 62) count("read62") else if (cls != null && row.intOrNull("cls") == null) wrong += "$file class $cls, is62"
            }
            false -> if (cls == 62) falseAccepts += "$file +62"
            null -> Unit
        }
        when (row.boolOrNull("is16")) {
            true -> {
                count("positives16")
                if (grand == 16) count("read16") else if (grand != null && row.intOrNull("grand") == null) wrong += "$file grand $grand, is16"
            }
            false -> if (grand == 16) falseAccepts += "$file +16"
            null -> Unit
        }
        row.intOrNull("cls")?.let { label -> if (cls != null && cls != label) wrong += "$file class $cls, label $label" }
        row.intOrNull("grand")?.let { label -> if (grand != null && grand != label) wrong += "$file grand $grand, label $label" }
    }
}

/** The rows of the samples' index, checked against what rust-reader read earlier in this run. */
private fun engineRows(runner: SupportDeviceTestRunner, case: JSONObject, test: String): List<JSONObject> {
    val dir = case.getString("samples")
    check(rustReaderPassed) { "$test compares with rust-reader, which must pass earlier in the same run" }
    val index = JSONArray(runner.fixture("$dir/index.json").toString(Charsets.UTF_8))
    val rows = (0 until index.length()).map { index.getJSONObject(it) }
    check(rows.isNotEmpty()) { "$dir/index.json has no rows" }
    check(rows.size == rustReaderReads.size) { "rust-reader read ${rustReaderReads.size} samples, the index has ${rows.size}" }
    return rows
}

/** Reads the samples with [engine] and fails on rust-engine's rules; the counts go into [report]. */
private fun checkSampleReads(runner: SupportDeviceTestRunner, case: JSONObject, test: String, engine: RustSupportScoreEngine, report: JSONObject) {
    val rows = engineRows(runner, case, test)
    val minRead62 = case.getInt("minRead62")
    val minRead16 = case.getInt("minRead16")
    val reads = EngineReads()
    reads.readSamples(runner, case.getString("samples"), rows, engine, test)
    reads.putInto(report.put("samples", rows.size), minRead62, minRead16)
    Log.i(TAG, "$test: ${reads.summary()}")
    reads.failure(rows.size, minRead62, minRead16)?.let { throw AssertionError("$test: $it. $report") }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun modelPin(runner: SupportDeviceTestRunner): OcrModelPin =
    runner.targetContext.assets.open(RustSupportScoreEngine.PIN_ASSET).use { OcrModelPin.parse(it) }

/** Offsets inside the 21 MB model, where a size check cannot see a change. */
private val DAMAGE_OFFSETS = listOf(1_000_000L, 5_000_000L, 10_000_000L, 15_000_000L, 20_000_000L)

/** What a call into the product binding answered; an exception out of it fails the case. */
private inline fun <T> noThrow(test: String, what: String, block: () -> T): T = try {
    block()
} catch (error: Throwable) {
    throw AssertionError("$test: $what threw out of the engine: $error", error)
}

/** In an APK without libfga_ffi.so: the engine must be unavailable with the loader's reason. */
private fun checkUnavailableWithoutLibrary(test: String, available: Boolean, reason: String?) {
    check(!available && reason != null && reason.startsWith("libfga_ffi.so did not load")) {
        "$test: without the library the engine must be unavailable with the loader's reason; available $available, reason $reason"
    }
}

/**
 * Design 0026, completion criterion 3, on the installed copy of the model (issue #665).
 * `rust-engine-damaged-model` inverts a few bytes inside the copy (same size), `rust-engine-short-model`
 * cuts it to half. The rule (OcrModelInstaller) is that the engine's first use finds the copy does not
 * match the pin, copies it again from the asset and loads: so with the library the case fails unless
 * the engine is available, the copy matches the pin again afterwards and the stored samples read as in
 * rust-engine. Without the library (an APK repackaged without libfga_ffi.so) it fails unless the engine
 * is unavailable with the loader's reason. An exception out of the engine fails it either way.
 *
 * The copy is restored from a backup in `finally`. A run killed in between leaves the damaged copy
 * (and the backup in the cache folder): the next engine load finds it does not match and replaces it.
 */
private fun damagedModelCase(runner: SupportDeviceTestRunner, case: JSONObject, test: String): JSONObject {
    val context = runner.targetContext
    val libraryLoads = NativeProbe.loaded
    val pin = modelPin(runner)
    // The intact copy an earlier run leaves; the copy needs no library.
    val installed = RustSupportScoreEngine.installedModel(context)
    val backup = File(copyDir(runner), "engine-model-backup.onnx")
    checkNotNull(backup.parentFile).mkdirs()
    installed.copyTo(backup, overwrite = true)
    val report = JSONObject().put("libraryLoads", libraryLoads)
    try {
        RandomAccessFile(installed, "rw").use { file ->
            if (test == "rust-engine-short-model") {
                file.setLength(pin.size / 2)
            } else {
                for (offset in DAMAGE_OFFSETS) {
                    file.seek(offset)
                    val byte = file.read()
                    file.seek(offset)
                    file.write(byte xor 0xff)
                }
            }
        }
        val damage = if (test == "rust-engine-short-model") "cut to ${installed.length()} of ${pin.size} bytes" else "${DAMAGE_OFFSETS.size} bytes inverted, size ${installed.length()}"
        report.put("damage", damage).put("damagedCopyMatchesPin", sha256(installed) == pin.sha256)
        val engine = RustSupportScoreEngine.forApp(context)
        try {
            val started = System.nanoTime()
            val available = noThrow(test, "available") { engine.available }
            val firstUseMs = (System.nanoTime() - started) / 1_000_000
            val reason = noThrow(test, "unavailableReason") { engine.unavailableReason }
            val repaired = installed.isFile && installed.length() == pin.size && sha256(installed) == pin.sha256
            report.put("available", available).put("reason", reason).put("firstUseMs", firstUseMs)
                .put("loadMs", engine.loadMillis).put("copyMatchesPinAfter", repaired)
            Log.i(TAG, "$test: $damage, available $available, reason $reason, first use $firstUseMs ms, copy matches the pin after $repaired")
            if (!libraryLoads) {
                checkUnavailableWithoutLibrary(test, available, reason)
                return report.put("outcome", "unavailable")
            }
            check(available) { "$test: the engine did not replace the copy and load: $reason. $report" }
            check(repaired) { "$test: the engine loaded, but the installed copy does not match the pin. $report" }
            report.put("outcome", "repaired")
            checkSampleReads(runner, case, test, engine, report)
        } finally {
            engine.close()
        }
    } finally {
        backup.copyTo(installed, overwrite = true)
        backup.delete()
    }
    return report
}

/**
 * Issue #665: an engine that was closed reads every field as unavailable and says why, and a new
 * engine in the same process loads into the slot the closed one unloaded and reads the stored
 * samples as in rust-engine. Without the library both engines are unavailable with the loader's
 * reason, and the closed one still answers unavailable.
 */
private fun afterCloseCase(runner: SupportDeviceTestRunner, case: JSONObject): JSONObject {
    val test = "rust-engine-after-close"
    val context = runner.targetContext
    val libraryLoads = NativeProbe.loaded
    val report = JSONObject().put("libraryLoads", libraryLoads)
    fun blankRead(engine: RustSupportScoreEngine): EngineScore =
        Mat(SCORE_CROP_HEIGHT, SCORE_CROP_WIDTH, CvType.CV_8UC1).let { mat -> DroidCvPattern(mat).use { noThrow(test, "read") { engine.read(it) } } }

    val first = RustSupportScoreEngine.forApp(context)
    val firstAvailable: Boolean
    val firstReason: String?
    try {
        firstAvailable = noThrow(test, "available") { first.available }
        firstReason = noThrow(test, "unavailableReason") { first.unavailableReason }
        if (firstAvailable) blankRead(first)
    } finally {
        first.close()
    }
    val closed = blankRead(first)
    val unavailable = EngineField.Unknown(EngineUnknownReason.Unavailable)
    report.put("firstAvailable", firstAvailable).put("firstReason", firstReason)
        .put("afterClose", JSONObject().put("class", closed.classScore.toString()).put("grand", closed.grandScore.toString())
            .put("display", closed.display.toString()).put("detail", closed.detail).put("reason", first.unavailableReason))
    check(closed.classScore == unavailable && closed.grandScore == unavailable && closed.display == EngineDisplay.Unknown && closed.text.isEmpty()) {
        "$test: a closed engine read a field: $report"
    }
    check(first.unavailableReason == RustSupportScoreEngine.CLOSED && closed.detail == RustSupportScoreEngine.CLOSED) { "$test: a closed engine gave another reason: $report" }
    if (!libraryLoads) {
        checkUnavailableWithoutLibrary(test, firstAvailable, firstReason)
        return report.put("outcome", "unavailable")
    }
    check(firstAvailable) { "$test: the first engine did not load: $firstReason" }
    val second = RustSupportScoreEngine.forApp(context)
    try {
        val available = noThrow(test, "available") { second.available }
        report.put("secondAvailable", available).put("secondReason", second.unavailableReason).put("secondLoadMs", second.loadMillis)
        check(available) { "$test: a new engine after the close did not load: ${second.unavailableReason}" }
        report.put("outcome", "reloaded")
        checkSampleReads(runner, case, test, second, report)
    } finally {
        second.close()
    }
    return report
}
