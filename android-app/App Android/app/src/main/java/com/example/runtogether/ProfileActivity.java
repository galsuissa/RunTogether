package com.example.runtogether;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.Request;
// NOTE: JsonArrayRequest import not used in this class. Consider removing to keep imports clean.
// import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * ProfileActivity
 *
 * Responsibilities:
 *  • Load and display the current user's profile and aggregate stats (runs/partners).
 *  • Validate profile edits client-side and submit updates to the backend.
 *  • Persist minimal session info in SharedPreferences (userId) and redirect if missing.
 */
public class ProfileActivity extends AppCompatActivity {

    private EditText etFullName, etAge, etPhone, etCity, etStreet;
    private Spinner spGender, spLevel;
    private CheckBox chMorning, chNoon, chEvening;
    private Button btnSave;
    private ProgressBar progress;
    private TextView tvRunsCount, tvPartnersCount;

    /** Backend base URL; prefer BuildConfig + HTTPS for production builds. */
    private static final String BASE_URL = "http://10.0.2.2:3000/api";

    /** SharedPreferences keys for minimal session state. */
    private static final String PREFS_NAME = "UserPrefs";
    private static final String PREF_KEY_USER_ID = "userId";

    private int userId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        bindViews();
        setupSpinners();
        resolveUserIdOrRedirect();
        // NOTE: If resolveUserIdOrRedirect() finishes the Activity, we still call fetchProfile().
        // Consider guarding here with `if (isFinishing()) return;` to avoid issuing a request post-finish.
        fetchProfile();

