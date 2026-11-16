package io.github.fate_grand_automata.scripts.locations

import assertk.assertThat
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.isWide
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.lib_automata.Location
import io.github.lib_automata.Region
import kotlin.test.Test

class AttackScreenLocationsTest {
    private fun transforms(width: Int, server: GameServer = GameServer.Jp.Original): IScriptAreaTransforms =
        object : IScriptAreaTransforms {
            override val scriptArea = Region(0, 0, width, 1440)
            override val isWide = scriptArea.size.isWide()
            override val gameServer = server
            override val canLongSwipe = false
            override fun Location.xFromCenter() = this + Location(scriptArea.center.x, 0)
            override fun Region.xFromCenter() = this + Location(scriptArea.center.x, 0)
            override fun Location.xFromRight() = this + Location(scriptArea.right, 0)
            override fun Region.xFromRight() = this + Location(scriptArea.right, 0)
            override fun Location.yFromBottom() = this + Location(0, scriptArea.bottom)
            override fun Region.yFromBottom() = this + Location(0, scriptArea.bottom)
        }

    @Test
    fun `swapIconRegion sits inside typeRegion column and never overlaps across cards in 16x9 and 21x9`() {
        for (width in listOf(2560, 3412)) {
            val loc = AttackScreenLocations(transforms(width))
            val regions = CommandCard.Face.list.map { card ->
                val type = loc.typeRegion(card)
                val swap = loc.swapIconRegion(card)
                assertThat(swap.x).isGreaterThanOrEqualTo(type.x)
                assertThat(swap.right).isLessThanOrEqualTo(type.right)
                swap
            }
            for (i in 0 until regions.lastIndex) {
                assertThat(regions[i].right).isLessThanOrEqualTo(regions[i + 1].x)
            }
        }
    }
}
