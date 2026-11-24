package io.github.fate_grand_automata.runner

import android.content.Context
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.di.script.ScriptComponent
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.lib_automata.EntryPoint
import javax.inject.Inject
import javax.inject.Provider

/**
 * Issue #568 and design 0026 PR C: a script only the beta and rust build types offer (the support
 * score scan). Its implementations, strings and Hilt bindings live in `src/scoreScan`, which
 * app/build.gradle.kts adds exactly to the variants with [BuildConfig.SCORE_SCAN], so no other build
 * compiles them; there the set below is empty and [OwnerTestScripts] never even asks for it.
 */
interface OwnerTestScript {
    /** Whether the launcher of the detected [mode] offers it. */
    fun offeredFor(mode: ScriptModeEnum): Boolean

    fun label(context: Context): String

    fun entryPoint(component: ScriptComponent): EntryPoint

    /** The text shown when its run ends with [e], or null when [e] is not its own. */
    fun exitMessage(context: Context, e: Exception): String?
}

/**
 * Owner decisions (issue #568, design 0026): the stable app offers none of the owner's scripts; a
 * build with [BuildConfig.SCORE_SCAN] (the beta and rust build types with the engine) offers them.
 */
fun ownerTestScriptsFor(scoreScan: Boolean, provide: () -> Set<OwnerTestScript>): List<OwnerTestScript> =
    if (scoreScan) provide().toList() else emptyList()

class OwnerTestScripts @Inject constructor(private val all: Provider<Set<@JvmSuppressWildcards OwnerTestScript>>) {
    val scripts: List<OwnerTestScript> by lazy { ownerTestScriptsFor(BuildConfig.SCORE_SCAN) { all.get() } }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class OwnerTestScriptsModule {
    @Multibinds
    abstract fun ownerTestScripts(): Set<@JvmSuppressWildcards OwnerTestScript>
}
