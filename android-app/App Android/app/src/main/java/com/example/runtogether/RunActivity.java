package com.example.runtogether;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.android.volley.Request;
import com.android.volley.toolbox.JsonObjectRequest;

import com.example.runtogether.RecommendationsService;
import com.example.runtogether.RecommendationsService.TickResponseDto;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;

import retrofit2.Retrofit;
import retrofit2.http.Body;
import retrofit2.http.POST;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * RunActivity
 *
 * Responsibilities:
 *  • Controls a run session start/stop.
 *  • By default, replays telemetry from a CSV in assets (assets/replay/RUN_SAMPLE.csv).
 *  • Falls back to synthetic simulation if CSV is missing/invalid.
 *  • Feeds live metrics into RecommendationsService (which posts /tick every second).
 *  • Displays live metrics and server recommendations.
 *
 * Notes:
 *  • Comments are in English per request.
 *  • Recommendation text shown to the user is localized to Hebrew on the client.
 */
public class RunActivity extends AppCompatActivity {

    // ------------------------- App configuration -------------------------

    /** Use default CSV replay from assets on start (without user selection). */
    private static final boolean USE_REPLAY_DEFAULT = true;

    /** Assets CSV path (adjust to your file name if needed). */
    private static final String ASSET_CSV_PATH = "replay/RUN_SAMPLE.csv";

    /** Node base URL (used to save history); 10.0.2.2 = host machine from Android emulator. */
    private static final String NODE_BASE_URL = "http://10.0.2.2:3000/";

    /** Synthetic random-walk simulation fallback if CSV isn't available. */
    private static final boolean SIMULATION_FALLBACK_DEFAULT = true;

    // ------------------------- UI -------------------------
    private Button btnConnectOrStart, btnEndRun;
    private LinearLayout llPhysiologicalData;
    private TextView tvSpeed, tvHeartRate, tvDistance, tvFeedback, tvTimeStart, tvSummaryHeader;

    // ------------------------- Connection / session state -------------------------
    private String connectedNodeId = null;
    private boolean isConnectedToWatch = false;
    private boolean isRunActive = false;

    // Whether synthetic simulation is active right now (fallback).
    private boolean simulateMode = SIMULATION_FALLBACK_DEFAULT;

    // Whether CSV replay mode is active right now.
    private boolean isReplayMode = false;

    // ------------------------- Live and aggregated metrics -------------------------
    private float  lastHr = 0f;
    private double lastSpeedKph = 0.0;
    private double lastDistanceKm = 0.0;

    private double sumHr = 0.0;     private int cntHr = 0;
    private double sumSpeed = 0.0;  private int cntSpeed = 0;

    private long runStartWallTimeMs = 0L;

    // ------------------------- Handlers (UI / simulation / replay) -------------------------
    private final Handler uiHandler  = new Handler(Looper.getMainLooper());
    private final Handler simHandler = new Handler(Looper.getMainLooper());
    private final Handler replayHandler = new Handler(Looper.getMainLooper());

    /** 1 Hz on-screen elapsed time ticker. */
    private final Runnable elapsedTicker = new Runnable() {
        @Override public void run() {
            if (isRunActive) {
                long elapsedMs = System.currentTimeMillis() - runStartWallTimeMs;
                tvTimeStart.setText("זמן ריצה:\n" + formatElapsed(elapsedMs));
                uiHandler.postDelayed(this, 1000);
            }
        }
    };

