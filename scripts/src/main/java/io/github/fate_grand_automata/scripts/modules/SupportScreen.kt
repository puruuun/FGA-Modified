package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.lib_automata.Swiper
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface SupportScreen {
    fun scrollDown()
    fun scrollToTop()
    fun click(supportClass: SupportClass)
    fun delay(duration: Duration)
    fun refresh()
    fun refreshAvailable(): Boolean
    fun isAnyDialogOpen(): Boolean
    fun noSupportsPresent(): Boolean
    fun someSupportsPresent(): Boolean
    fun isListLoaded(): Boolean
}

@ScriptScope
class RealSupportScreen @Inject constructor(
    api: IFgoAutomataApi,
    private val swipe: Swiper
) : IFgoAutomataApi by api, SupportScreen {
    private var hasRecommendedTab = false

    override fun scrollDown() {
        swipe(
            locations.support.listSwipeStart,
            locations.support.listSwipeEnd
        )
    }

    override fun scrollToTop() {
        locations.support.listTopClick.click()
    }

    override fun click(supportClass: SupportClass) =
        locations.support.locate(supportClass, hasRecommendedTab).click()

    override fun delay(duration: Duration) = duration.wait(false)

    override fun refresh() {
        locations.support.updateClick.click()
        1.seconds.wait()

        locations.support.updateYesClick.click()
    }
    override fun refreshAvailable() =
        images[Images.SupportRefresh] in locations.support.refreshRegion

    /**
     * Checks whether the Extra class tab is visible in [extraRegion] and which tab-bar scale is shown.
     * Matches both templates on one crop instead of nesting useSameSnapIn, because SupportScoreScanner
     * calls isAnyDialogOpen inside its own sameSnap block and useSameSnapIn is not re-entrant.
     * Comparing both scores avoids misclassifying 10-tab vs 11-tab if the user lowers minSimilarity.
     */
    private fun detectHasRecommendedTab(): Boolean? =
        locations.support.extraRegion.getPattern().use { crop ->
            val minSimilarity = prefs.platformPrefs.minSimilarity
            val standard = crop.findMatches(images[Images.SupportExtra], minSimilarity).maxOfOrNull { it.score }
            val compact = crop.findMatches(images[Images.SupportExtraCompact], minSimilarity).maxOfOrNull { it.score }
            when {
                standard == null && compact == null -> null
                compact != null && (standard == null || compact > standard) -> true
                else -> false
            }
        }

    override fun isAnyDialogOpen(): Boolean {
        val detected = detectHasRecommendedTab() ?: return true
        hasRecommendedTab = detected
        return false
    }

    override fun noSupportsPresent() = findImage(locations.support.notFoundRegion, Images.SupportNotFound)

    override fun someSupportsPresent() =
        locations.support.confirmSetupButtonRegion.exists(
            images[Images.SupportConfirmSetupButton],
            similarity = Support.supportRegionToolSimilarity
        ) || images[Images.Guest] in locations.support.friendRegion

    override fun isListLoaded() =
        useSameSnapIn { noSupportsPresent() || someSupportsPresent() }
}