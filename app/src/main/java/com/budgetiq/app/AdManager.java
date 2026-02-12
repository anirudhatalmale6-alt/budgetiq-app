package com.budgetiq.app;

import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.OnUserEarnedRewardListener;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;

/**
 * AdMob interstitial + rewarded ads via JavaScript bridge.
 * Exposed to WebView as window.BudgetIQAds
 *
 * - Interstitial: shown at natural breaks (task completion, module finish)
 * - Rewarded: user watches video to unlock Trade Desk features
 */
public class AdManager {

    private static final String TAG = "AdManager";

    // Ad Unit IDs - client's real IDs (same app ID: ca-app-pub-6353903215132082)
    // These need to be created in AdMob console. Using placeholder format for now.
    // Client will replace with actual unit IDs from their AdMob account.
    private static final String INTERSTITIAL_AD_UNIT = "ca-app-pub-6353903215132082/INTERSTITIAL_ID";
    private static final String REWARDED_AD_UNIT = "ca-app-pub-6353903215132082/REWARDED_ID";

    // Test ad unit IDs (used during development)
    private static final String TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712";
    private static final String TEST_REWARDED = "ca-app-pub-3940256099942544/5224354917";

    // Use test ads during development, switch to real for production
    private static final boolean USE_TEST_ADS = true;

    private final MainActivity activity;
    private InterstitialAd interstitialAd;
    private RewardedAd rewardedAd;
    private boolean isLoadingInterstitial = false;
    private boolean isLoadingRewarded = false;

    public AdManager(MainActivity activity) {
        this.activity = activity;
        preloadAds();
    }

    private void preloadAds() {
        loadInterstitial();
        loadRewarded();
    }

    private String getInterstitialUnit() {
        return USE_TEST_ADS ? TEST_INTERSTITIAL : INTERSTITIAL_AD_UNIT;
    }

    private String getRewardedUnit() {
        return USE_TEST_ADS ? TEST_REWARDED : REWARDED_AD_UNIT;
    }

    // ==================== INTERSTITIAL ADS ====================

    private void loadInterstitial() {
        if (isLoadingInterstitial || interstitialAd != null) return;
        isLoadingInterstitial = true;

        InterstitialAd.load(activity, getInterstitialUnit(),
                new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        interstitialAd = ad;
                        isLoadingInterstitial = false;
                        Log.d(TAG, "Interstitial loaded");

                        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                            @Override
                            public void onAdDismissedFullScreenContent() {
                                interstitialAd = null;
                                loadInterstitial(); // Preload next
                                notifyWebView("onInterstitialDismissed", "");
                            }

                            @Override
                            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                                interstitialAd = null;
                                loadInterstitial();
                                notifyWebView("onInterstitialFailed", "'" + adError.getMessage() + "'");
                            }

                            @Override
                            public void onAdShowedFullScreenContent() {
                                Log.d(TAG, "Interstitial shown");
                            }
                        });
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        interstitialAd = null;
                        isLoadingInterstitial = false;
                        Log.e(TAG, "Interstitial load failed: " + loadAdError.getMessage());
                    }
                });
    }

    /**
     * JS bridge: Show interstitial ad at natural break point
     * Called from web: BudgetIQAds.showInterstitial()
     */
    @JavascriptInterface
    public void showInterstitial() {
        activity.runOnUiThread(() -> {
            if (interstitialAd != null) {
                interstitialAd.show(activity);
            } else {
                Log.d(TAG, "Interstitial not ready, loading...");
                loadInterstitial();
                notifyWebView("onInterstitialDismissed", ""); // Don't block user
            }
        });
    }

    /**
     * JS bridge: Check if interstitial is ready
     */
    @JavascriptInterface
    public boolean isInterstitialReady() {
        return interstitialAd != null;
    }

    // ==================== REWARDED ADS ====================

    private void loadRewarded() {
        if (isLoadingRewarded || rewardedAd != null) return;
        isLoadingRewarded = true;

        RewardedAd.load(activity, getRewardedUnit(),
                new AdRequest.Builder().build(),
                new RewardedAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull RewardedAd ad) {
                        rewardedAd = ad;
                        isLoadingRewarded = false;
                        Log.d(TAG, "Rewarded ad loaded");
                        notifyWebView("onRewardedAdReady", "true");

                        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                            @Override
                            public void onAdDismissedFullScreenContent() {
                                rewardedAd = null;
                                loadRewarded(); // Preload next
                            }

                            @Override
                            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                                rewardedAd = null;
                                loadRewarded();
                                notifyWebView("onRewardedAdFailed", "'" + adError.getMessage() + "'");
                            }

                            @Override
                            public void onAdShowedFullScreenContent() {
                                Log.d(TAG, "Rewarded ad shown");
                            }
                        });
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        rewardedAd = null;
                        isLoadingRewarded = false;
                        Log.e(TAG, "Rewarded load failed: " + loadAdError.getMessage());
                        notifyWebView("onRewardedAdReady", "false");
                    }
                });
    }

    /**
     * JS bridge: Show rewarded ad (user opts in to watch video for reward)
     * Called from web: BudgetIQAds.showRewarded('feature_name')
     * Reward callback: window.onRewardEarned(featureName)
     */
    @JavascriptInterface
    public void showRewarded(String featureName) {
        activity.runOnUiThread(() -> {
            if (rewardedAd != null) {
                rewardedAd.show(activity, rewardItem -> {
                    Log.d(TAG, "User earned reward for: " + featureName);
                    notifyWebView("onRewardEarned", "'" + featureName + "'");
                });
            } else {
                Log.d(TAG, "Rewarded ad not ready, loading...");
                loadRewarded();
                notifyWebView("onRewardedAdFailed", "'Ad not ready. Please try again.'");
            }
        });
    }

    /**
     * JS bridge: Check if rewarded ad is ready
     */
    @JavascriptInterface
    public boolean isRewardedReady() {
        return rewardedAd != null;
    }

    // ==================== UTILITY ====================

    private void notifyWebView(String callback, String args) {
        activity.runOnUiThread(() -> {
            WebView webView = activity.getWebView();
            if (webView != null) {
                String js = "if(window." + callback + ") window." + callback + "(" + args + ");";
                webView.evaluateJavascript(js, null);
            }
        });
    }

    public void destroy() {
        interstitialAd = null;
        rewardedAd = null;
    }
}
