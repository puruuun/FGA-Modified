package io.github.fate_grand_automata.capture

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.scripts.capture.*
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import io.github.fate_grand_automata.ui.more.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal fun grandTestRoster(): FriendRoster {
    val base = syntheticRoster()
    return base.copy(friends = base.friends.mapValues { (id, friend) ->
        friend.copy(slots = friend.slots.map { it.copy(appends = listOf(5, 5, 5, null, null), grand = true) },
            grandSlots = listOf(FriendGrandSlot(
                seat = 10000 + id.toInt(), svtId = id.toLong(), lv = 120, np = 5,
                skills = listOf(10, 10, 10), appends = listOf(5, 5, 5, null, null),
                ce1 = FriendCe(90, 4, 100), ce3 = FriendCe(91, 2, 60),
                bondCeId = if (id == "3") null else 9300000,
                bondEffect = when (id) { "1" -> FriendBondEffect.NpCharge; "2" -> FriendBondEffect.Base; else -> FriendBondEffect.None },
            )))
    })
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class FriendGrandUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    private fun awaitRows(count: Int) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().size == count
    }

    private fun grand(width: Int, height: Int) = compose.shoot("friend-grand", "GrandFormation", width, height, locale = "ko", beforeCapture = {
        awaitRows(5)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-deck-grand").performClick()
        compose.onNodeWithTag("friends-apply").performClick()
        awaitRows(3)
        compose.onNodeWithText("인연: NP 차지", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("인연: 기본 효과", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("인연: 없음", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("friends-ce-line", useUnmergedTree = true).assertCountEquals(6)
    }) { FriendManagerContent(grandTestRoster(), friendTestNames(), FRIEND_NOW) }

    @Test fun grand360() = grand(360, 800)
    @Test fun grand1280() = grand(1280, 800)

    private fun panel(width: Int, height: Int) = compose.shoot("friend-grand", "FilterPanel", width, height, locale = "ko", beforeCapture = {
        awaitRows(5)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-login-inactive").performScrollTo().performClick()
        val bounds = compose.onNodeWithTag("friends-filter-panel").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, bounds.top, 1f)
        // Full width on phones; at most 640 dp and centred on wide screens, like the old bottom sheet (owner, 2026-11-10).
        val density = compose.density.density
        val expected = minOf(width, 640) * density
        assertEquals(expected, bounds.width, 2f)
        assertEquals((width * density - expected) / 2, bounds.left, 2f)
        compose.onNodeWithTag("friends-login-amount").performScrollTo().assertTextEquals("3")
        // One column at every width (owner, 2026-11-10): the CE section sits below the name field, not beside it.
        val name = compose.onNodeWithTag("friends-name").fetchSemanticsNode().boundsInRoot
        val ce = compose.onNodeWithTag("friends-ce").fetchSemanticsNode().boundsInRoot
        assertTrue(ce.top > name.bottom)
        assertEquals(name.left, ce.left, 2f)
    }) { FriendManagerContent(grandTestRoster(), friendTestNames(), FRIEND_NOW) }

    @Test fun panel360() = panel(360, 1000)
    @Test fun panel1280() = panel(1280, 800)

    private fun appends(width: Int, height: Int) = compose.shoot("friend-grand", "NormalAppends", width, height, locale = "ko", beforeCapture = {
        awaitRows(5)
        compose.onAllNodesWithTag("friends-appends", useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val layout = results.single()
            assertEquals("10/10/10/10/10", layout.layoutInput.text.text)
            assertFalse(layout.layoutInput.text.text.contains("\n"))
            if (width >= 720) assertEquals(1, layout.lineCount) else assertTrue(layout.lineCount > 1)
            assertFalse(layout.hasVisualOverflow)
        }
    }) { FriendManagerContent(grandTestRoster().let { roster ->
        roster.copy(friends = roster.friends.mapValues { (_, friend) ->
            friend.copy(slots = friend.slots.map { it.copy(appends = List(5) { 10 }) })
        })
    }, friendTestNames(), FRIEND_NOW) }

    @Test fun appends360() = appends(360, 800)
    @Test fun appends1280() = appends(1280, 800)

    @Test fun friendNameMultiCeAndRemovableChipsWork() {
        compose.setContent { io.github.fate_grand_automata.ui.FGATheme {
            FriendManagerContent(syntheticRoster(), friendTestNames(), FRIEND_NOW)
        } }
        awaitRows(5)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-name").performTextInput("친구 A")
        compose.onNodeWithTag("friends-ce").performScrollTo().performClick()
        compose.onNodeWithText("샘플 예장 하나 (2)").performClick()
        compose.onNodeWithText("샘플 예장 둘 (2)").performClick()
        compose.onNodeWithTag("friends-picker-done").performClick()
        compose.onNodeWithTag("friends-apply").performClick()
        awaitRows(2)
        compose.onNode(hasText("친구 A") and isSelected()).performClick()
        awaitRows(4)
        compose.onNode(hasText("샘플 예장 하나") and isSelected()).performClick()
        awaitRows(2)
        compose.onNode(hasText("샘플 예장 둘") and isSelected()).performClick()
        awaitRows(5)
    }

    @Test fun servantSearchFindsClassTextEvenWhenTheNameDoesNotContainIt() {
        val names = friendTestNames().copy(servants = mapOf(1L to "Alpha", 2L to "Beta", 3L to "Gamma"))
        compose.setContent { io.github.fate_grand_automata.ui.FGATheme {
            FriendManagerContent(syntheticRoster(), names, FRIEND_NOW)
        } }
        awaitRows(5)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-servant").performClick()
        compose.onNodeWithTag("friends-search").performTextInput("세이버")
        compose.onNodeWithText("(세이버) Beta (2)").assertIsDisplayed()
        compose.onNodeWithText("(캐스터) Alpha (2)").assertDoesNotExist()
    }

    @Test fun allBaseAndExtraClassesHaveKoreanLabels() {
        val classes = listOf("saber", "archer", "lancer", "rider", "caster", "assassin", "berserker",
            "shielder", "ruler", "avenger", "moonCancer", "alterEgo", "foreigner", "pretender", "beast")
        val names = GameNameMap(servants = classes.indices.associate { it.toLong() to "S" },
            servantData = classes.mapIndexed { index, name -> index.toLong() to AtlasBasic(index.toLong(), className = name) }.toMap())
        compose.setContent { io.github.fate_grand_automata.ui.FGATheme {
            Column { classes.indices.forEach { Text(servantPickerName(names, it.toLong())) } }
        } }
        listOf("세이버", "아처", "랜서", "라이더", "캐스터", "어새신", "버서커", "실더", "룰러", "어벤저",
            "문 캔서", "얼터 에고", "포리너", "프리텐더", "비스트").forEach {
            compose.onNodeWithText("($it) S").assertExists()
        }
    }

    @Test fun classSearchUsesAtlasClassAndUnknownClassesFallBackToRaw() {
        compose.setContent { io.github.fate_grand_automata.ui.FGATheme {
            Text(servantPickerName(GameNameMap(servants = mapOf(1L to "샘플"), servantData = mapOf(1L to AtlasBasic(1, className = "newClass"))), 1))
        } }
        compose.onNodeWithText("(newClass) 샘플").assertExists()
    }

    @Test fun classNamesUseJapaneseResources() {
        RuntimeEnvironment.setQualifiers("ja-w360dp-h800dp-hdpi")
        compose.setContent { io.github.fate_grand_automata.ui.FGATheme {
            Text(servantPickerName(friendTestNames(), 2))
        } }
        compose.onNodeWithText("(セイバー) 샘플 세이버").assertExists()
    }

    @Test fun restoredAmountClampsAndResetReturnsToDefaults() {
        var current = FriendFilter(formation = FriendFormation.Grand, login = FriendLogin.Inactive, loginAmount = 999, friendName = "old", ceIds = setOf(90))
        compose.setContent {
            var filter by remember { mutableStateOf(current) }
            FriendFilterPanel(filter, emptyMap(), emptyMap(), GameNameMap(), 0,
                onChange = { filter = it; current = it }, onReset = { filter = FriendFilter(); current = filter }, onDismiss = {})
        }
        compose.onNodeWithTag("friends-login-amount").performScrollTo().assertTextEquals("99")
        compose.onNodeWithTag("friends-login-plus").assertIsNotEnabled()
        compose.onNodeWithText("초기화").performClick()
        assertEquals(FriendFilter(), current)
    }
}
