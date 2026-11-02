package io.github.fate_grand_automata.scripts.models

/**
 * An enemy slot a skill command can tap, written as `t` followed by [autoSkillCode].
 * The code is a string because the raid layout has two-digit codes (`t10`..`t12`).
 */
sealed class EnemyTarget(val autoSkillCode: String) {
    /** The three slots of the normal layout. Auto target selection only knows these. */
    sealed class Normal(autoSkillCode: String) : EnemyTarget(autoSkillCode)

    object A : Normal("1")
    object B : Normal("2")
    object C : Normal("3")

    // Six-enemy layout: two rows of three.
    object SixTopLeft : EnemyTarget("4")
    object SixTopMiddle : EnemyTarget("5")
    object SixTopRight : EnemyTarget("6")
    object SixBottomLeft : EnemyTarget("7")
    object SixBottomMiddle : EnemyTarget("8")
    object SixBottomRight : EnemyTarget("9")

    // Raid layout: one boss on top, up to three enemies below it.
    object RaidBoss : EnemyTarget("R")
    object RaidLeft : EnemyTarget("10")
    object RaidMiddle : EnemyTarget("11")
    object RaidRight : EnemyTarget("12")

    companion object {
        /** Exactly the normal three, because AutoChooseTarget iterates this list. */
        val list: List<Normal> by lazy { listOf(A, B, C) }

        val sixList: List<EnemyTarget> by lazy {
            listOf(SixTopLeft, SixTopMiddle, SixTopRight, SixBottomLeft, SixBottomMiddle, SixBottomRight)
        }

        val raidList: List<EnemyTarget> by lazy { listOf(RaidBoss, RaidLeft, RaidMiddle, RaidRight) }

        val all: List<EnemyTarget> by lazy { list + sixList + raidList }

        fun fromCode(code: String): EnemyTarget? = all.firstOrNull { it.autoSkillCode == code }
    }
}
