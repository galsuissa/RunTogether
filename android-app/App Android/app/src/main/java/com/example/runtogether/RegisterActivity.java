package com.example.runtogether;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * RegisterActivity
 *
 * Responsibilities:
 *  • Render the registration form and validate user input on the client.
 *  • Build a JSON payload and send a POST request to the backend.
 *  • Handle server responses and display localized (Hebrew) user messages.
 *  • Redirect to LoginActivity upon successful registration.
 */
public class RegisterActivity extends AppCompatActivity {

    private EditText editFullName, editAge, editPhone, editCity, editStreet, editEmail, editPassword;
    private Spinner spinnerGender, spinnerLevel;
    private Button buttonRegister;

    /** Backend endpoint (emulator loopback). For release builds, use HTTPS and BuildConfig. */
    private static final String REGISTER_URL = "http://10.0.2.2:3000/api/register";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        bindViews();
        setupSpinners();
        buttonRegister.setOnClickListener(v -> registerUser());
    }

    /** Binds all views from the layout. */
    private void bindViews() {
        editFullName = findViewById(R.id.editFullName);
        editAge = findViewById(R.id.editAge);
        editPhone = findViewById(R.id.editPhone);
        editCity = findViewById(R.id.editCity);
        editStreet = findViewById(R.id.editStreet);
        editEmail = findViewById(R.id.editEmail);
        editPassword = findViewById(R.id.editPassword);
        spinnerGender = findViewById(R.id.spinnerGender);
        spinnerLevel = findViewById(R.id.spinnerLevel);
        buttonRegister = findViewById(R.id.buttonRegister);
    }

    /**
     * Initializes spinner adapters.
     */
    private void setupSpinners() {
        ArrayAdapter<String> genderAdapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item,
                new String[]{"בחר מין", "זכר", "נקבה"}
        );
        genderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerGender.setAdapter(genderAdapter);

        ArrayAdapter<String> levelAdapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item,
                new String[]{"בחר רמת ריצה", "מתחיל", "מתקדם", "מקצועי"}
        );
        levelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLevel.setAdapter(levelAdapter);
    }

    /**
     * Validates inputs, builds the registration payload, and sends it to the server.
     * On success, navigates to LoginActivity. On failure, shows a localized error message.
     */
    private void registerUser() {
        String fullName = editFullName.getText().toString().trim();
        String ageStr = editAge.getText().toString().trim();
        String phone = editPhone.getText().toString().trim();
        String city = editCity.getText().toString().trim();
        String street = editStreet.getText().toString().trim();
        String email = editEmail.getText().toString().trim();
        String password = editPassword.getText().toString().trim();
        String gender = spinnerGender.getSelectedItem().toString();
        String levelText = spinnerLevel.getSelectedItem().toString();

        boolean isValid = true;
        View firstInvalid = null;

        // Clear previous errors to avoid stale state.
        editFullName.setError(null);
        editAge.setError(null);
        editPhone.setError(null);
        editCity.setError(null);
        editStreet.setError(null);
        editEmail.setError(null);
        editPassword.setError(null);

        // Full name: non-empty and letters/spaces only (supports Unicode letters).
        if (fullName.isEmpty()) {
            editFullName.setError("נא להזין שם מלא");
            if (firstInvalid == null) firstInvalid = editFullName;
            isValid = false;
        } else if (!fullName.matches("^[\\p{L} ]+$")) {
            editFullName.setError("שם מלא חייב להכיל אותיות בלבד");
            if (firstInvalid == null) firstInvalid = editFullName;
            isValid = false;
        }

        // Age: must be a positive integer within a reasonable human range.
        int age = -1;
        if (ageStr.isEmpty()) {
            editAge.setError("נא להזין גיל");
            if (firstInvalid == null) firstInvalid = editAge;
            isValid = false;
        } else {
            try {
                age = Integer.parseInt(ageStr);
                if (age <= 0 || age > 120) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                editAge.setError("גיל לא תקין");
                if (firstInvalid == null) firstInvalid = editAge;
                isValid = false;
            }
        }

        // Phone: basic format using Android's PHONE pattern.
        if (phone.isEmpty()) {
            editPhone.setError("נא להזין טלפון");
            if (firstInvalid == null) firstInvalid = editPhone;
            isValid = false;
        } else if (!Patterns.PHONE.matcher(phone).matches()) {
            editPhone.setError("מספר טלפון לא תקין");
            if (firstInvalid == null) firstInvalid = editPhone;
            isValid = false;
        }

        // City: letters/spaces only.
        if (city.isEmpty()) {
            editCity.setError("נא להזין עיר");
            if (firstInvalid == null) firstInvalid = editCity;
            isValid = false;
        } else if (!city.matches("^[\\p{L} ]+$")) {
            editCity.setError("העיר חייבת להכיל אותיות בלבד");
            if (firstInvalid == null) firstInvalid = editCity;
            isValid = false;
        }

        // Street: alphanumeric + spaces.
        if (street.isEmpty()) {
            editStreet.setError("נא להזין רחוב");
            if (firstInvalid == null) firstInvalid = editStreet;
            isValid = false;
        } else if (!street.matches("^[\\p{L}0-9 ]+$")) {
            editStreet.setError("רחוב חייב להכיל אותיות, מספרים ורווחים בלבד");
            if (firstInvalid == null) firstInvalid = editStreet;
            isValid = false;
        }

        // Email: non-empty and valid format.
        if (email.isEmpty()) {
            editEmail.setError("נא להזין אימייל");
            if (firstInvalid == null) firstInvalid = editEmail;
            isValid = false;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            editEmail.setError("אימייל לא תקין");
            if (firstInvalid == null) firstInvalid = editEmail;
            isValid = false;
        }

        // Password: minimal length to reduce weak credentials.
        if (password.isEmpty()) {
            editPassword.setError("נא להזין סיסמה");
            if (firstInvalid == null) firstInvalid = editPassword;
            isValid = false;
        } else if (password.length() < 6) {
            editPassword.setError("הסיסמה חייבת להכיל לפחות 6 תווים");
            if (firstInvalid == null) firstInvalid = editPassword;
            isValid = false;
        }

        // Gender: require explicit selection (first item is a placeholder).
        if ("בחר מין".equals(gender)) {
            Toast.makeText(this, "בחר/י מין", Toast.LENGTH_SHORT).show();
            if (firstInvalid == null) firstInvalid = spinnerGender;
            isValid = false;
        }

        // Running level: require explicit selection (first item is a placeholder).
        if ("בחר רמת ריצה".equals(levelText)) {
            Toast.makeText(this, "בחר/י רמת ריצה", Toast.LENGTH_SHORT).show();
            if (firstInvalid == null) firstInvalid = spinnerLevel;
            isValid = false;
        }

        // Stop and focus the first invalid field if validation failed.
        if (!isValid) {
            Toast.makeText(this, "אנא תקן/י את השדות המסומנים", Toast.LENGTH_SHORT).show();
            if (firstInvalid != null) firstInvalid.requestFocus();
            return;
        }

        // Map human-readable level to a server-friendly numeric code.
        int level = mapLevel(levelText);

        // Collect availability selections into a JSON array.
        JSONArray availabilityArray = new JSONArray();
        if (((CheckBox) findViewById(R.id.checkMorning)).isChecked()) availabilityArray.put("בוקר");
        if (((CheckBox) findViewById(R.id.checkNoon)).isChecked())    availabilityArray.put("צהריים");
        if (((CheckBox) findViewById(R.id.checkEvening)).isChecked()) availabilityArray.put("ערב");

        // Build request payload.
        JSONObject params = new JSONObject();
        try {
            params.put("fullName", fullName);
            params.put("age", age);
            params.put("phone", phone);
            params.put("city", city);
            params.put("street", street);
            params.put("gender", gender);
            params.put("level", level);
            params.put("email", email);
            params.put("password", password);
            params.put("availability", availabilityArray);
        } catch (JSONException e) {
            // Defensive: should not fail with simple primitives & arrays.
            Toast.makeText(this, "שגיאה פנימית", Toast.LENGTH_SHORT).show();
            return;
        }

        // Execute POST /api/register
        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST,
                REGISTER_URL,
                params,
                response -> {
                    // Success path: show confirmation and navigate to login.
                    Toast.makeText(this, "נרשמת בהצלחה", Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(this, LoginActivity.class));
                    finish();
                },
                error -> {
                    // Attempt to surface a meaningful server error; otherwise show a generic message.
                    String message = "שגיאה בהרשמה";
                    if (error.networkResponse != null && error.networkResponse.data != null) {
                        try {
                            String body = new String(error.networkResponse.data, "UTF-8");
                            JSONObject data = new JSONObject(body);
                            if (data.has("message")) message = data.getString("message");
                        } catch (Exception ignored) {
                            // If parsing fails, keep the generic/fallback message.
                        }
                    } else {
                        message = "שגיאת רשת – ודא חיבור לאינטרנט";
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
        );

        // Apply a bounded retry policy to avoid long waits on flaky networks.
        request.setRetryPolicy(new DefaultRetryPolicy(10_000, 1, 1.5f));
        VolleySingleton.getInstance(this).addToRequestQueue(request);
    }

    /**
     * Maps the user-facing running level text to the server enum/int.
     * Keep this mapping centralized to maintain a single source of truth for the contract.
     *
     * @param levelText localized display text selected by the user
     * @return numeric level code expected by the backend
     */
    private int mapLevel(String levelText) {
        // Keep server contract centralized in one place
        switch (levelText) {
            case "מתחיל":  return 0;
            case "מתקדם":  return 1;
            case "מקצועי": return 2;
            default:       return -1;
        }
    }
}
