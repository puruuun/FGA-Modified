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
