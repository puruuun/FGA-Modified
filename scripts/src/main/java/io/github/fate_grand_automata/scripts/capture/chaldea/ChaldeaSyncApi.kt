package io.github.fate_grand_automata.scripts.capture.chaldea

import io.github.fate_grand_automata.scripts.capture.AccountState
import java.io.File
import java.io.OutputStream

/** One bound service session. The app implements it over the Shizuku UserService; tests use a fake. */
interface ChaldeaSyncSession {
    class PrepareOutcome(
        val result: ChaldeaSyncResult,
        val received: ChaldeaBackupReceiver.Received?,
        val backupMatches: Boolean,
    )

    suspend fun state(): ChaldeaStateResult
    suspend fun forceStop(): ChaldeaStateResult

    /** Streams the base file into [sink] (flushed, not closed). */
    suspend fun prepare(sink: OutputStream): PrepareOutcome
    suspend fun commit(
        patch: ChaldeaPatch,
        expectedSha256: String,
        allowCreate: Boolean,
        expectedPackage: String,
        expectedBundleSha256: String,
    ): ChaldeaSyncResult

    /**
     * Puts the files of the backup bundle in [bundle] (whose SHA-256 must be [sha256]) back into the Chaldea files of
     * [packageName], each to its own original.
     */
    suspend fun restore(bundle: File, sha256: String, packageName: String): ChaldeaSyncResult

    /** Reads Chaldea's cached game data (version + servant costumes/transforms), or returns an error result. */
    suspend fun gameData(knownVersion: String?): ChaldeaGameDataResult =
        ChaldeaGameDataResult.Error(ChaldeaSyncCode.ServiceError.name)

    /** True when either KR or official JP FGO is visible on screen. */
    suspend fun gameVisible(): Boolean = false

    /** Unbinds and lets the service exit. Safe to call twice. */
    fun close()
}

/** Everything the scheduler needs from the outside world besides storage and the clock. */
interface ChaldeaSyncEnv {
    /** Switch on AND log collection enabled (tracker switch and consent). The VPN need not run. */
    fun enabled(): Boolean

    /** Minutes in the background before Chaldea is closed: 10, 30, 60, or 0 for never. */
    fun closeMinutes(): Int

    /** Minutes with neither FGO nor Chaldea on screen before auto-sync stops: 10, 30, or 60. */
    fun stopMinutes(): Int = 10

    /** True when the device screen is interactive. */
    fun screenOn(): Boolean = true

    /** How the files are reached now: root wins over Shizuku. */
    fun access(): ChaldeaAccess

    /** Binds the service over the current access mode; null when it cannot be reached. */
    suspend fun openSession(): ChaldeaSyncSession?
}

/** Read access to the stored capture accounts. [load] runs on the scheduler thread and may read from disk. */
interface ChaldeaAccountSource {
    class Loaded(val server: ChaldeaServer, val state: AccountState)

    /** The account's server and state, or null when it is gone or is not a KR / JP account. */
    fun load(userId: String): Loaded?

    /** The current account of each server. */
    fun currentIds(): Map<ChaldeaServer, String>

    /** Cached Atlas collection number mappings for CEs and command codes. */
    fun collectionNumbers(): ChaldeaCollectionNumbers = ChaldeaCollectionNumbers.EMPTY
}

/** One-line result for the settings screen. Carries codes and counts only, never account data. */
sealed interface ChaldeaSyncStatus {
    data object Idle : ChaldeaSyncStatus
    data object Working : ChaldeaSyncStatus
    data class Synced(
        val timeMs: Long,
        val items: Int,
        val mysticCodes: Int,
        val boards: Int,
        val coins: Int = 0,
        val craftEssences: Int = 0,
        val commandCodes: Int = 0,
        val unmapped: Int = 0,
        val created: Boolean,
        val regionMismatch: Boolean,
        val packageName: String?,
        val bothPackages: Boolean,
        val servants: Int = 0,
    ) : ChaldeaSyncStatus
    data object WaitingScript : ChaldeaSyncStatus
    data object WaitingVisible : ChaldeaSyncStatus
    data class WaitingBackground(val minutes: Int, val closeAfter: Int) : ChaldeaSyncStatus
    data class Stopped(val minutes: Int) : ChaldeaSyncStatus
    data class ShizukuUnavailable(val state: ShizukuState) : ChaldeaSyncStatus
    data object CannotBind : ChaldeaSyncStatus
    data object StateUnknown : ChaldeaSyncStatus
    data object ChaldeaStartedDuring : ChaldeaSyncStatus
    data object FileNotFound : ChaldeaSyncStatus
    data object NoAccess : ChaldeaSyncStatus
    data object UnknownVersion : ChaldeaSyncStatus
    data object FileUnreadable : ChaldeaSyncStatus
    data object OwnerChanged : ChaldeaSyncStatus
    data object BackupFailed : ChaldeaSyncStatus
    data object AccountNotIdentified : ChaldeaSyncStatus
    data object PatchTooLarge : ChaldeaSyncStatus
    data object FailedRestored : ChaldeaSyncStatus
    data class RestoreFailed(val backupPath: String) : ChaldeaSyncStatus
    data class Failed(val code: ChaldeaSyncCode) : ChaldeaSyncStatus

    /** A write that was interrupted earlier could not be checked or undone; nothing is written until that is resolved. */
    data class RecoveryBlocked(val code: ChaldeaSyncCode) : ChaldeaSyncStatus

    /** Failures that stay shown until the next success or a switch toggle. */
    val persistent: Boolean
        get() = when (this) {
            is FileNotFound, is NoAccess, is UnknownVersion, is FileUnreadable, is OwnerChanged, is BackupFailed, is AccountNotIdentified,
            is PatchTooLarge, is FailedRestored, is RestoreFailed, is Failed, is RecoveryBlocked -> true
            else -> false
        }
}

/** What "sync now" found. */
sealed interface ChaldeaSyncNowOutcome {
    /** The sync ran; the final result is in the status. */
    data object Done : ChaldeaSyncNowOutcome

    /** Chaldea is running in this [state]; ask the user before closing it, then call again with that state confirmed. */
    data class NeedsConfirm(val state: ChaldeaRunState) : ChaldeaSyncNowOutcome
    data class Unavailable(val status: ChaldeaSyncStatus) : ChaldeaSyncNowOutcome
}
