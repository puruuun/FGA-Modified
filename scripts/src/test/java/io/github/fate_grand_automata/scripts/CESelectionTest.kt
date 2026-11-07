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
        var grandServant = true
        var grandOnScreen = true
        var requireBoth = true
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
            every { prefs.requireBothNormalAndRewardMatch } answers { requireBoth }
            every { prefs.grandServant } answers { grandServant }
            every { prefs.bondCEEffect } answers { effect }
            every { grand.isGrandPresent(any()) } answers { grandOnScreen }
            every { star.isStarPresent(any()) } answers { mlb }
            val crop = mockk<Pattern>(relaxed = true)
            every { crop.width } returns 1000
            every { crop.height } returns 1000
            every { with(api) { any<Region>().getPattern(any()) } } returns crop
            every { crop.findMatches(any(), 0.01) } answers {
                val score = when (firstArg<Pattern>()) { np -> npScore; default -> defaultScore; else -> ceScore }
                sequenceOf(Match(Region(0, 0, 10, 10), score))
            }
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

    @Test fun `both bond templates accept 0_55 only when ahead of the opposite icon`() {
        for (effect in listOf(BondCEEffectEnum.NP, BondCEEffectEnum.Default)) {
            val f = Fixture()
            f.effect = effect
            f.npScore = 0.20
            f.defaultScore = 0.20
            for (score in listOf(0.20, 0.22, 0.45, 0.549, 0.55, 0.5926, 0.739, 0.844)) {
                if (effect == BondCEEffectEnum.NP) f.npScore = score else f.defaultScore = score
                assertEquals(score >= 0.55, f.check())
            }
        }
        val f = Fixture()
        assertTrue(f.check())
        f.effect = BondCEEffectEnum.Default
        assertFalse(f.check())
    }

    @Test fun `NP wins Default wins both below threshold and ties`() {
        val f = Fixture()
        for ((np, default, winner) in listOf(
            Triple(0.5926, 0.20, BondCEEffectEnum.NP),
            Triple(0.55, 0.54, BondCEEffectEnum.NP),
            Triple(0.60, 0.737, BondCEEffectEnum.Default),
            Triple(0.54, 0.55, BondCEEffectEnum.Default),
            Triple(0.54, 0.45, null),
            Triple(0.60, 0.60, null)
        )) {
            f.npScore = np
            f.defaultScore = default
            for (effect in listOf(BondCEEffectEnum.NP, BondCEEffectEnum.Default)) {
                f.effect = effect
                assertEquals(effect == winner, f.check(), "$effect: NP=$np Default=$default")
            }
        }
    }

    @Test fun `both scores use one crop without nesting snapshots`() {
        val f = Fixture()
        assertTrue(f.check())
        verify(exactly = 1) { with(f.api) { f.bond.getPattern(any()) } }
        verify(exactly = 0) { f.api.useSameSnapIn<Any?>(any()) }
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

    @Test fun `diagnostics log the decision scores once and exhaust native match sequences`() {
        val f = Fixture()
        every { f.messages.supportCeDiagnosticsEnabled } returns true
        val crop = mockk<Pattern>(relaxed = true)
        every { crop.width } returns 1000
        every { crop.height } returns 1000
        var openScans = 0
        every { crop.findMatches(any(), 0.01) } answers {
            sequence {
                openScans++
                try { yield(Match(Region(0, 0, 10, 10), if (firstArg<Pattern>() == f.np) 0.5926 else 0.20)) } finally { openScans-- }
            }
        }
        every { with(f.api) { any<Region>().getPattern(any()) } } returns crop
        assertTrue(f.check())
        verify(exactly = 1) { f.messages.log(match {
            it is ScriptLog.SupportCe && it.matched && it.np?.score == 0.5926 && it.default?.score == 0.20
        }) }
        assertEquals(0, openScans)
    }

    @Test fun `non Grand support ignores leftover bond settings`() {
        val f = Fixture()
        f.grandOnScreen = false
        f.grandServant = false
        f.effect = BondCEEffectEnum.NP
        f.requireBoth = true
        assertTrue(f.check())
        f.effect = BondCEEffectEnum.Default
        assertTrue(f.check())
        // A missing CE still fails, so only the leftover options stopped mattering
        f.ceScore = 0.79
        assertFalse(f.check())
    }

    @Test fun `non Grand support with Grand Servant on keeps the old Grand only behaviour`() {
        val f = Fixture()
        f.grandOnScreen = false
        f.grandServant = true
        f.effect = BondCEEffectEnum.Ignore
        f.requireBoth = false
        assertTrue(f.check())
        f.effect = BondCEEffectEnum.NP
        assertFalse(f.check())
        f.effect = BondCEEffectEnum.Ignore
        f.requireBoth = true
        assertFalse(f.check())
    }

    @Test fun `Grand support with Grand Servant off treats bond effect as Ignore and one slot as enough`() {
        val f = Fixture()
        f.grandServant = false
        f.effect = BondCEEffectEnum.Default // would fail: only the NP icon scores high
        f.requireBoth = true
        assertTrue(f.check())
        verify(exactly = 0) { with(f.api) { any<Region>().getPattern(any()) } }
        // requireBoth is off too: with only the reward slot found, the check still passes
        every { with(f.api) { any<Region>().find(f.first, any()) } } returns null
        assertTrue(f.check())
    }

    @Test fun `Grand support with Grand Servant on still honours the bond effect and both slots`() {
        val f = Fixture()
        f.effect = BondCEEffectEnum.Default
        assertFalse(f.check())
        f.effect = BondCEEffectEnum.NP
        assertTrue(f.check())
        every { with(f.api) { any<Region>().find(f.first, any()) } } returns null
        assertFalse(f.check())
    }
}
