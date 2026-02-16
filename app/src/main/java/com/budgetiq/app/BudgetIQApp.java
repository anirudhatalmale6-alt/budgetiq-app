package com.budgetiq.app;

import android.app.Application;

/**
 * Custom Application class for BudgetIQ.
 * Initializes App Open Ad manager for showing ads when user returns to app.
 */
public class BudgetIQApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize App Open Ad manager
        new AppOpenAdManager(this);
    }
}
