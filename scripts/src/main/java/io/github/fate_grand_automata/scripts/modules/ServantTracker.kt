package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.OrderChangeMember
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.skills
import io.github.lib_automata.Pattern
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@ScriptScope
class ServantTracker @Inject constructor(
    api: IFgoAutomataApi,
    battleConfig: IBattleConfig
) : IFgoAutomataApi by api, AutoCloseable {

    private val servantQueue = mutableListOf<TeamSlot>()
    private val _deployed = mutableMapOf<FieldSlot, TeamSlot>()
    val deployed: Map<FieldSlot, TeamSlot> = _deployed

    // Decided once per run from the battle config; the battle config screen shows the same decision.
    val faceCardCheck: FaceCardCheckDecision = FaceCardCheckPolicy.decide(battleConfig)
    val faceCardCheckEnabled get() = faceCardCheck.enabled

    fun nextRun() {
        servantQueue.clear()
        servantQueue.addAll(
            listOf(TeamSlot.D, TeamSlot.E, TeamSlot.F)
        )

        _deployed.clear()
        _deployed.putAll(
            mapOf(
                FieldSlot.A to TeamSlot.A,
                FieldSlot.B to TeamSlot.B,
                FieldSlot.C to TeamSlot.C
            )
        )
    }

    init {
        nextRun()
        messages.log(ScriptLog.FaceCardCheck(faceCardCheck.toLogText()))
    }

    data class TeamSlotData(
        val checkImage: MutableList<Pattern>,
        val skills: List<Pattern>
    ) : AutoCloseable {
        override fun close() {
            checkImage.forEach { it.close() }
            skills.forEach { it.close() }
        }
    }

    val checkImages = mutableMapOf<TeamSlot, TeamSlotData>()
    private var supportSlot: TeamSlot? = null

    private val faceCardImages = mutableMapOf<TeamSlot, MutableList<Pattern>>()

    override fun close() {
        checkImages.values.forEach { it.close() }
        faceCardImages.values.flatten().forEach { it.close() }
        checkImages.clear()
    }

    private fun init(teamSlot: TeamSlot, slot: FieldSlot) {
        messages.log(
            ScriptLog.ServantEnteredSlot(
                servant = teamSlot,
                slot = slot
            )
        )

        var isSupport = false
        // use same screenshot for support + face detection
        useSameSnapIn {
            isSupport = isSupport(slot)

            if (teamSlot !in checkImages || isSupport) {
                checkImages[teamSlot] = TeamSlotData(
                    checkImage = mutableListOf(
                        locations.battle.servantChangeCheckRegion(slot)
                            .getPattern("Servant $teamSlot")
                    ),
                    skills = slot.skills().mapIndexed { index, it ->
                        locations.battle.imageRegion(it)
                            .getPattern("Servant $teamSlot S${index + 1}")
                    }
                )
            }
        }

        if (supportSlot == null && isSupport) {
            supportSlot = teamSlot
        }

        if (isSupport) {
            if (teamSlot == supportSlot) {
                // A different support may have entered this slot: replace the stored face
                faceCardImages.remove(teamSlot)?.forEach { it.close() }
                supportFaceUsable = false
                lastLoggedSupportFaceDecision = null
                initFaceCard(teamSlot, slot)
            }
        } else {
            // Don't useSameSnapIn here, since we open a dialog
            initFaceCard(teamSlot, slot)
        }
    }

    // Whether the support's own face may be a candidate when assigning cards without the SUPPORT tag.
    private var supportFaceUsable = false
    private var lastLoggedSupportFaceDecision: Boolean? = null

    private fun matchScore(a: Pattern, b: Pattern) =
        FaceCardGrouping.bestScore(a.findMatches(b, 0.0).map { it.score })

    /**
     * Compares the support face with every owned servant face; the support face is unusable if any is the same face.
     */
    private fun refreshSupportFaceDecision() {
        val support = supportSlot ?: return
        val supportImages = faceCardImages[support] ?: return
        if (supportImages.isEmpty()) return

        val ownedScores = faceCardImages
            .filterKeys { it != support }
            .flatMap { (owner, images) ->
                images.map { owned -> owner to supportImages.maxOf { matchScore(it, owned) } }
            }

        supportFaceUsable = FaceCardGrouping.supportFaceUsable(ownedScores.map { it.second })

        if (lastLoggedSupportFaceDecision != supportFaceUsable) {
            lastLoggedSupportFaceDecision = supportFaceUsable
            val best = ownedScores.maxByOrNull { it.second }
            messages.log(
                ScriptLog.SupportFaceDecision(
                    support = support,
                    usable = supportFaceUsable,
                    sameFaceServant = best?.first,
                    bestOwnedScore = best?.second,
                    ownedScores = ownedScores
                )
            )
        }
    }

    private fun initFaceCard(teamSlot: TeamSlot, slot: FieldSlot, addAnotherImage: Boolean = false) {
        if (!faceCardCheckEnabled || (!addAnotherImage && teamSlot in faceCardImages))
            return

        // Open details dialog and click on INFO
        locations.battle.servantOpenDetailsClick(slot).click()
        locations.battle.servantDetailsInfoClick.click()

        250.milliseconds.wait()

        val image = locations.battle.servantDetailsFaceCardRegion.getPattern("Face $teamSlot")

        // Close dialog
        locations.battle.extraInfoWindowCloseClick.click()

        faceCardImages.getOrPut(teamSlot) { mutableListOf() }
            .add(image)

        250.milliseconds.wait()

        refreshSupportFaceDecision()
    }

    private fun check(slot: FieldSlot) {
        // If a servant is not present, that means none are left in the backline
        if (!locations.battle.servantPresentRegion(slot)
                .exists(images[Images.ServantExist], similarity = 0.70)
        ) {
            _deployed.remove(slot)
            servantQueue.clear()
            return
        }

        val teamSlot = deployed[slot] ?: return
        if (teamSlot is TeamSlot.Unknown) return

        val checkImage = checkImages[teamSlot]?.checkImage

        if (checkImage == null) {
            init(teamSlot, slot)
            return
        }

        val isDifferentServant = checkImage.none { it in locations.battle.servantChangeCheckRegion(slot) }
        val isSupport = isSupport(slot)
        val wasSupport = supportSlot == teamSlot

        // New run with different support
        if (wasSupport && isSupport && isDifferentServant) {
            init(teamSlot, slot)
        } else if (isDifferentServant || (wasSupport != isSupport)) {
            val newTeamSlot = servantQueue.removeFirstOrNull()

            if (newTeamSlot != null) {
                _deployed[slot] = newTeamSlot
                init(newTeamSlot, slot)
            } else {
                // Something has gone wrong with matching servants, a servant is present but we don't know which one
                _deployed[slot] = TeamSlot.Unknown

                messages.log(
                    ScriptLog.ServantEnteredSlot(
                        servant = TeamSlot.Unknown,
                        slot = slot
                    )
                )
            }
        }
    }

    fun beginTurn() =
        FieldSlot.list.forEach {
            check(it)
        }

    fun orderChanged(starting: OrderChangeMember.Starting, sub: OrderChangeMember.Sub) {
        val startingSlot = when (starting) {
            OrderChangeMember.Starting.A -> FieldSlot.A
            OrderChangeMember.Starting.B -> FieldSlot.B
            OrderChangeMember.Starting.C -> FieldSlot.C
        }
        val subIndex = sub.autoSkillCode - OrderChangeMember.Sub.A.autoSkillCode

        if (subIndex in servantQueue.indices) {
            deployed[startingSlot]?.let { swapOut ->
                _deployed[startingSlot] = servantQueue[subIndex]
                servantQueue[subIndex] = swapOut

                check(startingSlot)
            }
        }
    }

    fun faceCardsGroupedByServant(): Map<TeamSlot, Collection<CommandCard.Face>> {
        if (!faceCardCheckEnabled) {
            return emptyMap()
        }

        val cards = CommandCard.Face.list
        val onFieldSupport = supportSlot?.takeIf { it in deployed.values }

        // Scores down to LOG_SCORE_FLOOR are read so the beta log shows near misses
        val tagScores = if (onFieldSupport != null) {
            cards.associateWith { card ->
                locations.attack.supportCheckRegion(card)
                    .find(images[Images.Support], FaceCardGrouping.LOG_SCORE_FLOOR)?.score
            }
        } else emptyMap()

        val supportTagged = tagScores
            .filterValues { it != null && it >= FaceCardGrouping.SUPPORT_TAG_SIMILARITY }
            .keys

        val ownedOnField = faceCardImages.keys
            .filter { it != supportSlot && it in deployed.values }

        val faceScores = mutableMapOf<CommandCard.Face, MutableMap<TeamSlot, Double>>()

        val result = FaceCardGrouping.group(
            cards = cards,
            supportSlot = onFieldSupport,
            supportTagged = supportTagged,
            ownedOnField = ownedOnField,
            supportFaceUsable = supportFaceUsable && onFieldSupport != null && onFieldSupport in faceCardImages
        ) { card, servant ->
            (faceCardImages[servant]?.maxOfOrNull { image ->
                locations.attack.servantMatchRegion(card)
                    .find(image, FaceCardGrouping.LOG_SCORE_FLOOR)?.score ?: 0.0
            } ?: 0.0).also { faceScores.getOrPut(card) { mutableMapOf() }[servant] = it }
        }

        messages.log(
            ScriptLog.FaceCardReadings(
                cards.map { card ->
                    ScriptLog.FaceCardReading(
                        card = card,
                        tagScore = tagScores[card],
                        faceScores = faceScores[card].orEmpty(),
                        servant = result.entries.firstOrNull { card in it.value }?.key
                    )
                },
                support = onFieldSupport
            )
        )

        result.forEach { (servant, cards) ->
            messages.log(
                ScriptLog.CardsBelongToServant(
                    cards,
                    servant,
                    isSupport = servant == supportSlot
                )
            )
        }

        return result
    }

    /**
     * Adds the 3rd Ascension Melusine image to the existing 1st/2nd Ascension
     * image so both are detected as the same Servant.
     */
    fun melusineChangedAscension(fieldSlot: FieldSlot) {
        val teamSlot = _deployed[fieldSlot]!!
        val teamSlotData = checkImages[teamSlot]
        if (teamSlotData != null && teamSlotData.checkImage.size == 1) {
            teamSlotData.checkImage.add(
                locations.battle.servantChangeCheckRegion(fieldSlot)
                    .getPattern("Melusine Asc3")
            )

            initFaceCard(teamSlot, fieldSlot, addAnotherImage = true)
        }
    }

    /**
     * Checks if the given [slot] is a Support Servant.
     */
    private fun isSupport(slot: FieldSlot) =
        images[Images.ServantCheckSupport] in locations.battle.servantChangeSupportCheckRegion(slot)
}
