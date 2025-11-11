package com.example.runtogether;

import android.content.Intent; // Explicit intent import
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

/**
 * MainActivity
 *
 * Responsibilities:
 *  • Serve as the app's main entry point after authentication.
 *  • Present primary navigation actions and route to feature screens via explicit Intents.
 */
public class MainActivity extends AppCompatActivity {

    // Main menu navigation buttons
    private Button btnStartRun, btnViewHistory, btnSearchPartner, btnViewInvites, btnViewProfile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Inflates a simple layout with five primary actions; no drawer/navigation host here.
        setContentView(R.layout.activity_main);
        bindViews();
        setupClickListeners();
    }

    /** Binds view references from activity_main.xml to member fields. */
    private void bindViews() {
        btnStartRun      = findViewById(R.id.btnStartRun);
        btnViewHistory   = findViewById(R.id.btnViewHistory);
        btnSearchPartner = findViewById(R.id.btnSearchPartner);
        btnViewInvites   = findViewById(R.id.btnViewInvites);
        btnViewProfile   = findViewById(R.id.btnViewProfile);
    }

    /**
     * Attaches click listeners that navigate to feature screens.
     * NOTE: We use the Activity context (MainActivity.this) for clarity with explicit Intents.
     */
    private void setupClickListeners() {
        btnStartRun.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, RunActivity.class))
        );

        btnViewHistory.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, HistoryActivity.class))
        );

        btnSearchPartner.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, MatchingActivity.class))
        );

        btnViewInvites.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, InvitationsActivity.class))
        );

        btnViewProfile.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, ProfileActivity.class))
        );
    }
}
