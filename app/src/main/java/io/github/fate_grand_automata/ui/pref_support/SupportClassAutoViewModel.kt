package io.github.fate_grand_automata.ui.pref_support

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.util.chaldea.BuiltinSupportIds
import io.github.fate_grand_automata.util.portraits.AtlasSupportRepository
import io.github.fate_grand_automata.util.portraits.SupportClassAutoSelect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SupportClassAutoViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val atlas: AtlasSupportRepository
) : ViewModel() {
    private val selector by lazy {
        val builtins = context.assets.open("Support/builtin-ids.json").bufferedReader()
            .use { BuiltinSupportIds.parse(it.readText()) }
        SupportClassAutoSelect(builtins, atlas)
    }

    /** Sets the support class from the single selected servant; leaves it alone when that cannot be resolved. */
    suspend fun onServantsChanged(prefs: SupportPrefsCore, previous: Set<String>, current: Set<String>) {
        if (current.size != 1 || previous == current) return
        try {
            withContext(Dispatchers.IO) { selector.update(prefs, previous, current) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Leave the class as it is.
        }
    }
}
