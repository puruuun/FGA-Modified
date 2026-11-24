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
import io.github.fate_grand_automata.scripts.models.toFieldSlot
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.lib_automata.OcrService
import io.github.lib_automata.Pattern
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@ScriptScope
class ServantTracker(
    api: IFgoAutomataApi,
    battleConfig: IBattleConfig,
    ocr: OcrService?,
    chainShown: Boolean
) : IFgoAutomataApi by api, AutoCloseable {
    @Inject
    constructor(api: IFgoAutomataApi, battleConfig: IBattleConfig, ocr: OcrService) :
        this(api, battleConfig, ocr, ChainPriorityAvailability.SHOWN)

    /** Without OCR, for tests that never read the NP type banner. */
    constructor(api: IFgoAutomataApi, battleConfig: IBattleConfig) :
        this(api, battleConfig, null, ChainPriorityAvailability.SHOWN)

    private val servantQueue = mutableListOf<TeamSlot>()
    private val _deployed = mutableMapOf<FieldSlot, TeamSlot>()
    val deployed: Map<FieldSlot, TeamSlot> = _deployed

    // Decided once per run from the battle config; the battle config screen shows the same decision.
    val faceCardCheck: FaceCardCheckDecision =
        FaceCardCheckPolicy.decide(FaceCardCheckPolicy.Input.of(battleConfig, chainShown))
    val faceCardCheckEnabled get() = faceCardCheck.enabled

    /**
     * NP card types are read only when some entry of this run has Chain Priority on (#654). Otherwise the tracker
     * opens, reads and waits exactly as it did before Chain Priority existed.
     */
    val readsNpCardTypes: Boolean = faceCardCheck.enabled &&
        FaceCardCheckPolicy.usesChainPriority(FaceCardCheckPolicy.Input.of(battleConfig, chainShown))

    private val npReader = NpCardTypeReader(api, ocr)

    /**
     * Servants like Melusine and Ptolemaios change their NP type when they transform with their skill,
     * so the NP type is tracked per form: [npCardTypes] for the form the Servant entered the field in,
     * [transformedNpCardTypes] for the other form, and [transformedServants] for who is currently in the other form.
     */
    private val npCardTypes = mutableMapOf<TeamSlot, CardTypeEnum>()
    private val transformedNpCardTypes = mutableMapOf<TeamSlot, CardTypeEnum>()
    private val transformedServants = mutableSetOf<TeamSlot>()

    /**
     * NP types changed by a skill for a limited time (e.g. Emiya's or BB Dubai's skill with a choice).
     * They take precedence over the type of the form until a read confirms the form's type again.
     */
    private val changedNpCardTypes = mutableMapOf<TeamSlot, CardTypeEnum>()

    /**
     * Tracks when to reopen each Servant's details. A readable counter delays the next check
     * until the banner should expire. While waiting, planning continues to use the last known type.
     */
    private val npCardTypesToReadAgain = NpCardTypeRechecks()

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

        // Every run starts with all Servants in the form they entered the field in
        transformedServants.clear()
        changedNpCardTypes.clear()
        npCardTypesToReadAgain.clear()
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

    /** The NP card type to plan with: a type changed by a skill, else the current form's, else Unknown. */
    fun getNpCardType(teamSlot: TeamSlot): CardTypeEnum =
        changedNpCardTypes[teamSlot] ?: formNpCardTypes(teamSlot)[teamSlot] ?: CardTypeEnum.Unknown

    private fun formNpCardTypes(teamSlot: TeamSlot) =
        if (teamSlot in transformedServants) transformedNpCardTypes else npCardTypes

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
                forgetNpCardTypes(teamSlot)
                supportFaceUsable = false
                lastLoggedSupportFaceDecision = null
                initFaceCard(teamSlot, slot)?.let { npCardTypes[teamSlot] = it }
            }
        } else {
            // Don't useSameSnapIn here, since we open a dialog
            initFaceCard(teamSlot, slot)?.let { npCardTypes[teamSlot] = it }
        }
    }

    private fun forgetNpCardTypes(teamSlot: TeamSlot) {
        npCardTypes.remove(teamSlot)
        transformedNpCardTypes.remove(teamSlot)
        transformedServants.remove(teamSlot)
        changedNpCardTypes.remove(teamSlot)
        npCardTypesToReadAgain.remove(teamSlot)
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

    /**
     * Captures the face card of the Servant in [slot] from their details dialog.
     *
     * @param addAnotherImage Capture even if [teamSlot] already has a face card, for a second form.
     * @return The NP type shown in the same dialog when NP types are read and one matched, else null.
     */
    private fun initFaceCard(teamSlot: TeamSlot, slot: FieldSlot, addAnotherImage: Boolean = false): CardTypeEnum? {
        if (!faceCardCheckEnabled || (!addAnotherImage && teamSlot in faceCardImages))
            return null

        // Open details dialog and click on INFO
        locations.battle.servantOpenDetailsClick(slot).click()
        locations.battle.servantDetailsInfoClick.click()

        // Upstream's fine-tune setting; its default is the 250 ms this wait always had.
        prefs.servantDetailsWait.wait()

        fun face() = locations.battle.servantDetailsFaceCardRegion.getPattern("Face $teamSlot")
        val reason = if (addAnotherImage) "transformed" else "entered"
        val (image, npCardType) =
            if (readsNpCardTypes) useSameSnapIn { face() to readNpCardType(teamSlot, reason) }
            else face() to null

        // Close dialog
        locations.battle.extraInfoWindowCloseClick.click()

        faceCardImages.getOrPut(teamSlot) { mutableListOf() }
            .add(image)

        250.milliseconds.wait()

        refreshSupportFaceDecision()

        return npCardType
    }

    private fun readNpCardType(teamSlot: TeamSlot, reason: String): CardTypeEnum? {
        val reading = npReader.readType(prefs.npCardTypeSimilarity, "NP type $teamSlot")
        messages.log(
            ScriptLog.NpCardTypeReading(teamSlot, reading.type, reading.scores, prefs.npCardTypeSimilarity, reason)
        )
        return reading.type
    }

    /**
     * Reads the NP type of the Servant in [fieldSlot] again from their details dialog, after a skill that lets
     * the player choose an option, such as Emiya's or BB Dubai's, which can change the NP type without changing the portrait.
     *
     * A type that differs from the form's is kept in [changedNpCardTypes] and checked next turn.
     * If the banner hides the type, its turn counter schedules the next read. An unreadable
     * counter means retry next turn. Checks end when a read confirms or establishes the form's type.
     */
    fun refreshNpCardType(fieldSlot: FieldSlot) {
        if (!readsNpCardTypes) return
        // An unrecognized Servant has no NP type to plan around
        val teamSlot = _deployed[fieldSlot]?.takeUnless { it is TeamSlot.Unknown } ?: return

        locations.battle.servantOpenDetailsClick(fieldSlot).click()
        locations.battle.servantDetailsInfoClick.click()
        prefs.servantDetailsWait.wait()

        val (npCardType, remainingTurns) = useSameSnapIn {
            val reading = npReader.readType(prefs.npCardTypeSimilarity, "NP type $teamSlot")
            // Read the small banner digit on the same frame only if the splash was unreadable.
            val turns = if (reading.type == null) npReader.readChangeTurns() else null
            messages.log(
                ScriptLog.NpCardTypeReading(
                    teamSlot, reading.type, reading.scores, prefs.npCardTypeSimilarity, "skill",
                    turnsText = turns?.text, turns = turns?.turns
                )
            )
            reading.type to turns?.turns
        }

        locations.battle.extraInfoWindowCloseClick.click()

        // Give the battle screen time to settle after the dialog closes.
        250.milliseconds.wait()

        val formTypes = formNpCardTypes(teamSlot)
        val formType = formTypes[teamSlot]
        // The change banner covers the NP read region during the effect, so a readable type
        // can fill in a missing baseline for the current form.
        val confirmedType = confirmedFormNpCardType(npCardType, formType)
        if (confirmedType != null) {
            if (formType == null) formTypes[teamSlot] = confirmedType
            changedNpCardTypes.remove(teamSlot)
            npCardTypesToReadAgain.remove(teamSlot)
        } else {
            if (npCardType != null) changedNpCardTypes[teamSlot] = npCardType
            npCardTypesToReadAgain.schedule(teamSlot, remainingTurns ?: 1)
        }
    }

    /**
     * Marks the NP type of the Servant in [fieldSlot] to be read again (see [npCardTypesToReadAgain]),
     * when it may have changed but couldn't be read now. The last known type is used until then.
     */
    fun npCardTypeMayHaveChanged(fieldSlot: FieldSlot) {
        if (!readsNpCardTypes) return
        val teamSlot = _deployed[fieldSlot]?.takeUnless { it is TeamSlot.Unknown } ?: return

        // No dialog was available after the skill. Read at the next opportunity, including Order Change.
        npCardTypesToReadAgain.schedule(teamSlot, 0)
    }

    private fun needsNpCardTypeRead(fieldSlot: FieldSlot): Boolean {
        val teamSlot = _deployed[fieldSlot] ?: return false
        return npCardTypesToReadAgain.isDue(teamSlot)
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

    fun beginTurn() {
        if (readsNpCardTypes) npCardTypesToReadAgain.beginTurn()

        FieldSlot.list.forEach {
            check(it)
        }

        // An NP type changed by a skill reverts when the skill's effect ends
        if (readsNpCardTypes) {
            FieldSlot.list
                .filter { needsNpCardTypeRead(it) }
                .forEach { refreshNpCardType(it) }
        }
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

                // beginTurn() only runs NP type re-checks for the Servants on the field at that time,
                // so a Servant brought in here with a changed type or a due re-check is read now
                if (readsNpCardTypes && needsNpCardTypeRead(startingSlot)) {
                    refreshNpCardType(startingSlot)
                }
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
     * The NP cards whose Servant portrait matches a captured face card, so that a spam NP is planned
     * only when it is actually on the card screen. Read only with Chain Priority.
     */
    fun npCardsDetectedUsingServantFaces(): Set<CommandCard.NP> {
        if (!readsNpCardTypes) return emptySet()

        return CommandCard.NP.list
            .filter { npCard ->
                val teamSlot = deployed[npCard.toFieldSlot()] ?: return@filter false
                // Searching at the configured similarity makes every value of the setting take effect
                faceCardImages[teamSlot].orEmpty().any { image ->
                    locations.attack.servantNPMatchRegion(npCard).find(image, prefs.npCardDetectionSimilarity) != null
                }
            }
            .toSet()
    }

    /**
     * After a skill that transforms a Servant (Melusine, Ptolemaios). Without NP type reading this is the
     * fork's unchanged handling; with it, upstream's form tracking that also captures the other form's NP type.
     */
    fun servantTransformed(fieldSlot: FieldSlot) {
        if (!readsNpCardTypes) {
            melusineChangedAscension(fieldSlot)
            return
        }

        val teamSlot = _deployed[fieldSlot] ?: return
        val checkImage = checkImages[teamSlot]?.checkImage ?: return
        val portraitRegion = locations.battle.servantChangeCheckRegion(fieldSlot)

        // checkImage[0] is the form the Servant entered the field in, checkImage[1] the other form once known
        val originalForm = checkImage.first()
        val otherForm = checkImage.getOrNull(1)

        // The form is read from the portrait, so a skill that was rejected (e.g. on cooldown) changes nothing.
        if (otherForm == null) {
            if (originalForm in portraitRegion) return

            checkImage.add(portraitRegion.getPattern("Servant $teamSlot transformed"))
            transformedServants += teamSlot

            initFaceCard(teamSlot, fieldSlot, addAnotherImage = true)
                ?.let { transformedNpCardTypes[teamSlot] = it }
            return
        }

        useSameSnapIn {
            when {
                otherForm in portraitRegion -> transformedServants += teamSlot
                originalForm in portraitRegion -> transformedServants -= teamSlot
            }
        }
    }

    /**
     * Adds the 3rd Ascension Melusine image to the existing 1st/2nd Ascension
     * image so both are detected as the same Servant.
     */
    private fun melusineChangedAscension(fieldSlot: FieldSlot) {
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
