package com.smartpetfeeder.auth;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.firebase.auth.FirebaseAuth;
import com.smartpetfeeder.MainActivity;
import com.smartpetfeeder.util.LocalScheduleStore;
import com.smartpetfeeder.wifi.DirectControlActivity;

public class AuthGateActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "spf_prefs";
    public static final String KEY_DARK_MODE = "dark_mode";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isDark = prefs.getBoolean(KEY_DARK_MODE, false);
        AppCompatDelegate.setDefaultNightMode(
                isDark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);

        super.onCreate(savedInstanceState);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            if (LocalScheduleStore.isDirectEntry(this)) {
                startActivity(new Intent(this, DirectControlActivity.class));
            } else {
                startActivity(new Intent(this, LoginActivity.class));
            }
        } else {
            startActivity(new Intent(this, MainActivity.class));
        }
        finish();
    }
}
