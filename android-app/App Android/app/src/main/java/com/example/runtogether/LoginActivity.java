package com.example.runtogether;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * LoginActivity
 *
 * Responsibilities:
 *  • Render the login form and validate user input client-side.
 *  • Send a login request to the backend using Volley (JSON POST).
 *  • Persist minimal user identity (userId) for later use.
 *  • Navigate to RegisterActivity (sign-up) or MainActivity on success.
 */
public class LoginActivity extends AppCompatActivity {

    private EditText editEmail, editPassword;
    private Button btnLogin;
    private TextView tvGoToRegister;

    /** Backend endpoint (emulator loopback). Move to BuildConfig for environment-specific builds. */
    private static final String LOGIN_URL = "http://10.0.2.2:3000/api/login";

    /** SharedPreferences configuration for lightweight user state. */
    private static final String PREFS_NAME = "UserPrefs";
    private static final String PREF_KEY_USER_ID = "userId";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // View binding
        editEmail = findViewById(R.id.editEmail);
        editPassword = findViewById(R.id.editPassword);
        btnLogin = findViewById(R.id.btnLogin);
        tvGoToRegister = findViewById(R.id.tvGoToRegister);

        // Attempt login when user taps the primary CTA.
        btnLogin.setOnClickListener(v -> loginUser());

        // Navigate to the registration screen for new users.
        tvGoToRegister.setOnClickListener(v ->
                startActivity(new Intent(LoginActivity.this, RegisterActivity.class)));
    }

    /**
     * Validates input and performs the login flow:
     *  1) Client-side checks (non-empty, valid email format).
     *  2) Build JSON payload and send POST request via Volley.
     *  3) On success: persist userId and navigate to MainActivity.
     *  4) On failure: show user-friendly error messages based on HTTP status.
     */
    private void loginUser() {
        String email = editEmail.getText().toString().trim();
        String password = editPassword.getText().toString().trim();

        // Guard: minimal validation before issuing a network call.
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "נא למלא את כל השדות", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "אימייל לא תקין", Toast.LENGTH_SHORT).show();
            return;
        }

        // Build request body
        JSONObject params = new JSONObject();
        try {
            params.put("email", email);
            params.put("password", password);
        } catch (JSONException e) {
            // Defensive: construction is deterministic; this should not occur for simple K/V.
            Log.e("LoginActivity", "JSON build error", e);
            Toast.makeText(this, "שגיאה פנימית", Toast.LENGTH_SHORT).show();
            return;
        }

        // Issue POST /api/login
        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST,
                LOGIN_URL,
                params,
                response -> {
                    try {
                        boolean success = response.getBoolean("success");
                        String message = response.optString("message", "");

                        if (success) {
                            // NOTE: Two reads of userId (getInt + optInt). Consider consolidating.
                            int userId = response.getInt("userId"); // (currently unused)
                            // Persist userId for later authenticated requests or personalization.
                            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                            int userIdFromServer = response.optInt("userId", -1);
                            prefs.edit().putInt(PREF_KEY_USER_ID, userIdFromServer).apply();

                            // Success UX: confirm and forward to main screen; prevent back to login.
                            Toast.makeText(this, "התחברת בהצלחה", Toast.LENGTH_SHORT).show();
                            startActivity(new Intent(this, MainActivity.class));
                            finish(); // Prevent back navigation to login
                        } else {
                            // Show server-provided message when available; fallback to generic.
                            Toast.makeText(this, message.isEmpty() ? "שגיאה בהתחברות" : message, Toast.LENGTH_SHORT).show();
                        }
                    } catch (JSONException e) {
                        // Server responded, but schema was not as expected.
                        Log.e("LoginActivity", "Failed to parse server response", e);
                        Toast.makeText(this, "שגיאה בנתוני השרת", Toast.LENGTH_LONG).show();
                    }
                },
                error -> {
                    // Map common HTTP errors to localized, user-friendly messages.
                    String message = "שגיאה בהתחברות";
                    if (error.networkResponse != null) {
                        int statusCode = error.networkResponse.statusCode;
                        if (statusCode == 401) {
                            message = "סיסמה שגויה";
                        } else if (statusCode == 404) {
                            message = "משתמש לא קיים במערכת";
                        }
                    } else {
                        // No network response typically means connectivity/timeout/DNS issues.
                        message = "שגיאת רשת – ודא חיבור לאינטרנט";
                    }

                    Log.e("LoginActivity", "Login request failed", error);
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
        );

        // Apply a bounded retry policy to avoid long waits on flaky networks.
        request.setRetryPolicy(new DefaultRetryPolicy(
                10_000, // timeout ms
                1,      // max retries
                1.5f    // backoff multiplier
        ));

        // Enqueue request.
        VolleySingleton.getInstance(this).addToRequestQueue(request);

    }
}
