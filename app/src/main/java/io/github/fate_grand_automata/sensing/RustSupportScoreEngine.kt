package io.github.fate_grand_automata.sensing

import android.content.Context
import android.os.Looper
import io.github.fate_grand_automata.scripts.supportSelection.EngineScore
import io.github.fate_grand_automata.scripts.supportSelection.EngineUnknownReason
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import io.github.lib_automata.Pattern
import timber.log.Timber
import java.io.File
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The Rust support-score engine behind [SupportScoreEngine] (issue #568), for the rust build type
 * only. Nothing happens when it is built: the library, the model copy and the load run on the
 * first [available] or [read] of a run, on the calling (script) thread, and [close] unloads at the
 * end of the run. Every failure on the way makes the engine unavailable for the rest of the run;
 * nothing is retried and nothing throws.
 *
 * [prepareModel] returns the model file to load. [isMainThread] exists for the unit tests, which
 * have no Android looper.
 */
class RustSupportScoreEngine(
    private val native: ScoreNative,
    private val prepareModel: () -> File,
    private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
) : SupportScoreEngine {
    private sealed interface State {
        data object NotLoaded : State
        data object Loaded : State
        data class Failed(val why: String) : State
        data object Closed : State
    }

    private var state: State = State.NotLoaded

    /** How long the first load took, library and model copy included; null before it ran. */
    var loadMillis: Long? = null
        private set

    override val available: Boolean
        @Synchronized get() = ensureLoaded() == null

    /** Null when loaded, otherwise why the engine cannot read. */
    private fun ensureLoaded(): String? {
        when (val current = state) {
            State.Loaded -> return null
            is State.Failed -> return current.why
            State.Closed -> return "the engine was closed at the end of the run"
            State.NotLoaded -> Unit
        }
        // A 21 MB copy and a model load would freeze the UI, so the main thread never starts one.
        if (isMainThread()) return "the engine cannot load on the main thread"
        val started = System.nanoTime()
        val failure = try {
            load()
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
        loadMillis = (System.nanoTime() - started) / 1_000_000
        state = if (failure == null) State.Loaded else State.Failed(failure)
        if (failure != null) Timber.w("Rust support-score engine unavailable: %s", failure)
        return failure
    }

    private fun load(): String? {
        native.loadLibrary()?.let { return "libfga_ffi.so did not load: $it" }
        val model = try {
            prepareModel()
        } catch (e: java.io.FileNotFoundException) {
            return "the OCR model asset is missing: ${e.message}"
        }
        val answer = native.load(model.path)
        return if (answer.startsWith("OK: ")) null else "model load failed: $answer"
    }

    @Synchronized
    override fun read(field: Pattern): EngineScore {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started).nanoseconds
        ensureLoaded()?.let { return EngineScore.unknown(EngineUnknownReason.Unavailable, it, elapsed()) }
        val pixels = field.grayPixels()
            ?: return EngineScore.unknown(EngineUnknownReason.EngineError, "the score field is not a grayscale image", elapsed())
        val reply = try {
            native.readScore(pixels, field.width, field.height)
        } catch (e: RuntimeException) {
            return EngineScore.unknown(EngineUnknownReason.EngineError, "${e.javaClass.simpleName}: ${e.message}", elapsed())
        }
        return ScoreWire.decode(reply, elapsed())
    }

    @Synchronized
    override fun close() {
        if (state == State.Loaded) {
            val code = native.unload()
            if (code != ScoreWire.CODE_YES) Timber.w("Rust support-score engine unload answered %d", code)
        }
        state = State.Closed
    }

    companion object {
        const val ASSET_DIR = "ocr"
        const val PIN_ASSET = "$ASSET_DIR/ocr-model.properties"

        /** The engine on the model packaged in the app's assets, copied by [OcrModelInstaller]. */
        fun forApp(context: Context, native: ScoreNative = RustScoreNative): RustSupportScoreEngine {
            val appContext = context.applicationContext ?: context
            return RustSupportScoreEngine(native, prepareModel = { installedModel(appContext) })
        }

        fun modelDir(context: Context) = File(context.noBackupFilesDir, "ocr-model")

        fun installedModel(context: Context): File {
            val pin = context.assets.open(PIN_ASSET).use { OcrModelPin.parse(it) }
            return OcrModelInstaller.install(pin, modelDir(context)) { context.assets.open("$ASSET_DIR/${pin.file}") }
        }
    }
}
