package io.github.fate_grand_automata.ui.launcher

sealed class ScriptLauncherResponse {
    data object Cancel : ScriptLauncherResponse()
    data class FP(val limit: Int?) : ScriptLauncherResponse()
    data class Lottery(
        val giftBox: GiftBox?
    ) : ScriptLauncherResponse()

    data class GiftBox(
        val maxGoldEmberStackSize: Int,
        val maxGoldEmberTotalCount: Int
    ) : ScriptLauncherResponse()

    data class CEBomb(val targetRarity: Int) : ScriptLauncherResponse()
    data object SupportImageMaker : ScriptLauncherResponse()
    data object Battle : ScriptLauncherResponse()

    data object ServantEnhancement : ScriptLauncherResponse()

    /** Target level per slot, 0 = off. */
    data class SkillLevel(val targets: List<Int>) : ScriptLauncherResponse()
    data class AppendLevel(val targets: List<Int>) : ScriptLauncherResponse()

    /** The weekly mission route entry: the route is computed and confirmed in a second dialog. */
    data object WeeklyRoute : ScriptLauncherResponse()
}

class ScriptLauncherResponseBuilder(
    val canBuild: () -> Boolean,
    val build: () -> ScriptLauncherResponse
)