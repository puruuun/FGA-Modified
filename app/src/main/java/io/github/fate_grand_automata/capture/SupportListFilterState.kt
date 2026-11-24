package io.github.fate_grand_automata.capture

import android.content.Context
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.SupportFilterSource
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.capture.SupportListCriteria
import androidx.core.content.edit
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

    /**
     * Raised by every [request] and every [clear]. A build carries the value of its request and is applied only while
     * that is still the latest, so a build that finishes after the switch went off, after a newer build was asked
     * for, or after the watcher was replaced, changes nothing.
     */
    private var generation = 0L

    /** Null relays every byte as received. */
    fun criteria(): SupportListCriteria? = active

    /** Stops the filter at once. A build still running is dropped when it finishes. */
    fun clear() = synchronized(lock) {
        generation++
        set(null)
    }

    /** The token of one build; a later [request] or [clear] makes it stale. */
    fun request(): Long = synchronized(lock) { ++generation }

    /**
     * Applies [build] only when [token] is still the latest and [switchesOn] (the feature switch and the toggle, read
     * now, under the lock) holds. The proxy gets criteria only from a ready build with a condition set.
     * @return whether [build] was applied.
     */
    fun offer(token: Long, build: SupportFilterBuild, switchesOn: () -> Boolean): Boolean = synchronized(lock) {
        if (token != generation || !switchesOn()) return@synchronized false
        set(build.applied)
        true
    }

    private fun set(next: SupportListCriteria?) {
        if (next != active) {
            active = next
            // A result of other criteria, or of a filter that stopped, must not stay on screen.
            SupportListLastResult.clear()
        }
    }

    internal fun resetForTesting() = clear()
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

    /** One per pass of the watcher, built or not. */
    internal val passesForTesting = java.util.concurrent.atomic.AtomicInteger()

    /** A test's slow or fixed build in place of the one that reads the files on disk. */
    @Volatile
    internal var buildForTesting: ((SupportPrefsCore) -> SupportFilterBuild)? = null

    @OptIn(FlowPreview::class)
    @Synchronized
    fun init(context: Context, prefs: PrefsCore) {
        val app = context.applicationContext
        // A cancelled watcher can still be inside a build, which does not stop at a cancel; the new watcher's first
        // request makes that build's result stale.
        watcher?.cancel()
        watcher = scope.launch {
            runCatching { SupportFilterMigration.run(prefs, SupportIdLookup.load(app)) }
                .onFailure { Timber.w(it, "Support list filter: migration failed") }
            runCatching { SupportFilterToggleMigration.run(prefs, app.getString(R.string.support_filter_profile_migrated)) }
                .onFailure { Timber.w(it, "Support list filter: toggle migration failed") }
            val active = prefs.activeSupportFilter
            val switchesOn = { prefs.supportFilterFeature.get() && prefs.supportFilterOn.get() }
            val switches = combine(prefs.supportFilterFeature.asFlow(), prefs.supportFilterOn.asFlow()) { f, t -> f && t }
                .distinctUntilChanged()
            // Apart from the builds and not debounced: switching off clears at once, even while a build is running.
            launch { switches.collect { on -> if (!on) SupportListFilterState.clear() } }
            combine(switches, SupportFilterSettings.changes(active).debounce(200)) { on, _ -> on }
                .collectLatest { on ->
                    if (on) {
                        val token = SupportListFilterState.request()
                        SupportListFilterState.offer(token, build(app, active), switchesOn)
                    }
                    passesForTesting.incrementAndGet()
                }
        }
    }

    private fun build(context: Context, config: SupportPrefsCore): SupportFilterBuild = try {
        buildForTesting?.invoke(config) ?: buildSupportFilter(SupportFilterSettings.of(config), SupportIdLookup.load(context))
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

    /** A test stops the run after this step, as a process that dies there would. */
    internal var stopAfterStepForTesting = Int.MAX_VALUE

    private class Stopped : RuntimeException()

    /**
     * The new values go to disk first, then the retired keys are removed, then the run is marked done; each step is
     * committed before the next. A run that stopped half way therefore runs again from the start and ends as an
     * uninterrupted run does: what is gone with the retired keys was already written in its new place.
     * @return true when this call ran the migration.
     */
    fun run(prefs: PrefsCore, profileName: String): Boolean {
        if (prefs.supportFilterToggleMigrated.get()) return false
        val key = PrefsCore.LEGACY_SUPPORT_LIST_FILTER_KEY
        val activeFile = prefs.activeSupportFilterFile
        val manualKnown = activeFile.contains(key)
        val manualOn = activeFile.getBoolean(key, false)
        // A filter that was never edited holds no condition key; it is not worth a profile.
        val hasConditions = activeFile.all.keys.any { it != key }
        val configs = prefs.battleConfigList.get().map { prefs.forBattleConfig(it).sharedPrefs }
        val anyConfigOn = configs.any { it.getBoolean(key, false) }
        fun step(n: Int) { if (n >= stopAfterStepForTesting) throw Stopped() }

        if (hasConditions) {
            // A fixed id, so a run that runs again replaces this profile instead of adding one.
            val profile = prefs.addSupportFilterProfile(profileName, PROFILE_ID)
            profile.support.copyConditionsFrom(prefs.activeSupportFilter)
            profile.commit()
            prefs.supportFilterSourceName.set(profileName)
            prefs.supportFilterSource.set(SupportFilterSource.Profile)
        }
        // Only from the retired key: a run after it was removed must not turn the toggle off again.
        if (manualKnown) prefs.supportFilterOn.set(manualOn)
        if (manualOn || anyConfigOn) prefs.supportFilterFeature.set(true)
        prefs.commitMainFile()
        step(1)
        for (config in configs) {
            if (config.contains(key)) config.edit(commit = true) { remove(key) }
        }
        step(2)
        if (activeFile.contains(key)) activeFile.edit(commit = true) { remove(key) }
        step(3)
        prefs.supportFilterToggleMigrated.set(true)
        prefs.commitMainFile()
        return true
    }
}
