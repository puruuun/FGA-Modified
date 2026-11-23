package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.SupportListCriteria

/** Null while the experiment (#636) is off or no servant is chosen: the proxy then relays every response untouched. */
internal fun supportFilterCriteria(prefs: PrefsCore): SupportListCriteria? {
    if (!prefs.captureSupportFilterEnabled.get()) return null
    val servants = prefs.captureSupportFilterServants.get().mapNotNull { it.toLongOrNull() }.toSet()
    if (servants.isEmpty()) return null
    return SupportListCriteria(servants, prefs.captureSupportFilterCes.get().mapNotNull { it.toLongOrNull() }.toSet())
}
