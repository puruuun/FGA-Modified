package io.github.fate_grand_automata.util.portraits

import kotlin.test.Test
import kotlin.test.assertEquals

class SupportCatalogFreshnessTest {
    private val baseTime = 1_000_000_000L

    @Test fun matchingHashUsesCacheEvenWhenOlderThanOneDay() {
        for (region in listOf("KR", "JP")) {
            val action = SupportCatalogFreshness.decide(
                region = region,
                hasCached = true,
                lastModifiedMs = baseTime - 5 * SupportCatalogFreshness.DAY_MS,
                regionHash = "h1",
                recordedHash = "h1",
                lastAttemptMs = baseTime - 1_000L,
                nowMs = baseTime,
            )
            assertEquals(SupportCatalogAction.UseCache, action)
        }
    }

    @Test fun differingOrMissingHashDownloadsAndThrottlesWithinOneHour() {
        for (recorded in listOf(null, "old")) {
            assertEquals(
                SupportCatalogAction.Download,
                SupportCatalogFreshness.decide(
                    region = "JP",
                    hasCached = true,
                    lastModifiedMs = baseTime,
                    regionHash = "new",
                    recordedHash = recorded,
                    lastAttemptMs = null,
                    nowMs = baseTime,
                ),
            )
            assertEquals(
                SupportCatalogAction.Throttled,
                SupportCatalogFreshness.decide(
                    region = "JP",
                    hasCached = true,
                    lastModifiedMs = baseTime,
                    regionHash = "new",
                    recordedHash = recorded,
                    lastAttemptMs = baseTime,
                    nowMs = baseTime + SupportCatalogFreshness.HOUR_MS - 1,
                ),
            )
            assertEquals(
                SupportCatalogAction.Download,
                SupportCatalogFreshness.decide(
                    region = "JP",
                    hasCached = true,
                    lastModifiedMs = baseTime,
                    regionHash = "new",
                    recordedHash = recorded,
                    lastAttemptMs = baseTime,
                    nowMs = baseTime + SupportCatalogFreshness.HOUR_MS,
                ),
            )
        }
    }

    @Test fun missingCacheDownloadsEvenIfHashFileMatchesAndRespectsHourlyThrottle() {
        assertEquals(
            SupportCatalogAction.Download,
            SupportCatalogFreshness.decide(
                region = "KR",
                hasCached = false,
                lastModifiedMs = 0L,
                regionHash = "kr1",
                recordedHash = "kr1",
                lastAttemptMs = null,
                nowMs = baseTime,
            ),
        )
        assertEquals(
            SupportCatalogAction.Throttled,
            SupportCatalogFreshness.decide(
                region = "KR",
                hasCached = false,
                lastModifiedMs = 0L,
                regionHash = "kr1",
                recordedHash = "kr1",
                lastAttemptMs = baseTime,
                nowMs = baseTime + 60_000L,
            ),
        )
    }

    @Test fun unknownVersionUsesOneDayRuleWithoutHourlyThrottle() {
        assertEquals(
            SupportCatalogAction.UseCache,
            SupportCatalogFreshness.decide(
                region = "JP",
                hasCached = true,
                lastModifiedMs = baseTime - SupportCatalogFreshness.DAY_MS,
                regionHash = null,
                recordedHash = null,
                lastAttemptMs = baseTime - 1_000L,
                nowMs = baseTime,
            ),
        )
        assertEquals(
            SupportCatalogAction.Download,
            SupportCatalogFreshness.decide(
                region = "JP",
                hasCached = true,
                lastModifiedMs = baseTime - SupportCatalogFreshness.DAY_MS - 1,
                regionHash = null,
                recordedHash = "jp1",
                lastAttemptMs = baseTime - 1_000L,
                nowMs = baseTime,
            ),
        )
    }

    @Test fun nonKrJpRegionsAlwaysUseOneDayRule() {
        for (region in listOf("NA", "CN", "TW")) {
            assertEquals(
                SupportCatalogAction.UseCache,
                SupportCatalogFreshness.decide(
                    region = region,
                    hasCached = true,
                    lastModifiedMs = baseTime - SupportCatalogFreshness.DAY_MS,
                    regionHash = "h2",
                    recordedHash = "h1",
                    lastAttemptMs = baseTime - 1_000L,
                    nowMs = baseTime,
                ),
            )
            assertEquals(
                SupportCatalogAction.Download,
                SupportCatalogFreshness.decide(
                    region = region,
                    hasCached = true,
                    lastModifiedMs = baseTime - SupportCatalogFreshness.DAY_MS - 1,
                    regionHash = "h1",
                    recordedHash = "h1",
                    lastAttemptMs = baseTime - 1_000L,
                    nowMs = baseTime,
                ),
            )
        }
    }
}
