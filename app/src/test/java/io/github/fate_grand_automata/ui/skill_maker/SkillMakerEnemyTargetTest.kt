package io.github.fate_grand_automata.ui.skill_maker

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.models.EnemyTarget
import kotlin.test.Test

class SkillMakerEnemyTargetTest {
    @Test
    fun `every enemy target survives opening and saving the command history`() {
        val source = "t1a,t4b,t9c,tRa,t10b,t11c,t12a4,t3b"
        val model = SkillMakerModel(source)
        assertThat(model.toString()).isEqualTo(source)
        assertThat(SkillMakerModel(model.toString()).toString()).isEqualTo(source)
    }

    @Test
    fun `the dialog opens on the layout of the current target`() {
        assertThat(enemyLayoutOf(null)).isEqualTo(EnemyLayout.Normal)
        EnemyTarget.list.forEach { assertThat(enemyLayoutOf(it.autoSkillCode)).isEqualTo(EnemyLayout.Normal) }
        EnemyTarget.sixList.forEach { assertThat(enemyLayoutOf(it.autoSkillCode)).isEqualTo(EnemyLayout.Six) }
        EnemyTarget.raidList.forEach { assertThat(enemyLayoutOf(it.autoSkillCode)).isEqualTo(EnemyLayout.Raid) }
    }
}
