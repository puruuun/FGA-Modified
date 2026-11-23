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
import kotlin.time.Duration.Companion.seconds

/**
 * The Rust support-score engine behind [SupportScoreEngine] (issue #568), for the variants that
 * carry the engine (BuildConfig.NATIVE_READER, see SupportScoreEngineBinding). Building it does
 * nothing: the library, the model copy and the load run on the first [unavailableReason],
 * [available] or [read] of a run, on the calling (script) thread, and [close] unloads at the end
 * of the run. Every failure on the way makes the engine unavailable for the
 * rest of the run; nothing is retried.
 *
 * State machine. [work] guards `state`; only a thread that holds it loads, reads or unloads.
 *
 *     NotLoaded --load ok--> Loaded --close--> Closed
 *     NotLoaded --load failed--> Failed(why) --close--> Closed
 *     NotLoaded --close--> Closed
 *
 * [close] runs on the main thread (`startScript` releases the previous run's engines there, and
 * the service's `onDestroy`) or on `Dispatchers.Default` (`onScriptExit` after the script ends,
 * by a stop too, and the early exits inside the launched start), while a load copies 21 MB and a
 * read takes tens of milliseconds, so close must not wait for the lock. It sets
 * [closeRequested] and finishes the close only if the lock is free. Otherwise the thread holding
 * the lock finishes it: every holder checks the flag before it starts (and then never loads or
 * reads), the load checks it again just before `native.load`, and every holder tries the lock
 * again after it has released it. The flag is set before close tries the lock, so whichever of the
 * two gets the lock after the flag is set sees it, and the close is never lost. That one
 * after-release attempt is enough: finishing the close while still holding the lock would only
 * duplicate it. `finishClose` unloads only from Loaded and always ends in Closed, so the library's
 * unload runs at most once per instance.
 *
 * Two runs in a row. Rust keeps one engine slot per process, and because close does not wait, the
 * previous run's engine can still be loaded (its worker finishing a read before it unloads) when
 * the next run's first use loads. Rule: a load that finds the slot filled treats it as an earlier
 * run that is still unloading and waits on the script thread, polling `isLoaded` every
 * [SLOT_POLL_MS] ms, for at most [slotWait]; then it loads, or, if the slot is still filled, the
 * run is unavailable with [SLOT_BUSY]. Filled means `isLoaded` answers loaded or busy (another
 * call holds the Rust lock; Rust answers that without waiting, so one poll stays short), or `load`
 * answers [ScoreWire.ALREADY_LOADED_REPLY] because another run loaded in between. It never unloads
 * a model it did not load, because the filled slot may belong to a run that is still reading, and
 * one live run holds one loaded engine. Waiting is bounded because an engine whose close never
 * finishes must cost the next run its engine, not its script thread. A close, a stop
 * ([stopRequested]) or an interrupt during the wait ends it without a load, and a run already
 * stopped before its first use loads neither the library nor the model. The model copy and
 * `native.load` themselves cannot be cut short; they take up to several hundred milliseconds, and
 * a stop that arrives during them takes effect after them.
 *
 * Errors: every Throwable from the library or the model copy (an `UnsatisfiedLinkError` from a
 * library that does not match this class included) becomes "unavailable" or "engine error" with
 * its text in the detail. A [VirtualMachineError] (out of memory, stack overflow, an internal VM
 * error) and [ThreadDeath] propagate, because the process cannot be trusted to go on after them.
 *
 * [prepareModel] returns the model file to load. [stopRequested] tells whether the run was stopped
 * (the script component's `ExitManager.isExitRequested`, wired in ScriptsModule); it must answer at
 * once and never wait, so a paused run's stop still ends the wait. [isMainThread] exists for the
 * unit tests, which have no Android looper, and [slotWait] for the test of an expiring wait.
 */
class RustSupportScoreEngine(
    private val native: ScoreNative,
    private val prepareModel: () -> File,
    private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
    private val slotWait: Duration = 3.seconds,
    private val stopRequested: () -> Boolean = { false },
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
     * when close was requested still returns its own answer. The unlock is unconditional and the
     * close is finished after it, so a throw while closing never leaves the lock held.
     */
    private inline fun <T> withWork(closed: () -> T, block: () -> T): T {
        try {
            if (!closeRequested) {
                work.lock()
                try {
                    if (!closeRequested) return block()
                } finally {
                    work.unlock()
                }
            }
            return closed()
        } finally {
            closeIfFree()
        }
    }

    /** Finishes a requested close if no other thread holds the lock; that thread finishes it otherwise. */
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
        // A run stopped before its first use would otherwise still copy the 21 MB model.
        if (stopRequested()) return STOPPED
        native.loadLibrary()?.let { return "libfga_ffi.so did not load: $it" }
        val model = try {
            prepareModel()
        } catch (e: FileNotFoundException) {
            return "the OCR model asset is missing: ${e.message}"
        }
        val deadline = System.nanoTime() + slotWait.inWholeNanoseconds
        while (true) {
            // The copy takes long enough for the run to end meanwhile; a closed run loads nothing.
            if (closeRequested) return CLOSED
            if (stopRequested()) return STOPPED
            val slot = native.isLoaded()
            if (slot != ScoreWire.CODE_YES && slot != ScoreWire.CODE_BUSY) {
                val answer = native.load(model.path)
                if (answer.startsWith("OK: ")) return null
                if (answer != ScoreWire.ALREADY_LOADED_REPLY) return "model load failed: $answer"
            }
            // nanoTime may wrap, so only the difference is compared.
            if (System.nanoTime() - deadline >= 0) return SLOT_BUSY
            try {
                Thread.sleep(SLOT_POLL_MS)
            } catch (e: InterruptedException) {
                // A cancelled run's coroutine machinery relies on the flag, so it is kept.
                Thread.currentThread().interrupt()
                return "the wait for an earlier run's engine was interrupted"
            }
        }
    }

    companion object {
        const val ASSET_DIR = "ocr"
        const val PIN_ASSET = "$ASSET_DIR/ocr-model.properties"
        const val CLOSED = "the engine was closed at the end of the run"
        const val SLOT_BUSY = "an earlier run's engine was still loaded when the wait for it ended; this run has no engine"
        const val STOPPED = "the run was stopped before the engine loaded; nothing was loaded"
        const val SLOT_POLL_MS = 20L

        private fun describe(t: Throwable) = "${t.javaClass.simpleName}: ${t.message}"

        @Suppress("DEPRECATION")
        private fun rethrowIfFatal(t: Throwable) {
            if (t is VirtualMachineError || t is ThreadDeath) throw t
        }

        /** The engine on the model packaged in the app's assets, copied by [OcrModelInstaller]. */
        fun forApp(
            context: Context,
            native: ScoreNative = RustScoreNative,
            stopRequested: () -> Boolean = { false },
        ): RustSupportScoreEngine {
            val appContext = context.applicationContext ?: context
            return RustSupportScoreEngine(native, prepareModel = { installedModel(appContext) }, stopRequested = stopRequested)
        }

        fun modelDir(context: Context) = File(context.noBackupFilesDir, "ocr-model")

        fun installedModel(context: Context): File {
            val pin = context.assets.open(PIN_ASSET).use { OcrModelPin.parse(it) }
            return OcrModelInstaller.install(pin, modelDir(context)) { context.assets.open("$ASSET_DIR/${pin.file}") }
        }
    }
}
