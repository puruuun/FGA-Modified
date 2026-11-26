package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.isInSupport
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.scripts.locations.IScriptAreaTransforms
import io.github.fate_grand_automata.scripts.locations.SupportScreenLocations
import io.github.fate_grand_automata.scripts.locations.WIDE_EDGE_INSET
import io.github.fate_grand_automata.scripts.modules.RealSupportScreen
import io.github.fate_grand_automata.scripts.supportSelection.FirstSupportSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportSelectionResult
import io.github.lib_automata.Location
import io.github.lib_automata.Match
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.Swiper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

class SupportTabBarLayoutTest {
    private fun transforms(width: Int): IScriptAreaTransforms = object : IScriptAreaTransforms {
        override val scriptArea = Region(0, 0, width, 1440)
        override val isWide = scriptArea.size.isWide()
        override val gameServer: GameServer = GameServers.default
        override val canLongSwipe = false
        override fun Location.xFromCenter() = this + Location(scriptArea.center.x, 0)
        override fun Region.xFromCenter() = this + Location(scriptArea.center.x, 0)
        override fun Location.xFromRight() = this + Location(scriptArea.right, 0)
        override fun Region.xFromRight() = this + Location(scriptArea.right, 0)
        override fun Location.yFromBottom() = this + Location(0, scriptArea.bottom)
        override fun Region.yFromBottom() = this + Location(0, scriptArea.bottom)
    }

    private inner class ApiFixture(width: Int = 2560) {
        val supportLocations = SupportScreenLocations(transforms(width))
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val swipe = mockk<Swiper>(relaxed = true)
        val patterns = mutableMapOf<Images, Pattern>()
        val clicks = mutableListOf<Location>()

        var minSimilarity: Double = 0.80
        var standardExtraScore: Double? = null
        var compactExtraScore: Double? = null
        var standardScreenScore: Double? = null
        var compactScreenScore: Double? = null
        var vanishPatternSeen: Pattern? = null
        var vanishResult: Boolean = true
        var closedCrops: Int = 0

        private fun cropPattern(region: Region): Pattern = mockk<Pattern>(relaxed = true).also { crop ->
            every { crop.close() } answers { closedCrops++ }
            every { crop.findMatches(any(), any()) } answers {
                val pattern = firstArg<Pattern>()
                val threshold = secondArg<Double>()
                val score = when {
                    region == supportLocations.extraRegion && pattern == patterns[Images.SupportExtra] -> standardExtraScore
                    region == supportLocations.extraRegion && pattern == patterns[Images.SupportExtraCompact] -> compactExtraScore
                    region == supportLocations.screenCheckRegion && pattern == patterns[Images.SupportScreen] -> standardScreenScore
                    region == supportLocations.screenCheckRegion && pattern == patterns[Images.SupportScreenCompact] -> compactScreenScore
                    else -> null
                }
                if (score != null && score >= threshold) sequenceOf(Match(region, score)) else emptySequence()
            }
        }

        init {
            every { api.prefs.platformPrefs.minSimilarity } answers { minSimilarity }
            every { api.locations.support } returns supportLocations
            every { api.images[any(), any()] } answers { patterns.getOrPut(firstArg()) { mockk() } }
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
            every { with(api) { any<Duration>().wait(any()) } } just Runs
            every { with(api) { any<Location>().click(any()) } } answers {
                clicks += firstArg<Location>()
            }
            every { with(api) { any<Region>().getPattern(any()) } } answers {
                cropPattern(firstArg<Region>())
            }
            every { with(api) { any<Region>().exists(any<Pattern>(), any(), any()) } } answers {
                val region = firstArg<Region>()
                val pattern = secondArg<Pattern>()
                val threshold = arg<Double?>(3) ?: minSimilarity
                when {
                    region == supportLocations.screenCheckRegion && pattern == patterns[Images.SupportScreen] ->
                        (standardScreenScore ?: 0.0) >= threshold
                    region == supportLocations.screenCheckRegion && pattern == patterns[Images.SupportScreenCompact] ->
                        (compactScreenScore ?: 0.0) >= threshold
                    else -> false
                }
            }
            every { with(api) { any<Region>().waitVanish(any<Pattern>(), any(), any()) } } answers {
                vanishPatternSeen = secondArg<Pattern>()
                vanishResult
            }
        }
    }

    @Test
    fun `11-tab compact Extra clears dialog wait and clicks shifted class coordinates`() {
        val f = ApiFixture()
        f.standardExtraScore = null
        f.compactExtraScore = 0.95
        val screen = RealSupportScreen(f.api, f.swipe)

        assertFalse(screen.isAnyDialogOpen())
        screen.click(SupportClass.All)
        screen.click(SupportClass.Caster)

        assertEquals(
            listOf(Location(300, 256), Location(907, 256)),
            f.clicks
        )
    }

