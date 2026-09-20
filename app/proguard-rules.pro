# Aggressive shrinking is fine here — no reflection, no JS-interface objects,
# no third-party libraries to preserve.

# Keep WebView JavaScript interface methods IF you ever add @JavascriptInterface
# methods later. Not currently used, but left here so the app doesn't silently
# break if one is added without updating this file.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Standard WebView related warnings can be safely ignored
-dontwarn android.webkit.**

# Keep the Activity class name reachable by the manifest (R8 already respects
# manifest-referenced components, this is just an explicit safety net).
-keep class com.example.ytlite.MainActivity { *; }

-dontwarn kotlin.**
-dontwarn kotlinx.**
