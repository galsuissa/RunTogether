package com.example.wear.presentation;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.example.wear.R;

/**
 * MainActivity (Wear)
 *
 * Responsibilities:
 *  • Acts as a simple splash screen that shows your logo and then finishes.
 */
public class MainActivity extends Activity {

    /** Splash duration; currently 80 seconds per original code. */
    private static final long SPLASH_DELAY_MS = 80_000L;

    /** Main-thread handler for posting the delayed finish. */
    private final Handler splashHandler = new Handler(Looper.getMainLooper());

    /** Action to perform after the splash delay. */
    private final Runnable finishRunnable = this::finish;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Displays your logo/layout

        // Post the delayed finish (acts like a splash timeout).
        splashHandler.postDelayed(finishRunnable, SPLASH_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        // Clean up any pending callbacks to avoid leaks if the Activity is destroyed early.
        splashHandler.removeCallbacks(finishRunnable);
        super.onDestroy();
    }
}
