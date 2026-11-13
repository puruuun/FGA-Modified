package io.github.fate_grand_automata.util

import io.github.fate_grand_automata.R

/**
 * Maps an Atlas servant class name to its localized string resource, or null to fall back to the raw name.
 *
 * When [matchBeastSubstring] is true, any class containing "beast" (except those starting with "unBeast")
 * maps to [R.string.portrait_class_beast]; otherwise only names starting with "beast" do.
 */
internal fun servantClassRes(name: String, matchBeastSubstring: Boolean = false): Int? = when (name) {
    "saber" -> R.string.p_support_class_saber
    "archer" -> R.string.p_support_class_archer
    "lancer" -> R.string.p_support_class_lancer
    "rider" -> R.string.p_support_class_rider
    "caster" -> R.string.p_support_class_caster
    "assassin" -> R.string.p_support_class_assassin
    "berserker" -> R.string.p_support_class_berserker
    "shielder" -> R.string.portrait_class_shielder
    "ruler" -> R.string.portrait_class_ruler
    "avenger" -> R.string.portrait_class_avenger
    "moonCancer" -> R.string.portrait_class_mooncancer
    "alterEgo" -> R.string.portrait_class_alterego
    "foreigner" -> R.string.portrait_class_foreigner
    "pretender" -> R.string.portrait_class_pretender
    else -> when {
        // Check unBeast before beast so unBeast* is not swallowed by the beast rule.
        name.startsWith("unBeast", ignoreCase = true) -> R.string.portrait_class_unbeast
        if (matchBeastSubstring) name.contains("beast", ignoreCase = true) else name.startsWith("beast", ignoreCase = true) ->
            R.string.portrait_class_beast
        else -> null
    }
}