        btnSave.setOnClickListener(v -> saveProfile());
    }

    /** Binds XML views to fields. */
    private void bindViews() {
        etFullName = findViewById(R.id.etFullName);
        etAge      = findViewById(R.id.etAge);
        etPhone    = findViewById(R.id.etPhone);
        etCity     = findViewById(R.id.etCity);
        etStreet   = findViewById(R.id.etStreet);

        spGender   = findViewById(R.id.spGender);
        spLevel    = findViewById(R.id.spLevel);

        chMorning  = findViewById(R.id.chMorning);
        chNoon     = findViewById(R.id.chNoon);
        chEvening  = findViewById(R.id.chEvening);

        btnSave    = findViewById(R.id.btnSave);
        progress   = findViewById(R.id.progress);

        tvRunsCount     = findViewById(R.id.tvRunsCount);
        tvPartnersCount = findViewById(R.id.tvPartnersCount);
    }

    /**
     * Initializes gender/level spinners.
     * */
    private void setupSpinners() {
        ArrayAdapter<String> genderAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                new String[]{"בחר מין", "זכר", "נקבה"});
        genderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGender.setAdapter(genderAdapter);

        ArrayAdapter<String> levelAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                new String[]{"בחר רמת ריצה", "מתחיל", "מתקדם", "מקצועי"});
        levelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spLevel.setAdapter(levelAdapter);
    }

    /**
     * Reads userId from SharedPreferences; redirects to LoginActivity if missing/invalid.
     */
    private void resolveUserIdOrRedirect() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        userId = prefs.getInt(PREF_KEY_USER_ID, -1);
        if (userId <= 0) {
            Toast.makeText(this, "נדרש להתחבר מחדש", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        }
    }

    /**
     * Fetches the current profile from the backend and applies it to the UI.
     */
    private void fetchProfile() {
        setLoading(true);
        String url = BASE_URL + "/users/" + userId;

        JsonObjectRequest req = new JsonObjectRequest(
                Request.Method.GET, url, null,
                res -> {
                    setLoading(false);
                    applyProfileToUi(res);
                },
                err -> {
                    setLoading(false);
                    Log.e("ProfileActivity", "Fetch profile failed", err);
                    Toast.makeText(this, "שגיאה בטעינת הפרופיל", Toast.LENGTH_LONG).show();
                }
        );
        Volley.newRequestQueue(this).add(req);
    }

    /**
     * Populates UI fields from the server response.
     * Uses defensive parsing (opt*) to avoid hard failures on partial payloads.
     */
    private void applyProfileToUi(JSONObject res) {
        etFullName.setText(res.optString("fullName", ""));
        int age = res.optInt("age", 0);
        if (age > 0) etAge.setText(String.valueOf(age));
        etPhone.setText(res.optString("phone", ""));
        etCity.setText(res.optString("city", ""));
        etStreet.setText(res.optString("street", ""));

        String gender = res.optString("gender", "");
        if ("זכר".equals(gender)) spGender.setSelection(1);
        else if ("נקבה".equals(gender)) spGender.setSelection(2);
        else spGender.setSelection(0); // placeholder

        // Level selection: server -> spinner index (offset +1 to account for placeholder at index 0)
        int level = res.optInt("level", 0);
        spLevel.setSelection(Math.max(0, Math.min(level + 1, 3)));

        // Availability checkboxes
        chMorning.setChecked(false);
        chNoon.setChecked(false);
        chEvening.setChecked(false);
        JSONArray availability = res.optJSONArray("availability");
        if (availability != null) {
            for (int i = 0; i < availability.length(); i++) {
                String a = availability.optString(i, "");
                if ("בוקר".equals(a))   chMorning.setChecked(true);
                if ("צהריים".equals(a)) chNoon.setChecked(true);
                if ("ערב".equals(a))    chEvening.setChecked(true);
            }
        }

        // Aggregate stats
        tvRunsCount.setText(String.valueOf(res.optInt("runsCount", 0)));
        tvPartnersCount.setText(String.valueOf(res.optInt("partnersCount", 0)));
    }

    /**
     * Validates inputs, builds the update payload, and submits it via PUT /users/{id}.
     * On success, updates the UI, notifies the user, and returns to MainActivity.
     */
    private void saveProfile() {
        String fullName = etFullName.getText().toString().trim();
        String ageStr   = etAge.getText().toString().trim();
        String phone    = etPhone.getText().toString().trim();
        String city     = etCity.getText().toString().trim();
        String street   = etStreet.getText().toString().trim();
        String gender   = spGender.getSelectedItem().toString();
        String levelTxt = spLevel.getSelectedItem().toString();

        boolean isValid = true;

        // Clear previous error messages (avoid stale errors from earlier attempts).
        etFullName.setError(null);
        etAge.setError(null);
        etPhone.setError(null);
        etCity.setError(null);
        etStreet.setError(null);

        // Full name: required; letters/spaces only (Unicode-aware).
        if (fullName.isEmpty()) {
            etFullName.setError("נא להזין שם מלא");
            isValid = false;
        } else if (!fullName.matches("^[\\p{L} ]+$")) {
            etFullName.setError("שם מלא חייב להכיל אותיות בלבד");
            isValid = false;
        }

        // Age: required; integer within a reasonable human range.
        int age = -1;
        if (ageStr.isEmpty()) {
            etAge.setError("נא להזין גיל");
            isValid = false;
        } else {
            try {
                age = Integer.parseInt(ageStr);
                if (age <= 0 || age > 120) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                etAge.setError("גיל לא תקין");
                isValid = false;
            }
        }

        // Phone: required; basic validation with Android's pattern.
        if (phone.isEmpty()) {
            etPhone.setError("נא להזין טלפון");
            isValid = false;
        } else if (!Patterns.PHONE.matcher(phone).matches()) {
            etPhone.setError("מספר טלפון לא תקין");
            isValid = false;
        }

        // City: letters/spaces only.
        if (city.isEmpty()) {
            etCity.setError("נא להזין עיר");
            isValid = false;
        } else if (!city.matches("^[\\p{L} ]+$")) {
            etCity.setError("העיר חייבת להכיל אותיות בלבד");
            isValid = false;
        }

        // Street: alphanumeric + spaces only.
        if (street.isEmpty()) {
            etStreet.setError("נא להזין רחוב");
            isValid = false;
        } else if (!street.matches("^[\\p{L}0-9 ]+$")) {
            etStreet.setError("רחוב יכול להכיל רק אותיות, מספרים ורווחים");
            isValid = false;
        }

        // Require explicit selections for gender and level (first items are placeholders).
        if ("בחר מין".equals(gender) || "בחר רמת ריצה".equals(levelTxt)) {
            Toast.makeText(this, "בחר/י מין ורמת ריצה", Toast.LENGTH_SHORT).show();
            isValid = false;
        }

        // Abort on validation failure.
        if (!isValid) {
            Toast.makeText(this, "נא לתקן את השדות המסומנים", Toast.LENGTH_SHORT).show();
            return;
        }

        // Map display text to the numeric level expected by the backend.
        // IMPORTANT: Align this mapping with the server contract and with RegisterActivity.
        int level = mapLevel(levelTxt);

        // Availability selection -> JSON array.
        JSONArray availability = new JSONArray();
        if (chMorning.isChecked()) availability.put("בוקר");
        if (chNoon.isChecked())    availability.put("צהריים");
        if (chEvening.isChecked()) availability.put("ערב");

        // Build the JSON payload for the update.
        JSONObject body = new JSONObject();
        try {
            body.put("fullName", fullName);
            body.put("age", age);
            body.put("phone", phone);
            body.put("city", city);
            body.put("street", street);
            body.put("gender", gender);
            body.put("level", level);
            body.put("availability", availability);
        } catch (JSONException e) {
            Log.e("ProfileActivity", "JSON build error", e);
            Toast.makeText(this, "שגיאה פנימית", Toast.LENGTH_SHORT).show();
            return;
        }

        // Execute PUT /api/users/{id}
        setLoading(true);
        String url = BASE_URL + "/users/" + userId;

        JsonObjectRequest req = new JsonObjectRequest(
                Request.Method.PUT, url, body,
                res -> {
                    setLoading(false);
                    Toast.makeText(this, "הפרופיל נשמר בהצלחה", Toast.LENGTH_SHORT).show();
                    applyProfileToUi(res);
                    // Navigate back to main screen after saving.
                    startActivity(new Intent(ProfileActivity.this, MainActivity.class));
                    finish();
                },
                err -> {
                    setLoading(false);
                    Log.e("ProfileActivity", "Save profile failed", err);
                    Toast.makeText(this, "שגיאה בשמירת הפרופיל", Toast.LENGTH_LONG).show();
                }
        );
        Volley.newRequestQueue(this).add(req);
    }

    /**
     * Maps user-facing level text to a numeric code.
     * TODO: Unify with the mapping used in RegisterActivity to maintain a single contract.
     *
     * @param txt localized display text
     * @return numeric level code
     */
    private int mapLevel(String txt) {
        switch (txt) {
            case "מתחיל":  return 0;
            case "מתקדם":  return 1;
            case "מקצועי": return 2;
            default:       return -1;
        }
    }

    /** Shows/hides the loading indicator and disables the save button while a request is in flight. */
    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnSave.setEnabled(!loading);
    }
}
