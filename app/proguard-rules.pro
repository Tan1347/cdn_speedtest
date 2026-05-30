-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# XZ/LZMA compression library
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# Remove debug/verbose Log calls in release
-assumenosideeffects public class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Remove Compose debug tooling traces
-assumenosideeffects public class androidx.compose.runtime.ComposerKt {
    public static void *Trace*();
    public static void *sourceInformation*();
    public static void *sourceInformationMarkerStart*();
    public static void *sourceInformationMarkerEnd*();
}

# Remove kotlinx.serialization metadata
-dontwarn kotlinx.serialization.**
