package com.example.runtogether;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.http.Body;
import retrofit2.http.POST;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.Call;
import retrofit2.Response;

/**
 * RecommendationsService
 *
 * Purpose:
 *  • Foreground service that pushes a /tick payload to the FastAPI backend every second.
 *  • Publishes the latest recommendation/prediction to the UI via {@link #recommendationLiveData}.

 */
public class RecommendationsService extends Service {

    // ====== CONFIG ======
    private static final String TAG = "RecommendationsService";
    private static final String BASE_URL = "http://10.0.2.2:8000/"; // Emulator → host. Move to BuildConfig in prod.
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_RUNNER_LEVEL = "runner_level";

    private static final String NOTIF_CHANNEL_ID = "rt_recs";
    private static final int NOTIF_ID = 3321;

    // ====== PUBLIC LIVE DATA ======
    /** Observed by the UI (RunActivity) for latest recommendations/predictions. */
    public static final MutableLiveData<TickResponseDto> recommendationLiveData = new MutableLiveData<>(null);

    // ====== RUNTIME ======
    private ScheduledExecutorService scheduler;
    private String sessionId = "run_" + System.currentTimeMillis();
    private int runnerLevel = 2;
    private boolean running = false;

    // ====== RETROFIT ======
    interface RecommendationApi {
        @POST("tick")
        Call<TickResponseDto> tick(@Body TickRequestDto body);
    }
    private RecommendationApi api;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification n = buildNotification("Real-time recommendations running…");

        // Important: Run as DATA_SYNC FGS on Android 10+ to avoid SecurityException around connectedDevice.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }

        api = buildApiClient(BASE_URL);
        Log.d(TAG, "Service created, BASE_URL=" + BASE_URL);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // Read session configuration if provided.
        if (intent != null) {
            String extraSession = intent.getStringExtra(EXTRA_SESSION_ID);
            if (extraSession != null && !extraSession.isEmpty()) sessionId = extraSession;
            runnerLevel = intent.getIntExtra(EXTRA_RUNNER_LEVEL, 2);
        }

