package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.SupportImageKind
import io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum
import io.github.fate_grand_automata.scripts.locations.SupportScreenLocations
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.fate_grand_automata.scripts.supportSelection.*
import io.github.lib_automata.Match
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CESelectionTest {
    private class Fixture {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val messages = mockk<IScriptMessages>(relaxed = true)
        val prefs = mockk<ISupportPreferences>()
        val star = mockk<SupportSelectionStarChecker>()
        val grand = mockk<SupportSelectionGrandChecker>()
        val loc = SupportScreenLocations(mockk(relaxed = true))
        val bounds = SupportBounds(loc.defaultBounds.copy(y = 358))
        val normal = loc.grandCeRegion1.copy(y = 358 + loc.grandCeRegion1.y)
        val reward = loc.grandCeRegion3.copy(y = 358 + loc.grandCeRegion3.y)
        val bond = loc.bondCeRegion.copy(y = 358 + loc.bondCeRegion.y)
        val first = mockk<Pattern>(relaxed = true)
        val third = mockk<Pattern>(relaxed = true)
        val np = mockk<Pattern>(relaxed = true)
        val default = mockk<Pattern>(relaxed = true)
        var effect = BondCEEffectEnum.NP
        var npScore = 0.739
        var defaultScore = 0.20
        var ceScore = 0.90
        var mlb = true
        val selection: CESelection

        init {
            every { api.locations.support } returns loc
            every { api.messages } returns messages
            every { messages.supportCeDiagnosticsEnabled } returns false
            every { api.images.loadSupportPattern(SupportImageKind.CE, "Foreign God") } returns listOf(first)
            every { api.images.loadSupportPattern(SupportImageKind.CE, "Chaldea Teatime") } returns listOf(third)
            every { api.images[Images.BondCeEffectNP, any()] } returns np
            every { api.images[Images.BondCeEffectDefault, any()] } returns default
            every { prefs.mlb } returns true
            every { prefs.requireBothNormalAndRewardMatch } returns true
            every { prefs.bondCEEffect } answers { effect }
            every { grand.isGrandPresent(any()) } returns true
            every { star.isStarPresent(any()) } answers { mlb }
            every { with(api) { any<Region>().find(any<Pattern>(), any()) } } answers {
                val region = firstArg<Region>()
                val pattern = secondArg<Pattern>()
                val similarity = thirdArg<Double?>() ?: 0.80
                val score = when (pattern) { np -> npScore; default -> defaultScore; else -> ceScore }
                val matchedRegion = when (pattern) { first -> normal; third -> reward; else -> region }
                if (score >= similarity) Match(matchedRegion, score) else null
            }
            selection = CESelection(api, prefs, star, grand)
        }

        fun check() = selection.check(listOf("Foreign God", "Chaldea Teatime"), bounds)
    }

    @Test fun `both bond templates explicitly use 0_60 and preserve their distinction`() {
        for (effect in listOf(BondCEEffectEnum.NP, BondCEEffectEnum.Default)) {
            val f = Fixture()
            f.effect = effect
            for (score in listOf(0.20, 0.22, 0.45, 0.599, 0.60, 0.739, 0.844)) {
                if (effect == BondCEEffectEnum.NP) f.npScore = score else f.defaultScore = score
                assertEquals(score >= 0.60, f.check())
            }
            verify(exactly = 7) { with(f.api) { f.bond.find(if (effect == BondCEEffectEnum.NP) f.np else f.default, 0.60) } }
        }
        val f = Fixture()
        assertTrue(f.check())
        f.effect = BondCEEffectEnum.Default
        assertFalse(f.check())
    }

    @Test fun `CE similarity and MLB gates remain unchanged`() {
        val f = Fixture()
        f.ceScore = 0.79
        assertFalse(f.check())
        f.ceScore = 0.80
        assertTrue(f.check())
        f.mlb = false
        assertFalse(f.check())
        verify { with(f.api) { any<Region>().find(f.first, null) } }
        verify { with(f.api) { any<Region>().find(f.third, null) } }
    }

    @Test fun `Ignore skips bond matching and non beta builds skip diagnostic scans`() {
        val f = Fixture()
        f.effect = BondCEEffectEnum.Ignore
        assertTrue(f.check())
        verify(exactly = 0) { with(f.api) { any<Region>().find(f.np, any()) } }
        verify(exactly = 0) { with(f.api) { any<Region>().find(f.default, any()) } }
        verify(exactly = 0) { with(f.api) { any<Region>().getPattern(any()) } }
        verify(exactly = 0) { f.messages.log(any()) }
    }

    @Test fun `diagnostic scores below threshold never change the product decision and emit one line`() {
        val f = Fixture()
        every { f.messages.supportCeDiagnosticsEnabled } returns true
        val crop = mockk<Pattern>(relaxed = true)
        every { crop.width } returns 1000
        every { crop.height } returns 1000
        var openScans = 0
        every { crop.findMatches(any(), 0.01) } answers {
            sequence {
                openScans++
                try { yield(Match(Region(0, 0, 10, 10), 0.1)) } finally { openScans-- }
            }
        }
        every { with(f.api) { any<Region>().getPattern(any()) } } returns crop
        assertTrue(f.check())
        verify(exactly = 1) { f.messages.log(match { it is ScriptLog.SupportCe && it.matched && it.np?.score == 0.1 }) }
        verify { with(f.api) { f.bond.find(f.np, 0.60) } }
        assertEquals(0, openScans)
    }
}
