package io.github.fate_grand_automata.ui.pref_support

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.more.*
import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.portraits.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File

/** No catalog or image request escapes this fake. Saved images use the real folder listing. */
internal class PickerDownloadFixture(val kind: SupportCatalogKind = SupportCatalogKind.Servant, storageReady: Boolean = true) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = PrefsCore(PrefMaker(context.getSharedPreferences("picker-${System.nanoTime()}", Context.MODE_PRIVATE)), context)
    val title = context.getString(if (kind == SupportCatalogKind.Servant) R.string.p_battle_config_support_pref_servants else R.string.p_battle_config_support_pref_ces)
    val storage = StorageProvider(prefs, context)
    val root = File(context.cacheDir, "picker-${System.nanoTime()}").apply { mkdirs() }
    val support = SupportViewModel(storage)
    val preference = if (kind == SupportCatalogKind.Servant) prefs.forBattleConfig("test").support.preferredServants
        else prefs.forBattleConfig("test").support.preferredCEs
    var saved = 1
    var failure: PortraitImportError? = null
    var searches = 0
    var saves = 0
    var reloads = 0
    val entry = SupportCatalogEntry(kind, 90, 90, "새 이미지", "saber", 5, null, emptySet())
    val controller = PortraitImportController(CoroutineScope(Dispatchers.Unconfined), { storage.rootDirName != null },
        { requested, _, _ -> searches++; listOf(entry.copy(kind = requested)) },
        { selected, progress ->
            saves++
            failure?.let { throw PortraitImportException(it) }
            if (saved > 0) File(root, "support/${selected.kind.storage.name.lowercase()}/${selected.folderName}/face.png").apply {
                parentFile!!.mkdirs(); writeBytes(byteArrayOf(1))
            }
            progress(PortraitImportProgress(selected.name, saved, 1))
            PortraitImportReport(selected.name, selected.folderName, saved, 1 - saved, selected.kind)
        })

    init {
        if (storageReady) StorageProvider::class.java.getDeclaredField("dirRoot").apply { isAccessible = true }
            .set(storage, DocumentFile.fromFile(root))
        preference.set(setOf("기존 선택"))
        listOf("기존 선택", "추가 선택").forEach { folder ->
            File(root, "support/${kind.storage.name.lowercase()}/$folder/face.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        }
        if (storageReady) support.refresh(context)
    }

    @Composable fun Picker(download: Boolean = true) {
        val entries = if (kind == SupportCatalogKind.Servant) support.servants else support.ces
        preference.SupportSelectPreference(title, entries,
            imageDownload = if (download) { select ->
                SupportImageDownload(kind, select, { reloads++; support.refresh(context) }, controller, storage)
            } else null)
    }

    @Composable fun Tools() {
        AdditionalToolsContent(emptyList(), { controller.openPortraitImport() }, {}, {})
        PortraitImportHost(controller, storage)
    }
}
@Composable internal fun Wide(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalPortraitImportDialogWidth provides 320.dp, content = content)
