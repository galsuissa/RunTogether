package com.example.runtogether;

import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;

/**
 * PhoneWearListenerService
 *
 * Responsibilities:
 *  • Receive messages from the paired Wear OS device via the Data Layer.
 *  • Parse simple numeric payloads (heart rate / speed / distance).
 *  • Forward values to the UI layer using local broadcasts (in-app only).
 */
public class PhoneWearListenerService extends WearableListenerService {

    // Log tag
    private static final String TAG = "PhoneWearListener";

    // Incoming message paths (must match the watch-side sender)
    private static final String PATH_HR       = "/hr";
    private static final String PATH_SPEED    = "/speed";
    private static final String PATH_DISTANCE = "/distance";

    // Outgoing local broadcast actions (kept as-is to match existing receivers)
    private static final String ACTION_HR       = "WEAR_HR";
    private static final String ACTION_SPEED    = "WEAR_SPEED";
    private static final String ACTION_DISTANCE = "WEAR_DISTANCE";

    // Intent extra keys (kept as-is)
    private static final String EXTRA_BPM        = "bpm";
    private static final String EXTRA_SPEED_MS   = "speed_ms";
    private static final String EXTRA_DISTANCE_M = "distance_m";

    @Override
    public void onMessageReceived(MessageEvent e) {
        final String path = e.getPath();
        byte[] payload = e.getData();
        if (payload == null) payload = new byte[0];

        // Decode as UTF-8 and trim to avoid parse issues with trailing whitespace/newlines.
        final String text = new String(payload, StandardCharsets.UTF_8).trim();

        final LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);

        try {
            switch (path) {
                case PATH_HR: {
                    // Expecting a float BPM string, e.g., "72.0"
                    float bpm = Float.parseFloat(text);
                    Intent iHr = new Intent(ACTION_HR);
                    iHr.putExtra(EXTRA_BPM, bpm);
                    lbm.sendBroadcast(iHr);
                    break;
                }
                case PATH_SPEED: {
                    // Expecting meters/second, e.g., "3.42"
                    double speedMs = Double.parseDouble(text);
                    Intent iSp = new Intent(ACTION_SPEED);
                    iSp.putExtra(EXTRA_SPEED_MS, speedMs);
                    lbm.sendBroadcast(iSp);
                    break;
                }
                case PATH_DISTANCE: {
                    // Expecting cumulative meters, e.g., "1250.7"
                    double distM = Double.parseDouble(text);
                    Intent iDist = new Intent(ACTION_DISTANCE);
                    iDist.putExtra(EXTRA_DISTANCE_M, distM);
                    lbm.sendBroadcast(iDist);
                    break;
                }
                default:
                    Log.w(TAG, "Unknown path: " + path);
            }
        } catch (NumberFormatException ex) {
            // Payload wasn't a valid number; log and ignore this message gracefully.
            Log.e(TAG, "Parse error for path " + path + " with payload: \"" + text + "\"", ex);
        } catch (Exception ex) {
            Log.e(TAG, "Unexpected error handling message for path " + path, ex);
        }
    }
}
