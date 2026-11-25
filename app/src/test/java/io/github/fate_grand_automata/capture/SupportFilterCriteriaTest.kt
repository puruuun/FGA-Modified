package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.SupportListCriteria
import io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportFilterCriteriaTest {
    @get:Rule val temp = TemporaryFolder()

    private val builtin = """[
        {"kind":"servant","folder":"Arcueid","id":2300500,"number":351,"name":"a","region":null},
        {"kind":"ce","folder":"Black Grail.png","id":9400480,"number":48,"name":"b","region":null}
    ]"""

    /** A fictional image-download list in Atlas's export shape. */
    private fun catalog(vararg rows: Triple<Long, Int, String>) = rows.joinToString(",", "[", "]") { (id, no, cls) ->
        """{"id":$id,"collectionNo":$no,"name":"Servant $no","type":"normal","className":"$cls","rarity":5}"""
    }

    private fun lookup(
        jpCatalog: String? = null,
        krCaptured: String? = null,
        jpEquips: String? = null,
    ): SupportIdLookup {
        val names = temp.newFolder()
        val catalogs = temp.newFolder()
        jpCatalog?.let { File(catalogs, "JP-servant.json").writeText(it) }
        jpEquips?.let { File(catalogs, "JP-equip.json").writeText(it) }
        krCaptured?.let { File(names, "basic_servant_details.json").writeText(it) }
        return SupportIdLookup.load(builtin, GameNames(names), catalogs)
    }

    private val full by lazy { lookup(
        jpCatalog = catalog(
            Triple(100100, 2, "saber"), Triple(900100, 900, "beastII"), Triple(110100, 110, "avenger"),
            Triple(230100, 230, "moonCancer"), Triple(100200, 102, "alterEgo"), Triple(250100, 250, "foreigner"),
            Triple(280100, 280, "pretender"), Triple(800100, 1, "shielder"), Triple(910100, 91, "ruler"),
            Triple(330100, 330, "beast"), Triple(380100, 380, "beastEresh"), Triple(400100, 400, "unBeastOlgaMarie"),
        ),
        krCaptured = """[{"id":200200,"type":"normal","className":"archer","collectionNo":7,"name":"k"}]""",
        jpEquips = """[{"id":9300100,"collectionNo":300,"name":"CE","type":"servantEquip","className":"","rarity":5}]""",
    ) }

    @Test fun builtInFolderNumberedFolderAndIdEntryMapToIds() {
        assertEquals(2300500, full.servantId("Arcueid"))
        assertEquals(100100, full.servantId("No.2 Somebody"))
        // A KR folder carries the KR number, which only the KR list knows.
        assertEquals(200200, full.servantId("KR.No.7 x"))
        assertNull(full.servantId("No.7 x"))
        assertEquals(555, full.servantId("#555"))
        assertNull(full.servantId("My own image"))
        assertNull(full.servantId("NA.No.2 x"))
        assertEquals(9400480, full.ceId("Black Grail.png"))
        assertEquals(9300100, full.ceId("No.300 CE"))
        assertNull(full.ceId("Unknown.png"))
    }

    @Test fun classesComeFromTheServantListsForTheObservedClassesOnly() {
        // beastII was not seen in any class board, so it stays out.
        assertEquals(
            mapOf(
                100100L to 1, 200200L to 2, 110100L to 11, 230100L to 23, 100200L to 10, 250100L to 25, 280100L to 28,
                800100L to 8, 910100L to 9, 330100L to 33, 380100L to 38, 400100L to 40,
            ),
            full.servantClassIds,
        )
        assertTrue(lookup().servantClassIds.isEmpty())
    }

    @Test fun migrationEntriesMapBackToTheSameId() {
        for (id in listOf(2300500L, 100100L, 200200L, 424242L)) assertEquals(id, full.servantId(full.servantEntry(id)))
        assertEquals("Arcueid", full.servantEntry(2300500))
        assertEquals("#424242", full.servantEntry(424242))
        assertEquals(9300100, full.ceId(full.ceEntry(9300100)))
    }

    private val on = SupportFilterSettings()

    @Test fun anUnmappableEntryStopsTheFilterAndNamesIt() {
        val build = buildSupportFilter(on.copy(servants = setOf("Arcueid", "My own image"), ces = setOf("Unknown.png")), full)
        assertEquals(SupportFilterBuild.Unmapped(listOf("My own image"), listOf("Unknown.png")), build)
        assertNull(build.applied)
    }

    @Test fun classScoreWithoutAServantListStopsTheFilter() {
        assertEquals(SupportFilterBuild.NoClassList, buildSupportFilter(on.copy(score62 = true), lookup()))
        val ready = buildSupportFilter(on.copy(score62 = true), full).applied!!
        assertTrue(ready.classScore62)
        assertEquals(full.servantClassIds, ready.servantClassIds)
    }

    @Test fun classScoreWithAChosenServantOfUnknownClassStopsTheFilterAndNamesIt() {
        val build = buildSupportFilter(on.copy(servants = setOf("No.2 a", "No.900 beast"), score62 = true), full)
        assertEquals(SupportFilterBuild.UnknownClass(listOf("No.900 beast")), build)
        assertNull(build.applied)
        // Without the class score condition the same servants filter as usual.
        assertEquals(setOf(100100L, 900100L), buildSupportFilter(on.copy(servants = setOf("No.2 a", "No.900 beast")), full).applied!!.servantIds)
        // With no servant chosen, a servant of unknown class simply does not match the condition.
        assertTrue(buildSupportFilter(on.copy(score62 = true), full).applied!!.classScore62)
    }

    @Test fun everyConditionIsCarriedOver() {
        val build = buildSupportFilter(
            on.copy(
                servants = setOf("Arcueid", "No.2 a"), ces = setOf("Black Grail.png"), mlb = true, maxAscended = true,
                displayedAscensions = setOf("s1|Arcueid", "c2|Arcueid", "s4|No.2 a", "s2|Not chosen", "zz|Arcueid"),
                skills = listOf(true, false, true), appendCheck = true, appends = listOf(false, true, false, false, true),
                npCheck = true, minimumNp = 3, friendsOnly = true,
            ),
            full,
        )
        assertEquals(
            SupportListCriteria(
                servantIds = setOf(2300500, 100100), ceIds = setOf(9400480), ceMlb = true, maxAscended = true,
                displayedAscensions = mapOf(2300500L to setOf(0, 12), 100100L to setOf(3)),
                skillsMax = listOf(true, false, true), appendsMax = listOf(false, true, false, false, true),
                minimumNpLevel = 3, friendsOnly = true,
            ),
            build.applied,
        )
    }

    @Test fun conditionsTheScriptTiesToAPreferredServantNeedOne() {
        val build = buildSupportFilter(
            on.copy(maxAscended = true, skills = listOf(true, true, true), appendCheck = true, appends = List(5) { true }, mlb = true),
            full,
        )
        // Nothing the script would apply is left, so the proxy relays the list untouched.
        assertNull(build.applied)
        assertEquals(SupportListCriteria(), (build as SupportFilterBuild.Ready).criteria)
    }

    @Test fun appendsNeedTheirSwitchAndNpNeedsALevelAboveOne() {
        val c = buildSupportFilter(
            on.copy(servants = setOf("Arcueid"), appends = List(5) { true }, npCheck = true, minimumNp = 1),
            full,
        ).applied!!
        assertEquals(List(5) { false }, c.appendsMax)
        assertEquals(0, c.minimumNpLevel)
    }

    private val grandAll = on.copy(
        servants = setOf("No.2 a"), ces = setOf("Black Grail.png"), grandServant = true, grandScore16 = true,
        bondCeEffect = BondCEEffectEnum.NP, bothCeSlots = true,
    )

    @Test fun theGrandConditionsAreCarriedOverWithAPreferredServantAndCe() {
        val c = buildSupportFilter(grandAll, full).applied!!
        assertTrue(c.grandServant)
        assertTrue(c.grandScore16)
        assertEquals(BondCEEffectEnum.NP, c.bondCeEffect)
        assertTrue(c.bothCeSlots)
        assertEquals(full.servantClassIds, c.servantClassIds)
        assertFalse(c.classScore62)
    }

    @Test fun theGrandConditionsApplyOnTheScriptsTerms() {
        // Without a preferred servant: none of them.
        val noServant = buildSupportFilter(grandAll.copy(servants = emptySet()), full).applied!!
        assertEquals(SupportListCriteria(ceIds = setOf(9400480)), noServant)
        // Without a preferred CE: the bond CE effect and both CE slots are left out.
        val noCe = buildSupportFilter(grandAll.copy(ces = emptySet()), full).applied!!
        assertTrue(noCe.grandServant)
        assertTrue(noCe.grandScore16)
        assertEquals(BondCEEffectEnum.Ignore, noCe.bondCeEffect)
        assertFalse(noCe.bothCeSlots)
        // Without grand servant: none of them, whatever else is stored.
        val off = buildSupportFilter(grandAll.copy(grandServant = false), full).applied!!
        assertEquals(SupportListCriteria(servantIds = setOf(100100), ceIds = setOf(9400480)), off)
    }

    @Test fun grandScoreStopsTheFilterOnAnUnknownClassLikeTheClassScore() {
        assertEquals(SupportFilterBuild.NoClassList, buildSupportFilter(grandAll.copy(servants = setOf("Arcueid")), lookup()))
        val build = buildSupportFilter(grandAll.copy(servants = setOf("No.2 a", "No.900 beast")), full)
        assertEquals(SupportFilterBuild.UnknownClass(listOf("No.900 beast")), build)
        // Without +16 the class is not needed.
        assertTrue(buildSupportFilter(grandAll.copy(servants = setOf("No.900 beast"), grandScore16 = false), full).applied!!.grandServant)
    }
}
