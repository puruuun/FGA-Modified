package io.github.fate_grand_automata.capture

import android.content.Context
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.capture.SupportListCriteria
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Which support list criteria the capture proxy applies (#685). While a script runs, only its battle config decides;
 * otherwise the manual filter does. The proxy thread reads [criteria], a volatile reference to a ready object; every
 * write happens here, under one lock, from the script hooks or the manual filter's watcher.
 */
object SupportListFilterState {
    private val lock = Any()

    @Volatile
    private var active: SupportListCriteria? = null

    private var manual: SupportFilterBuild = SupportFilterBuild.Off
    private var script: SupportFilterBuild = SupportFilterBuild.Off
    private var scriptRunning = false

    // A build that finishes after its script ended, or after the next script started, must not apply.
    private var scriptToken = 0L

    /** What the proxy applies to the next follower/list; null relays every byte as received. */
    fun criteria(): SupportListCriteria? = active

    fun setManual(build: SupportFilterBuild) = synchronized(lock) {
        manual = build
        update()
    }

    /** From now on until [endScript], the manual filter is not applied; the script's build arrives through [setScript]. */
    fun beginScript(): Long = synchronized(lock) {
        scriptToken++
        scriptRunning = true
        script = SupportFilterBuild.Off
        update()
        scriptToken
    }

    fun setScript(token: Long, build: SupportFilterBuild) = synchronized(lock) {
        if (!scriptRunning || token != scriptToken) return@synchronized
        script = build
        update()
    }

    /** Back to the manual filter. */
    fun endScript() = synchronized(lock) {
        scriptToken++
        scriptRunning = false
        script = SupportFilterBuild.Off
        update()
    }

    private fun update() {
        val next = (if (scriptRunning) script else manual).applied
        if (next != active) {
            active = next
            // A result of other criteria, or of a filter that stopped, must not stay on screen.
            SupportListLastResult.clear()
        }
    }

    internal fun resetForTesting() = synchronized(lock) {
        scriptToken = 0
        scriptRunning = false
        manual = SupportFilterBuild.Off
        script = SupportFilterBuild.Off
        active = null
    }
}

/**
 * Builds the criteria off the proxy thread: once for the manual filter at app start and again whenever one of its
 * settings changes, and once per script start for the running battle config.
 */
object SupportListFilterController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    // One watcher per process; a second init (a new Application in a test run) replaces it instead of piling up.
    private var watcher: Job? = null

    internal val watcherForTesting: Job? get() = watcher
    internal val manualBuildsForTesting = java.util.concurrent.atomic.AtomicInteger()

    @OptIn(FlowPreview::class)
    @Synchronized
    fun init(context: Context, prefs: PrefsCore) {
        val app = context.applicationContext
        appContext = app
        watcher?.cancel()
        watcher = scope.launch {
            runCatching { SupportFilterMigration.run(prefs, SupportIdLookup.load(app)) }
                .onFailure { Timber.w(it, "Support list filter: migration failed") }
            val manual = prefs.manualSupportFilter
            SupportFilterSettings.changes(manual).debounce(200).collectLatest {
                SupportListFilterState.setManual(build(app, manual, script = false))
                manualBuildsForTesting.incrementAndGet()
            }
        }
    }

    /** [config] is the running battle config's support settings, or null for a script without one. */
    fun onScriptStart(config: SupportPrefsCore?) {
        val token = SupportListFilterState.beginScript()
        val context = appContext ?: return
        if (config == null) return
        scope.launch { SupportListFilterState.setScript(token, build(context, config, script = true)) }
    }

    fun onScriptEnd() = SupportListFilterState.endScript()

    private fun build(context: Context, config: SupportPrefsCore, script: Boolean): SupportFilterBuild = try {
        val settings = SupportFilterSettings.of(config, script)
        if (!settings.enabled || !settings.preferredMode) SupportFilterBuild.Off
        else buildSupportFilter(settings, SupportIdLookup.load(context))
    } catch (e: Exception) {
        Timber.w(e, "Support list filter: criteria not built")
        SupportFilterBuild.Off
    }
}

/**
 * Moves the servant and CE ids of the retired experiment (#636) into the manual filter, once. An id becomes the
 * built-in image name, a folder name that maps back to it, or an id entry when neither is known. The experiment's
 * switch is not carried over: the manual filter starts off, like every new switch.
 */
object SupportFilterMigration {
    /** @return true when this call ran the migration. */
    fun run(prefs: PrefsCore, lookup: SupportIdLookup): Boolean {
        if (prefs.supportFilterMigrated.get()) return false
        val servants = prefs.legacySupportFilterServants.get().mapNotNull { it.toLongOrNull() }.filter { it > 0 }
        val ces = prefs.legacySupportFilterCes.get().mapNotNull { it.toLongOrNull() }.filter { it > 0 }
        val manual = prefs.manualSupportFilter
        if (servants.isNotEmpty()) manual.preferredServants.set(manual.preferredServants.get() + servants.map(lookup::servantEntry))
        if (ces.isNotEmpty()) manual.preferredCEs.set(manual.preferredCEs.get() + ces.map(lookup::ceEntry))
        prefs.legacySupportFilterEnabled.resetToDefault()
        prefs.legacySupportFilterServants.resetToDefault()
        prefs.legacySupportFilterCes.resetToDefault()
        prefs.supportFilterMigrated.set(true)
        return true
    }
}
