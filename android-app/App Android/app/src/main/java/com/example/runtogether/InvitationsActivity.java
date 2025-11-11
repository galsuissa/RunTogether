package com.example.runtogether;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.Request;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * InvitationsActivity
 *
 * Responsibilities:
 *  • Fetch and display incoming/outgoing run invitations for the logged-in user.
 *  • Provide a simple empty-state message when no invitations are returned.
 */
public class InvitationsActivity extends AppCompatActivity {

    private static final String TAG = "InvitationsActivity";
    /** Backend endpoint; switch to HTTPS + BuildConfig in production. */
    private static final String BASE_URL = "http://10.0.2.2:3000/api/invitations/";
    private ListView listInvitations;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_invitations);

        // Ensure the ID matches your activity_invitations.xml ListView
        listInvitations = findViewById(R.id.listInvitations);

        // Retrieve the current user session (written during login)
        SharedPreferences prefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        int userId = prefs.getInt("userId", -1);

        if (userId == -1) {
            // No session available; avoid performing the network call
            Toast.makeText(this, "שגיאה: משתמש לא מחובר", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        fetchInvitations(userId);
    }

    /**
     * Fetches invitations for the given userId and binds them to the ListView.
     * On empty result, a localized message is shown.
     */
    private void fetchInvitations(int userId) {
        String url = BASE_URL + userId;

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.GET,
                url,
                null,
                response -> {
                    try {
                        JSONArray invitationsArray = response.optJSONArray("invitations");
                        if (invitationsArray == null || invitationsArray.length() == 0) {
                            // Empty-state UX; consider showing a dedicated empty view instead of Toast
                            Toast.makeText(this, "אין הזמנות", Toast.LENGTH_SHORT).show();
                            // TODO: Optionally clear any existing adapter to avoid showing stale data.
                            return;
                        }

                        List<JSONObject> invitationsList = new ArrayList<>();
                        for (int i = 0; i < invitationsArray.length(); i++) {
                            JSONObject invitation = invitationsArray.getJSONObject(i);
                            // NOTE: Add client-side filtering here if needed (e.g., by status).
                            invitationsList.add(invitation);
                        }

                        // Bind adapter (consider RecyclerView for large datasets).
                        InvitationAdapter adapter = new InvitationAdapter(this, invitationsList);
                        listInvitations.setAdapter(adapter);

                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse invitations payload", e);
                        Toast.makeText(this, "שגיאה בטיפול בהזמנות", Toast.LENGTH_SHORT).show();
                    }
                },
                error -> {
                    Log.e(TAG, "Failed to load invitations from server", error);
                    Toast.makeText(this, "שגיאה בטעינת ההזמנות מהשרת", Toast.LENGTH_SHORT).show();
                }
        );

        VolleySingleton.getInstance(this).addToRequestQueue(request);
    }
}
