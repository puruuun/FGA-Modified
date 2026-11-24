package io.github.fate_grand_automata.capture

import android.content.Context
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.SupportFilterSource
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.capture.SupportListCriteria
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * What the capture proxy applies to the next follower/list (#695). Owner decision: one filter, switched by hand;
 * a script start or end changes nothing. The proxy thread reads [criteria], a volatile reference to a ready object;
 * every write happens here, under one lock, from the prefs watcher in [SupportListFilterController].
 */
object SupportListFilterState {
    private val lock = Any()

    @Volatile
    private var active: SupportListCriteria? = null

    /** Null relays every byte as received. */
    fun criteria(): SupportListCriteria? = active

    /**
     * [build] is the build of the active conditions, or [SupportFilterBuild.Off] when they were not built. The proxy
     * gets criteria only when the feature switch and the toggle are both on and the build is ready.
     */
    fun update(featureOn: Boolean, toggleOn: Boolean, build: SupportFilterBuild) = synchronized(lock) {
        val next = if (featureOn && toggleOn) build.applied else null
        if (next != active) {
            active = next
            // A result of other criteria, or of a filter that stopped, must not stay on screen.
            SupportListLastResult.clear()
        }
    }

    internal fun resetForTesting() = synchronized(lock) { active = null }
}

/**
 * Builds the criteria off the proxy thread, at app start and again whenever the feature switch, the toggle or one of
 * the active conditions changes.
 */
object SupportListFilterController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One watcher per process; a second init (a new Application in a test run) replaces it instead of piling up.
    private var watcher: Job? = null

    internal val watcherForTesting: Job? get() = watcher
    internal val buildsForTesting = java.util.concurrent.atomic.AtomicInteger()

    @OptIn(FlowPreview::class)
    @Synchronized
    fun init(context: Context, prefs: PrefsCore) {
        val app = context.applicationContext
        watcher?.cancel()
        watcher = scope.launch {
            runCatching { SupportFilterMigration.run(prefs, SupportIdLookup.load(app)) }
                .onFailure { Timber.w(it, "Support list filter: migration failed") }
            runCatching { SupportFilterToggleMigration.run(prefs, app.getString(R.string.support_filter_profile_migrated)) }
                .onFailure { Timber.w(it, "Support list filter: toggle migration failed") }
            val active = prefs.activeSupportFilter
            // Not debounced, so switching off stops the filter at once.
            val switches = combine(prefs.supportFilterFeature.asFlow(), prefs.supportFilterOn.asFlow()) { f, t -> f to t }
                .distinctUntilChanged()
            combine(switches, SupportFilterSettings.changes(active).debounce(200)) { s, _ -> s }
                .collectLatest { (featureOn, toggleOn) ->
                    val build = if (featureOn && toggleOn) build(app, active) else SupportFilterBuild.Off
                    SupportListFilterState.update(featureOn, toggleOn, build)
                    buildsForTesting.incrementAndGet()
                }
        }
    }

    private fun build(context: Context, config: SupportPrefsCore): SupportFilterBuild = try {
        buildSupportFilter(SupportFilterSettings.of(config), SupportIdLookup.load(context))
    } catch (e: Exception) {
        Timber.w(e, "Support list filter: criteria not built")
        SupportFilterBuild.Off
    }
}

/** Copies [from]'s conditions into the active filter (#695): a snapshot, so a later change to [from] is not applied. */
fun PrefsCore.loadSupportFilter(from: SupportPrefsCore, source: SupportFilterSource, name: String) {
    activeSupportFilter.copyConditionsFrom(from)
    supportFilterSourceName.set(name)
    supportFilterSource.set(source)
}

/**
 * Moves the servant and CE ids of the retired experiment (#636) into the active filter, once. An id becomes the
 * built-in image name, a folder name that maps back to it, or an id entry when neither is known. The experiment's
 * switch is not carried over.
 */
object SupportFilterMigration {
    /** @return true when this call ran the migration. */
    fun run(prefs: PrefsCore, lookup: SupportIdLookup): Boolean {
        if (prefs.supportFilterMigrated.get()) return false
        val servants = prefs.legacySupportFilterServants.get().mapNotNull { it.toLongOrNull() }.filter { it > 0 }
        val ces = prefs.legacySupportFilterCes.get().mapNotNull { it.toLongOrNull() }.filter { it > 0 }
        val manual = prefs.activeSupportFilter
        if (servants.isNotEmpty()) manual.preferredServants.set(manual.preferredServants.get() + servants.map(lookup::servantEntry))
        if (ces.isNotEmpty()) manual.preferredCEs.set(manual.preferredCEs.get() + ces.map(lookup::ceEntry))
        prefs.legacySupportFilterEnabled.resetToDefault()
        prefs.legacySupportFilterServants.resetToDefault()
        prefs.legacySupportFilterCes.resetToDefault()
        prefs.supportFilterMigrated.set(true)
        return true
    }
}

/**
 * From the #685 switches to the #695 toggle, once. The manual filter's conditions stay the active conditions and are
 * also kept as a profile; its switch becomes the toggle. The feature switch is turned on only for a user who had
 * switched the filter on somewhere (the manual switch or any battle config's), so that user keeps seeing it and
 * everyone else keeps the launcher as it was. The per-config switch is removed from every battle config's file; their
 * other support settings are not touched.
 */
object SupportFilterToggleMigration {
    const val PROFILE_ID = "migrated"

    /** @return true when this call ran the migration. */
    fun run(prefs: PrefsCore, profileName: String): Boolean {
        if (prefs.supportFilterToggleMigrated.get()) return false
        val key = PrefsCore.LEGACY_SUPPORT_LIST_FILTER_KEY
        val activeFile = prefs.activeSupportFilterFile
        val manualOn = activeFile.getBoolean(key, false)
        // A filter that was never edited holds no condition key; it is not worth a profile.
        val hasConditions = activeFile.all.keys.any { it != key }
        val configs = prefs.battleConfigList.get().map { prefs.forBattleConfig(it).sharedPrefs }
        val anyConfigOn = configs.any { it.getBoolean(key, false) }

        if (hasConditions) {
            // A fixed id, so a run that stopped half way and runs again replaces this profile instead of adding one.
            val profile = prefs.addSupportFilterProfile(profileName, PROFILE_ID)
            profile.support.copyConditionsFrom(prefs.activeSupportFilter)
            prefs.supportFilterSourceName.set(profileName)
            prefs.supportFilterSource.set(SupportFilterSource.Profile)
        }
        prefs.supportFilterOn.set(manualOn)
        if (manualOn || anyConfigOn) prefs.supportFilterFeature.set(true)
        for (config in configs) {
            if (config.contains(key)) config.edit().remove(key).commit()
        }
        if (activeFile.contains(key)) activeFile.edit().remove(key).commit()
        prefs.supportFilterToggleMigrated.set(true)
        return true
    }
}
