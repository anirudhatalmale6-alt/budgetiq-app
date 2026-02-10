# WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# AdMob
-keep class com.google.android.gms.ads.** { *; }

# Keep app classes
-keep class com.budgetiq.app.** { *; }
