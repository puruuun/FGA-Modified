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
 * Issue #568: a script only the owner's rust build type offers (the support score scan). Its
 * implementations, strings and Hilt bindings live in the `rust` source set, so no other build
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

/** Owner decision (issue #568): users of the public app see nothing of the Rust engine. */
fun ownerTestScriptsFor(rustTestBuild: Boolean, provide: () -> Set<OwnerTestScript>): List<OwnerTestScript> =
    if (rustTestBuild) provide().toList() else emptyList()

class OwnerTestScripts @Inject constructor(private val all: Provider<Set<@JvmSuppressWildcards OwnerTestScript>>) {
    val scripts: List<OwnerTestScript> by lazy { ownerTestScriptsFor(BuildConfig.RUST_TEST_BUILD) { all.get() } }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class OwnerTestScriptsModule {
    @Multibinds
    abstract fun ownerTestScripts(): Set<@JvmSuppressWildcards OwnerTestScript>
}
