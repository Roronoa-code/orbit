package com.mani.orbit;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.TextView;
import android.widget.ScrollView;

/** Explanation required by Android before users grant health permissions. */
public final class HealthPrivacyActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("Orbit and Samsung Health\n\nOrbit reads records Samsung Health shares with Health Connect: activity, workouts, heart rate, sleep, food, water and body measurements. Allow only the categories you want.\n\nYour imported records are saved privately on this phone. Orbit does not upload them or change records in Samsung Health or Health Connect.\n\nHistory access lets Orbit read older shared records. Samsung Health may not have shared all of its older history. Missing measurements remain empty.\n\nYou can revoke access in Health Connect at any time. Previously imported records stay in Orbit until you clear Orbit’s app storage or uninstall it.");
        text.setTextSize(18); text.setTextColor(Color.WHITE); text.setPadding(28, 64, 28, 48);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(Color.rgb(10, 10, 12)); scroll.addView(text); setContentView(scroll);
    }
}
