package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.SupportSkillLayout
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.fate_grand_automata.scripts.supportSelection.*
import io.github.lib_automata.Location
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppendSupportTest {
    private class Fixture(
        enabled: Boolean = true,
        required: List<Boolean> = List(5) { true },
        characters: List<String> = listOf("test"),
        scoreEnabled: Boolean = false,
        ceNames: List<String> = if (characters.isEmpty()) listOf("CE") else emptyList(),
        npEnabled: Boolean = false,
        minimumNp: Int = 5,
        grandScoreEnabled: Boolean = false
    ) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val prefs = mockk<ISupportPreferences>(relaxed = true)
        val normal = mockk<Pattern>()
        val append = mockk<Pattern>()
        val score = mockk<Pattern>()
        val scoreOcr = mockk<OcrService>(relaxed = true)
        val scoreCrop = mockk<Pattern>(relaxed = true)
        val labels = Region(1644, 332, 500, 1091)
        val button = Location(1695, 260)
        var view = SupportSkillDisplay.View.Normal
        var snapshot = false
        var color = false
        var ignoreTaps = false
        var breakRestore = false
        var taps = 0
        val reader = mockk<SupportSkillLevelReader>()
        val npReader = mockk<SupportNpLevelReader>()
        val boundsFinder = mockk<SupportBoundsFinder>()
        val servants = mockk<ServantSelection>()
        val ces = mockk<CESelection>()
        val first = SupportBounds(Region(76, 408, 2356, 428))
        val second = SupportBounds(Region(76, 808, 2356, 428))
        val display: SupportSkillDisplay
        val selector: AppendSkillSelection
        val preferred: PreferredSupportSelection

        init {
            // These fixtures test the JP Grand score path; servers without Grand scores ignore +16 (0005 D-10).
            every { api.prefs.gameServer } returns GameServer.Jp.Original
            every { prefs.appendSkillCheck } returns enabled
            every { prefs.appendSkillsMax } returns required
            every { prefs.preferredServants } returns characters
            every { prefs.preferredCEs } returns ceNames
            every { prefs.score62Check } returns scoreEnabled
            every { prefs.grandScore16Check } returns grandScoreEnabled
            every { scoreCrop.width } returns 55
            every { scoreCrop.height } returns 30
            for (bound in listOf(first, second)) {
                every { with(api) { SupportScoreLayout.numberRegion(bound.region).getPattern("support_class_grand_scores") } } returns scoreCrop
            }
            every { scoreOcr.detectTextLine(scoreCrop, any()) } answers {
                assertTrue(snapshot, "Score OCR must use the candidate snapshot before Append switching")
                OcrText("+0/+16", 92)
            }
            every { prefs.npLevelCheck } returns npEnabled
            every { prefs.minimumNpLevel } returns minimumNp
            every { prefs.friendNames } returns emptyList()
            every { prefs.friendsOnly } returns false
            every { api.locations.support.skillDisplayLabelsRegion } returns labels
            every { api.locations.support.skillDisplaySwitchClick } returns button
            every { api.images[Images.SupportNormalSkills, null] } returns normal
            every { api.images[Images.SupportAppendSkills, null] } returns append
            every { api.images[Images.SupportScore62, null] } returns score
            every { with(api) { any<Region>().exists(score, any(), any()) } } answers {
                assertTrue(snapshot, "Score must use the same candidate frame as the other preferred checks")
                true
            }
            every { api.useColor<Any?>(any()) } answers {
                color = true
                try { firstArg<() -> Any?>().invoke() } finally { color = false }
            }
            every { api.useSameSnapIn<Any?>(any()) } answers {
                assertFalse(snapshot, "Display transitions must not reuse an outer normal-screen snapshot")
                snapshot = true
                try { firstArg<() -> Any?>().invoke() } finally { snapshot = false }
            }
            every { with(api) { labels.exists(normal, any(), any()) } } answers {
                assertTrue(color, "Grayscale cannot distinguish gold from cyan skill labels")
                view == SupportSkillDisplay.View.Normal || view == SupportSkillDisplay.View.Both
            }
            every { with(api) { labels.exists(append, any(), any()) } } answers {
                assertTrue(color)
                view == SupportSkillDisplay.View.Append || view == SupportSkillDisplay.View.Both
            }
            every { with(api) { button.click(any()) } } answers {
                assertFalse(snapshot, "Clicking under a cached screenshot would read stale skills")
                ++taps
                if (!ignoreTaps && !(breakRestore && taps > 1)) {
                    view = when (view) {
                        SupportSkillDisplay.View.Normal -> SupportSkillDisplay.View.Append
                        SupportSkillDisplay.View.Append -> SupportSkillDisplay.View.Both
                        else -> SupportSkillDisplay.View.Normal
                    }
                }
            }
            every { reader.read(any(), true) } answers {
                assertTrue(snapshot)
                assertThat(view).isEqualTo(SupportSkillDisplay.View.Append)
                List(5) { true }
            }
            every { boundsFinder.all() } returns listOf(first, second)
            every { servants.check(any(), any()) } answers {
                assertTrue(snapshot)
                assertThat(view).isEqualTo(SupportSkillDisplay.View.Normal)
                true
            }
            every { ces.check(any(), any()) } returns true
            every { npReader.read(any()) } answers {
                assertTrue(snapshot, "NP must use the same candidate snapshot as the normal conditions")
                assertThat(view).isEqualTo(SupportSkillDisplay.View.Normal)
                5
            }
            display = SupportSkillDisplay(api)
            selector = AppendSkillSelection(api, prefs, display, reader)
            preferred = PreferredSupportSelection(prefs, api, boundsFinder, mockk(relaxed = true), servants, ces, mockk(relaxed = true), selector,
                SupportScoreSelection(api, prefs, SupportScoreReader(api, scoreOcr)), SupportNpSelection(api, prefs, npReader))
        }
    }

    @Test
    fun `normal conditions pass before append display and successful candidate is selected after restoration`() {
        val f = Fixture()
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        assertThat(f.taps).isEqualTo(3)
        assertThat(f.view).isEqualTo(SupportSkillDisplay.View.Normal)
        verify(exactly = 1) { f.reader.read(f.first, true) }
        verify(exactly = 1) { with(f.api) { f.first.region.click(any()) } }
        verify(exactly = 0) { f.servants.check(any(), f.second) }
    }

    @Test
    fun `failed append restores twice then checks the next servant in normal display`() {
        val f = Fixture()
        every { f.reader.read(f.first, true) } returns listOf(true, false, true, true, true)
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        assertThat(f.taps).isEqualTo(6)
        assertThat(f.view).isEqualTo(SupportSkillDisplay.View.Normal)
        verify(exactly = 0) { with(f.api) { f.first.region.click(any()) } }
        verify(exactly = 1) { with(f.api) { f.second.region.click(any()) } }
    }

    @Test
    fun `normal filter failure never toggles that candidate`() {
        val f = Fixture()
        every { f.servants.check(any(), f.first) } returns false
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        assertThat(f.taps).isEqualTo(3)
        verify(exactly = 0) { f.reader.read(f.first, any()) }
        verify(exactly = 1) { f.reader.read(f.second, true) }
    }

    @Test
    fun `disabled append check preserves legacy selection without display changes`() {
        val f = Fixture(enabled = false)
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        assertThat(f.taps).isEqualTo(0)
        verify(exactly = 0) { f.reader.read(any(), any()) }
    }

    @Test
    fun `all wildcards skip append reading and a missing required fifth skill rejects the candidate`() {
        val wildcard = Fixture(required = List(5) { false })
        assertTrue(wildcard.selector.check(wildcard.first))
        assertThat(wildcard.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        assertThat(wildcard.taps).isEqualTo(0)
        val required = Fixture(required = listOf(false, false, false, false, true))
        every { required.reader.read(any(), true) } returns List(4) { true }
        assertFalse(required.selector.check(required.first))
        assertThat(required.taps).isEqualTo(3)
    }

    @Test
    fun `both failures restore before requesting another page`() {
        val f = Fixture()
        every { f.reader.read(any(), true) } returns List(5) { false }
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.EarlyRefresh)
        assertThat(f.taps).isEqualTo(6)
        assertThat(f.view).isEqualTo(SupportSkillDisplay.View.Normal)
        verify(exactly = 0) { with(f.api) { f.first.region.click(any()) } }
        verify(exactly = 0) { with(f.api) { f.second.region.click(any()) } }
    }

    @Test
    fun `append or combined starting display is normalized before regular skill checks`() {
        for ((view, extraTaps) in listOf(SupportSkillDisplay.View.Append to 2, SupportSkillDisplay.View.Both to 1)) {
            val f = Fixture()
            f.view = view
            assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
            assertThat(f.taps).isEqualTo(3 + extraTaps)
        }
    }

    @Test
    fun `failed toggle or restoration stops without selecting any support`() {
        for (restore in listOf(false, true)) {
            val f = Fixture()
            f.ignoreTaps = !restore
            f.breakRestore = restore
            val error = assertFailsWith<AutoBattle.BattleExitException> { f.preferred.select() }
            assertThat(error.reason).isEqualTo(AutoBattle.ExitReason.SupportSkillDisplayFailed)
            verify(exactly = 0) { with(f.api) { f.first.region.click(any()) } }
            verify(exactly = 0) { with(f.api) { f.second.region.click(any()) } }
        }
    }

    @Test
    fun `unknown display cannot trigger a blind toggle`() {
        val f = Fixture()
        f.view = SupportSkillDisplay.View.Unknown
        assertFailsWith<AutoBattle.BattleExitException> { f.preferred.select() }
        assertThat(f.taps).isEqualTo(0)
    }

    @Test
    fun `normal and append use three and five separate regions and all required levels are ANDed`() {
        val row = Region(76, 408, 2356, 428)
        assertThat(SupportSkillLayout.levelRegions(row, false).map { it.x }).isEqualTo(listOf(1686, 1776, 1866))
        assertThat(SupportSkillLayout.levelRegions(row, true).map { it.x }).isEqualTo(listOf(1686, 1761, 1836, 1911, 1986))
        assertTrue(SupportSkillLevelReader.matches(listOf(false, true, true, true, true), listOf(false, true, true, true, true)))
        assertFalse(SupportSkillLevelReader.matches(List(5) { true }, listOf(false, true, true, true, true)))
        assertFalse(SupportSkillLevelReader.matches(List(5) { true }, List(4) { true }))
    }

    @Test
    fun `real level reader reuses the enclosing snapshot for all three or five slots`() {
        val f = Fixture()
        val ten = mockk<Pattern>()
        val captured = mutableListOf<Region>()
        every { f.api.images[Images.SkillTen, null] } returns ten
        every { with(f.api) { any<Region>().exists(ten, any(), any()) } } answers {
            assertTrue(f.snapshot)
            captured.add(firstArg())
            true
        }
        val reader = SupportSkillLevelReader(f.api)
        for (append in listOf(false, true)) {
            captured.clear()
            val values = f.api.useSameSnapIn { reader.read(f.first, append) }
            assertThat(values).isEqualTo(List(if (append) 5 else 3) { true })
            assertThat(captured).isEqualTo(SupportSkillLayout.levelRegions(f.first.region, append))
        }
    }

    @Test
    fun `clearing preferred servants ignores retained append settings like normal skill settings`() {
        val f = Fixture(characters = emptyList())
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        assertThat(f.taps).isEqualTo(0)
        verify(exactly = 0) { f.reader.read(any(), any()) }
    }

    @Test
    fun `score check rejects the first candidate and selects the next plus 62 without toggling skills`() {
        val f = Fixture(enabled = false, scoreEnabled = true)
        every { with(f.api) { SupportScoreLayout.numberRegion(f.first.region).exists(f.score, any(), any()) } } returns false
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        assertThat(f.taps).isEqualTo(0)
        verify(exactly = 0) { with(f.api) { f.first.region.click(any()) } }
        verify(exactly = 1) { with(f.api) { f.second.region.click(any()) } }
    }

    @Test
    fun `grand only selection works without servant or CE images and ignores class score`() {
        val f = Fixture(enabled = false, characters = emptyList(), ceNames = emptyList(), grandScoreEnabled = true)
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        verify(exactly = 1) { with(f.api) { f.first.region.click(any()) } }
        verify(exactly = 1) { f.api.images[Images.SupportScore62, null] }
        assertThat(f.taps).isEqualTo(0)
    }

    @Test
    fun `failed grand score skips Append and a matching second candidate switches only once`() {
        val f = Fixture(grandScoreEnabled = true)
        every { f.scoreOcr.detectTextLine(f.scoreCrop, any()) } returnsMany
            (List(4) { OcrText("+62/+0", 92) } + List(4) { OcrText("+0/+16", 92) })
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        verify(exactly = 0) { f.reader.read(f.first, true) }
        verify(exactly = 1) { f.reader.read(f.second, true) }
        verify(exactly = 1) { with(f.api) { f.second.region.click(any()) } }
        assertThat(f.taps).isEqualTo(3)
    }

    @Test
    fun `both score conditions skip ordinary 62 and select the next 62 16 candidate`() {
        val f = Fixture(enabled = false, scoreEnabled = true, grandScoreEnabled = true)
        every { f.scoreOcr.detectTextLine(f.scoreCrop, any()) } returnsMany
            (List(4) { OcrText("+62", 92) } + List(4) { OcrText("+62/+16", 92) })
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        verify(exactly = 0) { with(f.api) { f.first.region.click(any()) } }
        verify(exactly = 1) { with(f.api) { f.second.region.click(any()) } }
    }

    @Test
    fun `Class only can select ordinary 62 without requiring a Grand field`() {
        val f = Fixture(enabled = false, scoreEnabled = true)
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        verify(exactly = 1) { with(f.api) { f.first.region.click(any()) } }
        verify(exactly = 0) { f.scoreOcr.detectTextLine(any(), any()) }
    }

    @Test
    fun `disabled score check does not load or match the score template`() {
        val f = Fixture(enabled = false)
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        verify(exactly = 0) { f.api.images[Images.SupportScore62, null] }
        verify(exactly = 0) { with(f.api) { any<Region>().exists(f.score, any(), any()) } }
    }

    @Test
    fun `score and append enabled require both and score failure skips append switching`() {
        val f = Fixture(scoreEnabled = true)
        every { with(f.api) { SupportScoreLayout.numberRegion(f.first.region).exists(f.score, any(), any()) } } returns false
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        assertThat(f.taps).isEqualTo(3)
        verify(exactly = 0) { f.reader.read(f.first, true) }
        verify(exactly = 1) { f.reader.read(f.second, true) }
    }

    @Test
    fun `passing score does not bypass failed append and restores before next score check`() {
        val f = Fixture(scoreEnabled = true)
        every { f.reader.read(f.first, true) } returns List(5) { false }
        every { with(f.api) { SupportScoreLayout.numberRegion(f.second.region).exists(f.score, any(), any()) } } answers {
            assertTrue(f.snapshot)
            assertThat(f.view).isEqualTo(SupportSkillDisplay.View.Normal)
            false
        }
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.EarlyRefresh)
        assertThat(f.taps).isEqualTo(3)
        assertThat(f.view).isEqualTo(SupportSkillDisplay.View.Normal)
        verify(exactly = 0) { with(f.api) { f.first.region.click(any()) } }
        verify(exactly = 0) { with(f.api) { f.second.region.click(any()) } }
    }

    @Test
    fun `score only selection works without a preferred servant or CE image`() {
        val f = Fixture(enabled = false, characters = emptyList(), scoreEnabled = true, ceNames = emptyList())
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        assertThat(f.taps).isEqualTo(0)
        verify(exactly = 1) { with(f.api) { f.first.region.click(any()) } }
    }

    @Test
    fun `unreadable or non 62 scores reject all candidates and request another page`() {
        val f = Fixture(enabled = false, scoreEnabled = true)
        every { with(f.api) { any<Region>().exists(f.score, any(), any()) } } returns false
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.EarlyRefresh)
        verify(exactly = 0) { with(f.api) { f.first.region.click(any()) } }
        verify(exactly = 0) { with(f.api) { f.second.region.click(any()) } }
    }

    @Test
    fun `empty selection with score disabled still rejects missing preferences`() {
        val f = Fixture(enabled = false, characters = emptyList(), ceNames = emptyList())
        val error = assertFailsWith<AutoBattle.BattleExitException> { f.preferred.select() }
        assertThat(error.reason).isEqualTo(AutoBattle.ExitReason.SupportSelectionPreferredNotSet)
    }

    @Test
    fun `score number region follows the candidate row offset`() {
        assertThat(SupportScoreLayout.numberRegion(Region(76, 408, 2356, 428)))
            .isEqualTo(Region(2036, 718, 110, 60))
        assertThat(SupportScoreLayout.numberRegion(Region(287, 808, 2356, 428)))
            .isEqualTo(Region(2247, 1118, 110, 60))
    }

    @Test
    fun `NP below minimum skips the entire append cycle and examines the next candidate`() {
        val f = Fixture(npEnabled = true)
        every { f.npReader.read(f.first) } returns 1
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        assertThat(f.taps).isEqualTo(3)
        verify(exactly = 0) { f.reader.read(f.first, true) }
        verify(exactly = 1) { f.reader.read(f.second, true) }
        verify(exactly = 0) { with(f.api) { f.first.region.click(any()) } }
        verify(exactly = 1) { with(f.api) { f.second.region.click(any()) } }
    }

    @Test
    fun `NP higher than minimum passes and append remains the final filter`() {
        val f = Fixture(npEnabled = true, minimumNp = 2, scoreEnabled = true)
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        assertThat(f.taps).isEqualTo(3)
        io.mockk.verifyOrder {
            f.servants.check(any(), f.first)
            with(f.api) { SupportScoreLayout.numberRegion(f.first.region).exists(f.score, any(), any()) }
            f.ces.check(any(), f.first)
            f.npReader.read(f.first)
            f.reader.read(f.first, true)
            with(f.api) { f.first.region.click(any()) }
        }
    }

    @Test
    fun `unreadable NP rejects all candidates without append switching or selection`() {
        val f = Fixture(npEnabled = true)
        every { f.npReader.read(any()) } returns null
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.EarlyRefresh)
        assertThat(f.taps).isEqualTo(0)
        verify(exactly = 0) { f.reader.read(any(), any()) }
        verify(exactly = 0) { with(f.api) { f.first.region.click(any()) } }
        verify(exactly = 0) { with(f.api) { f.second.region.click(any()) } }
    }

    @Test
    fun `disabled NP filter does not invoke OCR and preserves existing settings`() {
        val f = Fixture()
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        verify(exactly = 0) { f.npReader.read(any()) }
    }

    @Test
    fun `NP only filter works without servant CE or friend name images`() {
        val f = Fixture(enabled = false, characters = emptyList(), ceNames = emptyList(), npEnabled = true, minimumNp = 3)
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        assertThat(f.taps).isEqualTo(0)
        verify(exactly = 1) { f.npReader.read(f.first) }
    }

    @Test
    fun `score failure skips both NP OCR and append and all enabled conditions must pass`() {
        val f = Fixture(npEnabled = true, scoreEnabled = true)
        every { with(f.api) { SupportScoreLayout.numberRegion(f.first.region).exists(f.score, any(), any()) } } returns false
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        verify(exactly = 0) { f.npReader.read(f.first) }
        verify(exactly = 0) { f.reader.read(f.first, true) }
        verify(exactly = 1) { f.npReader.read(f.second) }
        assertThat(f.taps).isEqualTo(3)
    }

    @Test
    fun `append failure restores before reading the next candidate NP and never bypasses it`() {
        val f = Fixture(npEnabled = true)
        every { f.reader.read(f.first, true) } returns List(5) { false }
        every { f.npReader.read(f.second) } answers {
            assertTrue(f.snapshot)
            assertThat(f.view).isEqualTo(SupportSkillDisplay.View.Normal)
            1
        }
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.EarlyRefresh)
        assertThat(f.taps).isEqualTo(3)
        verify(exactly = 0) { f.reader.read(f.second, true) }
        verify(exactly = 0) { with(f.api) { f.first.region.click(any()) } }
        verify(exactly = 0) { with(f.api) { f.second.region.click(any()) } }
    }

    @Test
    fun `invalid imported NP requirement fails closed instead of weakening the filter`() {
        for (minimum in listOf(0, 6, -1)) {
            val f = Fixture(npEnabled = true, minimumNp = minimum)
            assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.EarlyRefresh)
            assertThat(f.taps).isEqualTo(0)
        }
    }
}
