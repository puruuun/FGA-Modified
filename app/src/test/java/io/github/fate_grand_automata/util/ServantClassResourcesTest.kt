package io.github.fate_grand_automata.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.account.dashboardClassRes
import io.github.fate_grand_automata.ui.more.portraitClassRes
import kotlin.test.Test

class ServantClassResourcesTest {
    @Test
    fun `unBeast prefixes map to unbeast before the beast rule`() {
        listOf("unBeastOlgaMarie", "UNBEASTx", "unbeast").forEach { name ->
            assertThat(dashboardClassRes(name), name).isEqualTo(R.string.portrait_class_unbeast)
            assertThat(portraitClassRes(name), name).isEqualTo(R.string.portrait_class_unbeast)
        }
    }

    @Test
    fun `other beast names still map to beast`() {
        listOf("beast", "beastIII", "beastEresh", "BEAST").forEach { name ->
            assertThat(dashboardClassRes(name), name).isEqualTo(R.string.portrait_class_beast)
            assertThat(portraitClassRes(name), name).isEqualTo(R.string.portrait_class_beast)
        }
        assertThat(dashboardClassRes("fooBeast")).isNull()
        assertThat(portraitClassRes("fooBeast")).isEqualTo(R.string.portrait_class_beast)
    }

    @Test
    fun `known classes map to their localized resources and unknown classes return null`() {
        val expected = mapOf(
            "saber" to R.string.p_support_class_saber,
            "archer" to R.string.p_support_class_archer,
            "lancer" to R.string.p_support_class_lancer,
            "rider" to R.string.p_support_class_rider,
            "caster" to R.string.p_support_class_caster,
            "assassin" to R.string.p_support_class_assassin,
            "berserker" to R.string.p_support_class_berserker,
            "shielder" to R.string.portrait_class_shielder,
            "ruler" to R.string.portrait_class_ruler,
            "avenger" to R.string.portrait_class_avenger,
            "moonCancer" to R.string.portrait_class_mooncancer,
            "alterEgo" to R.string.portrait_class_alterego,
            "foreigner" to R.string.portrait_class_foreigner,
            "pretender" to R.string.portrait_class_pretender,
        )
        expected.forEach { (name, res) ->
            assertThat(dashboardClassRes(name), name).isEqualTo(res)
            assertThat(portraitClassRes(name), name).isEqualTo(res)
        }

        assertThat(dashboardClassRes("foo")).isNull()
        assertThat(portraitClassRes("foo")).isNull()
    }
}
