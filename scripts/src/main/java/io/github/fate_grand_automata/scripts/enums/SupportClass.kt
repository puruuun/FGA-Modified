package io.github.fate_grand_automata.scripts.enums

enum class SupportClass {
    None,
    All,
    Saber,
    Archer,
    Lancer,
    Rider,
    Caster,
    Assassin,
    Berserker,
    Extra,
    Mix
}

val SupportClass.canAlsoCheckAll get() =
    this !in listOf(SupportClass.None, SupportClass.All, SupportClass.Mix)
private val baseServantClasses = listOf(
    SupportClass.Saber, SupportClass.Archer, SupportClass.Lancer, SupportClass.Rider,
    SupportClass.Caster, SupportClass.Assassin, SupportClass.Berserker
)

private val extraServantClasses = setOf(
    "shielder", "ruler", "avenger", "mooncancer", "alterego", "foreigner", "pretender"
)

/**
 * Maps an Atlas Academy servant class name (e.g. `saber`, `moonCancer`, `beastEresh`) to the support class tab
 * it is listed under in game. Returns null for an unknown class.
 */
fun supportClassOfServantClass(className: String): SupportClass? {
    val name = className.trim().lowercase()
    return baseServantClasses.firstOrNull { it.name.lowercase() == name }
        ?: if (name in extraServantClasses || name.startsWith("beast")) SupportClass.Extra else null
}
