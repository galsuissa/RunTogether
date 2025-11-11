// MatchAdapter.java
package com.example.runtogether;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.toolbox.JsonObjectRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MatchAdapter
 *
 * Purpose:
 *  • Renders a list of potential running partners and exposes an "Invite" action.
 *  • The "invited" state is derived exclusively from the server-reported pending set.
 *
 * Key Ideas:
 *  • Server is the single source of truth for pending invitations (no local optimistic state).
 *  • After sending an invitation, we call back to refresh the pending set from the server.
 *  • Dialog flows (date → time) are guarded to restore the button state if the user cancels.
 */
public class MatchAdapter extends BaseAdapter {

    private static final String TAG = "MatchAdapter";
    /** Backend endpoint; prefer HTTPS + BuildConfig in production. */
    private static final String INVITE_URL = "http://10.0.2.2:3000/api/invitations";

    private final Context context;
    private final List<JSONObject> matches;
    private final int senderId;

    /** Server-truth set of receiverIds that currently have a pending invitation from senderId. */
    private final Set<Integer> pendingReceivers;

    /** Callback to trigger a fresh pending fetch on success (implemented by MatchingActivity). */
    public interface OnPendingRefresh { void refresh(); }
    private final OnPendingRefresh onPendingRefresh;

    public MatchAdapter(Context context,
                        List<JSONObject> matches,
                        int senderId,
                        Set<Integer> pendingReceivers,
                        OnPendingRefresh onPendingRefresh) {
        this.context = context;
        this.matches = matches;
        this.senderId = senderId;
        this.pendingReceivers = pendingReceivers != null ? pendingReceivers : new HashSet<>();
        this.onPendingRefresh = onPendingRefresh;
    }

    @Override public int getCount() { return matches.size(); }
    @Override public Object getItem(int position) { return matches.get(position); }
    @Override public long getItemId(int position) { return position; } // No stable IDs available

    /** ViewHolder to minimize repeated findViewById calls on scroll. */
    private static class ViewHolder {
        TextView tvName, tvScore, tvCity, tvPhone, tvLevel, tvAvailability;
        Button btnInvite;
    }

