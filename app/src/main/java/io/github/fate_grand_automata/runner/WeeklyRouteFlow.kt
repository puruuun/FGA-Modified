package io.github.fate_grand_automata.runner

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.scripts.weekly.AtlasRouteRepository
import io.github.fate_grand_automata.scripts.weekly.WeeklyRouteRun
import io.github.fate_grand_automata.util.WEEKLY_ROUTE_TAG
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/** Hands the route confirmed in the launcher panel to the next script run. */
@ServiceScoped
class WeeklyRouteFlow @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: IPreferences,
    private val session: WeeklyRouteSession
) {
    private fun log(message: String) = Timber.tag(WEEKLY_ROUTE_TAG).i(message)

    /** Quests whose panel an earlier probe found; kept per server so a rerun can skip them. */
    private fun succeededFile(server: String) = File(context.filesDir, "weekly-probe-succeeded-$server.txt")
    fun probeSucceeded(server: String): Set<Int> = runCatching {
        succeededFile(server).takeIf { it.exists() }?.readLines()?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
    }.getOrNull().orEmpty()
    fun rememberProbeSucceeded(server: String, ids: Collection<Int>) {
        if (ids.isEmpty()) return
        runCatching { succeededFile(server).writeText((probeSucceeded(server) + ids).sorted().joinToString("\n")) }
    }

    /** D-25: the probe run of [items] (one visit each, no AP). Called by the launcher's shared OK in probe mode. */
    fun prepareProbe(data: AtlasRouteRepository.Dataset, items: List<io.github.fate_grand_automata.scripts.weekly.RouteItem>, skipSucceeded: Boolean) {
        val done = if (skipSucceeded) probeSucceeded(data.server) else emptySet()
        val todo = items.filter { it.quest.id !in done }
        session.run = WeeklyRouteRun(data.server, todo, emptyList(), data.wars, File(context.cacheDir, "atlas-weekly"), data.questNames, probe = true)
        log("mode=probe confirmed server=${data.server} quests=${todo.size} skippedSucceeded=${items.size - todo.size}")
    }

    /** Called only by the launcher's shared OK, after its left-side config was saved. */
    fun prepare(data: AtlasRouteRepository.Dataset, items: List<io.github.fate_grand_automata.scripts.weekly.RouteItem>, manual: List<String>) {
        require(items.isNotEmpty() && items.all { it.count > 0 })
        session.run = WeeklyRouteRun(data.server, items, manual, data.wars, File(context.cacheDir, "atlas-weekly"), data.questNames)
        log("confirmed server=${data.server} quests=${items.size} config=${preferences.selectedBattleConfig.id} " +
            items.joinToString(" ") { "${it.quest.id}x${it.count}" })
    }

}
