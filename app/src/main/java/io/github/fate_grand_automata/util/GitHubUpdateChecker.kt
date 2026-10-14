package io.github.fate_grand_automata.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.inject.Inject

data class GitHubUpdate(val version: String, val releaseUrl: String)

/** Only called by the foreground app. No worker, background polling or APK installation. */
class GitHubUpdateChecker @Inject constructor() {
    suspend fun findUpdate(installedVersion: String): GitHubUpdate? = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "FGA-mod-update-check")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub release check returned HTTP ${connection.responseCode}")
            }
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                it.readTextLimited()
            }
            newerRelease(json, installedVersion)
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.Reader.readTextLimited(): String {
        val text = StringBuilder()
        val buffer = CharArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) return text.toString()
            if (text.length + count > 1_048_576) throw IOException("GitHub response too large")
            text.append(buffer, 0, count)
        }
    }

    companion object {
        const val LATEST_RELEASE_API = "https://api.github.com/repos/puruuun/FGA-Modified/releases/latest"
        private const val REPOSITORY_PATH = "/puruuun/FGA-Modified/releases/"
        private val stableVersion = Regex("""v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:\+[0-9A-Za-z.-]+)?""")

        private fun versionParts(version: String): List<Int>? {
            val match = stableVersion.matchEntire(version) ?: return null
            return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
        }

        internal fun isNewer(candidate: String, installed: String): Boolean {
            val next = versionParts(candidate) ?: return false
            val current = versionParts(installed) ?: return false
            return next.zip(current).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
        }

        private fun repositoryUrl(url: String, path: String): Boolean {
            val uri = URI(url)
            return uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
                uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path == path
        }

        internal fun newerRelease(json: String, installedVersion: String): GitHubUpdate? {
            return runCatching {
                val release = Json.parseToJsonElement(json).jsonObject
                if (release["draft"]?.jsonPrimitive?.booleanOrNull != false ||
                    release["prerelease"]?.jsonPrimitive?.booleanOrNull != false) return null
                val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
                if (!isNewer(tag, installedVersion)) return null
                val version = tag.removePrefix("v")
                val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return null
                if (!repositoryUrl(url, "${REPOSITORY_PATH}tag/$tag")) return null
                // A published release can briefly exist before its APK upload has completed.
                val apkName = "FGA-mod-$version-arm.apk"
                val hasApk = release["assets"]?.jsonArray?.any { item ->
                    val asset = item.jsonObject
                    asset["name"]?.jsonPrimitive?.contentOrNull == apkName &&
                        asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded" &&
                        (asset["size"]?.jsonPrimitive?.longOrNull ?: 0) > 0 &&
                        asset["browser_download_url"]?.jsonPrimitive?.contentOrNull?.let {
                            repositoryUrl(it, "${REPOSITORY_PATH}download/$tag/$apkName")
                        } == true
                } == true
                if (hasApk) GitHubUpdate(version, url) else null
            }.getOrNull()
        }
    }
}
