package io.github.fate_grand_automata.util.portraits

import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.scripts.enums.supportClassOfServantClass
import io.github.fate_grand_automata.util.chaldea.BuiltinSupportId
import kotlinx.coroutines.CancellationException

/**
 * Picks the support class tab for a battle config whose preferred servants changed.
 * It answers only when exactly one servant is selected and its class can be resolved; otherwise the caller keeps
 * the class as it is.
 */
class SupportClassAutoSelect(
    private val builtins: List<BuiltinSupportId>,
    private val source: SupportCatalogSource
) {
    /**
     * Applies the class of the single selected servant to [prefs]. Nothing happens when the servant selection did not
     * change (for example a CE-only edit), so a class picked by hand stays until the servants change again.
     */
    suspend fun update(prefs: SupportPrefsCore, previous: Set<String>, current: Set<String>) {
        if (previous == current) return
        val resolved = classFor(current) ?: return
        if (prefs.supportClass.get() != resolved) prefs.supportClass.set(resolved)
    }

    suspend fun classFor(servants: Set<String>): SupportClass? {
        val folder = servants.singleOrNull() ?: return null
        return try {
            className(folder)?.let(::supportClassOfServantClass)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun className(folder: String): String? {
        val builtin = builtins.firstOrNull { it.kind == "servant" && it.folder == folder }
        if (builtin != null) {
            val id = builtin.id ?: return null
            for (region in SupportImageCatalog.lookupRegions) {
                source.catalog(SupportCatalogKind.Servant, region).firstOrNull { it.id == id }?.let { return it.className }
            }
            return null
        }
        val number = ServantPortraitFolder.number(folder) ?: return null
        val region = ServantPortraitFolder.region(folder) ?: "JP"
        return source.catalog(SupportCatalogKind.Servant, region).firstOrNull { it.number == number }?.className
    }
}
