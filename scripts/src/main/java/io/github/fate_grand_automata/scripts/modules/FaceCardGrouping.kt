package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.TeamSlot

/**
 * Pure decision logic for assigning command cards to servants, kept free of image matching so it can be unit tested.
 */
object FaceCardGrouping {
    /** A face-card score below this is "no match". */
    const val MIN_FACE_SCORE = 0.5

    /** Two detail-window faces scoring at least this are the same servant art. */
    const val SAME_FACE_SCORE = 0.99

    /** Similarity for the SUPPORT tag on a command card. */
    const val SUPPORT_TAG_SIMILARITY = 0.7

    /** Lowest score read for the log; decisions still use the thresholds above. */
    const val LOG_SCORE_FLOOR = 0.3

    /**
     * The best score of a `findMatches` sequence, which yields the best match first.
     * Only the first element is read: the matcher flood-fills each match with 0.0, so a sequence
     * searched with a similarity of 0.0 never ends.
     */
    fun bestScore(scores: Sequence<Double>) = scores.firstOrNull() ?: 0.0

    /**
     * The support face may be used as a candidate only if it does not look like any owned servant's face
     * (otherwise a duplicate servant would steal the owner's cards).
     *
     * @param scoresAgainstOwned the support face compared with each owned servant face image
     */
    fun supportFaceUsable(scoresAgainstOwned: Collection<Double>) =
        scoresAgainstOwned.none { it >= SAME_FACE_SCORE }

    /**
     * Groups the cards by servant.
     *
     * - A card with the SUPPORT tag belongs to [supportSlot] and takes no part in face matching.
     * - Every other card goes to the best-scoring candidate with a score of at least [MIN_FACE_SCORE].
     *   The candidates are the [ownedOnField] servants, plus [supportSlot] if it has a usable face image.
     * - Cards without a matching candidate are left out.
     *
     * @param supportSlot the support servant if it is on the field, else null
     * @param supportTagged cards found to carry the SUPPORT tag
     * @param ownedOnField owned servants on the field that have a face image
     * @param supportFaceUsable whether the support face is a candidate (a face was stored and it differs from every owned face)
     * @param score best face score of a card against a servant's face images (0 if unmatched)
     */
    fun group(
        cards: Collection<CommandCard.Face>,
        supportSlot: TeamSlot?,
        supportTagged: Set<CommandCard.Face>,
        ownedOnField: Collection<TeamSlot>,
        supportFaceUsable: Boolean,
        score: (CommandCard.Face, TeamSlot) -> Double
    ): Map<TeamSlot, Set<CommandCard.Face>> {
        val result = mutableMapOf<TeamSlot, Set<CommandCard.Face>>()

        val tagged = if (supportSlot != null) cards.filter { it in supportTagged }.toSet() else emptySet()
        if (supportSlot != null) {
            result[supportSlot] = tagged
        }

        val candidates = ownedOnField.filter { it != supportSlot } +
                listOfNotNull(supportSlot.takeIf { supportFaceUsable })

        (cards.toSet() - tagged)
            .groupBy { card ->
                candidates
                    .map { it to score(card, it) }
                    .filter { it.second >= MIN_FACE_SCORE }
                    .maxByOrNull { it.second }
                    ?.first
            }
            .forEach { (servant, group) ->
                if (servant != null) {
                    result[servant] = (result[servant] ?: emptySet()) + group
                }
            }

        return result
    }
}
