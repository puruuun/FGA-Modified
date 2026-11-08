-keepattributes LineNumberTable,SourceFile
-keep class org.opencv.core.CvException { *; }

# Enum constants go into SharedPreferences by name and come back through enumValueOf,
# so their names have to survive obfuscation.
-keep class io.github.fate_grand_automata.scripts.enums.* { *; }

# Glance instantiates action callbacks by class name and no-argument constructor.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
