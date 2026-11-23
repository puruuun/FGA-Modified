package io.github.fate_grand_automata.ui.account

import io.github.fate_grand_automata.capture.AtlasBasic
import io.github.fate_grand_automata.capture.DashboardServant
import kotlin.math.roundToInt

internal data class RaritySummary(val rarity: Long, val owned: Int, val total: Int?, val np: Long)

/**
 * [total] is null when the account's server list is not loaded; nothing is then guessed from the other server's list.
 * NP sums use the value the Servants rows show, and a servant without a stored NP level counts as 0.
 */
internal data class ServantSummary(
    val owned: Int, val total: Int?, val star5Np: Long, val allNp: Long, val rarities: List<RaritySummary>,
) {
    val percent: Int? get() = total?.takeIf { it > 0 }?.let { (owned * 100f / it).roundToInt() }
}

private const val TOP_RARITY = 5L

/**
 * @param classes the raw class names a class chip selects; null counts everything.
 * @param catalog the playable servants of the account's own server, see `DashboardData.servantCatalog`.
 */
internal fun servantSummary(servants: List<DashboardServant>, catalog: List<AtlasBasic>?, classes: Set<String>? = null): ServantSummary {
    val own = if (classes == null) servants else servants.filter { it.atlas?.className in classes }
    val all = if (classes == null) catalog else catalog?.filter { it.className in classes }
    fun np(list: List<DashboardServant>) = list.sumOf { it.np ?: 0L }
    val ownedByRarity = own.filter { it.atlas?.rarity != null }.groupBy { it.atlas!!.rarity!! }
    val totalByRarity = all?.filter { it.rarity != null }?.groupBy { it.rarity!! }
    val lowest = listOfNotNull(totalByRarity?.keys?.minOrNull(), ownedByRarity.keys.minOrNull()).minOrNull() ?: TOP_RARITY
    // With a known catalog every rarity down to the lowest one on the server gets a row, even one with nothing owned.
    val rarities = if (totalByRarity == null) ownedByRarity.keys.sortedDescending() else (TOP_RARITY downTo lowest).toList()
    return ServantSummary(
        owned = own.size,
        total = all?.size,
        star5Np = np(ownedByRarity[TOP_RARITY].orEmpty()),
        allNp = np(own),
        rarities = rarities.map { r ->
            RaritySummary(r, ownedByRarity[r].orEmpty().size, totalByRarity?.let { it[r].orEmpty().size }, np(ownedByRarity[r].orEmpty()))
        },
    )
}
