package io.github.fate_grand_automata.ui.skill_maker

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.models.EnemyTarget
import kotlin.test.Test

class SkillMakerEnemyTargetTest {
    @Test
    fun `every enemy target survives opening and saving the command history`() {
        val source = "t1a,t4b,t9c,tRa,tAb,tBc,tCa4,tDb,tEc,tFa,t3b"
        val model = SkillMakerModel(source)
        assertThat(model.toString()).isEqualTo(source)
        assertThat(SkillMakerModel(model.toString()).toString()).isEqualTo(source)
    }

    @Test
    fun `beta two digit codes are saved back as letters`() {
        val model = SkillMakerModel("t10a,t11b,t12c")
        assertThat(model.toString()).isEqualTo("tAa,tBb,tCc")
    }

    @Test
    fun `the dialog opens on the layout of the current target`() {
        assertThat(enemyLayoutOf(null)).isEqualTo(EnemyLayout.Normal)
        EnemyTarget.list.forEach { assertThat(enemyLayoutOf(it.autoSkillCode)).isEqualTo(EnemyLayout.Normal) }
        EnemyTarget.sixList.forEach { assertThat(enemyLayoutOf(it.autoSkillCode)).isEqualTo(EnemyLayout.Six) }
        EnemyTarget.raidList.forEach { assertThat(enemyLayoutOf(it.autoSkillCode)).isEqualTo(EnemyLayout.Raid) }
    }
}
