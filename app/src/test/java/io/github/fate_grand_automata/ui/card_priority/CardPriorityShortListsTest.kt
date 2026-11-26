package io.github.fate_grand_automata.ui.card_priority

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.PerEntrySettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The screen shows, and on save writes, what the battle reads from a stored per-wave list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class CardPriorityShortListsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private val none = BraveChainEnum.None
    private val np = BraveChainEnum.WithNP
    private val avoid = BraveChainEnum.Avoid

    // The same table as BattleExecutionTest, which pins the battle's reading.
    private val brave = listOf(
        emptyList<BraveChainEnum>() to listOf(none, none, none),
        listOf(np) to listOf(np, np, np),
        listOf(np, avoid) to listOf(np, avoid, avoid),
        listOf(np, avoid, none) to listOf(np, avoid, none),
        listOf(np, avoid, none, np) to listOf(np, avoid, none)
    )
    private val rearrange = listOf(
        emptyList<Boolean>() to listOf(false, false, false),
        listOf(true) to listOf(true, true, true),
        listOf(true, false) to listOf(true, false, false),
        listOf(false, true) to listOf(false, true, true),
        listOf(true, false, true) to listOf(true, false, true),
        listOf(true, false, true, false) to listOf(true, false, true)
    )

    private fun viewModel(name: String, setup: (PrefsCore) -> Unit): CardPriorityViewModel {
        val shared = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        val core = PrefsCore(PrefMaker(shared), context)
        val profile = core.forBattleConfig(name).executionProfile(0)
        profile.cardPriority.set(CardPriorityPerWave.of(List(3) { "WB, WA, WQ, B, A, Q, RB, RA, RQ" }.joinToString("\n")))
        setup(core)
        return CardPriorityViewModel(core, name, profileIndex = 0)
    }

    @Test
    fun `brave chains of a short list show as the battle reads them and save without changing it`() {
        brave.forEachIndexed { i, (stored, expected) ->
            val name = "short_brave_$i"
            val vm = viewModel(name) { it.forBattleConfig(name).executionProfile(0).braveChains.set(stored) }
            assertEquals("stored $stored", expected, vm.cardPriorityItems.map { it.braveChains.value })
            vm.save()
            val saved = vm.battleConfig.braveChains.get()
            assertEquals("saved $stored", expected, List(3) { PerEntrySettings.at(saved, it, none) })
        }
    }

    @Test
    fun `rearrange cards of a short list show as the battle reads them and save without changing it`() {
        rearrange.forEachIndexed { i, (stored, expected) ->
            val name = "short_rearrange_$i"
            val vm = viewModel(name) { it.forBattleConfig(name).executionProfile(0).rearrangeCards.set(stored) }
            assertEquals("stored $stored", expected, vm.cardPriorityItems.map { it.rearrangeCards.value })
            vm.save()
            val saved = vm.battleConfig.rearrangeCards.get()
            assertEquals("saved $stored", expected, List(3) { PerEntrySettings.at(saved, it, false) })
        }
    }
}
