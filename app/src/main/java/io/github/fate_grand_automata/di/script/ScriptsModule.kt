package io.github.fate_grand_automata.di.script

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.fate_grand_automata.accessibility.AccessibilityGestures
import io.github.fate_grand_automata.sensing.supportScoreEngine
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import io.github.fate_grand_automata.imaging.TesseractOcrService
import io.github.fate_grand_automata.util.StorageReaderDiagnostics
import io.github.fate_grand_automata.scripts.FgoAutomataApi
import io.github.fate_grand_automata.scripts.FgoGameAreaManager
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.diagnostics.IReaderDiagnostics
import io.github.fate_grand_automata.scripts.locations.IScriptAreaTransforms
import io.github.fate_grand_automata.scripts.locations.ScriptAreaTransforms
import io.github.fate_grand_automata.scripts.modules.RealSupportScreen
import io.github.fate_grand_automata.scripts.modules.SupportScreen
import io.github.fate_grand_automata.scripts.supportSelection.ISupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreReader
import io.github.lib_automata.GameAreaManager
import io.github.lib_automata.GestureService
import io.github.lib_automata.OcrService
import io.github.lib_automata.PlatformImpl
import io.github.lib_automata.dagger.ScriptScope

@Module
@InstallIn(ScriptComponent::class)
abstract class ScriptsModule {
    @ScriptScope
    @Binds
    abstract fun bindCaptureFeed(feed: io.github.fate_grand_automata.capture.CaptureBattleFeed): io.github.fate_grand_automata.scripts.capture.ICaptureFeed
    companion object {
        @ScriptScope
        @Provides
        fun provideGameAreaManager(platformImpl: PlatformImpl): GameAreaManager =
            FgoGameAreaManager(
                gameSizeWithBorders = platformImpl.windowRegion.size,
                offset = { platformImpl.windowRegion.location }
            )

        // Issue #568: one engine per run, unloaded by ScriptManager when the run's component ends.
        @ScriptScope
        @Provides
        fun provideSupportScoreEngine(@ApplicationContext context: Context): SupportScoreEngine =
            supportScoreEngine(context)
    }

    @ScriptScope
    @Binds
    abstract fun api(api: FgoAutomataApi): IFgoAutomataApi

    @ScriptScope
    @Binds
    abstract fun gestures(gestures: AccessibilityGestures): GestureService

    @ScriptScope
    @Binds
    abstract fun scriptAreaTransforms(scriptAreaTransforms: ScriptAreaTransforms): IScriptAreaTransforms

    @ScriptScope
    @Binds
    abstract fun supportScreen(screen: RealSupportScreen): SupportScreen

    @ScriptScope
    @Binds
    abstract fun bindOcrService(ocrService: TesseractOcrService): OcrService

    @ScriptScope
    @Binds
    abstract fun bindWeeklyRouteSession(session: io.github.fate_grand_automata.runner.WeeklyRouteSession): io.github.fate_grand_automata.scripts.weekly.IWeeklyRouteSession

    @ScriptScope
    @Binds
    abstract fun bindPatternFileLoader(loader: io.github.fate_grand_automata.imaging.PatternFileLoader): io.github.fate_grand_automata.scripts.weekly.IPatternFileLoader

    @ScriptScope
    @Binds
    abstract fun bindReaderDiagnostics(diagnostics: StorageReaderDiagnostics): IReaderDiagnostics

    // Binds to the one scoped SupportScoreReader so a script run still has a single instance.
    @ScriptScope
    @Binds
    abstract fun bindSupportScoreReader(reader: SupportScoreReader): ISupportScoreReader

    @ScriptScope
    @Binds
    abstract fun bindWeeklyPanelDiagnostics(diagnostics: io.github.fate_grand_automata.util.WeeklyPanelDiagnostics): io.github.fate_grand_automata.scripts.weekly.IWeeklyPanelDiagnostics
}
