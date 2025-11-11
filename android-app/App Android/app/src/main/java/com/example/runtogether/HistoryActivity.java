package com.example.runtogether;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.android.volley.*;
import com.android.volley.toolbox.*;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class HistoryActivity extends AppCompatActivity {

    ListView listRuns;
    private static final String BASE_URL = "http://10.0.2.2:3000/api/history/";

    // רכיבי סטטיסטיקה
    TextView textAvgSpeed, textAvgDistance, textAvgHeartRate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        listRuns = findViewById(R.id.listRuns);
        textAvgSpeed = findViewById(R.id.textAvgSpeed);
        textAvgDistance = findViewById(R.id.textAvgDistance);
        textAvgHeartRate = findViewById(R.id.textAvgHeartRate);

        SharedPreferences prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE);
        int userId = prefs.getInt("userId", -1);

        if (userId == -1) {
            Toast.makeText(this, "שגיאה: משתמש לא מחובר", Toast.LENGTH_SHORT).show();
            return;
        }

        String url = BASE_URL + userId;

        Log.d("HISTORY", "User ID: " + userId);
        Log.d("HISTORY", "Request URL: " + url);

        JsonArrayRequest request = new JsonArrayRequest(Request.Method.GET, url, null,
                response -> {
                    ArrayList<String> runSummaries = new ArrayList<>();
                    double totalSpeed = 0, totalDistance = 0, totalHeartRate = 0;
                    int runCount = response.length();

                    try {
                        // ממיינים מהחדש לישן לפי תאריך
                        ArrayList<JSONObject> runsList = new ArrayList<>();
                        for (int i = 0; i < response.length(); i++) {
                            runsList.add(response.getJSONObject(i));
                        }

                        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());

                        runsList.sort((o1, o2) -> {
                            String s1 = o1.optString("run_date", "");
                            String s2 = o2.optString("run_date", "");
                            Date d1 = parseDateSafe(sdf, s1);
                            Date d2 = parseDateSafe(sdf, s2);
                            if (d1 != null && d2 != null) {
                                return Long.compare(d2.getTime(), d1.getTime()); // חדש קודם
                            }
                            return s2.compareTo(s1);
                        });

                        // יוצרים רשומות ריצה
                        for (JSONObject run : runsList) {
                            String date = run.optString("run_date", "לא ידוע");
                            double time = run.optDouble("total_time_minutes", 0);
                            double heartRate = run.optDouble("average_heart_rate", 0);
                            double speed = run.optDouble("average_speed_kmh", 0);
                            double distance = run.optDouble("total_distance_km", 0);

                            SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                            SimpleDateFormat outputFormat = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
                            Date parsedDate = inputFormat.parse(date);
                            String formattedDate = outputFormat.format(parsedDate);

                            totalSpeed += speed;
                            totalDistance += distance;
                            totalHeartRate += heartRate;

                            String summary = "📅 תאריך: " + formattedDate +
                                    "\n⏱️ זמן: " + time + " דק" +
                                    "\n💓 דופק ממוצע: " + String.format("%.2f", heartRate) +
                                    "\n🚀 מהירות: " + String.format("%.2f", speed) + " קמ\"ש" +
                                    "\n📏 מרחק: " + String.format("%.2f", distance) + " ק\"מ";

                            runSummaries.add(summary);
                        }

                        // עדכון ממוצעים בריבועים למעלה
                        if (runCount > 0) {
                            double avgSpeed = totalSpeed / runCount;
                            double avgDistance = totalDistance / runCount;
                            double avgHeartRate = totalHeartRate / runCount;

                            textAvgSpeed.setText(String.format("%.2f\nקמ\"ש", avgSpeed));
                            textAvgDistance.setText(String.format("%.2f\nק\"מ", avgDistance));
                            textAvgHeartRate.setText(String.format("%.2f\nBPM", avgHeartRate));
                        }

                    } catch (Exception e) {
                        Log.e("HISTORY", "שגיאה בעיבוד JSON", e);
                    }

                    if (runSummaries.isEmpty()) {
                        Toast.makeText(this, "לא נמצאו ריצות עבור המשתמש", Toast.LENGTH_SHORT).show();
                    }

                    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.list_item_run, R.id.textRun, runSummaries);
                    listRuns.setAdapter(adapter);
                },
                error -> {
                    Log.e("HISTORY", "❌ שגיאה בטעינת הריצות", error);
                    Toast.makeText(this, "שגיאה בטעינת הריצות", Toast.LENGTH_SHORT).show();
                });

        Volley.newRequestQueue(this).add(request);
    }

    // פונקציית עזר לפריסת תאריכים
    private Date parseDateSafe(SimpleDateFormat sdf, String s) {
        try {
            return sdf.parse(s);
        } catch (ParseException e) {
            return null;
        }
    }
}
