package io.github.fate_grand_automata.sensing

import android.content.Context
import android.os.Looper
import io.github.fate_grand_automata.scripts.supportSelection.EngineScore
import io.github.fate_grand_automata.scripts.supportSelection.EngineUnknownReason
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import io.github.lib_automata.Pattern
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The Rust support-score engine behind [SupportScoreEngine] (issue #568), for the rust build type
 * only. Building it does nothing: the library, the model copy and the load run on the first
 * [unavailableReason], [available] or [read] of a run, on the calling (script) thread, and [close]
 * unloads at the end of the run. Every failure on the way makes the engine unavailable for the
 * rest of the run; nothing is retried.
 *
 * State machine. [work] guards `state`; only a thread that holds it loads, reads or unloads.
 *
 *     NotLoaded --load ok--> Loaded --close--> Closed
 *     NotLoaded --load failed--> Failed(why) --close--> Closed
 *     NotLoaded --close--> Closed
 *
 * [close] runs on the main thread (ScriptManager releases a run there), while a load copies 21 MB
 * and a read takes tens of milliseconds, so close must not wait for the lock. It sets
 * [closeRequested] and finishes the close only if the lock is free. Otherwise the thread holding
 * the lock finishes it: every holder checks the flag before it starts (and then never loads or
 * reads) and again after it has released the lock, taking the lock back if it is free. The flag
 * is set before close tries the lock, so whichever of the two gets the lock after the flag is set
 * sees it, and the close is never lost. `finishClose` unloads only from Loaded and always ends in
 * Closed, so the library's unload runs at most once.
 *
 * Errors: every Throwable from the library or the model copy (an `UnsatisfiedLinkError` from a
 * library that does not match this class included) becomes "unavailable" or "engine error" with
 * its text in the detail. A [VirtualMachineError] (out of memory, stack overflow, an internal VM
 * error) and [ThreadDeath] propagate, because the process cannot be trusted to go on after them.
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

    private val work = ReentrantLock()
    private var state: State = State.NotLoaded

    @Volatile private var closeRequested = false

    @Volatile private var loadNanos: Long = -1

    override val loadTime: Duration?
        get() = loadNanos.takeIf { it >= 0 }?.nanoseconds

    /** The same as [loadTime], in milliseconds, for reports. */
    val loadMillis: Long? get() = loadTime?.inWholeMilliseconds

    override val unavailableReason: String?
        get() = withWork(closed = { CLOSED }) { ensureLoaded() }

    override fun read(field: Pattern): EngineScore {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started).nanoseconds
        return withWork(closed = { EngineScore.unknown(EngineUnknownReason.Unavailable, CLOSED, elapsed()) }) {
            val why = ensureLoaded()
            if (why != null) {
                EngineScore.unknown(EngineUnknownReason.Unavailable, why, elapsed())
            } else if (closeRequested) {
                // A close arrived during the load this call made; nothing is read after it.
                EngineScore.unknown(EngineUnknownReason.Unavailable, CLOSED, elapsed())
            } else {
                try {
                    val pixels = field.grayPixels()
                    if (pixels == null) {
                        EngineScore.unknown(EngineUnknownReason.EngineError, "the score field is not a grayscale image", elapsed())
                    } else {
                        ScoreWire.decode(native.readScore(pixels, field.width, field.height), elapsed())
                    }
                } catch (t: Throwable) {
                    rethrowIfFatal(t)
                    EngineScore.unknown(EngineUnknownReason.EngineError, describe(t), elapsed())
                }
            }
        }
    }

    override fun close() {
        closeRequested = true
        if (work.tryLock()) {
            try { finishClose() } finally { work.unlock() }
        }
    }

    /**
     * Runs [block] under the lock unless a close was requested, in which case the answer is
     * [closed], and finishes a close requested while [block] ran. A call that was already running
     * when close was requested still returns its own answer.
     */
    private inline fun <T> withWork(closed: () -> T, block: () -> T): T {
        if (!closeRequested) {
            work.lock()
            try {
                if (!closeRequested) return block()
            } finally {
                if (closeRequested) finishClose()
                work.unlock()
                closeIfFree()
            }
        }
        closeIfFree()
        return closed()
    }

    /** Covers a close that set its flag after this thread's last check but before it unlocked. */
    private fun closeIfFree() {
        if (closeRequested && work.tryLock()) {
            try { finishClose() } finally { work.unlock() }
        }
    }

    private fun finishClose() {
        if (state == State.Loaded) {
            try {
                val code = native.unload()
                if (code != ScoreWire.CODE_YES) Timber.w("Rust support-score engine unload answered %d", code)
            } catch (t: Throwable) {
                rethrowIfFatal(t)
                Timber.w(t, "Rust support-score engine unload failed")
            } finally {
                state = State.Closed
            }
        }
        state = State.Closed
    }

    /** Null when loaded, otherwise why the engine cannot read. Called with the lock held. */
    private fun ensureLoaded(): String? {
        when (val current = state) {
            State.Loaded -> return null
            is State.Failed -> return current.why
            State.Closed -> return CLOSED
            State.NotLoaded -> Unit
        }
        // A 21 MB copy and a model load would freeze the UI, so the main thread never starts one.
        if (isMainThread()) return "the engine cannot load on the main thread"
        val started = System.nanoTime()
        val failure = try {
            load()
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            describe(t)
        }
        loadNanos = System.nanoTime() - started
        state = if (failure == null) State.Loaded else State.Failed(failure)
        if (failure != null) Timber.w("Rust support-score engine unavailable: %s", failure)
        return failure
    }

    private fun load(): String? {
        native.loadLibrary()?.let { return "libfga_ffi.so did not load: $it" }
        val model = try {
            prepareModel()
        } catch (e: FileNotFoundException) {
            return "the OCR model asset is missing: ${e.message}"
        }
        val answer = native.load(model.path)
        return if (answer.startsWith("OK: ")) null else "model load failed: $answer"
    }

    companion object {
        const val ASSET_DIR = "ocr"
        const val PIN_ASSET = "$ASSET_DIR/ocr-model.properties"
        const val CLOSED = "the engine was closed at the end of the run"

        private fun describe(t: Throwable) = "${t.javaClass.simpleName}: ${t.message}"

        @Suppress("DEPRECATION")
        private fun rethrowIfFatal(t: Throwable) {
            if (t is VirtualMachineError || t is ThreadDeath) throw t
        }

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
