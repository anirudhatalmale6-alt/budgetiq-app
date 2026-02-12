# WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# AdMob
-keep class com.google.android.gms.ads.** { *; }

# Google Play Billing
-keep class com.android.billingclient.** { *; }
-keep class com.android.vending.billing.** { *; }

# Keep app classes
-keep class com.budgetiq.app.** { *; }
