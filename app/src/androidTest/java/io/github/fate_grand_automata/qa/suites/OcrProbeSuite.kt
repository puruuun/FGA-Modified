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
 *
 * The model and the samples are local-only data (docs/agents/rust-native.md, "Test data"), so
 * they are pushed to the device before the run; the case file names their device paths. All cases
 * run in one process, so the case file puts each engine's memory case before its other cases.
 */
internal fun runOcrProbeCase(runner: SupportDeviceTestRunner, case: JSONObject): JSONObject {
    val test = case.getString("test")
    if (!NativeProbe.loaded) {
        return JSONObject().put("test", test)
            .put("skipped", "libfga_ffi.so is not packaged; build with -Pfga.native=true")
    }
    val actual = when (test) {
        "tesseract-baseline" -> tesseractBaseline(runner, case)
        "tract-memory" -> tractMemory(runner, case)
        "tract-recognise", "tract-recognise-allowed" -> tractRecognise(runner, case)
        else -> error("Unknown ocrProbe test $test")
    }
    Log.i(TAG, "$test: $actual")
    return actual.put("test", test)
}

private const val TAG = "FgaOcrProbe"
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
        val raw = ByteArray((mat.total() * channels).toInt())
        mat.get(0, 0, raw)
        val gray = ByteArray(mat.rows() * mat.cols()) { i ->
            if (channels == 1) raw[i] else {
                // OpenCV orders colour channels B, G, R(, A): index 2 is the PNG's first channel.
                val b = raw[i * channels]; val g = raw[i * channels + 1]; val r = raw[i * channels + 2]
                check(r == g && g == b) { "$file pixel $i is not gray ($r, $g, $b)" }
                r
            }
        }
        return Sample(file, pyText, gray, mat.cols(), mat.rows())
    } finally { mat.release() }
}

/** Rust opens the model by path, and the app process cannot read /sdcard/Download directly. */
private fun modelFile(runner: SupportDeviceTestRunner, devicePath: String): File {
    copiedModel?.let { (path, file) -> if (path == devicePath && file.isFile) return file }
    val target = File(runner.targetContext.cacheDir, "ocr-probe/${devicePath.substringAfterLast('/')}")
    checkNotNull(target.parentFile).mkdirs()
    if (runner.copyFixture(devicePath, target) == 0L) {
        target.delete()
        error("$devicePath is missing on the device; push the OCR model first (docs/agents/rust-native.md)")
    }
    copiedModel = devicePath to target
    return target
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

private fun tractMemory(runner: SupportDeviceTestRunner, case: JSONObject): JSONObject {
    val samples = samples(runner, case.getString("samples"))
    val model = modelFile(runner, case.getString("model"))
    val readings = mutableListOf<JSONObject>()
    var errors = 0
    var firstError: String? = null
    collectGarbage()
    readings += memory("beforeLoad")
    val load = try {
        okJson("ocrLoad", NativeProbe.ocrLoad(model.path)).also {
            readings += memory("afterLoad")
            for (sample in samples) {
                val result = NativeProbe.ocrRead(sample.gray, sample.width, sample.height, "")
                if (!result.startsWith("OK: ")) { errors++; firstError = firstError ?: "${sample.file}: $result" }
            }
            readings += memory("afterReadingAll")
        }
    } finally {
        NativeProbe.ocrUnload()
    }
    collectGarbage()
    readings += memory("afterUnloadAndGc")
    return JSONObject()
        .put("samples", samples.size)
        .put("loadMs", load.getLong("loadMs"))
        .put("readErrors", errors)
        .put("firstReadError", firstError)
        .put("memory", memoryReport(readings))
}

private fun tractRecognise(runner: SupportDeviceTestRunner, case: JSONObject): JSONObject {
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
    val load = try {
        okJson("ocrLoad", NativeProbe.ocrLoad(model.path)).also {
            for (sample in samples) {
                val started = System.nanoTime()
                val result = NativeProbe.ocrRead(sample.gray, sample.width, sample.height, allowed)
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
        }
    } finally {
        NativeProbe.ocrUnload()
    }
    val report = JSONObject()
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
    if (errorCount > 0 || equal < minEqual) {
        throw AssertionError("$test: $equal of ${samples.size} texts equal py_text (need $minEqual), $errorCount ERR. $report")
    }
    return report
}
