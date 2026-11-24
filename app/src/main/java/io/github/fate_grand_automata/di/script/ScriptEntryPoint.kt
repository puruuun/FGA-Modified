package io.github.fate_grand_automata.di.script

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import io.github.fate_grand_automata.scripts.entrypoints.*
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import io.github.lib_automata.OcrService

@EntryPoint
@InstallIn(ScriptComponent::class)
interface ScriptEntryPoint {
    fun battle(): AutoBattle
    fun fp(): AutoFriendGacha
    fun giftBox(): AutoGiftBox
    fun lottery(): AutoLottery
    fun supportImageMaker(): SupportImageMaker
    fun ceEnhance(): AutoCEEnhance

    fun servantLevel(): AutoServantLevel

    fun skillLevel(): AutoSkillLevel

    fun appendLevel(): AutoAppendLevel

    fun weeklyRoute(): AutoWeeklyRoute

    fun autoDetect(): AutoDetect

    /** Exposed so [ScriptComponent]'s OCR engine can be released when the run ends. */
    fun ocrService(): OcrService

    /** Exposed, as [ocrService], so the run's Rust engine (issue #568) is unloaded when the run ends. */
    fun supportScoreEngine(): SupportScoreEngine
}
