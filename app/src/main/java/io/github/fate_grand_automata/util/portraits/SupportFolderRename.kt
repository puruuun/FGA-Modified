package io.github.fate_grand_automata.util.portraits

/** Some document providers move the folder but fail while returning its new URI. */
internal object SupportFolderRename {
    fun confirmed(
        source: String,
        destination: String,
        exists: (String) -> Boolean,
        rename: () -> Boolean
    ): Boolean {
        if (!exists(source) || exists(destination)) return false
        try {
            rename()
        } catch (_: Exception) {
            // Verify the provider's actual state before treating the move as a failure.
        }
        return !exists(source) && exists(destination)
    }
}
