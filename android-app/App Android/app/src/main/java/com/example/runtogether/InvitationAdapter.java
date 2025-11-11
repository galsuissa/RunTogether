package com.example.runtogether;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.Request;
import com.android.volley.toolbox.JsonObjectRequest;

import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Date;
import java.util.List;

/**
 * InvitationAdapter
 *
 * Responsibilities:
 *  • Render a list of incoming invitations and allow the user to approve (accept) each one.
 *  • For each row, fetch sender details (name/level/phone) and display them.
 */
public class InvitationAdapter extends BaseAdapter {
    private static final String USER_URL_BASE   = "http://10.0.2.2:3000/api/users/";
    private static final String STATUS_URL_BASE = "http://10.0.2.2:3000/api/invitations/status/";

    private final Context context;
    private final List<JSONObject> invitations; // raw invitation JSON objects from server
    private final LayoutInflater inflater;

    /** ViewHolder to avoid repeated findViewById calls. */
    private static final class ViewHolder {
        TextView tvSenderName;
        TextView tvLevel;
        TextView tvPhone;
        TextView tvInvitationDateTime;
        Button   btnApprove;
        String   invitationId; // bind the id to the row to use on click
        int      senderId;     // bind sender id (for potential future use)
    }

    public InvitationAdapter(Context context, List<JSONObject> invitations) {
        this.context = context;
        this.invitations = invitations;
        this.inflater = LayoutInflater.from(context);
    }

    @Override public int getCount() { return invitations.size(); }

    @Override public Object getItem(int position) { return invitations.get(position); }

    @Override public long getItemId(int position) { return position; }

    /** Maps numeric running level to a Hebrew label. */
    private String levelToText(int level) {
        switch (level) {
            case 0: return "מתחיל";
            case 1: return "מתקדם";
            case 2: return "מקצועי";
            default: return "לא ידוע";
        }
    }

