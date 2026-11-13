-keepattributes LineNumberTable,SourceFile
-keep class org.opencv.core.CvException { *; }

# Enum constants go into SharedPreferences by name and come back through enumValueOf,
# so their names have to survive obfuscation.
-keep class io.github.fate_grand_automata.scripts.enums.* { *; }

# Glance instantiates action callbacks by class name and no-argument constructor.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# Glance maps each receiver to its widget by the GlanceAppWidget class name and keys sessions by it.
# R8 merged ServiceWidget into ApWidget's class in 1.8.0-beta.15, so both widgets shared one name and
# the service widget's content was drawn into the AP widget. Keep every widget class whole and named.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { <init>(); }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { <init>(); }

# The AP widget is a plain AppWidgetProvider (Glance cannot host a StackView adapter), and its stop tile fires an
# explicit broadcast. The system creates both by class name, so neither may be renamed or merged.
-keep class * extends android.appwidget.AppWidgetProvider { <init>(); }
-keep class io.github.fate_grand_automata.widget.StopServiceReceiver { <init>(); }
-keep class io.github.fate_grand_automata.widget.ApWidgetNavReceiver { <init>(); }
-keep class io.github.fate_grand_automata.widget.ToggleCaptureReceiver { <init>(); }
-keep class io.github.fate_grand_automata.widget.WidgetRestoreReceiver { <init>(); }

# Chaldea sync: Shizuku starts ChaldeaSyncService by class name in a separate shell-uid process that loads this
# APK's dex. Keep the service, its AIDL stub, and the pure scripts classes it runs there (the state parser, the
# file engine, the editor, the patch and result types) by name, so nothing it needs is renamed or merged away.
-keep class io.github.fate_grand_automata.capture.chaldea.ChaldeaSyncService { *; }
-keep class io.github.fate_grand_automata.capture.chaldea.IChaldeaSyncService { *; }
-keep class io.github.fate_grand_automata.capture.chaldea.IChaldeaSyncService$Stub { *; }
-keep class io.github.fate_grand_automata.capture.chaldea.IChaldeaSyncService$Stub$Proxy { *; }
-keep class io.github.fate_grand_automata.scripts.capture.chaldea.** { *; }

# The Chaldea sync keeps its settings collector in write-only fields so it stays reachable. R8 would delete them,
# and the collector (held only through a weak SharedPreferences listener) would be garbage collected (#358).
-keepclassmembers class io.github.fate_grand_automata.capture.chaldea.ChaldeaSyncManager {
    private kotlinx.coroutines.CoroutineScope settingsScope;
    private kotlinx.coroutines.Job settingsJob;
}

# Touch separation spike (#387): Shizuku starts TouchInjectorService by class name in a shell process and libsu
# starts TouchRootService in a root process. Keep the services and AIDL stub intact.
-keep class io.github.fate_grand_automata.touch.TouchInjector { *; }
-keep class io.github.fate_grand_automata.touch.TouchInjectorService { *; }
-keep class io.github.fate_grand_automata.touch.TouchRootService { *; }
-keep class io.github.fate_grand_automata.touch.ITouchInjector { *; }
-keep class io.github.fate_grand_automata.touch.ITouchInjector$Stub { *; }
-keep class io.github.fate_grand_automata.touch.ITouchInjector$Stub$Proxy { *; }