        // Idempotent start: only start loop once.
        if (!running) {
            running = true;
            startLoop();
        }
        return START_STICKY; // Keep service running (system may restart it with a null intent)
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        super.onDestroy();
        Log.d(TAG, "Service destroyed");
    }

    /**
     * Starts the 1 Hz loop that reads the latest sensor snapshot and posts it to the server.
     * Uses synchronous Retrofit on a background thread to keep tick ordering deterministic.
     */
    private void startLoop() {
        scheduler = Executors.newSingleThreadScheduledExecutor();

        // Optionally retain "last good" values so occasional nulls (e.g., watch gaps) won't stall ticks.
        final AtomicReference<Float> lastGoodHr  = new AtomicReference<>(null);
        final AtomicReference<Float> lastGoodSpd = new AtomicReference<>(null);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                double ts = System.currentTimeMillis() / 1000.0;

                // Snapshot sensor values (atomic references).
                Float hr       = SensorsRepository.getHeartRate();
                Float speed    = SensorsRepository.getSpeedKmh();
                Float cadence  = SensorsRepository.getCadence();
                Float power    = SensorsRepository.getPower();
                Float distance = SensorsRepository.getDistanceKm();
                Float elev     = SensorsRepository.getElevationM();

                // Gate: require both HR and Speed for a valid tick.
                if (hr == null || speed == null) {
                    // Fallback to last known good values (optional).
                    if (hr == null)    hr    = lastGoodHr.get();
                    if (speed == null) speed = lastGoodSpd.get();

                    // Still missing a critical field? Skip this tick to avoid skewing server windows.
                    if (hr == null || speed == null) {
                        return;
                    }
                } else {
                    lastGoodHr.set(hr);
                    lastGoodSpd.set(speed);
                }

                // Build a single-sample tick (server expects rolling windows on its side).
                SampleDto sample = new SampleDto();
                sample.timestamp = ts;
                sample.heartRate = hr;
                sample.enhancedSpeed = speed; // Ensure this matches the server contract.
                // sample.speedKmh = speed;    // If server expects speed_kmh instead, use this field.
                sample.cadence = cadence;
                sample.power = power;
                sample.distanceKm = distance;
                sample.elevationM = elev;

                TickRequestDto req = new TickRequestDto();
                req.sessionId = sessionId;
                req.runnerLevel = runnerLevel;
                req.samples = new ArrayList<>();
                req.samples.add(sample);

                // Synchronous call on the executor thread (not the main thread).
                Call<TickResponseDto> call = api.tick(req);
                Response<TickResponseDto> resp = call.execute();

                if (resp.isSuccessful() && resp.body() != null) {
                    TickResponseDto body = resp.body();
                    recommendationLiveData.postValue(body);
                    updateNotification(body.result != null ? body.result.recommendation : "No recommendation");
                    Log.d(TAG, "tick OK: display=" + body.displayRecommendation
                            + " rec=" + (body.result != null ? body.result.recommendation : null)
                            + " pred_hr=" + (body.result != null ? body.result.predHr : null)
                            + " pred_speed=" + (body.result != null ? body.result.predSpeed : null));
                } else {
                    String errTxt = null;
                    try {
                        if (resp.errorBody() != null) {
                            errTxt = resp.errorBody().string();
                        }
                    } catch (Exception ignored) {}
                    Log.w(TAG, "tick HTTP " + (resp != null ? resp.code() : -1)
                            + (errTxt != null ? (" body=" + errTxt) : ""));
                }
            } catch (Exception e) {
                // Network or serialization error; keep the loop alive.
                Log.w(TAG, "tick failed: " + e.getMessage());
            }
        }, 0, 1, TimeUnit.SECONDS);
    }


    // ====== Notification helpers ======
    /** Creates a low-importance channel for ongoing foreground updates. */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "Real-time Recommendations",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }

    /** Builds an ongoing notification for the foreground service. */
    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Run Together")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .build();
    }

    /** Updates the foreground notification with the latest recommendation text. */
    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    // ====== Retrofit builder ======
    /**
     * Constructs the Retrofit client with short timeouts and basic HTTP logging.*/
    private RecommendationApi buildApiClient(String baseUrl) {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build();

        Retrofit r = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .client(http)
                .build();

        return r.create(RecommendationApi.class);
    }

    // ====== DTOs (Gson) ======
    /** Single telemetry sample sent to the server; field names mapped to snake_case via @SerializedName. */
    public static class SampleDto {
        public Double timestamp;
        @SerializedName("heart_rate")    public Float heartRate;
        @SerializedName("enhanced_speed")public Float enhancedSpeed;
        @SerializedName("speed_kmh")     public Float speedKmh;
        @SerializedName("speed_mps")     public Float speedMps;
        public Float cadence;
        public Float power;
        @SerializedName("distance_km")   public Float distanceKm;
        @SerializedName("elevation_m")   public Float elevationM;
    }

    /** Request envelope for /tick; includes the session, runner level, and a batch of samples. */
    public static class TickRequestDto {
        @SerializedName("session_id")    public String sessionId;
        @SerializedName("runner_level")  public Integer runnerLevel;
        public List<SampleDto> samples;
    }

    /** Model returned from the server describing the recommendation and optional predictions. */
    public static class RecommendationDto {
        @SerializedName("pred_hr")       public Float predHr;
        @SerializedName("pred_speed")    public Float predSpeed;
        public String recommendation;
    }

    /** Response envelope for /tick; observed by the UI via LiveData. */
    public static class TickResponseDto {
        @SerializedName("session_id")            public String sessionId;
        @SerializedName("display_recommendation")public boolean displayRecommendation;
        public RecommendationDto result;
        @SerializedName("server_time")           public Double serverTime;
    }

    // ====== Simple sensors repository ======
    /**
     * Thread-safe in-memory store for the latest sensor values.
     * All fields use AtomicReference for lock-free reads/writes across threads.
     */
    public static class SensorsRepository {
        private static final AtomicReference<Float> HR       = new AtomicReference<>(null);
        private static final AtomicReference<Float> SPEED_KMH= new AtomicReference<>(null);
        private static final AtomicReference<Float> CADENCE  = new AtomicReference<>(null);
        private static final AtomicReference<Float> POWER    = new AtomicReference<>(null);
        private static final AtomicReference<Float> DIST_KM  = new AtomicReference<>(null);
        private static final AtomicReference<Float> ELEV_M   = new AtomicReference<>(null);

        public static void setHeartRate(Float v)  { HR.set(v); }
        public static void setSpeedKmh(Float v)   { SPEED_KMH.set(v); }
        public static void setCadence(Float v)    { CADENCE.set(v); }
        public static void setPower(Float v)      { POWER.set(v); }
        public static void setDistanceKm(Float v) { DIST_KM.set(v); }
        public static void setElevationM(Float v) { ELEV_M.set(v); }

        @Nullable public static Float getHeartRate()  { return HR.get(); }
        @Nullable public static Float getSpeedKmh()   { return SPEED_KMH.get(); }
        @Nullable public static Float getCadence()    { return CADENCE.get(); }
        @Nullable public static Float getPower()      { return POWER.get(); }
        @Nullable public static Float getDistanceKm() { return DIST_KM.get(); }
        @Nullable public static Float getElevationM() { return ELEV_M.get(); }
    }
}
