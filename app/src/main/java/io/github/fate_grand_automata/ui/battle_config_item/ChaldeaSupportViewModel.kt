package io.github.fate_grand_automata.ui.battle_config_item

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.chaldea.*
import io.github.fate_grand_automata.util.portraits.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ChaldeaSupportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val storage: StorageProvider,
    atlas: AtlasSupportRepository,
    private val importer: ServantPortraitImporter
) : ViewModel(), ChaldeaSupportImages {
    val supportImport = ChaldeaSupportImport(this)
    private val lookup = SupportImageLookup(atlas)
    private val builtins by lazy {
        context.assets.open("Support/builtin-ids.json").bufferedReader().use { BuiltinSupportIds.parse(it.readText()) }
    }

    override suspend fun entry(kind: SupportCatalogKind, id: Int, language: String) = lookup.byId(kind, id, language)

    override suspend fun saved(kind: SupportCatalogKind) = withContext(Dispatchers.IO) {
        storage.existingSupportEntries(kind.storage)
    }

    override fun builtin(entry: SupportCatalogEntry): String? = builtins.singleOrNull {
        it.kind == (if (entry.kind == SupportCatalogKind.Servant) "servant" else "ce") && it.id == entry.id
    }?.folder

    override suspend fun materialize(plan: SupportImagePlan): String = withContext(Dispatchers.IO) {
        when (plan.origin) {
            SupportImageOrigin.Saved -> plan.folder!!
            SupportImageOrigin.Builtin -> {
                val folder = plan.folder!!
                if (folder in storage.existingSupportEntries(plan.entry.kind.storage)) return@withContext folder
                val path = "Support/${if (plan.entry.kind == SupportCatalogKind.Servant) "servant" else "ce"}/$folder"
                val children = context.assets.list(path).orEmpty()
                storage.createNoMediaFile()
                for (name in if (children.isEmpty()) listOf("") else children.toList()) {
                    context.assets.open(if (name.isEmpty()) path else "$path/$name").use { input ->
                        storage.writeSupportImage(plan.entry.kind.storage, if (name.isEmpty()) folder else "$folder/$name")
                            .use { input.copyTo(it) }
                    }
                }
                folder
            }
            SupportImageOrigin.Download -> importer.run(plan.entry) {}.let {
                if (it.saved == 0 || it.failed > 0) throw PortraitImportException(PortraitImportError.Image)
                it.folder
            }
        }
    }
}
