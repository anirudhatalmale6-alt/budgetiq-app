package com.budgetiq.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.appopen.AppOpenAd;

import java.util.Date;

/**
 * Manages App Open Ads - shown when user returns to the app after being away.
 * Shows at most once per 4 hours to avoid being annoying.
 */
public class AppOpenAdManager implements Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    private static final String TAG = "AppOpenAdManager";
    private static final String AD_UNIT_ID = "ca-app-pub-6353903215132082/6486228008";
    private static final long MIN_INTERVAL_MS = 4 * 60 * 60 * 1000; // 4 hours between ads
    private static final long AD_EXPIRY_MS = 4 * 60 * 60 * 1000; // Ad expires after 4 hours

    private final Application application;
    private AppOpenAd appOpenAd = null;
    private boolean isShowingAd = false;
    private boolean isLoadingAd = false;
    private Activity currentActivity;
    private long loadTime = 0;
    private long lastShownTime = 0;
    private boolean isFirstLaunch = true;

    public AppOpenAdManager(Application application) {
        this.application = application;
        application.registerActivityLifecycleCallbacks(this);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
    }

    /**
     * Load an App Open Ad.
     */
    private void loadAd() {
        if (isLoadingAd || isAdAvailable()) return;
        isLoadingAd = true;

        AppOpenAd.load(application, AD_UNIT_ID,
                new AdRequest.Builder().build(),
                new AppOpenAd.AppOpenAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull AppOpenAd ad) {
                        appOpenAd = ad;
                        isLoadingAd = false;
                        loadTime = new Date().getTime();
                        Log.d(TAG, "App Open Ad loaded");
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        isLoadingAd = false;
                        Log.e(TAG, "App Open Ad failed to load: " + loadAdError.getMessage());
                    }
                });
    }

    private boolean isAdAvailable() {
        return appOpenAd != null && !isAdExpired();
    }

    private boolean isAdExpired() {
        long now = new Date().getTime();
        return (now - loadTime) > AD_EXPIRY_MS;
    }

    /**
     * Show app open ad if available and enough time has passed since last shown.
     */
    private void showAdIfAvailable() {
        if (isShowingAd || !isAdAvailable()) {
            loadAd();
            return;
        }

        // Don't show on first app launch (let user see splash/welcome)
        if (isFirstLaunch) {
            isFirstLaunch = false;
            loadAd();
            return;
        }

        // Respect minimum interval between ads
        long now = new Date().getTime();
        if (lastShownTime > 0 && (now - lastShownTime) < MIN_INTERVAL_MS) {
            return;
        }

        // Don't show if current activity is splash
        if (currentActivity instanceof SplashActivity) {
            return;
        }

        isShowingAd = true;
        appOpenAd.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                appOpenAd = null;
                isShowingAd = false;
                lastShownTime = new Date().getTime();
                loadAd();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                appOpenAd = null;
                isShowingAd = false;
                loadAd();
            }

            @Override
            public void onAdShowedFullScreenContent() {
                Log.d(TAG, "App Open Ad shown");
            }
        });

        appOpenAd.show(currentActivity);
    }

    // ========== Lifecycle Observer (app foreground/background) ==========

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        showAdIfAvailable();
    }

    // ========== Activity Lifecycle Callbacks ==========

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        if (!isShowingAd) {
            currentActivity = activity;
        }
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        currentActivity = activity;
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {}

    @Override
    public void onActivityStopped(@NonNull Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (currentActivity == activity) {
            currentActivity = null;
        }
    }
}