    /** Synthetic fallback simulation (simple random walk for speed & HR). */
    private final Runnable simTick = new Runnable() {
        private double hr = 135;     // realistic starting point
        private double sp = 8.8;     // km/h
        private double distKm = 0.0;

        @Override public void run() {
            if (!isRunActive || !simulateMode) return;

            sp += ThreadLocalRandom.current().nextDouble(-0.10, 0.12);
            sp = clamp(sp, 4.0, 12.5);

            double targetHr = 60 + (200 - 60) * Math.pow(sp / 12.5, 0.85);
            hr += 0.18 * (targetHr - hr) + ThreadLocalRandom.current().nextDouble(-0.8, 0.8);
            hr = clamp(hr, 90, 195);

            distKm += sp / 3600.0;

            lastHr = (float) hr;
            lastSpeedKph = sp;
            lastDistanceKm = distKm;

            // Update UI
            tvHeartRate.setText("דופק:\n" + Math.round(lastHr));
            tvSpeed.setText("מהירות:\n" + String.format(Locale.getDefault(), "%.2f", lastSpeedKph) + " קמ\"ש");
            tvDistance.setText("מרחק:\n" + String.format(Locale.getDefault(), "%.2f", lastDistanceKm) + " ק\"מ");

            // Feed service repository (read by RecommendationsService loop)
            RecommendationsService.SensorsRepository.setHeartRate(lastHr);
            RecommendationsService.SensorsRepository.setSpeedKmh((float) lastSpeedKph);
            RecommendationsService.SensorsRepository.setDistanceKm((float) lastDistanceKm);

            // Aggregates
            sumHr += lastHr;     cntHr++;
            sumSpeed += lastSpeedKph; cntSpeed++;

            simHandler.postDelayed(this, 1000);
        }
    };

    /** Maps app "level" to API runner_level (1..3). */
    private int apiRunnerLevel = 2;

    // ------------------------- Node history API (retrofit) -------------------------
    interface HistoryApi { @POST("api/history") Call<Object> saveRun(@Body RunSummaryDto body); }
    private HistoryApi historyApi;

    // ------------------------- Observe server recommendations -------------------------
    private final Observer<TickResponseDto> recObserver = dto -> {
        if (!isRunActive || dto == null || dto.result == null) return;

        // Localize the server's English recommendation into Hebrew for display.
        String recEn = dto.result.recommendation != null ? dto.result.recommendation : "";
        String recHe = localizeRecommendation(recEn);

        Float predHr  = dto.result.predHr;
        Float predSpd = dto.result.predSpeed;

        StringBuilder sb = new StringBuilder("המלצה: ").append(recHe);
        if (predHr != null)  sb.append("\n🔍 חזוי דופק: ").append(String.format(Locale.getDefault(), "%.1f", predHr));
        if (predSpd != null) sb.append("\n🔍 מהירות מומלצת: ").append(String.format(Locale.getDefault(), "%.2f", predSpd));

        tvFeedback.setVisibility(View.VISIBLE);
        tvFeedback.setText(sb.toString());
    };

    /** Simple localizer: maps common English recommendation phrases → Hebrew. */
    private String localizeRecommendation(String rec) {
        if (rec == null) return "";
        String s = rec.toLowerCase(Locale.US);

        // Warm-up related
        if (s.contains("starting too fast")) return "אתה מתחיל מהר מדי — האט לחימום נכון";
        if (s.contains("smoother warm-up")) return "שקול להאט מעט לחימום חלק יותר";
        if (s.contains("gradually increase")) return "נסה להגביר את הקצב בהדרגה";
        if (s.contains("warm-up phase")) return "שלב חימום — שמור על קצב יציב";

        // Safety / fatigue
        if (s.contains("fatigue")) return "עייפות מצטברת — האט או קצר את הצעד";
        if (s.contains("rising heart rate")) return "דופק עולה — שקול להפחית קצב";
        if (s.contains("stabilize your speed")) return "נסה לייצב את המהירות לשיפור הסבולת";

        // Gap-based rules
        if (s.contains("increase pace slightly")) return "הגבר קצב במעט";
        if (s.contains("slow down to reduce heart rate")) return "האט כדי להפחית את הדופק";
        if (s.contains("reduce intensity") && s.contains("too high")) return "הפחת עצימות — הדופק גבוה מדי";
        if (s.contains("increase intensity") && s.contains("too low")) return "הגבר עצימות — הדופק נמוך מדי";
        if (s.contains("running too fast")) return "אתה רץ מהר מדי — האט מעט";
        if (s.contains("target pace")) return "נסה להגיע לקצב היעד";
        if (s.contains("maintain current pace")) return "שמור על הקצב הנוכחי";

        // Fallback: if no mapping matched, show original (better than hiding it)
        return rec;
    }

