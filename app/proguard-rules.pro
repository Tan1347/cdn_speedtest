-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# XZ/LZMA compression library
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**
