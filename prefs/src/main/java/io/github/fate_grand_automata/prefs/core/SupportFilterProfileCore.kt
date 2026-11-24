package io.github.fate_grand_automata.prefs.core

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * A named set of support list filter conditions (#695), kept in a file of its own so it belongs to no battle config
 * and to no `.fga` export. Loading one copies its conditions into the active filter; nothing stays linked.
 */
class SupportFilterProfileCore(val id: String, internal val sharedPrefs: SharedPreferences) {
    private val maker = PrefMaker(sharedPrefs)

    val name = maker.string("support_filter_profile_name", "")

    val support = SupportPrefsCore(maker)

    /** Writes what was only applied to memory so far, synchronously. */
    fun commit() = sharedPrefs.edit(commit = true) { }
}

/** Where the active filter's conditions were last copied from, for the launcher's "Conditions from" line. */
enum class SupportFilterSource {
    /** Nothing copied in yet. */
    None,
    Config,
    Profile,
}