    // ------------------------- Watch data receiver (if connected) -------------------------
    private final BroadcastReceiver wearDataReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null || !isRunActive) return;

            if ("WEAR_HR".equals(action)) {
                lastHr = intent.getFloatExtra("bpm", 0f);
                tvHeartRate.setText("דופק:\n" + Math.round(lastHr));
                RecommendationsService.SensorsRepository.setHeartRate(lastHr);
                if (lastHr > 0) { sumHr += lastHr; cntHr++; }

            } else if ("WEAR_SPEED".equals(action)) {
                double speedMs = intent.getDoubleExtra("speed_ms", 0.0);
                lastSpeedKph = speedMs * 3.6;
                tvSpeed.setText("מהירות:\n" + String.format(Locale.getDefault(), "%.2f", lastSpeedKph) + " קמ\"ש");
                RecommendationsService.SensorsRepository.setSpeedKmh((float) lastSpeedKph);
                if (lastSpeedKph >= 0) { sumSpeed += lastSpeedKph; cntSpeed++; }

            } else if ("WEAR_DISTANCE".equals(action)) {
                double distMeters = intent.getDoubleExtra("distance_m", 0.0);
                lastDistanceKm = distMeters / 1000.0;
                tvDistance.setText("מרחק:\n" + String.format(Locale.getDefault(), "%.2f", lastDistanceKm) + " ק\"מ");
                RecommendationsService.SensorsRepository.setDistanceKm((float) lastDistanceKm);
            }
        }
    };

    // ------------------------- CSV Replay (assets default) -------------------------

    private static final String[] CSV_REQUIRED = {"heart_rate", "speed", "distance"};
    private List<CsvReplayRow> replayRows = new ArrayList<>();
    private int replayIdx = 0;
    private double replaySpeed = 1.0; // 1.0 = real time
    private long defaultTickMs = 1000L;

    /** 1-shot runnable that emits next CSV row according to dataset timing. */
    private final Runnable replayLoop = new Runnable() {
        @Override public void run() {
            if (!isRunActive || !isReplayMode || replayIdx >= replayRows.size()) {
                stopReplay();
                return;
            }
            CsvReplayRow row = replayRows.get(replayIdx);

            emitTickFromRow(row);

            long delayMs = nextDelayMs(replayIdx);
            replayIdx++;

            if (isRunActive && isReplayMode && replayIdx < replayRows.size()) {
                replayHandler.postDelayed(this, delayMs);
            } else {
                stopReplay();
            }
        }
    };

    /** In-memory representation of one CSV row. */
    private static class CsvReplayRow {
        Long   timestampMs; // optional
        Double timeSec;     // optional
        Float  heartRate;   // bpm (may be decimal in file -> we round)
        Double speedKmh;    // km/h
        Double distanceKm;  // cumulative km
        Double cadence;     // optional
        Double power;       // optional
    }

    /** Opens assets CSV, parses rows, or falls back to synthetic sim on failure. */
    private void startReplayFromAssets(String assetPath) {
        try (InputStream is = getAssets().open(assetPath)) {
            List<CsvReplayRow> rows = parseCsv(is);
            if (rows.isEmpty()) {
                Toast.makeText(this, "CSV (assets) ריק", Toast.LENGTH_SHORT).show();
                fallbackToSyntheticSim();
                return;
            }
            beginReplay(rows);
        } catch (Exception e) {
            Toast.makeText(this, "טעינת CSV מ-assets נכשלה: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
            fallbackToSyntheticSim();
        }
    }

    /** CSV parser; supports common column aliases and unit conversions. */
    private List<CsvReplayRow> parseCsv(InputStream is) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String headerLine = br.readLine();
        if (headerLine == null) throw new Exception("Empty CSV");
        String[] headers = headerLine.split(",", -1);

        // Normalize common column names
        Map<String,Integer> col = new HashMap<>();
        int hrIdx = -1;
        int spKmhIdx = -1;
        int spMpsIdx = -1;
        int distKmIdx = -1;
        int distMIdx = -1;
        int tsMsIdx = -1;
        int tSecIdx = -1;

        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().toLowerCase(Locale.US);
            switch (h) {
                case "heart_rate":
                case "hr":
                case "bpm":
                    hrIdx = i; break;
                case "speed":
                case "speed_kmh":
                case "spd":
                    spKmhIdx = i; break;
                case "speed_mps":
                    spMpsIdx = i; break;
                case "distance":
                case "distance_km":
                    distKmIdx = i; break;
                case "distance_m":
                case "dist_m":
                    distMIdx = i; break;
                case "timestamp_ms":
                case "ts_ms":
                case "epoch_ms":
                case "timestamp":
                    tsMsIdx = i; break;
                case "time_s":
                case "elapsed_s":
                case "time_sec":
                    tSecIdx = i; break;
                default:
                    col.put(h, i);
            }
        }

        List<CsvReplayRow> out = new ArrayList<>();
        String line;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            String[] p = line.split(",", -1);

            CsvReplayRow r = new CsvReplayRow();

            // Heart rate: support int/decimal, then clamp to plausible range and round.
            Double hrD = parseDouble(p, hrIdx);
            if (hrD == null && col.containsKey("heart_rate")) {
                hrD = parseDouble(p, col.get("heart_rate"));
            }
            if (hrD != null) {
                int hrInt = (int) Math.round(hrD);
                if (hrInt >= 35 && hrInt <= 230) r.heartRate = (float) hrInt;
            }

            // Speed: km/h or m/s -> normalize to km/h.
            Double spKmh = parseDouble(p, spKmhIdx);
            Double spMps = parseDouble(p, spMpsIdx);
            if (spKmh != null) {
                r.speedKmh = Math.max(0.0, spKmh);
            } else if (spMps != null) {
                r.speedKmh = Math.max(0.0, spMps * 3.6);
            } else if (col.containsKey("speed")) {
                Double spAny = parseDouble(p, col.get("speed"));
                if (spAny != null) r.speedKmh = Math.max(0.0, spAny);
            }

            // Distance: km or meters -> normalize to km.
            Double dKm = parseDouble(p, distKmIdx);
            Double dM  = parseDouble(p, distMIdx);
            if (dKm != null) {
                r.distanceKm = Math.max(0.0, dKm);
            } else if (dM != null) {
                r.distanceKm = Math.max(0.0, dM / 1000.0);
            } else if (col.containsKey("distance_km")) {
                Double d = parseDouble(p, col.get("distance_km"));
                if (d != null) r.distanceKm = Math.max(0.0, d);
            } else if (col.containsKey("distance")) {
                Double d = parseDouble(p, col.get("distance"));
                if (d != null) r.distanceKm = Math.max(0.0, d);
            }

            // Optional cadence/power
            r.cadence = parseDouble(p, col.getOrDefault("cadence", -1));
            r.power   = parseDouble(p, col.getOrDefault("power", -1));

            // Time columns (either absolute ms or elapsed seconds)
            r.timeSec = parseDouble(p, tSecIdx);
            Long tms  = parseLong(p, tsMsIdx);
            if (tms == null && col.containsKey("timestamp_ms")) {
                tms = parseLong(p, col.get("timestamp_ms"));
            }
            r.timestampMs = tms;

            out.add(r);
        }
        br.close();
        return out;
    }

    /** Starts replay: seeds first valid sample to repo/UI, then schedules timed loop. */
    private void beginReplay(List<CsvReplayRow> rows) {
        stopReplay(); // safety
        this.replayRows = rows;
        this.isReplayMode = true;

        // Prevent synthetic sim from running in parallel.
        this.simulateMode = false;
        simHandler.removeCallbacks(simTick);

        tvFeedback.setVisibility(View.VISIBLE);
        tvFeedback.setText("מצב סימולציה מקובץ: פעיל");

        // Find first row that contains both HR and speed so the first /tick is valid.
        int firstValid = findFirstValidIndex(rows);
        if (firstValid < 0) {
            Toast.makeText(this, "לא נמצאה אף שורה עם HR ומהירות — מעבר לסימולציה", Toast.LENGTH_LONG).show();
            fallbackToSyntheticSim();
            return;
        }

        // Seed initial values BEFORE service tick loop starts (avoid empty initial ticks).
        emitTickFromRow(rows.get(firstValid));
        this.replayIdx = firstValid + 1;

        // Schedule the next row according to dataset timing (or 1s default).
        long delay = nextDelayMs(firstValid);
        replayHandler.postDelayed(replayLoop, delay);

        Toast.makeText(this, "Replay התחיל (" + rows.size() + " שורות)", Toast.LENGTH_SHORT).show();
    }

    /** Finds the first row that has both HR and speed. */
    private int findFirstValidIndex(List<CsvReplayRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            CsvReplayRow r = rows.get(i);
            if (r != null && r.heartRate != null && r.speedKmh != null) {
                return i;
            }
        }
        return -1;
    }

    /** Stops replay loop. */
    private void stopReplay() {
        isReplayMode = false;
        replayHandler.removeCallbacks(replayLoop);
    }

    /** Computes delay (ms) to the next row, using timestamp/time_s if available. */
    private long nextDelayMs(int idx) {
        if (idx + 1 >= replayRows.size()) return (long) (defaultTickMs / replaySpeed);
        CsvReplayRow cur = replayRows.get(idx);
        CsvReplayRow nxt = replayRows.get(idx + 1);

        if (cur.timestampMs != null && nxt.timestampMs != null) {
            long dt = Math.max(1, nxt.timestampMs - cur.timestampMs);
            return (long) Math.max(1, dt / replaySpeed);
        }
        if (cur.timeSec != null && nxt.timeSec != null) {
            double dt = Math.max(0.001, nxt.timeSec - cur.timeSec) * 1000.0;
            return (long) Math.max(1, dt / replaySpeed);
        }
        return (long) Math.max(1, defaultTickMs / replaySpeed);
    }

    /** Emits one row: updates UI and feeds SensorsRepository for the service loop. */
    private void emitTickFromRow(CsvReplayRow row) {
        if (row.heartRate != null) {
            lastHr = row.heartRate;
            tvHeartRate.setText("דופק:\n" + Math.round(lastHr));
            RecommendationsService.SensorsRepository.setHeartRate(lastHr);
            if (lastHr > 0) { sumHr += lastHr; cntHr++; }
        }
        if (row.speedKmh != null) {
            lastSpeedKph = Math.max(0.0, row.speedKmh);
            tvSpeed.setText("מהירות:\n" + String.format(Locale.getDefault(), "%.2f", lastSpeedKph) + " קמ\"ש");
            RecommendationsService.SensorsRepository.setSpeedKmh((float) lastSpeedKph);
            sumSpeed += lastSpeedKph; cntSpeed++;
        }
        if (row.distanceKm != null) {
            lastDistanceKm = Math.max(0.0, row.distanceKm);
            tvDistance.setText("מרחק:\n" + String.format(Locale.getDefault(), "%.2f", lastDistanceKm) + " ק\"מ");
            RecommendationsService.SensorsRepository.setDistanceKm((float) lastDistanceKm);
        }
    }

    // ------------------------- Small parsing helpers -------------------------

    private Integer parseInt(String[] a, int i) {
        if (i < 0 || i >= a.length) return null;
        try {
            String s = a[i].trim();
            if (s.isEmpty()) return null;
            s = s.replace(',', '.'); // support European CSV decimals
            double d = Double.parseDouble(s);
            return (int) Math.round(d);
        } catch (Exception e) { return null; }
    }
    private Double parseDouble(String[] a, int i) {
        if (i < 0 || i >= a.length) return null;
        try {
            String s = a[i].trim();
            if (s.isEmpty()) return null;
            s = s.replace(',', '.'); // support European CSV decimals
            return Double.parseDouble(s);
        } catch (Exception e) { return null; }
    }
    private Long parseLong(String[] a, int i) {
        if (i < 0 || i >= a.length) return null;
        try {
            String s = a[i].trim();
            if (s.isEmpty()) return null;
            return Long.parseLong(s);
        } catch (Exception e) { return null; }
    }

    // ------------------------- Activity lifecycle -------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_run);

        // Bind UI
        btnConnectOrStart   = findViewById(R.id.btnConnectWatch);
        btnEndRun           = findViewById(R.id.btnEndRun);
        llPhysiologicalData = findViewById(R.id.llPhysiologicalData);
        tvSpeed     = findViewById(R.id.tvSpeed);
        tvHeartRate = findViewById(R.id.tvHeartRate);
        tvDistance  = findViewById(R.id.tvDistance);
        tvFeedback  = findViewById(R.id.tvFeedback);
        tvSummaryHeader  = findViewById(R.id.tvSummaryHeader);
        tvTimeStart = findViewById(R.id.tvTimeStart);

        // Initial UI state
        llPhysiologicalData.setVisibility(View.GONE);
        tvFeedback.setVisibility(View.GONE);
        tvTimeStart.setText("זמן ריצה:\n00:00");
        btnEndRun.setVisibility(View.GONE);

        // Build Node API
        historyApi = buildNodeHistoryApi(NODE_BASE_URL);

        // Map runner level (0..2) → API (1..3)
        apiRunnerLevel = mapRunnerLevelFromPrefs();

        // "Connect/watch" button becomes "Start run" after attempt
        btnConnectOrStart.setOnClickListener(v -> tryConnectToWatch());

        // End run button
        btnEndRun.setOnClickListener(v -> finishRun());

        // Register watch broadcast receiver
        IntentFilter f = new IntentFilter();
        f.addAction("WEAR_HR");
        f.addAction("WEAR_SPEED");
        f.addAction("WEAR_DISTANCE");
        LocalBroadcastManager.getInstance(this).registerReceiver(wearDataReceiver, f);
    }

    @Override protected void onStart() {
        super.onStart();
        RecommendationsService.recommendationLiveData.observe(this, recObserver);
    }

    @Override protected void onStop() {
        super.onStop();
        RecommendationsService.recommendationLiveData.removeObserver(recObserver);
    }

    @Override protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(wearDataReceiver);
        super.onDestroy();
    }

    @Override protected void onPause() {
        super.onPause();
        // Safety stops
        stopReplay();
        simHandler.removeCallbacks(simTick);
    }

    // ------------------------- Connect / Start / Finish -------------------------

    /** Attempts to connect to a watch; if not found, we can still run with CSV/simulation. */
    private void tryConnectToWatch() {
        ConnectWatchHelper.connectToWatch(this, new ConnectWatchHelper.OnWatchConnected() {
            @Override public void onConnected(String nodeId) {
                isConnectedToWatch = true;
                connectedNodeId = nodeId;
                saveNodeId(nodeId);
                Toast.makeText(RunActivity.this, "מחובר לשעון ✅", Toast.LENGTH_SHORT).show();
                promoteButtonToStart();
            }
            @Override public void onNotConnected() {
                isConnectedToWatch = false;
                connectedNodeId = null;
                Toast.makeText(RunActivity.this, "לא נמצא שעון — נשתמש בסימולציה", Toast.LENGTH_SHORT).show();
                promoteButtonToStart();
            }
        });
    }

    /** Turns the first button into a "Start Run" action. */
    private void promoteButtonToStart() {
        btnConnectOrStart.setText("התחל ריצה");
        btnConnectOrStart.setOnClickListener(v -> startRun());
    }

    /** Starts a run session, prioritizing CSV replay; seeds first metrics before starting service. */
    private void startRun() {
        if (isRunActive) return;
        isRunActive = true;
        runStartWallTimeMs = System.currentTimeMillis();
        resetAggregates();

        llPhysiologicalData.setVisibility(View.VISIBLE);
        btnConnectOrStart.setVisibility(View.GONE);
        btnEndRun.setVisibility(View.VISIBLE);
        tvFeedback.setText("");
        tvFeedback.setVisibility(View.VISIBLE);
        tvTimeStart.setText("זמן ריצה:\n00:00");
        uiHandler.post(elapsedTicker);

        tvHeartRate.setText("דופק:\n—");
        tvSpeed.setText("מהירות:\n—");
        tvDistance.setText("מרחק:\n0.00 ק\"מ");

        if (isConnectedToWatch && connectedNodeId != null) {
            WearMessaging.sendMessage(this, connectedNodeId, "/start_run", null, null, null);
        }

        String sessionId = "user" + getUserId() + "-" + System.currentTimeMillis();

        if (USE_REPLAY_DEFAULT) {
            // Ensure synthetic sim is off.
            simulateMode = false;
            simHandler.removeCallbacks(simTick);

            // Start replay (seeds first valid row immediately).
            startReplayFromAssets(ASSET_CSV_PATH);

            // Start foreground service slightly after we seeded values to avoid empty ticks.
            uiHandler.postDelayed(() -> {
                Intent svc = new Intent(this, RecommendationsService.class);
                svc.putExtra(RecommendationsService.EXTRA_SESSION_ID, sessionId);
                svc.putExtra(RecommendationsService.EXTRA_RUNNER_LEVEL, apiRunnerLevel);
                ContextCompat.startForegroundService(this, svc);
            }, 400);
            return;
        }

        // If not using replay — either run synthetic sim or just start service with current data.
        if (simulateMode) {
            RecommendationsService.SensorsRepository.setHeartRate(135f);
            RecommendationsService.SensorsRepository.setSpeedKmh(8.8f);
            RecommendationsService.SensorsRepository.setDistanceKm(0f);

            simHandler.post(simTick);

            uiHandler.postDelayed(() -> {
                Intent svc = new Intent(this, RecommendationsService.class);
                svc.putExtra(RecommendationsService.EXTRA_SESSION_ID, sessionId);
                svc.putExtra(RecommendationsService.EXTRA_RUNNER_LEVEL, apiRunnerLevel);
                ContextCompat.startForegroundService(this, svc);
            }, 400);
        } else {
            Intent svc = new Intent(this, RecommendationsService.class);
            svc.putExtra(RecommendationsService.EXTRA_SESSION_ID, sessionId);
            svc.putExtra(RecommendationsService.EXTRA_RUNNER_LEVEL, apiRunnerLevel);
            ContextCompat.startForegroundService(this, svc);
        }
    }

    /** Clean fallback back to synthetic simulation. */
    private void fallbackToSyntheticSim() {
        this.isReplayMode = false;
        this.simulateMode = true;
        tvFeedback.setText("Replay נכשל — עוברים לסימולציה סינתטית");
        simHandler.post(simTick);
    }

    /** Ends the run session; stops loops and shows a summary. */
    private void finishRun() {
        if (!isRunActive) return;
        isRunActive = false;

        uiHandler.removeCallbacks(elapsedTicker);
        simHandler.removeCallbacks(simTick);
        stopReplay();

        if (connectedNodeId != null) {
            WearMessaging.sendMessage(this, connectedNodeId, "/stop_run", null, null, null);
        }

        stopService(new Intent(this, RecommendationsService.class));

        long elapsedMs = System.currentTimeMillis() - runStartWallTimeMs;
        double totalTimeMinutes = elapsedMs / 60000.0;
        double avgHr    = (cntHr > 0) ? (sumHr / cntHr) : 0.0;
        double avgSpeed = (cntSpeed > 0) ? (sumSpeed / cntSpeed) : 0.0;
        double totalKm  = Math.max(0.0, lastDistanceKm);

        tvSummaryHeader.setVisibility(View.VISIBLE);
        tvHeartRate.setText("דופק ממוצע:\n" + String.format(Locale.getDefault(), "%.0f", avgHr));
        tvSpeed.setText("מהירות ממוצעת:\n" + String.format(Locale.getDefault(), "%.2f", avgSpeed) + " קמ\"ש");
        tvDistance.setText("מרחק:\n" + String.format(Locale.getDefault(), "%.2f", totalKm) + " ק\"מ");
        tvTimeStart.setText("זמן:\n" + formatElapsed(elapsedMs));

        tvFeedback.setVisibility(View.GONE);
        btnEndRun.setVisibility(View.GONE);
        btnConnectOrStart.setVisibility(View.GONE);
        llPhysiologicalData.setVisibility(View.VISIBLE);

        Toast.makeText(this, "הריצה הושלמה בהצלחה ✅", Toast.LENGTH_SHORT).show();

        RunSummaryDto dto = new RunSummaryDto();
        dto.id = getUserId();
        dto.average_heart_rate = round2(avgHr);
        dto.total_time_minutes = round2(totalTimeMinutes);
        dto.average_speed_kmh  = round2(avgSpeed);
        dto.total_distance_km  = round2(totalKm);
        dto.run_date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        historyApi.saveRun(dto).enqueue(new Callback<Object>() {
            @Override public void onResponse(Call<Object> call, Response<Object> response) {
                incrementRunsCountWithVolley(getUserId());
            }
            @Override public void onFailure(Call<Object> call, Throwable t) {
                incrementRunsCountWithVolley(getUserId());
            }
        });
    }

    // ------------------------- Small activity helpers -------------------------

    private void resetAggregates() {
        sumHr = 0.0; cntHr = 0;
        sumSpeed = 0.0; cntSpeed = 0;
        lastDistanceKm = 0.0;
    }

    private String formatElapsed(long ms) {
        long totalSec = TimeUnit.MILLISECONDS.toSeconds(ms);
        long mm = totalSec / 60;
        long ss = totalSec % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", mm, ss);
    }

    private int mapRunnerLevelFromPrefs() {
        SharedPreferences prefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        int appLevel = prefs.getInt("level", 0);
        return Math.max(1, Math.min(3, appLevel + 1));
    }

    private int getUserId() {
        SharedPreferences prefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        return prefs.getInt("userId", 0);
    }

    private HistoryApi buildNodeHistoryApi(String base) {
        HttpLoggingInterceptor log = new HttpLoggingInterceptor();
        log.setLevel(HttpLoggingInterceptor.Level.BASIC);
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(log).build();
        Retrofit r = new Retrofit.Builder()
                .baseUrl(base)
                .client(http)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        return r.create(HistoryApi.class);
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void saveNodeId(String nodeId) {
        SharedPreferences sp = getSharedPreferences("wear_prefs", MODE_PRIVATE);
        sp.edit().putString("node_id", nodeId).apply();
    }

    // ------------------------- DTO for Node -------------------------
    public static class RunSummaryDto {
        public int id;
        public double average_heart_rate;
        public double total_time_minutes;
        public double average_speed_kmh;
        public double total_distance_km;
        public String run_date; // "yyyy-MM-dd"
    }

    // ------------------------- Shared Volley updater -------------------------
    private void incrementRunsCountWithVolley(int userId) {
        try {
            String url = NODE_BASE_URL + "api/users/" + userId + "/runsCount";
            JSONObject body = new JSONObject();
            body.put("delta", 1);

            JsonObjectRequest req = new JsonObjectRequest(
                    Request.Method.PUT,
                    url,
                    body,
                    res -> {
                        int newCount = res.optInt("runsCount", -1);
                        if (newCount >= 0) {
                            getSharedPreferences("UserPrefs", MODE_PRIVATE)
                                    .edit()
                                    .putInt("runsCount", newCount)
                                    .apply();
                        }
                    },
                    err -> { /* non-critical */ }
            );

            VolleySingleton.getInstance(this).addToRequestQueue(req);

        } catch (Exception ignored) { }
    }
}
