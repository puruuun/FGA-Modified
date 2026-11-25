package io.github.fate_grand_automata.sensing

import io.github.fate_grand_automata.scripts.supportSelection.EngineDisplay
import io.github.fate_grand_automata.scripts.supportSelection.EngineField
import io.github.fate_grand_automata.scripts.supportSelection.EngineScore
import io.github.fate_grand_automata.scripts.supportSelection.EngineUnknownReason
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Reads the reply of `RustSensing.readScore`, laid out by native/fga-ffi/src/score_engine/wire.rs
 * (the table there). Anything that does not fit that layout exactly is
 * [EngineUnknownReason.EngineError] for both fields, never a value: a wrong magic or length, an
 * unknown status, display or reason code, a value outside its range, a value with a reason, an
 * unknown field without one, a flag bit it does not know, or text that is not valid code points.
 */
object ScoreWire {
    /** `FSC2`: the layout with the flags at index 10 (the sign correction, QA 0025 section 4.19). */
    const val MAGIC = 0x46534332
    const val HEADER_LEN = 12

    /** Where the flags word sits in a reply. */
    const val FLAGS_INDEX = 10
    const val STATUS_READ = 0
    const val STATUS_ERROR = 1
    const val NO_VALUE = -1
    const val NO_REASON = 0

    /** wire.rs `FLAG_SIGN_CORRECTED`: the reader read a misread sign as `+`. */
    const val FLAG_SIGN_CORRECTED = 1
    const val MAX_CLASS = 62
    const val MAX_GRAND = 16
    /** fga-ffi bounds the text at 256 code points (error messages); more is malformed. */
    const val MAX_TEXT = 256

    const val CODE_NO = 0
    const val CODE_YES = 1

    /** `isLoaded` only: another call holds the engine lock, so the slot is in use. */
    const val CODE_BUSY = 2
    const val CODE_ERROR = -1
    const val CODE_PENDING_EXCEPTION = -2

    /** score_engine's `ALREADY_LOADED`; `load` answers it with the `ERR: ` prefix. */
    const val ALREADY_LOADED = "the support-score engine is already loaded; unload it before loading again"
    const val ALREADY_LOADED_REPLY = "ERR: $ALREADY_LOADED"

    /** Rust's reason codes in order: 1 is Blank … 7 is CropSize. */
    val REASONS = listOf(
        EngineUnknownReason.Blank,
        EngineUnknownReason.LowConfidence,
        EngineUnknownReason.Format,
        EngineUnknownReason.Range,
        EngineUnknownReason.Clipped,
        EngineUnknownReason.NotShown,
        EngineUnknownReason.CropSize,
    )

    private val DISPLAYS = listOf(EngineDisplay.Ordinary, EngineDisplay.Compound, EngineDisplay.Unknown)

    private class Malformed(message: String) : Exception(message)

    /** [duration] is the caller's time for the whole call; Rust's own time goes into [EngineScore.engineTime]. */
    fun decode(reply: IntArray?, duration: Duration): EngineScore = try {
        parse(reply, duration)
    } catch (e: Malformed) {
        EngineScore.unknown(EngineUnknownReason.EngineError, "malformed engine reply: ${e.message}", duration)
    }

    private fun parse(reply: IntArray?, duration: Duration): EngineScore {
        if (reply == null) throw Malformed("no reply (JNI failure)")
        if (reply.size < HEADER_LEN) throw Malformed("${reply.size} ints, need at least $HEADER_LEN")
        if (reply[0] != MAGIC) throw Malformed("magic ${reply[0]}")
        val length = reply[11]
        if (length < 0 || length > MAX_TEXT || reply.size != HEADER_LEN + length) {
            throw Malformed("text length $length for ${reply.size} ints")
        }
        val text = buildString {
            for (i in HEADER_LEN until reply.size) {
                val codePoint = reply[i]
                if (codePoint !in 0..0x10FFFF || codePoint in 0xD800..0xDFFF) throw Malformed("code point $codePoint")
                appendCodePoint(codePoint)
            }
        }
        when (reply[1]) {
            STATUS_READ -> Unit
            STATUS_ERROR -> return EngineScore.unknown(EngineUnknownReason.EngineError, text, duration)
            else -> throw Malformed("status ${reply[1]}")
        }
        val classScore = field("class", reply[2], reply[5], MAX_CLASS)
        val grandScore = field("grand", reply[3], reply[6], MAX_GRAND)
        val display = DISPLAYS.getOrNull(reply[4]) ?: throw Malformed("display ${reply[4]}")
        if (reply[9] < 0) throw Malformed("engine time ${reply[9]}")
        if (reply[FLAGS_INDEX] and FLAG_SIGN_CORRECTED.inv() != 0) throw Malformed("flags ${reply[FLAGS_INDEX]}")
        return EngineScore(
            classScore = classScore,
            grandScore = grandScore,
            display = display,
            text = text,
            confidence = confidence(reply[7]),
            minCharConfidence = confidence(reply[8]),
            duration = duration,
            engineTime = reply[9].microseconds,
            signCorrected = reply[FLAGS_INDEX] and FLAG_SIGN_CORRECTED != 0,
        )
    }

    /** NaN is "none" in the layout; wire.rs never sends an infinity, so one is treated as none too. */
    private fun confidence(bits: Int): Float? = Float.fromBits(bits).takeIf { it.isFinite() }

    private fun field(name: String, value: Int, reason: Int, max: Int): EngineField = when {
        reason == NO_REASON && value in 0..max -> EngineField.Value(value)
        value == NO_VALUE && reason in 1..REASONS.size -> EngineField.Unknown(REASONS[reason - 1])
        else -> throw Malformed("$name value $value with reason $reason")
    }
}
