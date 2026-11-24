package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.BattleScreenLocations
import io.github.fate_grand_automata.scripts.locations.IScriptAreaTransforms
import io.github.fate_grand_automata.scripts.modules.NpCardTypeReader
import io.github.fate_grand_automata.scripts.modules.parseNpChangeTurns
import io.github.lib_automata.Location
import io.github.lib_automata.Match
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test

class NpCardTypeReaderTest {
    private fun battle(server: GameServer): BattleScreenLocations {
        val transforms = object : IScriptAreaTransforms {
            override val scriptArea = Region(0, 0, 2560, 1440)
            override val isWide = false
            override val gameServer = server
            override val canLongSwipe = false
            override fun Location.xFromCenter() = this + Location(scriptArea.center.x, 0)
            override fun Region.xFromCenter() = this + Location(scriptArea.center.x, 0)
            override fun Location.xFromRight() = this + Location(scriptArea.right, 0)
            override fun Region.xFromRight() = this + Location(scriptArea.right, 0)
            override fun Location.yFromBottom() = this + Location(0, scriptArea.bottom)
            override fun Region.yFromBottom() = this + Location(0, scriptArea.bottom)
        }
        return BattleScreenLocations(transforms, mockk(relaxed = true))
    }

    @Test
    fun `the NP type region is upstream's on every server`() {
        for (server in listOf(GameServer.Kr, GameServer.Jp.Original)) {
            assertThat(battle(server).servantNpCardTypeRegion).isEqualTo(Region(545, 760, 100, 50))
        }
    }

    @Test
    fun `only KR moves the turn digit region`() {
        assertThat(battle(GameServer.Jp.Original).servantNpChangeTurnsRegion).isEqualTo(Region(624, 764, 28, 34))
        assertThat(battle(GameServer.Kr).servantNpChangeTurnsRegion).isEqualTo(Region(670, 764, 26, 34))
    }

    private fun template(name: String) = mockk<Pattern>(name = name)

    private fun reader(scores: Map<String, Double>): Pair<NpCardTypeReader, Pattern> {
        val templates = mapOf(
            Images.NpBuster to template("buster"),
            Images.NpArts to template("arts"),
            Images.NpQuick to template("quick")
        )
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        every { api.images[any(), any()] } answers { templates.getValue(firstArg()) }
        val crop = mockk<Pattern>()
        every { crop.findBestMatch(any(), 0.0) } answers {
            val name = templates.entries.first { it.value === firstArg<Pattern>() }.key
            val score = scores.getValue(name.name)
            Match(Region(0, 0, 50, 25), score)
        }
        return NpCardTypeReader(api, null) to crop
    }

    @Test
    fun `the best type at or above the similarity wins`() {
        val (reader, crop) = reader(mapOf("NpBuster" to 0.20, "NpArts" to 0.06, "NpQuick" to 0.985))
        val reading = reader.readType(crop, 0.5)
        assertThat(reading.type).isEqualTo(CardTypeEnum.Quick)
        assertThat(reading.scores).isEqualTo(
            mapOf(CardTypeEnum.Buster to 0.20, CardTypeEnum.Arts to 0.06, CardTypeEnum.Quick to 0.985)
        )
    }

    @Test
    fun `a banner over the art gives no type`() {
        val (reader, crop) = reader(mapOf("NpBuster" to -0.01, "NpArts" to 0.10, "NpQuick" to -0.06))
        assertThat(reader.readType(crop, 0.5).type).isNull()
    }

    @Test
    fun `the turn counter accepts only digits`() {
        assertThat(parseNpChangeTurns("3")).isEqualTo(3)
        for (text in listOf("턴", "앞3", "3턴", "로", "")) {
            assertThat(parseNpChangeTurns(text), text).isNull()
        }
    }
}
