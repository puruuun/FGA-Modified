package io.github.fate_grand_automata.scripts.entrypoints

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.scripts.modules.SkillLevelMode
import io.github.fate_grand_automata.scripts.modules.SkillLevelScreens
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class AutoDetect @Inject constructor(
    api: IFgoAutomataApi,
    private val skillScreens: SkillLevelScreens,
) : IFgoAutomataApi by api {
    /** D-10: only a screen with a selected servant gets a settings dialog. */
    fun skillServantSelected() = skillScreens.servantSelected()

    fun get() = useSameSnapIn {
        val emberSearchRegion = locations.scriptArea.let {
            it.copy(width = it.width / 3)
        }

        // D-10: the skill / append screens are told apart by the centre-left label; only KR and JP have the templates.
        val skillMode by lazy {
            skillScreens.detectMode().also { mode ->
                // Only KR / JP have the templates; one line with the best scores for reports of a wrong detection.
                if (prefs.gameServer is GameServer.Kr || prefs.gameServer is GameServer.Jp) {
                    runCatching { messages.log(ScriptLog.SkillLevel("auto-detect " + skillScreens.bestScores().describe(mode))) }
                }
            }
        }

        when {
            images[Images.FriendSummon] in locations.fp.summonCheck ||
                    images[Images.FriendSummon] in locations.fp.initialSummonCheck ->
                ScriptModeEnum.FP

            images[Images.LotteryBoxFinished] in locations.lottery.checkRegion || images[Images.LotteryBoxFinished] in locations.lottery.finishedRegion ->
                ScriptModeEnum.Lottery

            listOf(images[Images.GoldXP], images[Images.SilverXP], images[Images.Gold5StarXP]) in emberSearchRegion ->
                ScriptModeEnum.PresentBox

            locations.support.confirmSetupButtonRegion.exists(images[Images.SupportConfirmSetupButton], similarity = 0.75) ->
                ScriptModeEnum.SupportImageMaker

            skillMode == SkillLevelMode.Skill -> ScriptModeEnum.SkillLevel

            skillMode == SkillLevelMode.Append -> ScriptModeEnum.AppendLevel

            mapOf(
                images[Images.ServantAutoSelect] to locations.servant.servantAutoSelectRegion,
                images[Images.ServantAutoSelectOff] to locations.servant.servantAutoSelectRegion,
                images[Images.ServantAscensionBanner] to locations.enhancementBannerRegion
            ).exists()->
                ScriptModeEnum.ServantLevel

            images[Images.EmptyEnhance] in locations.emptyEnhanceRegion ->
                ScriptModeEnum.CEBomb

            else -> ScriptModeEnum.Battle
        }
    }
}
