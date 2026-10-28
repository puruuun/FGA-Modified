package io.github.fate_grand_automata.ui.prefs

import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Canonical keys shared by settings, onboarding and the saved initial app locale. */
fun resolveApplicationLanguage(locale: Locale): String = when (locale.language) {
    "ko", "ja", "vi" -> locale.language
    "zh" -> when (locale.script) {
        "Hans" -> "zh-CN"
        "Hant" -> "zh-TW"
        else -> when (locale.country) {
            "CN", "SG" -> "zh-CN"
            "TW", "HK", "MO" -> "zh-TW"
            else -> "en"
        }
    }
    else -> "en"
}

fun systemApplicationLanguage(): String = resolveApplicationLanguage(Resources.getSystem().configuration.locales[0])

/** Called after AppCompat has restored any saved locale, so an existing choice is preserved. */
fun initializeApplicationLanguage() {
    if (AppCompatDelegate.getApplicationLocales().isEmpty) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(systemApplicationLanguage()))
    }
}
