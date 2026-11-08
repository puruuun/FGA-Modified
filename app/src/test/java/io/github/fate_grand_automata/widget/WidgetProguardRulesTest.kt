package io.github.fate_grand_automata.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Release and beta builds are minified, unit tests are not. These rules are what keeps the widgets
 * working there: 1.8.0-beta.14 lost the stop callback's constructor and 1.8.0-beta.15 merged
 * ServiceWidget into ApWidget. The real check is the R8 mapping of a beta build.
 */
class WidgetProguardRulesTest {
    private val rules = File("proguard-rules.pro").readText()

    @Test fun actionCallbacksKeepTheirConstructor() {
        assertTrue(rules.contains("-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }"))
    }

    @Test fun widgetClassesAreNeitherRenamedNorMerged() {
        assertTrue(rules.contains("-keep class * extends androidx.glance.appwidget.GlanceAppWidget { <init>(); }"))
        assertTrue(rules.contains("-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { <init>(); }"))
    }

    @Test fun plainAppWidgetProviderAndStopReceiverKeepTheirNames() {
        assertTrue(rules.contains("-keep class * extends android.appwidget.AppWidgetProvider { <init>(); }"))
        assertTrue(rules.contains("-keep class io.github.fate_grand_automata.widget.StopServiceReceiver { <init>(); }"))
    }

    @Test fun stopReceiverIsNotExported() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val block = manifest.substringAfter("android:name=\".widget.StopServiceReceiver\"").substringBefore("/>")
        assertTrue(block.contains("android:exported=\"false\""))
    }
}
