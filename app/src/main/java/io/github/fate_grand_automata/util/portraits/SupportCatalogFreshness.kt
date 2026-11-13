package io.github.fate_grand_automata.util.portraits

/** Provides current Atlas region data hashes (e.g. `"KR"` and `"JP"`), or `null` when unavailable. */
fun interface SupportCatalogVersionSource {
    fun current(): Map<String, String>?
}

internal enum class SupportCatalogAction {
    UseCache,
    Download,
    Throttled,
}

internal object SupportCatalogFreshness {
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L
    val VERSIONED_REGIONS = setOf("KR", "JP")

    fun decide(
        region: String,
        hasCached: Boolean,
        lastModifiedMs: Long,
        regionHash: String?,
        recordedHash: String?,
        lastAttemptMs: Long?,
        nowMs: Long,
    ): SupportCatalogAction {
        if (region in VERSIONED_REGIONS && regionHash != null) {
            if (!hasCached) {
                return SupportCatalogAction.Download
            }
            if (recordedHash == regionHash) {
                return SupportCatalogAction.UseCache
            }
            if (lastAttemptMs != null && nowMs - lastAttemptMs in 0L until HOUR_MS) {
                return SupportCatalogAction.Throttled
            }
            return SupportCatalogAction.Download
        }
        return if (hasCached && nowMs - lastModifiedMs in 0L..DAY_MS) {
            SupportCatalogAction.UseCache
        } else {
            SupportCatalogAction.Download
        }
    }
}
