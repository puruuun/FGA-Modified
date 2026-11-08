package io.github.fate_grand_automata.ui.main

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.FgaScreen
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.battle_config_item.BattleConfigDestination
import io.github.fate_grand_automata.ui.battle_config_item.BattleConfigScreen
import io.github.fate_grand_automata.ui.battle_config_item.BattleConfigScreenViewModel
import io.github.fate_grand_automata.ui.battle_config_list.BattleConfigListScreen
import io.github.fate_grand_automata.ui.card_priority.CardPriorityScreen
import io.github.fate_grand_automata.ui.card_priority.CardPriorityViewModel
import io.github.fate_grand_automata.ui.fine_tune.AddedFeatureTuningScreen
import io.github.fate_grand_automata.ui.fine_tune.FineTuneScreen
import io.github.fate_grand_automata.ui.log.LogScreen
import io.github.fate_grand_automata.ui.more.LicenseScreen
import io.github.fate_grand_automata.ui.more.MoreOptionsScreen
import io.github.fate_grand_automata.ui.onboarding.OnboardingScreen
import io.github.fate_grand_automata.ui.openLinkIntent
import io.github.fate_grand_automata.ui.pref_support.PreferredSupportScreen
import io.github.fate_grand_automata.ui.pref_support.PreferredSupportViewModel
import io.github.fate_grand_automata.ui.pref_support.SupportViewModel
import io.github.fate_grand_automata.ui.skill_maker.SkillMakerActivity
import io.github.fate_grand_automata.ui.spam.SpamScreen
import io.github.fate_grand_automata.ui.spam.SpamScreenViewModel

