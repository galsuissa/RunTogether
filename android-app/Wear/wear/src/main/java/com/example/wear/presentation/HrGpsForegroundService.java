package com.example.wear.presentation;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * HrGpsForegroundService (Wear OS)
 *
 * Responsibilities:
 *  • Run as a foreground service that streams heart-rate (BODY_SENSORS) and GPS speed to your backend.
 *  • Handle runtime permission checks defensively (service cannot prompt UI).
 *  • Respect Android foreground service types (location; health on API 34+).
 */
public class HrGpsForegroundService extends Service implements SensorEventListener {

    private static final String CHANNEL_ID = "hr_gps_channel";
    private static final int NOTIF_ID = 1001;

    private SensorManager sensorManager;
    private Sensor heartRateSensor;
    private FusedLocationProviderClient locationClient;

    // Keep last known values so we can send pairs (HR + speed) even if events arrive separately
    private volatile float  lastHeartRate = 0f;   // bpm
    private volatile double lastSpeedMps  = 0.0;  // m/s

    @Override
    public void onCreate() {
        super.onCreate();

        // Create channel & start as a foreground service BEFORE requesting location/sensors.
        createNotificationChannel();
        Notification n = buildNotification("Collecting heart rate and GPS data");
        if (Build.VERSION.SDK_INT >= 29) {
            int type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            // On Android 14+ you can declare the HEALTH type for body sensors.
            if (Build.VERSION.SDK_INT >= 34) {
                type |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
            }
            startForeground(NOTIF_ID, n, type);
        } else {
            startForeground(NOTIF_ID, n);
        }

        // ---- Heart Rate (BODY_SENSORS) ----
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        boolean hasBodySensorsPerm =
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
                        == PackageManager.PERMISSION_GRANTED;

        if (hasBodySensorsPerm && sensorManager != null) {
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            if (heartRateSensor != null) {
                // SENSOR_DELAY_NORMAL is fine for HR; adjust if you need finer sampling.
                sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
        // If permission is missing, we still continue with GPS if available.

        // ---- GPS (ACCESS_FINE_LOCATION) ----
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        boolean hasFine =
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;

        if (hasFine) {
            LocationRequest request = new LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    2000L // interval
            ).setMinUpdateIntervalMillis(1000L)
                    .build();


            locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
        }
        // If both HR and Location perms are missing, consider stopSelf() to avoid a "zombie" FGS.
        if (!hasBodySensorsPerm && !hasFine) {
            stopSelf();
        }
    }

    // Receive GPS updates; keep latest speed and send combined snapshot.
    private final LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult result) {
            for (Location location : result.getLocations()) {
                lastSpeedMps = location.getSpeed(); // m/s
                sendDataToServer(lastHeartRate, lastSpeedMps);
            }
        }
    };

    // Receive heart-rate updates; keep latest HR and send combined snapshot.
    @Override
    public void onSensorChanged(android.hardware.SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_HEART_RATE) {
            lastHeartRate = event.values != null && event.values.length > 0 ? event.values[0] : 0f;
            sendDataToServer(lastHeartRate, lastSpeedMps);
        }
    }

    /**
     * Replace with your networking or Data Layer logic.
     * @param heartRate bpm
     * @param speedMps  meters/second (convert to km/h by * 3.6 if needed)
     */
    private void sendDataToServer(float heartRate, double speedMps) {
        // TODO: Implement HTTP/WebSocket/DataLayer send
        // Example conversions:
        // double speedKmh = speedMps * 3.6;
        // String payload = "{\"hr\":" + heartRate + ",\"speed_mps\":" + speedMps + "}";
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { /* no-op */ }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    // ---- Notification helpers ----
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "HR + GPS Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String contentText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Running Tracking")
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (locationClient != null) {
            locationClient.removeLocationUpdates(locationCallback);
        }
    }
}