    /**
     * Binds one match row. Button state is computed from the server-pending set.
     * On "Invite", user picks date & time, then we POST the invitation.
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder h;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.list_item_match, parent, false);
            h = new ViewHolder();
            h.tvName = convertView.findViewById(R.id.tvName);
            h.tvScore = convertView.findViewById(R.id.tvScore);
            h.tvCity = convertView.findViewById(R.id.tvCity);
            h.tvPhone = convertView.findViewById(R.id.tvPhone);
            h.tvLevel = convertView.findViewById(R.id.tvLevel);
            h.tvAvailability = convertView.findViewById(R.id.tvAvailability);
            h.btnInvite = convertView.findViewById(R.id.btnInvite);
            convertView.setTag(h);
        } else {
            h = (ViewHolder) convertView.getTag();
        }

        JSONObject match = matches.get(position);

        final String name = match.optString("name", "");
        final double score = match.optDouble("score", 0.0);
        final String city = match.optString("city", "");
        final String phone = match.optString("phone", "");
        final int level = match.optInt("level", 0);
        final int receiverId = match.optInt("id", -1);

        // Availability -> "a, b, c" (Hebrew strings kept as-is)
        String availability = "לא ידוע";
        JSONArray availArr = match.optJSONArray("availability");
        if (availArr != null && availArr.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < availArr.length(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(availArr.optString(i));
            }
            availability = sb.toString();
        }

        // Bind UI text
        h.tvName.setText(name);
        h.tvScore.setText("התאמה: " + Math.round(score) + "%");
        h.tvCity.setText("עיר: " + city);
        h.tvPhone.setText("טלפון: " + phone);
        h.tvLevel.setText("רמה: " + levelToText(level));
        h.tvAvailability.setText("זמינות: " + availability);

        // Button state is derived from server truth only
        boolean isPending = receiverId > 0 && pendingReceivers.contains(receiverId);
        setInviteButtonState(h.btnInvite, isPending);

        // Invite flow: pick date → pick time → send POST
        h.btnInvite.setOnClickListener(v -> {
            if (receiverId < 0) {
                Toast.makeText(context, "שגיאה: מזהה נמען לא תקין", Toast.LENGTH_SHORT).show();
                return;
            }

            // Guard against rapid double-taps while dialogs are open
            h.btnInvite.setEnabled(false);

            final Calendar cal = Calendar.getInstance();

            DatePickerDialog datePickerDialog = new DatePickerDialog(
                    context,
                    R.style.OrangeDatePickerDialogTheme,
                    (view, year, month, dayOfMonth) -> {
                        cal.set(Calendar.YEAR, year);
                        cal.set(Calendar.MONTH, month);
                        cal.set(Calendar.DAY_OF_MONTH, dayOfMonth);

                        TimePickerDialog timePickerDialog = new TimePickerDialog(
                                context,
                                R.style.OrangeTimePickerDialogTheme,
                                (timeView, hour, minute) -> {
                                    cal.set(Calendar.HOUR_OF_DAY, hour);
                                    cal.set(Calendar.MINUTE, minute);
                                    cal.set(Calendar.SECOND, 0);
                                    cal.set(Calendar.MILLISECOND, 0);

                                    long tsMillis = cal.getTimeInMillis();

                                    // POST invitation; on success, refresh pending from server (source of truth)
                                    sendRunInvitationWithDate(
                                            senderId,
                                            receiverId,
                                            tsMillis,
                                            () -> {
                                                if (onPendingRefresh != null) onPendingRefresh.refresh();
                                                Toast.makeText(
                                                        context,
                                                        "📩 הזמנת את " + name + " ל-" +
                                                                android.text.format.DateFormat.format("dd/MM/yyyy HH:mm", cal),
                                                        Toast.LENGTH_LONG
                                                ).show();
                                            },
                                            errMsg -> {
                                                // On error restore button state based on server truth (likely "not pending")
                                                setInviteButtonState(h.btnInvite, false);
                                                Toast.makeText(context, "שגיאה בשליחת ההזמנה: " + errMsg, Toast.LENGTH_SHORT).show();
                                            }
                                    );
                                },
                                cal.get(Calendar.HOUR_OF_DAY),
                                cal.get(Calendar.MINUTE),
                                true
                        );

                        // Style & restore behaviors
                        timePickerDialog.setOnShowListener(d -> tintDialogButtons(timePickerDialog));
                        timePickerDialog.setOnDismissListener(d -> {
                            boolean stillPending = pendingReceivers.contains(receiverId);
                            setInviteButtonState(h.btnInvite, stillPending);
                        });
                        timePickerDialog.show();
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
            );

            // Style & restore on dismiss (user canceled date picking)
            datePickerDialog.setOnShowListener(d -> tintDialogButtons(datePickerDialog));
            datePickerDialog.setOnDismissListener(d -> {
                boolean stillPending = pendingReceivers.contains(receiverId);
                setInviteButtonState(h.btnInvite, stillPending);
            });
            datePickerDialog.show();
        });

        return convertView;
    }

    /**
     * Sends an invitation to the backend with the selected timestamp.
     * Success → onSuccess(); Error → onError(message)
     */
    private void sendRunInvitationWithDate(int senderId,
                                           int receiverId,
                                           long timestampMillis,
                                           Runnable onSuccess,
                                           java.util.function.Consumer<String> onError) {
        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("senderId", senderId);
            jsonBody.put("receiverId", receiverId);
            jsonBody.put("timestamp", timestampMillis); // Epoch millis; ensure server expects this unit

            JsonObjectRequest request = new JsonObjectRequest(
                    Request.Method.POST,
                    INVITE_URL,
                    jsonBody,
                    response -> {
                        // Do not mutate local pending state; server remains the source of truth.
                        if (onSuccess != null) onSuccess.run();
                    },
                    error -> {
                        String msg = (error.getMessage() != null) ? error.getMessage() : "שגיאת רשת";
                        Log.e(TAG, "Invite request failed", error);
                        if (onError != null) onError.accept(msg);
                    }
            );

            // Reasonable retry policy to bound UI wait time.
            request.setRetryPolicy(new DefaultRetryPolicy(7000, 1, 1.0f));
            VolleySingleton.getInstance(context).getRequestQueue().add(request);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build invite body", e);
            if (onError != null) onError.accept("שגיאה בנתוני הבקשה");
        }
    }

    /** Tints AlertDialog action buttons (for Date/Time pickers with AlertDialog base). */
    private void tintDialogButtons(android.app.AlertDialog dialog) {
        Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        Button negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (positive != null) positive.setTextColor(Color.parseColor("#FF9800"));
        if (negative != null) negative.setTextColor(Color.parseColor("#FF9800"));
    }

    private void tintDialogButtons(DatePickerDialog dialog) {
        Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        Button negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (positive != null) positive.setTextColor(Color.parseColor("#FF9800"));
        if (negative != null) negative.setTextColor(Color.parseColor("#FF9800"));
    }

    private void tintDialogButtons(TimePickerDialog dialog) {
        Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        Button negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (positive != null) positive.setTextColor(Color.parseColor("#FF9800"));
        if (negative != null) negative.setTextColor(Color.parseColor("#FF9800"));
    }

    /** Maps numeric level to a Hebrew label (kept for user-facing consistency). */
    private String levelToText(int level) {
        switch (level) {
            case 0: return "מתחיל";
            case 1: return "מתקדם";
            case 2: return "מקצועי";
            default: return "לא ידוע";
        }
    }

    /**
     * Updates "Invite" button visuals. When pending=true we lock the button and show "sent".
     * Server drives the state; we do not set it optimistically on the client.
     */
    private void setInviteButtonState(Button btn, boolean pending) {
        if (pending) {
            btn.setText("הזמנה נשלחה");
            btn.setEnabled(false);
            btn.setAlpha(0.75f);
        } else {
            btn.setText("הזמן לריצה");
            btn.setEnabled(true);
            btn.setAlpha(1f);
        }
    }
}
