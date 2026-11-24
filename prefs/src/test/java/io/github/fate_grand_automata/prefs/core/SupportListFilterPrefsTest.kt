package io.github.fate_grand_automata.prefs.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.fate_grand_automata.prefs.BattleConfigFile
import io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum
import org.junit.jupiter.api.Test

class SupportListFilterPrefsTest {
    private fun core() = InMemorySharedPreferences().let { it to SupportPrefsCore(PrefMaker(it)) }

    @Test
    fun `the ascension set is empty by default`() {
        val (_, support) = core()
        assertThat(support.displayedAscensions.get()).isEqualTo(emptySet())
    }

    @Test
    fun `copying takes every condition`() {
        val (_, source) = core()
        source.preferredServants.set(setOf("Arcueid", "No.351 x"))
        source.preferredNoblePhantasms.set(setOf("np.png"))
        source.displayedAscensions.set(setOf("s4|Arcueid", "c1|No.351 x"))
        source.preferredCEs.set(setOf("Black Grail.png"))
        source.mlb.set(true)
        source.maxAscended.set(true)
        source.skill2Max.set(true)
        source.appendSkillCheck.set(true)
        source.appendSkillsMax[2].set(true)
        source.minimumNpLevel.set(3)
        source.npLevelCheck.set(true)
        source.score62Check.set(true)
        source.friendsOnly.set(true)
        source.friendNames.set(setOf("friend.png"))
        source.grandServant.set(true)
        source.bondCEEffect.set(BondCEEffectEnum.NP)

        val (_, target) = core()
        target.copyConditionsFrom(source)

        assertThat(target.preferredServants.get()).isEqualTo(setOf("Arcueid", "No.351 x"))
        // Copied after the servants, which clear the NP images when they change.
        assertThat(target.preferredNoblePhantasms.get()).isEqualTo(setOf("np.png"))
        assertThat(target.displayedAscensions.get()).isEqualTo(setOf("s4|Arcueid", "c1|No.351 x"))
        assertThat(target.preferredCEs.get()).isEqualTo(setOf("Black Grail.png"))
        assertThat(target.mlb.get()).isTrue()
        assertThat(target.maxAscended.get()).isTrue()
        assertThat(target.skill2Max.get()).isTrue()
        assertThat(target.appendSkillsMax.map { it.get() }).isEqualTo(listOf(false, false, true, false, false))
        assertThat(target.npLevelCheck.get()).isTrue()
        assertThat(target.minimumNpLevel.get()).isEqualTo(3)
        assertThat(target.score62Check.get()).isTrue()
        assertThat(target.friendsOnly.get()).isTrue()
        assertThat(target.friendNames.get()).isEqualTo(setOf("friend.png"))
        assertThat(target.grandServant.get()).isTrue()
        assertThat(target.bondCEEffect.get()).isEqualTo(BondCEEffectEnum.NP)
    }

    @Test
    fun `a copy is a snapshot`() {
        val (_, source) = core()
        source.preferredServants.set(setOf("Arcueid"))
        source.friendsOnly.set(true)
        val (_, target) = core()
        target.copyConditionsFrom(source)
        source.preferredServants.set(setOf("Other"))
        source.friendsOnly.set(false)
        assertThat(target.preferredServants.get()).isEqualTo(setOf("Arcueid"))
        assertThat(target.friendsOnly.get()).isTrue()
    }

    @Test
    fun `the ascension set survives a battle config export and the retired switch is no longer part of the schema`() {
        val (store, support) = core()
        support.displayedAscensions.set(setOf("s1|Arcueid"))
        val decoded = BattleConfigFile.decode(BattleConfigFile.encode(store.all))
        assertThat((decoded["support_pref_disp_ascension"] as Collection<*>).toSet()).isEqualTo(setOf("s1|Arcueid"))
        assertThat(BattleConfigCore.prefTypes["support_pref_disp_ascension"]).isEqualTo(PrefType.StringSet)
        assertThat(BattleConfigCore.prefTypes[PrefsCore.LEGACY_SUPPORT_LIST_FILTER_KEY]).isNull()
    }

    @Test
    fun `an old export that still carries the per config switch decodes`() {
        val decoded = BattleConfigFile.decode("""{"autoskill_name":"old","support_list_filter":true,"support_friends_only":true}""")
        assertThat(decoded["autoskill_name"]).isEqualTo("old")
        assertThat(decoded["support_friends_only"]).isEqualTo(true)
    }
}
