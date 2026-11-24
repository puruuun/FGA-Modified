package io.github.fate_grand_automata.prefs.core

import android.content.Context
import com.fredporciuncula.flow.preferences.Serializer
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy
import io.github.lib_automata.Location
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

const val MASTER_SKILL_MENU_DELAY_DEFAULT_MS = 1500
const val MASTER_SKILL_MENU_DELAY_MIN_MS = 300
const val MASTER_SKILL_MENU_DELAY_MAX_MS = 3000

// The slider bounds the UI, but an imported or hand-edited settings file can hold any value.
fun masterSkillMenuDelayOf(storedMs: Int): Duration =
    storedMs.coerceIn(MASTER_SKILL_MENU_DELAY_MIN_MS, MASTER_SKILL_MENU_DELAY_MAX_MS).milliseconds

@Singleton
class PrefsCore @Inject constructor(
    maker: PrefMaker,
    @param:ApplicationContext val context: Context
) {
    companion object {
        const val GAME_SERVER_AUTO_DETECT = "auto_detect"

        // increase for each changed Onboarding screen
        const val CURRENT_ONBOARDING_VERSION = 2

        /** The active filter's conditions; the name is from #685, when this file held the filter for manual play. */
        const val MANUAL_SUPPORT_FILTER_FILE = "support_list_filter_manual"

        /** One file per support list filter profile (#695): this prefix and the profile's id. */
        const val SUPPORT_FILTER_PROFILE_FILE_PREFIX = "support_filter_profile_"

        /**
         * The #685 switch, in a battle config's file and in [MANUAL_SUPPORT_FILTER_FILE]. Retired by #695; only the
         * app's migration reads it, and a battle config import drops it.
         */
        const val LEGACY_SUPPORT_LIST_FILTER_KEY = "support_list_filter"
    }

    val onboardingCompletedVersion = maker.int("onboarding_completed_version")

    val scriptMode = maker.enum("script_mode", ScriptModeEnum.Battle)

    val gameServerRaw = maker.string(
        "game_server",
        GAME_SERVER_AUTO_DETECT
    )

    val battleConfigList = maker.stringSet("autoskill_list")

    val storySkip = maker.bool("story_skip")

    /** Retired global switch; only read once by [RetreatMergeMigration]. */
    val legacyWithdrawEnabled = maker.bool("withdraw_enabled")
    val retreatMergeMigrated = maker.bool("retreat_merge_migrated")

    val stopOnCEGet = maker.bool("stop_on_ce_get")
    val stopOnFirstClearRewards = maker.bool("stop_on_first_clear_rewards")

    val boostItemSelectionMode = maker.stringAsInt("selected_boost_item", -1)

    val useRootForScreenshots = maker.bool("use_root_screenshot")
    val recordScreen = maker.bool("record_screen")
    val screenshotDrops = maker.bool("screenshot_drops")
    val screenshotDropsUnmodified = maker.bool("screenshot_drops_unmodified")
    val screenshotBond = maker.bool("screenshot_bond")
    val missionPopupClear = maker.bool("mission_popup_clear")
    val missionPopupClearSeconds = maker.int("mission_popup_clear_seconds", 10)
    val hidePlayButton = maker.bool("hide_play_button")
    val debugMode = maker.bool("debug_mode")
    val supportReaderDiagnostics = maker.bool("support_reader_diagnostics")
    val autoStartService = maker.bool("auto_start_service")
    val checkForUpdates = maker.bool("check_for_updates", true)
    val appTheme = maker.enum("app_theme", AppTheme.System)

    val hideSQInAPResources = maker.bool("hide_sq_in_ap_resources", true)
    val weeklyRouteFeatureEnabled = maker.bool("weekly_route_feature_enabled", true)
    val bondTrackingFeatureEnabled = maker.bool("bond_tracking_feature_enabled", true)
    val questItemControl = QuestItemControlPrefsCore(maker)
    val weeklyRoute = WeeklyRoutePrefsCore(maker)

    /** Gains tracker (KR), the in-app capture base. Off until the user opts in. */
    val captureTrackerEnabled = maker.bool("capture_tracker_enabled", false)
    val captureConsentVersion = maker.int("capture_consent_version", 0)
    val captureStopWithService = maker.bool("capture_stop_with_service", false)
    val captureJpRoot = maker.bool("capture_jp_root", false)
    val captureConnectionMode = maker.enum("capture_connection_mode", CaptureConnectionMode.Vpn)
    val captureProxyPort = maker.int("capture_proxy_port", CaptureConnectionMode.DEFAULT_PROXY_PORT).map(
        defaultValue = CaptureConnectionMode.DEFAULT_PROXY_PORT,
        convert = CaptureConnectionMode::clampProxyPort,
        reverse = CaptureConnectionMode::clampProxyPort,
    )

    /**
     * Retired experiment (#636): svtIds stored as strings. Only read once by the app's support filter migration (#685),
     * which moves the ids into [manualSupportFilter] and sets [supportFilterMigrated].
     */
    val legacySupportFilterEnabled = maker.bool("capture_support_filter_enabled", false)
    val legacySupportFilterServants = maker.stringSet("capture_support_filter_servants")
    val legacySupportFilterCes = maker.stringSet("capture_support_filter_ces")
    val supportFilterMigrated = maker.bool("capture_support_filter_migrated", false)

    /**
     * The support list filter (#695). [supportFilterFeature] is "use this feature" in More: off, the launcher looks as
     * it did before the filter existed and the proxy relays every byte. [supportFilterOn] is the launcher's toggle.
     */
    val supportFilterFeature = maker.bool("support_filter_feature", false)
    val supportFilterOn = maker.bool("support_filter_on", false)
    val supportFilterSource = maker.enum("support_filter_source", SupportFilterSource.None)

    /** The config or profile name [supportFilterSource] refers to, as it was when the conditions were copied. */
    val supportFilterSourceName = maker.string("support_filter_source_name", "")
    val supportFilterProfiles = maker.stringSet("support_filter_profiles")

    /** Set once the app's migration from the #685 switches has run. */
    val supportFilterToggleMigrated = maker.bool("support_filter_toggle_migrated", false)
    val captureAdGuardAutoSwitch = maker.bool("capture_adguard_auto_switch", false)
    val captureAdGuardAutomationPassword = maker.string("capture_adguard_automation_password", "")

    /** Chaldea direct sync (stage 1): writes captured account data into Chaldea through Shizuku. Off until opted in. */
    val chaldeaSyncEnabled = maker.bool("chaldea_sync_enabled", false)
    /** Minutes in the background before Chaldea is closed to sync: 10, 30, 60, or 0 for never. */
    val chaldeaSyncCloseMinutes = maker.int("chaldea_sync_close_minutes", 30)
    /** Minutes without FGO or Chaldea on screen before auto-sync stops its session: 10, 30, or 60. */
    val chaldeaSyncStopMinutes = maker.int("chaldea_sync_stop_minutes", 10)
    /** Root was granted to FGA before, so a silent root check at start is allowed (issue #362). */
    val chaldeaSyncRootGranted = maker.bool("chaldea_sync_root_granted", false)

    /** Home-screen widget look. The widgets read the same keys straight from the preference file. */
    val widgetTheme = maker.enum("widget_theme", WidgetTheme.Dark)
    val widgetShowLogButton = maker.bool("widget_show_log_button", true)
    val widgetDisplayStyle = maker.enum("widget_display_style", WidgetDisplayStyle.OneAccount)
    val widgetShowServiceButton = maker.bool("widget_show_service_button", true)
    val widgetShowServer = maker.bool("widget_show_server", false)

    /** Percent of the widget card that is see-through: 0 (opaque) to 100, in steps of 10. */
    val widgetBackgroundTransparency = maker.int("widget_background_transparency", 0)
    val logRetention = LogRetentionPrefsCore(maker)
    val addedFeatureTuning = AddedFeatureTuningPrefsCore(maker)

    val shouldLimitFP = maker.bool("should_fp_limit")
    val limitFP = maker.int("fp_limit", 1)
    val fpAutoSell = maker.bool("fpAutoSell", false)
    val fpBannerPosition = maker.int("fpBannerPosition", 1)
    val fpSellDryRun = maker.bool("fpSellDryRun", FpSellPolicy.DEFAULT_DRY_RUN)

    val receiveEmbersWhenGiftBoxFull = maker.bool("receive_embers_when_gift_box_full")

    val supportSwipesPerUpdate = maker.int("support_swipes_per_update_x", 10)
    val supportMaxUpdates = maker.int("support_max_updates_x", 20)

    val minSimilarity = maker.int("min_similarity", 80)
    val mlbSimilarity = maker.int("mlb_similarity", 70)
    val stageCounterSimilarity = maker.int("stage_counter_similarity", 85)
    // Upstream's keys and defaults (#2087), so settings move between the fork and original FGA.
    val npCardTypeSimilarity = maker.int("card_type_similarity", 50)
    val npCardDetectionSimilarity = maker.int("np_card_detection_similarity", 80)
    val stageCounterNew = maker.bool("stage_counter_new")

    val skillDelay = maker.int("skill_delay", 500)
    val masterSkillMenuDelay = maker.int("master_skill_menu_delay", MASTER_SKILL_MENU_DELAY_DEFAULT_MS)
    val waitMultiplier = maker.int("wait_multiplier", 100)
    val waitBeforeTurn = maker.int("wait_before_turn", 500)
    val waitBeforeCards = maker.int("wait_before_cards", 2000)
    val waitAfterOrderChange = maker.int("wait_after_order_change", 1000)
    val servantDetailsWait = maker.int("servant_details_wait", 250)

    val clickWaitTime = maker.int("click_wait_time", 300)
    val clickDuration = maker.int("click_duration", 50)
    val clickDelay = maker.int("click_delay", 10)

    val swipeWaitTime = maker.int("swipe_wait_time", 700)
    val swipeDuration = maker.int("swipe_duration", 300)
    val swipeMultiplier = maker.int("swipe_multiplier", 100)

    val maxGoldEmberSetSize = maker.int("max_gold_ember_set_size", 1)
    val maxGoldEmberTotalCount = maker.int("max_gold_ember_total_count", 100)


    val stopAfterThisRun = maker.bool("stop_after_this_run")

    val playBtnLocation = maker.serialized(
        "play_btn_location",
        serializer = object : Serializer<Location> {
            override fun deserialize(serialized: String) =
                try {
                    val split = serialized.split(',')

                    Location(split[0].toInt(), split[1].toInt())
                } catch (e: Exception) {
                    Location()
                }

            override fun serialize(value: Location) =
                "${value.x},${value.y}"
        },
        default = Location()
    )

    val gameArea = GameAreaPrefsCore(maker)
    val gameAreaMode = gameArea.mode
    val gameOffsetLeft = gameArea.left
    val gameOffsetTop = gameArea.top
    val gameOffsetRight = gameArea.right
    val gameOffsetBottom = gameArea.bottom

    val dirRoot = maker.string("dir_root")
    val supportImagesExtractedVersion = maker.int("support_images_extracted_version")

    var showGameServer = maker.serialized(
        key = "show_game_server",
        default = listOf(GameServers.default),
        serializer = object : Serializer<List<GameServer>> {
            private val separator = ","
            override fun deserialize(serialized: String): List<GameServer> {
                val values = serialized.split(separator)
                return values.mapNotNull { GameServers.deserialize(it) }
            }

            override fun serialize(value: List<GameServer>): String = value.joinToString(separator)

        }
    )

    private val battleConfigMap = mutableMapOf<String, BattleConfigCore>()

    fun forBattleConfig(id: String): BattleConfigCore =
        battleConfigMap.getOrPut(id) {
            BattleConfigCore(
                id,
                context
            )
        }

    fun removeBattleConfig(id: String) = battleConfigMap.remove(id)

    private val perServerConfigPrefsMap = mutableMapOf<String, PerServerConfigPrefsCore>()

    fun forPerServerConfigPrefs(gameServer: GameServer): PerServerConfigPrefsCore =
        perServerConfigPrefsMap.getOrPut(gameServer.simple) {
            PerServerConfigPrefsCore(
                gameServer,
                context
            )
        }

    val servantEnhancement = ServantEnhancementPrefsCore(maker)

    /** The file of [activeSupportFilter], for the app's migration of the retired #685 switch in it. */
    val activeSupportFilterFile: android.content.SharedPreferences by lazy {
        context.getSharedPreferences(MANUAL_SUPPORT_FILTER_FILE, Context.MODE_PRIVATE)
    }

    /**
     * The conditions the support list filter applies (#695): the same items as a battle config's support settings, in
     * a file of its own so they are part of no battle config and of no battle config export. They are filled by
     * copying a battle config's or a profile's conditions in.
     */
    val activeSupportFilter: SupportPrefsCore by lazy { SupportPrefsCore(PrefMaker(activeSupportFilterFile)) }

    private val supportFilterProfileMap = mutableMapOf<String, SupportFilterProfileCore>()

    @Synchronized
    fun forSupportFilterProfile(id: String): SupportFilterProfileCore = supportFilterProfileMap.getOrPut(id) {
        SupportFilterProfileCore(id, context.getSharedPreferences(SUPPORT_FILTER_PROFILE_FILE_PREFIX + id, Context.MODE_PRIVATE))
    }

    /** A new, empty profile named [name], added to [supportFilterProfiles]. */
    @Synchronized
    fun addSupportFilterProfile(name: String, id: String = java.util.UUID.randomUUID().toString()): SupportFilterProfileCore {
        val profile = forSupportFilterProfile(id)
        profile.sharedPrefs.edit().clear().commit()
        profile.name.set(name)
        supportFilterProfiles.set(supportFilterProfiles.get() + id)
        return profile
    }

    @Synchronized
    fun removeSupportFilterProfile(id: String) {
        supportFilterProfiles.set(supportFilterProfiles.get() - id)
        forSupportFilterProfile(id).sharedPrefs.edit().clear().commit()
        supportFilterProfileMap.remove(id)
    }

    init {
        RetreatMergeMigration.run(
            done = retreatMergeMigrated,
            legacyWithdrawEnabled = legacyWithdrawEnabled,
            targets = battleConfigList.get().map {
                val config = forBattleConfig(it)
                RetreatMergeMigration.Target(config.turnLimitRetreatEnabled, config.turnLimitRetreatTurns)
            }
        )
    }
}
