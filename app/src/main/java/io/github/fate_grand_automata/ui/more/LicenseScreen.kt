package io.github.fate_grand_automata.ui.more

import android.content.res.AssetManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Name of the repository LICENSE as bundled into the APK assets by the `copyLicenseAsset` task. */
const val LICENSE_ASSET = "LICENSE"

/** The notices of the libraries and data every build carries (a committed file, packaged in every build type). */
const val ANDROID_NOTICES_ASSET = "licenses/THIRD_PARTY_NOTICES.txt"

/**
 * The third-party notices of the text recogniser (design 0026, D-10), packaged by the
 * `copyEngineNotices` task only in builds that carry it.
 */
const val OPEN_SOURCE_NOTICES_ASSET = "ocr/THIRD_PARTY_NOTICES.txt"

/** Shown in this order, as one list. */
val NOTICE_ASSETS = listOf(ANDROID_NOTICES_ASSET, OPEN_SOURCE_NOTICES_ASSET)

fun assetPackaged(assets: AssetManager, name: String): Boolean =
    try {
        assets.list(name.substringBeforeLast('/')).orEmpty().contains(name.substringAfterLast('/'))
    } catch (e: Exception) {
        false
    }

fun packagedNoticeAssets(assets: AssetManager): List<String> = NOTICE_ASSETS.filter { assetPackaged(assets, it) }

/**
 * The Android notices are about 63 KB and the engine's about 110 KB, too long for one Text: they are read
 * off the main thread and shown as one lazy item per paragraph, so only the visible ones are laid out.
 */
fun noticeParagraphs(text: String): List<String> =
    text.split(Regex("\n\\s*\n")).map { it.trimEnd() }.filter { it.isNotBlank() }

@Composable
fun LicenseScreen() {
    val context = LocalContext.current
    val licenseText = remember {
        try {
            context.assets.open(LICENSE_ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }
    val notices: (suspend () -> List<String>)? = remember {
        val names = packagedNoticeAssets(context.assets)
        if (names.isNotEmpty()) {
            { loadNoticeAssets(names) { context.assets.open(it).bufferedReader().use { r -> r.readText() } } }
        } else {
            null
        }
    }
    LicenseScreenContent(licenseText, notices)
}

/** [notices] null: the build has no notices file, and the screen is the licence alone. */
@Composable
fun LicenseScreenContent(licenseText: String, notices: (suspend () -> List<String>)?) {
    var showNotices by rememberSaveable { mutableStateOf(false) }
    if (notices != null && showNotices) {
        BackHandler { showNotices = false }
        OpenSourceNotices(notices)
        return
    }

    CappedWidth {
        Column(modifier = Modifier.fillMaxSize()) {
            Heading(stringResource(R.string.p_license))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.license_header),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = licenseText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                if (notices != null) {
                    Text(
                        text = stringResource(R.string.open_source_notices_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                    OutlinedButton(
                        onClick = { showNotices = true },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.p_open_source_notices))
                    }
                }
            }
        }
    }
}

/**
 * An asset that cannot be read adds nothing and does not hide the others. A blocking read cannot throw a
 * cancellation itself, so leaving the screen is noticed between the assets by [ensureActive]: the remaining
 * assets are not read, and the cancellation reaches the caller through [withContext].
 */
internal suspend fun loadNoticeAssets(names: List<String>, read: (String) -> String): List<String> =
    withContext(Dispatchers.IO) {
        names.flatMap { name ->
            ensureActive()
            try {
                noticeParagraphs(read(name))
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

/** An unreadable asset shows an empty list; leaving the screen cancels the load, which must stay a cancellation. */
internal suspend fun loadNoticesOrEmpty(load: suspend () -> List<String>): List<String> =
    try {
        load()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

@Composable
private fun OpenSourceNotices(load: suspend () -> List<String>) {
    val paragraphs by produceState<List<String>?>(initialValue = null, load) {
        value = loadNoticesOrEmpty(load)
    }

    CappedWidth {
        Column(modifier = Modifier.fillMaxSize()) {
            Heading(stringResource(R.string.p_open_source_notices))
            val loaded = paragraphs
            if (loaded == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    itemsIndexed(loaded) { _, paragraph ->
                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                }
            }
        }
    }
}
