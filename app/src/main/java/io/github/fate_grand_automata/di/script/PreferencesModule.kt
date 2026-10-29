package io.github.fate_grand_automata.di.script

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferencesCommon
import io.github.lib_automata.dagger.ScriptScope

@Module
@InstallIn(ScriptComponent::class)
class PreferencesModule {
    @ScriptScope
    @Provides
    fun provideAddedFeatureTuning(prefs: PrefsCore): IAddedFeatureTuning =
        prefs.addedFeatureTuning.snapshot()

    @ScriptScope
    @Provides
    fun provideBattleConfig(prefs: IPreferences): IBattleConfig =
        prefs.selectedBattleConfig

    @ScriptScope
    @Provides
    fun provideSupportPrefs(battleConfig: IBattleConfig): ISupportPreferences =
        battleConfig.support

    @ScriptScope
    @Provides
    fun provideCommonSupportPrefs(prefs: IPreferences): ISupportPreferencesCommon =
        prefs.support

}
