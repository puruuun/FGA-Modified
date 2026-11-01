package io.github.fate_grand_automata.prefs.core

/**
 * One-time migration for merging the global "enable withdraw" switch into the per-config
 * "retreat and restart" setting. Runs once, guarded by [done].
 */
object RetreatMergeMigration {
    /** A battle config's switch and turn count. */
    class Target(val enabled: Pref<Boolean>, val turns: Pref<Int>)

    /**
     * If the old global switch was on, every config with the feature off becomes on with 0 turns
     * (party wipe only). Configs that are already on keep their settings. If the old switch was off,
     * nothing changes.
     *
     * @return true when this call ran the migration.
     */
    fun run(done: Pref<Boolean>, legacyWithdrawEnabled: Pref<Boolean>, targets: List<Target>): Boolean {
        if (done.get()) return false

        if (legacyWithdrawEnabled.get()) {
            for (target in targets) {
                if (!target.enabled.get()) {
                    target.enabled.set(true)
                    target.turns.set(0)
                }
            }
        }

        done.set(true)
        return true
    }
}
