package io.github.fate_grand_automata.ui.log

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
import io.github.fate_grand_automata.scripts.capture.GainedItem
import io.github.fate_grand_automata.scripts.capture.RunGains
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class CardCountLogTest {
    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)

    @Test fun emberNamesReuseClassLabelsIncludingAllBeastVariantsAndAllClass() {
        val classes = listOf("saber", "archer", "lancer", "rider", "caster", "assassin", "berserker", "shielder",
            "ruler", "avenger", "moonCancer", "alterEgo", "foreigner", "pretender", "beastI", "beastIIIR", "beastEresh", "all", "unknown")
        val labels = listOf("세이버", "아처", "랜서", "라이더", "캐스터", "어쌔신", "버서커", "실더",
            "룰러", "어벤저", "문 캔서", "얼터 에고", "포리너", "프리텐더", "비스트", "비스트", "비스트", "전체", "unknown")
        val names = GameNameMap(svtData = classes.mapIndexed { index, name ->
            index.toLong() to AtlasBasic(index.toLong(), "예지의 맹화", "combineMaterial", name)
        }.toMap())
        var actual = emptyList<String>()
        rule.setContent { actual = classes.indices.map { resourceName(LogResource.SVT, it.toLong(), names) } }
        rule.runOnIdle { assertEquals(labels.map { "예지의 맹화 ($it)" }, actual) }
    }

    @Test fun ceFouServantAndCommandCodeUseAtlasNamesOrHashIdFallback() {
        val names = GameNameMap(svtData = mapOf(
            10L to AtlasBasic(10, "합성 서번트", "normal", "saber"),
            11L to AtlasBasic(11, "합성 예장", "servantEquip"),
            12L to AtlasBasic(12, "합성 포우", "statusUp"),
            13L to AtlasBasic(13, " ", "combineMaterial", "all"),
        ), commandCodeData = mapOf(10L to AtlasBasic(10, "합성 코드")))
        var actual = emptyList<String>()
        rule.setContent {
            actual = (10L..14L).map { resourceName(LogResource.SVT, it, names) } +
                listOf(10L, 20L).map { resourceName(LogResource.COMMAND_CODE, it, names) }
        }
        rule.runOnIdle { assertEquals(listOf("합성 서번트", "합성 예장", "합성 포우", "#13", "#14", "합성 코드", "#20"), actual) }
    }

    @Test fun cardFilterKeysCannotCollideWithItemOrBattleDropKeysAndUseGainSemantics() {
        val period = OutsidePeriod("synthetic", 1)
            .add(ChangeSource.of("present/receive"), AccountDelta(svts = mapOf(10L to 1L), commandCodes = mapOf(10L to 1L)), 2)
            .add(ChangeSource.of("shop/sell"), AccountDelta(items = mapOf(10L to 2L), svts = mapOf(10L to -3L), commandCodes = mapOf(10L to -2L)), 3)
        val entry = CaptureLogItem(outside = outsideFixture().copy(period = period))
        assertEquals(setOf("2:10", "-1:10", "-2:10"), logResources(listOf(entry)))
        listOf("2:10", "-1:10", "-2:10").forEach { key ->
            assertEquals(listOf(entry), filterCaptureLogs(listOf(entry), LogTimeFilter.All, key, 0))
        }
        listOf("1:10", "3:10").forEach { key ->
            assertTrue(filterCaptureLogs(listOf(entry), LogTimeFilter.All, key, 0).isEmpty())
        }
        val loss = CaptureLogItem(outside = outsideFixture().copy(period = OutsidePeriod("synthetic", 1)
            .add(ChangeSource.of("shop/sell"), AccountDelta(svts = mapOf(10L to -1L)), 2)))
        assertTrue(filterCaptureLogs(listOf(loss), LogTimeFilter.All, "-1:10", 0).isEmpty())
    }

    @Test fun enhancementAndShopDetailsContainTheirCardTilesBesideItems() {
        val period = OutsidePeriod("synthetic", 1)
            .add(ChangeSource.of("svtCombine/execute"), AccountDelta(items = mapOf(6510L to -2L), svts = mapOf(10L to -12L)), 2)
            .add(ChangeSource.of("shop/sell"), AccountDelta(svts = mapOf(20L to -3L), commandCodes = mapOf(30L to -1L)), 3)
        rule.setContent { OutsideLogDetail(outsideFixture().copy(period = period), {}) }
        rule.onNodeWithText("강화").performScrollTo().performClick()
        rule.onNode(hasTestTag("resource:-1:10") and hasAnyAncestor(hasTestTag("outside-source:Enhancement:"))).assertExists()
        rule.onNode(hasTestTag("resource:2:6510") and hasAnyAncestor(hasTestTag("outside-source:Enhancement:"))).assertExists()
        rule.onNodeWithText("상점").performScrollTo().performClick()
        rule.onNode(hasTestTag("resource:-1:20") and hasAnyAncestor(hasTestTag("outside-source:Shop:"))).assertExists()
        rule.onNode(hasTestTag("resource:-2:30") and hasAnyAncestor(hasTestTag("outside-source:Shop:"))).assertExists()
    }

    @Test fun resourceDialogListsCardsWithSeparateLocalIcons() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val names = cardCountNames(context)
        val resources = setOf("-1:9700100", "-1:9400010", "-2:10")
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalLogIconLoader provides localIconLoader(context)) {
                LogFilterDialog(LogTimeFilter.All, null, null, null, 200, 0, 1, resources, names, {}, { _, _, _, _, _ -> })
            }
        }
        listOf("-1:9700100" to "예지의 맹화 (전체)", "-1:9400010" to "합성 예장", "-2:10" to "합성 코드").forEach { (key, name) ->
            rule.onNodeWithTag("log-filter-resources").performScrollToNode(hasText(name))
            rule.onNodeWithText(name).assertIsDisplayed()
            rule.waitUntil(10_000) {
                rule.onAllNodes(hasTestTag("log-icon-loaded") and hasAnyAncestor(hasTestTag("log-filter-resource:$key")), useUnmergedTree = true)
                    .fetchSemanticsNodes().size == 1
            }
        }
    }

    @Test fun positiveBattleDropTypesKeepTheirExistingMappingAndNames() {
        val names = GameNameMap(items = mapOf(10L to "아이템"), servants = mapOf(10L to "서번트"), equips = mapOf(10L to "예장"),
            svtData = mapOf(10L to AtlasBasic(10, "카드")), commandCodeData = mapOf(10L to AtlasBasic(10, "코드")))
        val resources = battleResources(RunGains(items = (1..5).map { GainedItem(it, 10, 1) }))
        assertEquals((1..5).toList(), resources.map { it.type })
        var labels = emptyList<String>()
        rule.setContent { labels = resources.map { resourceName(it.type, it.id, names) } }
        rule.runOnIdle { assertEquals(listOf("서번트", "아이템", "예장", "Item #10", "Item #10"), labels) }
    }
}