    @Test
    fun `10-tab standard Extra keeps original class coordinates`() {
        val f = ApiFixture()
        f.standardExtraScore = 0.96
        f.compactExtraScore = null
        val screen = RealSupportScreen(f.api, f.swipe)

        assertFalse(screen.isAnyDialogOpen())
        screen.click(SupportClass.All)
        screen.click(SupportClass.Caster)

        assertEquals(
            listOf(Location(184, 256), Location(858, 256)),
            f.clicks
        )
    }

    @Test
    fun `when both Extra templates match at lowered minSimilarity the higher score picks the layout`() {
        val f = ApiFixture()
        f.minSimilarity = 0.70
        val screen = RealSupportScreen(f.api, f.swipe)

        // 11-tab frame with lowered minSimilarity: standard matches ~0.72, compact matches ~0.95.
        f.standardExtraScore = 0.72
        f.compactExtraScore = 0.95
        assertFalse(screen.isAnyDialogOpen())
        screen.click(SupportClass.All)

        // Next quest is 10-tab: standard matches ~0.96, compact matches ~0.71; must revert to 10-tab.
        f.standardExtraScore = 0.96
        f.compactExtraScore = 0.71
        assertFalse(screen.isAnyDialogOpen())
        screen.click(SupportClass.All)

        assertEquals(
            listOf(Location(300, 256), Location(184, 256)),
            f.clicks
        )
        assertEquals(2, f.closedCrops)
        verify(exactly = 0) { f.api.useSameSnapIn<Any?>(any()) }
    }

    @Test
    fun `dialog open returns true when neither Extra template matches`() {
        val f = ApiFixture()
        f.standardExtraScore = null
        f.compactExtraScore = null
        val screen = RealSupportScreen(f.api, f.swipe)

        assertTrue(screen.isAnyDialogOpen())
    }

    @Test
    fun `11-tab coordinates apply headerOffset on wide screens`() {
        val normal = SupportScreenLocations(transforms(2560))
        val wide = SupportScreenLocations(transforms(3360))

        val expected11 = mapOf(
            SupportClass.All to 300,
            SupportClass.Saber to 421,
            SupportClass.Archer to 543,
            SupportClass.Lancer to 664,
            SupportClass.Rider to 786,
            SupportClass.Caster to 907,
            SupportClass.Assassin to 1029,
            SupportClass.Berserker to 1150,
            SupportClass.Extra to 1272,
            SupportClass.Mix to 1394,
        )
        for ((cls, x) in expected11) {
            assertEquals(Location(x, 256), normal.locate(cls, hasRecommendedTab = true))
            assertEquals(Location(x + WIDE_EDGE_INSET, 256), wide.locate(cls, hasRecommendedTab = true))
        }
    }

    @Test
    fun `isInSupport matches compact 11-tab header when standard header falls below threshold`() {
        val f = ApiFixture()
        f.standardScreenScore = 0.84
        f.compactScreenScore = 0.97

        assertTrue(f.api.isInSupport())
        verify(exactly = 0) { f.api.useSameSnapIn<Any?>(any()) }
    }

    @Test
    fun `isInSupport short-circuits on standard 10-tab header without checking compact`() {
        val f = ApiFixture()
        val compactPattern = f.api.images[Images.SupportScreenCompact]
        f.standardScreenScore = 0.96
        f.compactScreenScore = 0.87

        assertTrue(f.api.isInSupport())
        verify(exactly = 0) {
            with(f.api) {
                f.supportLocations.screenCheckRegion.exists(compactPattern, any(), any())
            }
        }
    }

    @Test
    fun `isInSupport returns false when neither header meets 0_85`() {
        val f = ApiFixture()
        f.standardScreenScore = 0.80
        f.compactScreenScore = 0.81

        assertFalse(f.api.isInSupport())
    }

    @Test
    fun `FirstSupportSelection waits for SupportScreenCompact to vanish on 11-tab screen`() {
        val f = ApiFixture()
        f.standardScreenScore = 0.88
        f.compactScreenScore = 0.97
        f.vanishResult = true

        val result = FirstSupportSelection(f.api).select()

        assertEquals(SupportSelectionResult.Done, result)
        assertEquals(f.patterns[Images.SupportScreenCompact], f.vanishPatternSeen)
        assertEquals(1, f.closedCrops)
    }

    @Test
    fun `FirstSupportSelection waits for SupportScreen to vanish on 10-tab screen`() {
        val f = ApiFixture()
        f.standardScreenScore = 0.96
        f.compactScreenScore = 0.87
        f.vanishResult = false

        val result = FirstSupportSelection(f.api).select()

        assertEquals(SupportSelectionResult.Refresh, result)
        assertEquals(f.patterns[Images.SupportScreen], f.vanishPatternSeen)
    }
}
