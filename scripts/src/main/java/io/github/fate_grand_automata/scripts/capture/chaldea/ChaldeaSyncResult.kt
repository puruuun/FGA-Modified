package io.github.fate_grand_automata.scripts.capture.chaldea

import kotlinx.serialization.json.*

/** Outcome codes of the service operations. Names are the wire format. */
enum class ChaldeaSyncCode {
    Ok,
    ChaldeaRunning,

    /** The Chaldea state could not be determined; treated like running. */
    StateUnknown,
    NotFound,
    NoAccess,
    Unreadable,
    TooLarge,
    FileChanged,
    VerifyFailed,

    /** A write failed and every file was put back to its original bytes. */
    Restored,

    /** A write failed and at least one file could not be put back. */
    RestoreFailed,
    BadPatch,
    InvalidJson,
    NotObject,
    UnknownVersion,
    MissingUsers,
    AccountNotIdentified,
    NoMatchingUser,
    UnexpectedShape,
    ServiceError,
}

fun ChaldeaRefusal.toCode(): ChaldeaSyncCode = when (this) {
    ChaldeaRefusal.InvalidJson -> ChaldeaSyncCode.InvalidJson
    ChaldeaRefusal.NotObject -> ChaldeaSyncCode.NotObject
    ChaldeaRefusal.UnknownVersion -> ChaldeaSyncCode.UnknownVersion
    ChaldeaRefusal.MissingUsers -> ChaldeaSyncCode.MissingUsers
    ChaldeaRefusal.AccountNotIdentified -> ChaldeaSyncCode.AccountNotIdentified
    ChaldeaRefusal.NoMatchingUser -> ChaldeaSyncCode.NoMatchingUser
    ChaldeaRefusal.UnexpectedShape -> ChaldeaSyncCode.UnexpectedShape
    ChaldeaRefusal.VerifyFailed -> ChaldeaSyncCode.VerifyFailed
    ChaldeaRefusal.TooLarge -> ChaldeaSyncCode.TooLarge
    ChaldeaRefusal.Unreadable -> ChaldeaSyncCode.Unreadable
}

/**
 * Small structured result of `prepare` and `commit`, passed over the binder as JSON text. Holds counts and codes,
 * never account data.
 */
data class ChaldeaSyncResult(
    val code: ChaldeaSyncCode,
    /** For Restored / RestoreFailed: the code of the failure that caused it. */
    val cause: ChaldeaSyncCode? = null,
    val packageName: String? = null,
    val base: ChaldeaBaseSource? = null,
    /** Size and SHA-256 of the base bytes (prepare), or of the bytes written to the files (commit). */
    val size: Long? = null,
    val sha256: String? = null,
    /** Prepare only: size and SHA-256 of the backup stream (both files), which FGA checks after receiving it. */
    val bundleSize: Long? = null,
    val bundleSha256: String? = null,
    /** Commit only: SHA-256 of the base the edit started from. */
    val baseSha256: String? = null,
    val itemCount: Int = 0,
    val mysticCodeCount: Int = 0,
    val boardCount: Int = 0,
    val created: Boolean = false,
    val tookOver: Boolean = false,
    val regionMismatch: Boolean = false,
    /** Whether userdata.json.bak was written too. */
    val bakWritten: Boolean = false,
    /** Both Chaldea packages have a userdata.json; [packageName] is the newest one. */
    val bothPackages: Boolean = false,
) {
    val ok get() = code == ChaldeaSyncCode.Ok

    fun toJson(): String = buildJsonObject {
        put("code", code.name)
        cause?.let { put("cause", it.name) }
        packageName?.let { put("package", it) }
        base?.let { put("base", it.name) }
        size?.let { put("size", it) }
        sha256?.let { put("sha256", it) }
        baseSha256?.let { put("baseSha256", it) }
        bundleSize?.let { put("bundleSize", it) }
        bundleSha256?.let { put("bundleSha256", it) }
        put("items", itemCount)
        put("mysticCodes", mysticCodeCount)
        put("boards", boardCount)
        put("created", created)
        put("tookOver", tookOver)
        put("regionMismatch", regionMismatch)
        put("bakWritten", bakWritten)
        put("bothPackages", bothPackages)
    }.toString()

    companion object {
        /** Parses [text]; a malformed text becomes [ChaldeaSyncCode.ServiceError]. */
        fun fromJson(text: String?): ChaldeaSyncResult = try {
            val o = Json.parseToJsonElement(text ?: "") as JsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
            fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
            ChaldeaSyncResult(
                code = ChaldeaSyncCode.valueOf(s("code")!!),
                cause = s("cause")?.let { ChaldeaSyncCode.valueOf(it) },
                packageName = s("package"),
                base = s("base")?.let { ChaldeaBaseSource.valueOf(it) },
                size = (o["size"] as? JsonPrimitive)?.longOrNull,
                sha256 = s("sha256"),
                baseSha256 = s("baseSha256"),
                bundleSize = (o["bundleSize"] as? JsonPrimitive)?.longOrNull,
                bundleSha256 = s("bundleSha256"),
                itemCount = i("items"), mysticCodeCount = i("mysticCodes"), boardCount = i("boards"),
                created = b("created"), tookOver = b("tookOver"), regionMismatch = b("regionMismatch"),
                bakWritten = b("bakWritten"),
                bothPackages = b("bothPackages"),
            )
        } catch (e: Exception) {
            ChaldeaSyncResult(ChaldeaSyncCode.ServiceError)
        }
    }
}

/** Result of the service's state() and forceStop() calls. */
data class ChaldeaStateResult(val state: ChaldeaRunState, val packageName: String?) {
    fun toJson(): String = buildJsonObject {
        put("state", state.name)
        packageName?.let { put("package", it) }
    }.toString()

    companion object {
        fun fromJson(text: String?): ChaldeaStateResult = try {
            val o = Json.parseToJsonElement(text ?: "") as JsonObject
            ChaldeaStateResult(
                ChaldeaRunState.valueOf((o["state"] as JsonPrimitive).content),
                (o["package"] as? JsonPrimitive)?.contentOrNull,
            )
        } catch (e: Exception) {
            ChaldeaStateResult(ChaldeaRunState.Unknown, null)
        }
    }
}
