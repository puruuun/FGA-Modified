package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shop records block (#730) for the owner's review: names with an event expanded, numbers only, and while reading. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ShopRecordsScreenshotTest(private val width: Int, private val dark: Boolean, private val locale: String) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp_dark={1}_{2}")
        fun cases() = listOf(
            arrayOf<Any>(360, false, "ko"), arrayOf<Any>(360, true, "ko"), arrayOf<Any>(360, false, "ja"),
            arrayOf<Any>(1280, false, "ko"), arrayOf<Any>(1280, true, "ko"),
        )
    }
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }

    private fun shot(name: String, state: ShopRecordsState, readyTag: String, then: () -> Unit = {}) =
        compose.shoot("dashboard-730", name, width, if (width >= 840) 1000 else 1800, dark, locale, beforeCapture = {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(readyTag).fetchSemanticsNodes().isNotEmpty() }
            then()
        }) {
            CompositionLocalProvider(LocalNavigateBack provides {}) {
                AccountDashboardContent(fixture.data.copy(hasShopRecords = true), fixture.names, "샘플 별명", {}, {},
                    initialSection = DashboardBlockId.ShopRecords, userId = "synthetic", shopRecords = shopSource(state))
            }
        }

    @Test fun eventExpanded() = shot("ShopRecords", ShopRecordsState(shopRecordsFixture(), RecordNameStatus.Ready), "shop-event:80001") {
        compose.onNodeWithTag("shop-event-toggle:80001").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("shop:80001001").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun numbersOnly() = shot("ShopRecordsNumbers", ShopRecordsState(shopRecordsFixture(names = false), RecordNameStatus.Missing), "shop-names-missing") {
        compose.onNodeWithTag("shop-filter:Other").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("shop:80001001").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun loading() = shot("ShopRecordsLoading", ShopRecordsState(), "shop-records-loading")
}
