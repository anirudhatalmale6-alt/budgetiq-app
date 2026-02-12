package com.budgetiq.app;

import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.NonNull;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Play Billing v7 integration via JavaScript bridge.
 * Exposed to WebView as window.BudgetIQBilling
 */
public class BillingManager implements PurchasesUpdatedListener {

    private static final String TAG = "BillingManager";
    private static final String PRODUCT_ID = "sub_pro_149";
    private static final String VERIFY_URL = "https://optioninsights.in/api/v1/endpoints/billing.php";

    private final MainActivity activity;
    private BillingClient billingClient;
    private ProductDetails productDetails;
    private boolean isConnected = false;

    public BillingManager(MainActivity activity) {
        this.activity = activity;
        initBillingClient();
    }

    private void initBillingClient() {
        billingClient = BillingClient.newBuilder(activity)
                .setListener(this)
                .enablePendingPurchases()
                .build();

        startConnection();
    }

    private void startConnection() {
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    isConnected = true;
                    Log.d(TAG, "Billing client connected");
                    queryProduct();
                    queryExistingPurchases();
                } else {
                    Log.e(TAG, "Billing setup failed: " + billingResult.getDebugMessage());
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                isConnected = false;
                Log.d(TAG, "Billing service disconnected");
            }
        });
    }

    private void queryProduct() {
        List<QueryProductDetailsParams.Product> productList = new ArrayList<>();
        productList.add(
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
        );

        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build();

        billingClient.queryProductDetailsAsync(params, (billingResult, list) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && !list.isEmpty()) {
                productDetails = list.get(0);
                Log.d(TAG, "Product loaded: " + productDetails.getName());
                notifyWebView("onBillingReady", "true");
            } else {
                Log.e(TAG, "Product query failed: " + billingResult.getDebugMessage());
            }
        });
    }

    private void queryExistingPurchases() {
        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                (billingResult, purchases) -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        boolean hasActive = false;
                        for (Purchase purchase : purchases) {
                            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                hasActive = true;
                                if (!purchase.isAcknowledged()) {
                                    acknowledgePurchase(purchase);
                                }
                            }
                        }
                        notifyWebView("onSubscriptionStatus",
                                hasActive ? "'active'" : "'none'");
                    }
                }
        );
    }

    /**
     * JS bridge: Launch subscription purchase flow
     * Called from web: BudgetIQBilling.launchSubscription()
     */
    @JavascriptInterface
    public void launchSubscription() {
        activity.runOnUiThread(() -> {
            if (!isConnected) {
                startConnection();
                notifyWebView("onPurchaseFailed", "'Billing service not connected. Please try again.'");
                return;
            }

            if (productDetails == null) {
                notifyWebView("onPurchaseFailed", "'Subscription product not available yet. Please try again.'");
                return;
            }

            // Get the first offer token
            List<ProductDetails.SubscriptionOfferDetails> offers =
                    productDetails.getSubscriptionOfferDetails();
            if (offers == null || offers.isEmpty()) {
                notifyWebView("onPurchaseFailed", "'No subscription offers available.'");
                return;
            }

            String offerToken = offers.get(0).getOfferToken();

            List<BillingFlowParams.ProductDetailsParams> productDetailsParamsList = new ArrayList<>();
            productDetailsParamsList.add(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .setOfferToken(offerToken)
                            .build()
            );

            BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(productDetailsParamsList)
                    .build();

            billingClient.launchBillingFlow(activity, billingFlowParams);
        });
    }

    /**
     * JS bridge: Check current subscription status
     * Called from web: BudgetIQBilling.checkSubscription()
     */
    @JavascriptInterface
    public void checkSubscription() {
        if (!isConnected) {
            notifyWebView("onSubscriptionStatus", "'unknown'");
            return;
        }
        queryExistingPurchases();
    }

    /**
     * JS bridge: Get product price info
     * Called from web: BudgetIQBilling.getProductInfo()
     */
    @JavascriptInterface
    public String getProductInfo() {
        if (productDetails == null) return "{}";
        try {
            JSONObject info = new JSONObject();
            info.put("productId", productDetails.getProductId());
            info.put("name", productDetails.getName());
            info.put("description", productDetails.getDescription());

            List<ProductDetails.SubscriptionOfferDetails> offers =
                    productDetails.getSubscriptionOfferDetails();
            if (offers != null && !offers.isEmpty()) {
                List<ProductDetails.PricingPhase> phases =
                        offers.get(0).getPricingDetails().getPricingPhaseList();
                if (!phases.isEmpty()) {
                    ProductDetails.PricingPhase phase = phases.get(0);
                    info.put("price", phase.getFormattedPrice());
                    info.put("priceMicros", phase.getPriceAmountMicros());
                    info.put("currencyCode", phase.getPriceCurrencyCode());
                    info.put("billingPeriod", phase.getBillingPeriod());
                }
            }
            return info.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * JS bridge: Check if billing is available
     */
    @JavascriptInterface
    public boolean isAvailable() {
        return isConnected && productDetails != null;
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult,
                                    List<Purchase> purchases) {
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK
                && purchases != null) {
            for (Purchase purchase : purchases) {
                handlePurchase(purchase);
            }
        } else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            notifyWebView("onPurchaseFailed", "'User cancelled'");
        } else {
            notifyWebView("onPurchaseFailed",
                    "'" + billingResult.getDebugMessage() + "'");
        }
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            // Verify on backend
            verifyOnBackend(purchase);

            // Acknowledge
            if (!purchase.isAcknowledged()) {
                acknowledgePurchase(purchase);
            }

            // Notify web
            try {
                JSONObject result = new JSONObject();
                result.put("productId", PRODUCT_ID);
                result.put("purchaseToken", purchase.getPurchaseToken());
                result.put("orderId", purchase.getOrderId());
                notifyWebView("onPurchaseSuccess", result.toString());
            } catch (Exception e) {
                notifyWebView("onPurchaseSuccess", "'" + purchase.getPurchaseToken() + "'");
            }
        } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
            notifyWebView("onPurchasePending", "'Payment is pending. Access will be granted once confirmed.'");
        }
    }

    private void acknowledgePurchase(Purchase purchase) {
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();

        billingClient.acknowledgePurchase(params, billingResult -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Purchase acknowledged");
            }
        });
    }

    private void verifyOnBackend(Purchase purchase) {
        new Thread(() -> {
            try {
                URL url = new URL(VERIFY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("action", "verify_purchase");
                body.put("purchase_token", purchase.getPurchaseToken());
                body.put("product_id", PRODUCT_ID);
                body.put("order_id", purchase.getOrderId());
                body.put("package_name", activity.getPackageName());

                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.close();

                int code = conn.getResponseCode();
                Log.d(TAG, "Backend verification response: " + code);
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Backend verification failed: " + e.getMessage());
            }
        }).start();
    }

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
        if (billingClient != null) {
            billingClient.endConnection();
        }
    }
}
