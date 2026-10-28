package io.github.fate_grand_automata

import io.github.fate_grand_automata.ui.prefs.resolveApplicationLanguage
import java.util.Locale
import kotlin.test.*

class ApplicationLanguageTest {
    @Test fun `offered system languages resolve to the dropdown keys`() {
        for ((tag, expected) in mapOf("ko-KR" to "ko", "ko" to "ko", "ja-JP" to "ja",
            "vi-VN" to "vi", "en-US" to "en", "fr-FR" to "en", "und" to "en")) {
            assertEquals(expected, resolveApplicationLanguage(Locale.forLanguageTag(tag)), tag)
        }
    }

    @Test fun `Chinese script wins over region and region is used when script is absent`() {
        for ((tag, expected) in mapOf("zh-Hans" to "zh-CN", "zh-Hant" to "zh-TW",
            "zh-Hans-TW" to "zh-CN", "zh-Hant-CN" to "zh-TW", "zh-CN" to "zh-CN",
            "zh-SG" to "zh-CN", "zh-TW" to "zh-TW", "zh-HK" to "zh-TW", "zh-MO" to "zh-TW",
            "zh" to "en", "zh-US" to "en")) {
            assertEquals(expected, resolveApplicationLanguage(Locale.forLanguageTag(tag)), tag)
        }
    }
}
