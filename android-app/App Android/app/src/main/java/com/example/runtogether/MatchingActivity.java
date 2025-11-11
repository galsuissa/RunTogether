package com.example.runtogether;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.JsonObjectRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MatchingActivity
 *
 * Responsibilities:
 *  • Fetch a list of matching partners for the current user.
 *  • Fetch the server-side "pending invitations" set and reflect it in the UI.
 *  • Keep a single source of truth for pending invitation state (receiverIds) provided by the server.
 */
public class MatchingActivity extends AppCompatActivity {

    private static final String TAG = "MATCHING";
    /** Backend endpoints; prefer BuildConfig + HTTPS for production. */
    private static final String MATCH_URL   = "http://10.0.2.2:3000/api/match";
    private static final String PENDING_URL = "http://10.0.2.2:3000/api/invitations/pending?senderId=";

    private ListView listMatches;
    private int userId;

    /** Server-truth set of receiverIds that currently have pending invitations. */
    private final Set<Integer> pendingReceivers = new HashSet<>();
    private MatchAdapter adapter;
    private final List<JSONObject> matchList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_matching);

        listMatches = findViewById(R.id.listMatches);

        // Read the logged-in user id from SharedPreferences (written during login flow).
        SharedPreferences prefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        userId = prefs.getInt("userId", -1);

        if (userId == -1) {
            // No session; avoid making network calls without a valid user.
            Toast.makeText(this, "שגיאה: משתמש לא מחובר", Toast.LENGTH_SHORT).show();
            // Optional: startActivity(new Intent(this, LoginActivity.class)); finish();
            return;
        }

        // 1) Load matches for the current user; on success, load pending invitations.
        fetchMatches();
    }

    /**
     * Loads matches for the current user (POST /match).
     * On success: updates the local list and then calls fetchPendingAndBind() to hydrate "invited" flags.
     */
    private void fetchMatches() {
        try {
            JSONObject body = new JSONObject();
            body.put("userId", userId);

            JsonObjectRequest req = new JsonObjectRequest(
                    Request.Method.POST,
                    MATCH_URL,
                    body,
                    response -> {
                        // Refresh the matches list from the server payload.
                        matchList.clear();
                        JSONArray arr = response.optJSONArray("matches");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject obj = arr.optJSONObject(i);
                                if (obj != null) matchList.add(obj);
                            }
                        }

                        if (matchList.isEmpty()) {
                            Toast.makeText(this, "לא נמצאו התאמות", Toast.LENGTH_SHORT).show();
                        }

                        // 2) Only after we have matches, load pending invitations to mark "invited" correctly.
                        fetchPendingAndBind();
                    },
                    error -> {
                        Log.e(TAG, "Match request failed", error);
                        Toast.makeText(this, "שגיאה בטעינת התאמות", Toast.LENGTH_SHORT).show();
                    }
            );

            // Keep retries bounded to avoid long UI hangs on flaky networks.
            req.setRetryPolicy(new DefaultRetryPolicy(7000, 1, 1.0f));
            VolleySingleton.getInstance(this).getRequestQueue().add(req);
        } catch (Exception e) {
            // Defensive: building the request body should not normally fail.
            Log.e(TAG, "Failed to build match request", e);
        }
    }

    /**
     * Fetches the server-side set of pending invitations for the current sender (GET /invitations/pending).
     * Updates the adapter with the "server-truth" pending set so that "Invite" buttons reflect reality.
     */
    private void fetchPendingAndBind() {
        String url = PENDING_URL + userId;

        JsonArrayRequest pendingReq = new JsonArrayRequest(
                Request.Method.GET,
                url,
                null,
                response -> {
                    // Rebuild the server-truth set of receiverIds that have pending invitations.
                    pendingReceivers.clear();
                    for (int i = 0; i < response.length(); i++) {
                        JSONObject row = response.optJSONObject(i);
                        if (row == null) continue;

                        // Expected server payload shape: { "receiverId": <int> }
                        // Adjust this parsing if your server uses a different key or structure.
                        int rid = row.optInt("receiverId", -1);
                        if (rid > 0) pendingReceivers.add(rid);
                    }

                    // Bind the adapter if needed, or just notify to refresh "invited" states.
                    if (adapter == null) {
                        adapter = new MatchAdapter(
                                this,
                                matchList,
                                userId,
                                pendingReceivers,
                                this::refreshPendingAfterSend // callback invoked by adapter after successful invite
                        );
                        listMatches.setAdapter(adapter);
                    } else {
                        adapter.notifyDataSetChanged();
                    }
                },
                error -> {
                    Log.e(TAG, "Pending fetch failed", error);
                    // Even if pending fails, present the matches so the UI remains usable.
                    if (adapter == null) {
                        adapter = new MatchAdapter(
                                this,
                                matchList,
                                userId,
                                pendingReceivers,        // empty set means nothing is marked as pending
                                this::refreshPendingAfterSend
                        );
                        listMatches.setAdapter(adapter);
                    } else {
                        adapter.notifyDataSetChanged();
                    }
                }
        );

        pendingReq.setRetryPolicy(new DefaultRetryPolicy(7000, 1, 1.0f));
        VolleySingleton.getInstance(this).getRequestQueue().add(pendingReq);
    }

    /**
     * Callback provided to the adapter: after an invitation is successfully sent,
     * refresh the pending set from the server so the UI stays consistent.
     */
    private void refreshPendingAfterSend() {
        fetchPendingAndBind();
    }
}
