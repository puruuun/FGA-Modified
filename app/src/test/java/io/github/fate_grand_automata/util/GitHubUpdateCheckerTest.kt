package io.github.fate_grand_automata.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubUpdateCheckerTest {
    // Relevant fields from the public FGA-Modified latest-release response, captured 2026-10-14.
    private val published = javaClass.getResource("/github/latest-stable.json")!!.readText()

    @Test fun `the real stable release offers an update to an older install only`() {
        val update = assertNotNull(GitHubUpdateChecker.newerRelease(published, "0.8.0"))
        assertEquals("0.9.0", update.version)
        assertEquals("https://github.com/puruuun/FGA-Modified/releases/tag/v0.9.0", update.releaseUrl)
        assertNull(GitHubUpdateChecker.newerRelease(published, "0.9.0"))
        assertNull(GitHubUpdateChecker.newerRelease(published, "0.9.1"))
        assertNull(GitHubUpdateChecker.newerRelease(published, "1.0.0"))
    }

    @Test fun `version components are compared numerically rather than alphabetically`() {
        for ((next, installed) in listOf("v0.10.0" to "0.9.9", "1.0.0" to "0.99.99", "0.9.10" to "0.9.9")) {
            assertTrue(GitHubUpdateChecker.isNewer(next, installed))
            assertFalse(GitHubUpdateChecker.isNewer(installed, next))
        }
        assertFalse(GitHubUpdateChecker.isNewer("v0.9.0+build2", "0.9.0"))
    }

    @Test fun `pre releases upstream build numbers and invalid tags never become update prompts`() {
        for (tag in listOf("v0.10.0-beta.1", "v3059-cardcount.4", "3075", "0.10", "latest", "0.09.1", "1.0.999999999999999")) {
            assertFalse(GitHubUpdateChecker.isNewer(tag, "0.9.0"), tag)
        }
        assertNull(GitHubUpdateChecker.newerRelease(published, "unknown"))
        assertNull(GitHubUpdateChecker.newerRelease(published.replace("\"prerelease\": false", "\"prerelease\": true"), "0.8.0"))
        assertNull(GitHubUpdateChecker.newerRelease(published.replace("\"draft\": false", "\"draft\": true"), "0.8.0"))
    }

    @Test fun `a release is offered only after its compatible APK is uploaded`() {
        for (json in listOf(
            published.replace("\"uploaded\"", "\"new\""),
            published.replace("-arm.apk", "-x86.apk"),
            published.replace(Regex("\"size\": \\d+"), "\"size\": 0"),
            published.substringBefore("\"assets\"") + "\"assets\": []}"
        )) assertNull(GitHubUpdateChecker.newerRelease(json, "0.8.0"))
    }

    @Test fun `only release and download links in the configured HTTPS repository are accepted`() {
        for (json in listOf(
            published.replace("puruuun/FGA-Modified", "Fate-Grand-Automata/FGA"),
            published.replace("https://github.com/", "http://github.com/"),
            published.replace("https://github.com/", "https://github.com.evil.example/"),
            published.replace("/tag/v0.9.0", "/tag/v1.0.0"),
            published.replace("/download/v0.9.0", "/download/v0.8.0")
        )) assertNull(GitHubUpdateChecker.newerRelease(json, "0.8.0"))
    }

    @Test fun `malformed missing or unexpected release fields do not crash the check`() {
        for (json in listOf("", "not json", "[]", "{}", "{\"draft\":false}", published.replace("\"assets\": [", "\"assets\": {"))) {
            assertNull(GitHubUpdateChecker.newerRelease(json, "0.8.0"))
        }
    }

    private fun betaRelease(version: String = "1.0.2-beta.2") = """{
        "draft": false, "prerelease": true, "tag_name": "v$version",
        "html_url": "https://github.com/puruuun/FGA-Modified/releases/tag/v$version",
        "assets": [{"name":"FGA-beta-$version-arm.apk", "state":"uploaded", "size":123,
          "browser_download_url":"https://github.com/puruuun/FGA-Modified/releases/download/v$version/FGA-beta-$version-arm.apk"}]
    }"""

    @Test fun `beta checks stay on the beta channel and choose the newest numeric beta`() {
        val releases = "[${betaRelease()},${betaRelease("1.0.2-beta.10")},$published]"
        assertEquals("1.0.2-beta.10", GitHubUpdateChecker.newerBetaRelease(releases, "1.0.2-beta.1")?.version)
        assertNull(GitHubUpdateChecker.newerBetaRelease(releases, "1.0.2-beta.10"))
        assertNull(GitHubUpdateChecker.newerRelease(betaRelease(), "1.0.1"))
        assertNull(GitHubUpdateChecker.newerBetaRelease("[$published]", "1.0.2-beta.1"))
    }

    @Test fun `beta checks reject missing APK assets drafts and mismatched URLs`() {
        for (release in listOf(
            betaRelease().replace("\"draft\": false", "\"draft\": true"),
            betaRelease().replace("FGA-beta-", "FGA-mod-"),
            betaRelease().replace("\"uploaded\"", "\"new\""),
            betaRelease().replace("puruuun/FGA-Modified", "other/repository"),
            betaRelease().replace("\"size\":123", "\"size\":0")
        )) assertNull(GitHubUpdateChecker.newerBetaRelease("[$release]", "1.0.2-beta.1"))
    }
}