@Composable
fun FgaApp(
    vm: MainScreenViewModel,
    supportVm: SupportViewModel
) {
    val motionDistance = with(LocalDensity.current) { 30.dp.roundToPx() }
    val context = LocalContext.current
    val backStack = rememberNavBackStack(
        if (vm.prefs.isOnboardingRequired()) {
            Route.Onboarding
        } else {
            Route.Home
        }
    )

    /*
     * Anything above the start destination can be popped, so every Heading below gets a back
     * arrow; the root (Home, or Onboarding on first run) gets none.
     */
    val navigateBack: (() -> Unit)? =
        if (backStack.size > 1) {
            { backStack.removeLastOrNull() }
        } else null

    // A widget start tap that arrives before onboarding is done must not start the service later.
    LaunchedEffect(Unit) { if (vm.prefs.isOnboardingRequired()) vm.widgetRequests.takeStart() }

    // Account widget tap: open that account's dashboard once, on top of the home screen.
    val widgetAccount = vm.widgetRequests.accountPending
    LaunchedEffect(widgetAccount) {
        val id = vm.widgetRequests.takeAccount() ?: return@LaunchedEffect
        if (!vm.prefs.isOnboardingRequired()) {
            backStack.clear()
            backStack.add(Route.Home)
            backStack.add(Route.AccountDashboard(id))
        }
    }

    FgaScreen {
        AppUpdateDialog(vm)
        CompositionLocalProvider(LocalNavigateBack provides navigateBack) {
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator()
                ),
                transitionSpec = { sharedAxisX(motionDistance) },
                popTransitionSpec = { sharedAxisX(-motionDistance) },
                predictivePopTransitionSpec = { sharedAxisX(-motionDistance) },
                entryProvider = entryProvider {
                    entry<Route.Home> {
                        MainScreen(
                            vm = vm,
                            navigate = {
                                when (it) {
                                    MainScreenDestinations.AccessibilitySettings -> {
                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        context.startActivity(intent)
                                    }

                                    MainScreenDestinations.BattleConfigs -> {
                                        backStack.add(Route.BattleConfigList)
                                    }

                                    MainScreenDestinations.GainsTracker -> {
                                        backStack.add(Route.GainsTracker)
                                    }

                                    MainScreenDestinations.MoreOptions -> {
                                        backStack.add(Route.MoreOptions)
                                    }

                                    is MainScreenDestinations.AccountDashboard -> {
                                        backStack.add(Route.AccountDashboard(it.userId))
                                    }

                                    MainScreenDestinations.Logs -> {
                                        backStack.add(Route.Logs)
                                    }

                                    MainScreenDestinations.OverlaySettings -> {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            "package:${context.packageName}".toUri()
                                        )

                                        context.startActivity(intent)
                                    }

                                    MainScreenDestinations.Releases -> {
                                        context.openLinkIntent(R.string.link_releases)
                                    }

                                }
                            }
                        )
                    }
                    entry<Route.Onboarding> {
                        OnboardingScreen(
                            vm = hiltViewModel(),
                            navigateToHome = {
                                // disables going back to onboarding from home screen
                                backStack.clear()
                                backStack.add(Route.Home)
                            }
                        )
                    }
                    entry<Route.BattleConfigList> {
                        BattleConfigListScreen(
                            vm = hiltViewModel(),
                            navigate = { backStack.add(Route.BattleConfigItem(it)) }
                        )
                    }
                    entry<Route.MoreOptions> {
                        MoreOptionsScreen(
                            vm = hiltViewModel(),
                            navigateToFineTune = { backStack.add(Route.FineTune) },
                            navigateToAddedFeatureTuning = { backStack.add(Route.AddedFeatureTuning) },
                            navigateToLicense = { backStack.add(Route.License) },
                            navigateToWeeklySettings = { backStack.add(Route.WeeklyRouteSettings) },
                            navigateToGainsTracker = { backStack.add(Route.GainsTracker) },
                            navigateToFriendManager = { backStack.add(Route.FriendManager()) }
                        )
                    }
                    entry<Route.WeeklyRouteSettings> {
                        io.github.fate_grand_automata.ui.more.WeeklyRouteSettingsScreen(vm.prefsCore)
                    }
                    entry<Route.GainsTracker> {
                        io.github.fate_grand_automata.ui.more.GainsTrackerScreen(vm = hiltViewModel())
                    }
                    entry<Route.FriendManager> { key ->
                        io.github.fate_grand_automata.ui.more.FriendManagerScreen(vm = hiltViewModel(), requestedId = key.userId)
                    }
                    entry<Route.License> {
                        LicenseScreen()
                    }
                    entry<Route.AccountDashboard> { key ->
                        io.github.fate_grand_automata.ui.account.AccountDashboardScreen(
                            userId = key.userId,
                            openSettings = { backStack.add(Route.GainsTracker) },
                            openLogs = { backStack.add(Route.Logs) },
                            openFriends = { backStack.add(Route.FriendManager(key.userId)) },
                        )
                    }
                    entry<Route.Logs> {
                        LogScreen()
                    }
                    entry<Route.FineTune> {
                        FineTuneScreen(
                            vm = hiltViewModel()
                        )
                    }
                    entry<Route.AddedFeatureTuning> {
                        AddedFeatureTuningScreen(vm = hiltViewModel())
                    }
                    entry<Route.BattleConfigItem> { key ->
                        val id = key.id

                        BattleConfigScreen(
                            vm = hiltViewModel<BattleConfigScreenViewModel, BattleConfigScreenViewModel.Factory>(
                                creationCallback = { it.create(id) }
                            ),
                            navigate = {
                                when (it) {
                                    BattleConfigDestination.Back -> backStack.removeLastOrNull()
                                    is BattleConfigDestination.CardPriority ->
                                        backStack.add(Route.CardPriority(id, it.profileIndex))

                                    is BattleConfigDestination.Other -> {
                                        // replaces the current battle config screen with another one,
                                        // discarding any of its child screens (Card Priority, etc.)
                                        val listIndex =
                                            backStack.indexOfLast { it is Route.BattleConfigList }
                                        check(listIndex >= 0) {
                                            "Route.BattleConfigList not found below $it on the back stack"
                                        }
                                        backStack.subList(listIndex + 1, backStack.size).clear()
                                        backStack.add(Route.BattleConfigItem(it.id))
                                    }

                                    BattleConfigDestination.PreferredSupport ->
                                        backStack.add(Route.PreferredSupport(id))

                                    is BattleConfigDestination.SkillMaker -> {
                                        val intent = Intent(context, SkillMakerActivity::class.java).apply {
                                            putExtra(Route.BattleConfig.idArg, id)
                                            putExtra(Route.BattleConfig.profileArg, it.profileIndex)
                                        }

                                        context.startActivity(intent)
                                    }

                                    BattleConfigDestination.Spam -> backStack.add(Route.Spam(id))
                                }
                            }
                        )
                    }
                    entry<Route.CardPriority> { key ->
                        CardPriorityScreen(
                            vm = hiltViewModel<CardPriorityViewModel, CardPriorityViewModel.Factory>(
                                creationCallback = { it.create(key.id, key.profileIndex) }
                            )
                        )
                    }
                    entry<Route.PreferredSupport> { key ->
                        PreferredSupportScreen(
                            vm = hiltViewModel<PreferredSupportViewModel, PreferredSupportViewModel.Factory>(
                                creationCallback = { it.create(key.id) }
                            ),
                            supportVm = supportVm
                        )
                    }
                    entry<Route.Spam> { key ->
                        SpamScreen(
                            vm = hiltViewModel<SpamScreenViewModel, SpamScreenViewModel.Factory>(
                                creationCallback = { it.create(key.id) }
                            )
                        )
                    }
                }
            )
        }
    }
}

/** Material emphasized-decelerate: short X movement with matching opacity in both directions. */
private fun sharedAxisX(distance: Int): androidx.compose.animation.ContentTransform {
    val easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    return (slideInHorizontally(tween(250, easing = easing)) { distance } +
        fadeIn(tween(250, easing = easing))) togetherWith
        (slideOutHorizontally(tween(250, easing = easing)) { -distance } +
            fadeOut(tween(250, easing = easing)))
}
