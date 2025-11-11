package com.example.wear.presentation;

import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;

/**
 * WatchMessageService
 *
 * Responsibilities:
 *  • Listens for Data Layer messages from the phone.
 *  • Starts/stops the HR+GPS foreground service on specific paths.
 */
public class WatchMessageService extends WearableListenerService {

    private static final String TAG = "WatchMessageService";

    // Message paths from the phone (must match the sender)
    private static final String PATH_START = "/start_run";
    private static final String PATH_STOP  = "/stop_run";

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        final String path = messageEvent.getPath();
        final String data = new String(messageEvent.getData(), StandardCharsets.UTF_8);

        Log.d(TAG, "Path: " + path + " Data: " + data);

        if (PATH_START.equals(path)) {
            // Start HR + GPS ForegroundService (safe for Android 8.0+)
            Intent svc = new Intent(this, HrGpsForegroundService.class);
            ContextCompat.startForegroundService(this, svc);

        } else if (PATH_STOP.equals(path)) {
            // Stop the ForegroundService
            stopService(new Intent(this, HrGpsForegroundService.class));

        } else {
            Log.w(TAG, "Unknown path received: " + path);
        }
    }
}