    /** Parse server timestamp to millis. Supports ISO-8601 UTC (with millis) and epoch millis strings. */
    private long parseServerTimestamp(String ts) {
        if (ts == null || ts.isEmpty()) return 0L;

        // 1) Try epoch millis (numeric string)
        try {
            return Long.parseLong(ts);
        } catch (NumberFormatException ignore) {
            // not epoch millis – fall through to ISO parsing
        }

        // 2) Try ISO-8601 with millis (e.g., 2025-08-18T12:34:56.789Z)
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault());
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = sdf.parse(ts);
            return (d != null) ? d.getTime() : 0L;
        } catch (ParseException ignore) {}

        // 3) Try ISO-8601 without millis as a last resort
        try {
            SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault());
            sdf2.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = sdf2.parse(ts);
            return (d != null) ? d.getTime() : 0L;
        } catch (ParseException ignore) {}

        return 0L;
    }

    @SuppressLint("SetTextI18n")
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder h;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.list_item_invitation, parent, false);
            h = new ViewHolder();
            h.tvSenderName        = convertView.findViewById(R.id.tvSenderName);
            h.tvLevel             = convertView.findViewById(R.id.tvLevel);
            h.tvPhone             = convertView.findViewById(R.id.tvPhone);
            h.tvInvitationDateTime= convertView.findViewById(R.id.tvInvitationDateTime);
            h.btnApprove          = convertView.findViewById(R.id.btnConfirm);
            convertView.setTag(h);
        } else {
            h = (ViewHolder) convertView.getTag();
        }

        JSONObject invitation = invitations.get(position);

        // Extract bound fields early for clarity
        h.invitationId = invitation.optString("_id", "");
        h.senderId     = invitation.optInt("senderId", -1);

        // Display date/time (with graceful fallback if parsing fails)
        String timestampStr = invitation.optString("timestamp", "");
        long timestamp = parseServerTimestamp(timestampStr);
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String dateStr = (timestamp > 0) ? dateFormat.format(timestamp) : "לא זמין";
        String timeStr = (timestamp > 0) ? timeFormat.format(timestamp) : "";
        h.tvInvitationDateTime.setText("תאריך ושעה: " + dateStr + (timeStr.isEmpty() ? "" : " " + timeStr));

        // Disable approve button until sender details are fetched (prevents null tag race)
        h.btnApprove.setEnabled(false);
        h.btnApprove.setText("מאשר...");

        // Fetch sender details for this row (consider caching to reduce duplicate requests)
        if (h.senderId > 0) {
            String userUrl = USER_URL_BASE + h.senderId;

            JsonObjectRequest userReq = new JsonObjectRequest(
                    Request.Method.GET,
                    userUrl,
                    null,
                    response -> {
                        try {
                            String senderName = response.optString("fullName", "שם לא זמין");
                            int senderLevel   = response.optInt("level", -1);
                            String phone      = response.optString("phone", "טלפון לא זמין");

                            h.tvSenderName.setText(senderName);
                            h.tvLevel.setText("רמה: " + levelToText(senderLevel));
                            h.tvPhone.setText("טלפון: " + phone);

                            // Store the display name on the button for use in the approval toast
                            h.btnApprove.setTag(senderName);
                            h.btnApprove.setEnabled(true);
                            h.btnApprove.setText("אשר");
                        } catch (Exception e) {
                            h.tvSenderName.setText("שם לא זמין");
                            h.tvLevel.setText("רמה: לא ידוע");
                            h.tvPhone.setText("טלפון: לא זמין");
                            h.btnApprove.setEnabled(true); // allow approval even without name
                            h.btnApprove.setText("אשר");
                        }
                    },
                    error -> {
                        h.tvSenderName.setText("שם לא זמין");
                        h.tvLevel.setText("רמה: לא ידוע");
                        h.tvPhone.setText("טלפון: לא זמין");
                        h.btnApprove.setEnabled(true);
                        h.btnApprove.setText("אשר");
                    }
            );

            // Use the shared RequestQueue via the singleton (per your decision)
            VolleySingleton.getInstance(context).addToRequestQueue(userReq);
        } else {
            // Missing/invalid senderId; show fallbacks and keep button disabled
            h.tvSenderName.setText("שם לא זמין");
            h.tvLevel.setText("רמה: לא ידוע");
            h.tvPhone.setText("טלפון: לא זמין");
            h.btnApprove.setEnabled(false);
            h.btnApprove.setText("—");
        }

        // Approve click: PUT /invitations/status/{invitationId} with { status: "accepted" }
        h.btnApprove.setOnClickListener(v -> {
            // Guard: ensure we have an invitation id
            if (h.invitationId == null || h.invitationId.isEmpty()) {
                Toast.makeText(context, "שגיאה: מזהה הזמנה חסר", Toast.LENGTH_SHORT).show();
                return;
            }

            h.btnApprove.setEnabled(false);
            h.btnApprove.setText("אושרה");

            String senderName = (h.btnApprove.getTag() instanceof String)
                    ? (String) h.btnApprove.getTag()
                    : ""; // fallback if name not yet loaded

            updateInvitationStatus(h.invitationId, "accepted", h.btnApprove, senderName);
        });

        return convertView;
    }

    /**
     * Updates invitation status on the backend.
     * On success: shows a localized toast and locks the "Approve" button.
     */
    private void updateInvitationStatus(String invitationId, String newStatus, Button btnApprove, String senderName) {
        String url = STATUS_URL_BASE + invitationId;

        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("status", newStatus);
        } catch (Exception e) {
            Toast.makeText(context, "שגיאה בהכנת הבקשה", Toast.LENGTH_SHORT).show();
            // Restore button to allow retry
            btnApprove.setEnabled(true);
            btnApprove.setText("אשר");
            return;
        }

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.PUT,
                url,
                jsonBody,
                response -> {
                    // Success UX
                    String namePart = (senderName != null && !senderName.isEmpty()) ? (" עם " + senderName) : "";
                    Toast.makeText(context, "הריצה נקבעה" + namePart, Toast.LENGTH_SHORT).show();
                    btnApprove.setText("אושרה");
                    btnApprove.setEnabled(false);
                },
                error -> {
                    // Failure UX — restore to allow retry
                    Toast.makeText(context, "שגיאה בעדכון הסטטוס", Toast.LENGTH_SHORT).show();
                    btnApprove.setEnabled(true);
                    btnApprove.setText("אשר");
                }
        );

        // Enqueue with the singleton (replaces Volley.newRequestQueue(context).add(request))
        VolleySingleton.getInstance(context).addToRequestQueue(request);
    }
}
