package com.smartpetfeeder.wifi;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.smartpetfeeder.R;

import org.json.JSONObject;

public class SettingsActivity extends AppCompatActivity {

    private TextView    tvConnectionStatus;
    private TextView    tvSsid;
    private TextView    tvIp;
    private TextView    tvSignal;
    private TextView    tvApInfo;
    private Button      btnChangeWifi;
    private Button      btnResetWifi;
    private Button      btnRestart;
    private Button      btnSaveTray;
    private EditText    etTrayMinutes;
    private TextView    tvTrayInfo;
    private com.smartpetfeeder.firebase.FirebaseService firebaseService;
    private Button      btnRefresh;
    private ProgressBar progressBar;
    private LinearLayout layoutConnected;
    private LinearLayout layoutDisconnected;

    private EspApiClient    espClient;
    private DeviceDiscovery discovery;
    private final Handler   uiHandler = new Handler(Looper.getMainLooper());
    private int             refreshRequestId = 0;
    private static final long DISCOVERY_TIMEOUT_MS = 12_000L;
    private Runnable        discoveryTimeoutRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Settings");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        initViews();
        setupListeners();

        discovery = new DeviceDiscovery(this);
        try {
            firebaseService = new com.smartpetfeeder.firebase.FirebaseService(getApplicationContext());
        } catch (Exception e) {
            firebaseService = null;
        }
        loadTrayInfoFromCloud();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (discoveryTimeoutRunnable != null) {
            uiHandler.removeCallbacks(discoveryTimeoutRunnable);
        }
        if (espClient != null) espClient.shutdown();
        if (discovery != null) discovery.shutdown();
        if (firebaseService != null) firebaseService.shutdown();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private void initViews() {
        tvConnectionStatus = findViewById(R.id.tvSettingsConnectionStatus);
        tvSsid             = findViewById(R.id.tvSettingsSsid);
        tvIp               = findViewById(R.id.tvSettingsIp);
        tvSignal           = findViewById(R.id.tvSettingsSignal);
        tvApInfo           = findViewById(R.id.tvSettingsApInfo);
        btnChangeWifi      = findViewById(R.id.btnSettingsChangeWifi);
        btnResetWifi       = findViewById(R.id.btnSettingsResetWifi);
        btnRestart         = findViewById(R.id.btnSettingsRestart);
        findViewById(R.id.btnSettingsDirect).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, DirectControlActivity.class)));
        btnSaveTray        = findViewById(R.id.btnSettingsSaveTray);
        etTrayMinutes      = findViewById(R.id.etSettingsTrayMinutes);
        tvTrayInfo         = findViewById(R.id.tvSettingsTrayInfo);
        btnRefresh         = findViewById(R.id.btnSettingsRefresh);
        progressBar        = findViewById(R.id.progressBarSettings);
        layoutConnected    = findViewById(R.id.layoutSettingsConnected);
        layoutDisconnected = findViewById(R.id.layoutSettingsDisconnected);
    }

    private void setupListeners() {

        btnRefresh.setOnClickListener(v -> refreshStatus());

        btnChangeWifi.setOnClickListener(v -> {
            startActivity(new Intent(this, WiFiConfigActivity.class));
        });

        btnResetWifi.setOnClickListener(v -> confirmReset());
        btnRestart.setOnClickListener(v -> confirmRestart());
        btnSaveTray.setOnClickListener(v -> saveTrayTimer());
    }

    private void refreshStatus() {
        final int requestId = ++refreshRequestId;
        setLoading(true);
        tvConnectionStatus.setText("Searching on current WiFi…");

        if (discoveryTimeoutRunnable != null) {
            uiHandler.removeCallbacks(discoveryTimeoutRunnable);
        }

        discoveryTimeoutRunnable = () -> {
            if (requestId != refreshRequestId) return;
            setLoading(false);
            showDisconnectedState(
                    "Could not find the feeder on this WiFi within 12 seconds.\n\n" +
                    "If feeder is already connected, make sure your phone is on the same WiFi network.\n" +
                    "If setting up/reconfiguring, connect to \"PetFeeder_Setup\" and tap \"Change WiFi Network\".");
        };
        uiHandler.postDelayed(discoveryTimeoutRunnable, DISCOVERY_TIMEOUT_MS);

        discovery.discover(new DeviceDiscovery.DiscoveryCallback() {

            @Override
            public void onFound(String baseUrl, String method) {
                if (requestId != refreshRequestId) return;
                if (discoveryTimeoutRunnable != null) {
                    uiHandler.removeCallbacks(discoveryTimeoutRunnable);
                }
                if (espClient != null) espClient.shutdown();
                espClient = new EspApiClient(baseUrl);
                fetchStatus();
            }

            @Override
            public void onNotFound() {
                if (requestId != refreshRequestId) return;
                if (discoveryTimeoutRunnable != null) {
                    uiHandler.removeCallbacks(discoveryTimeoutRunnable);
                }
                setLoading(false);
                String trace = discovery != null ? discovery.getLastDiscoveryTrace() : "";
                showDisconnectedState("Device not found on network.\n" +
                        "Make sure your phone and feeder are on the same WiFi.\n\n" +
                        "Connect to \"PetFeeder_Setup\" only when setting up for the first time.\n\n" +
                        "Debug: " + trace);
            }
        });
    }

    private void fetchStatus() {
        espClient.getStatus(new EspApiClient.Callback() {

            @Override
            public void onSuccess(JSONObject response) {
                setLoading(false);

                boolean connected = response.optBoolean("connected", false);
                int trayMinutes = response.optInt("tray_close_minutes", -1);
                if (trayMinutes > 0 && !etTrayMinutes.hasFocus()) {
                    etTrayMinutes.setText(String.valueOf(trayMinutes));
                }
                String  ssid      = response.optString("ssid", "—");
                String  ip        = response.optString("ip",   "—");
                int     rssi      = response.optInt("rssi",    0);

                if (connected) {
                    showConnectedState(ssid, ip, rssi);
                    discovery.saveLastKnownIp(ip);
                } else {
                    showDisconnectedState("Device is in setup mode (AP).\n" +
                            "Connect to \"PetFeeder_Setup\" and use Change WiFi.");
                }
            }

            @Override
            public void onError(String errorMessage) {
                setLoading(false);
                showDisconnectedState("Could not reach device:\n" + errorMessage);
            }
        });
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset Device WiFi?")
                .setMessage(
                        "This will erase saved credentials and reboot the device " +
                                "into setup mode.\n\n" +
                                "You will need to reconnect to \"PetFeeder_Setup\" to " +
                                "configure it again.")
                .setPositiveButton("Reset", (d, w) -> performReset())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performReset() {
        if (espClient == null) {
            Toast.makeText(this, "Device not reachable.", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        tvConnectionStatus.setText("Sending reset command…");

        espClient.resetWifi(new EspApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                setLoading(false);
                discovery.clearLastKnownIp();
                showDisconnectedState(
                        "Device is rebooting to setup mode.\n\n" +
                                "Connect your phone to \"PetFeeder_Setup\" WiFi, " +
                                "then tap \"Change WiFi\" to reconfigure.");

                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Device Reset")
                        .setMessage(
                                "The device has been reset.\n\n" +
                                        "Steps to reconnect:\n" +
                                        "1. Open phone WiFi settings\n" +
                                        "2. Connect to \"PetFeeder_Setup\"\n" +
                                        "3. Return here and tap Change WiFi\n" +
                                        "4. Enter your home network credentials")
                        .setPositiveButton("OK", null)
                        .show();
            }

            @Override
            public void onError(String errorMessage) {
                setLoading(false);
                Toast.makeText(SettingsActivity.this,
                        "Reset failed: " + errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void loadTrayInfoFromCloud() {
        if (firebaseService == null) return;
        firebaseService.readTrayInfo((closeMinutes, autoCloseAtMs, lastClosedAtMs, lastClosedBy) -> {
            if (isFinishing() || isDestroyed()) return;
            if (closeMinutes > 0 && !etTrayMinutes.hasFocus()
                    && etTrayMinutes.getText().toString().trim().isEmpty()) {
                etTrayMinutes.setText(String.valueOf(closeMinutes));
            }
            java.text.DateFormat fmt = new java.text.SimpleDateFormat(
                    "MMM d, h:mm a", java.util.Locale.getDefault());
            StringBuilder info = new StringBuilder();
            if (autoCloseAtMs > System.currentTimeMillis()) {
                info.append("Tray closes automatically at ")
                    .append(new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                            .format(new java.util.Date(autoCloseAtMs)));
            }
            if (lastClosedAtMs > 0) {
                if (info.length() > 0) info.append("\n");
                info.append("Last closed: ").append(fmt.format(new java.util.Date(lastClosedAtMs)));
                if ("auto".equals(lastClosedBy)) info.append(" (automatically)");
                else if ("manual".equals(lastClosedBy)) info.append(" (manually)");
            }
            if (info.length() > 0) {
                tvTrayInfo.setText(info.toString());
                tvTrayInfo.setVisibility(View.VISIBLE);
            }
        });
    }

    private void saveTrayTimer() {
        String raw = etTrayMinutes.getText().toString().trim();
        int minutes;
        try {
            minutes = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            minutes = -1;
        }
        if (minutes < 1 || minutes > 1440) {
            Toast.makeText(this, "Enter a time between 1 and 1440 minutes.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (firebaseService == null && espClient == null) {
            Toast.makeText(this, "Sign in or connect to the feeder first.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final int saved = minutes;
        final String done = "Tray will close " + saved + " min after a feeding.";

        if (espClient != null) {
            espClient.setTrayCloseMinutes(saved, new EspApiClient.Callback() {
                @Override public void onSuccess(JSONObject response) { }
                @Override public void onError(String errorMessage) { }
            });
        }

        if (firebaseService != null) {
            firebaseService.pushTrayCloseMinutes(saved,
                    new com.smartpetfeeder.firebase.FirebaseService.AckCallback() {
                @Override public void onAcknowledged() {
                    Toast.makeText(SettingsActivity.this, done, Toast.LENGTH_LONG).show();
                }
                @Override public void onFailed(String error) {
                    Toast.makeText(SettingsActivity.this,
                            "Saved offline. It will sync to the feeder when you are online.",
                            Toast.LENGTH_LONG).show();
                }
            });
        } else {
            Toast.makeText(this, done, Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRestart() {
        new AlertDialog.Builder(this)
                .setTitle("Restart Feeder?")
                .setMessage("The feeder will reboot and come back online in about 15 seconds. "
                        + "Your WiFi settings are kept.")
                .setPositiveButton("Restart", (d, w) -> performRestart())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performRestart() {
        if (espClient == null) {
            Toast.makeText(this, "Device not reachable.", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        tvConnectionStatus.setText("Restarting feeder…");

        espClient.restartDevice(new EspApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                Toast.makeText(SettingsActivity.this,
                        "Feeder is restarting. Reconnecting shortly…", Toast.LENGTH_LONG).show();
                uiHandler.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) refreshStatus();
                }, 15_000L);
            }

            @Override
            public void onError(String errorMessage) {
                setLoading(false);
                Toast.makeText(SettingsActivity.this,
                        "Restart failed: " + errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showConnectedState(String ssid, String ip, int rssi) {
        tvConnectionStatus.setText("● Connected");
        tvConnectionStatus.setTextColor(getColor(R.color.colorStatusCompleted));
        tvSsid.setText("SSID:  " + ssid);
        tvIp.setText("IP:     " + ip);
        tvSignal.setText("Signal: " + rssi + " dBm");

        layoutConnected.setVisibility(View.VISIBLE);
        layoutDisconnected.setVisibility(View.GONE);
        btnResetWifi.setVisibility(View.VISIBLE);
        btnRestart.setVisibility(View.VISIBLE);
        tvApInfo.setVisibility(View.GONE);
    }

    private void showDisconnectedState(String message) {
        tvConnectionStatus.setText("● Not Connected");
        tvConnectionStatus.setTextColor(getColor(R.color.colorStatusAnomaly));
        tvApInfo.setText(message);

        layoutConnected.setVisibility(View.GONE);
        layoutDisconnected.setVisibility(View.VISIBLE);
        btnResetWifi.setVisibility(View.GONE);
        btnRestart.setVisibility(View.GONE);
        tvApInfo.setVisibility(View.VISIBLE);
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnRefresh.setEnabled(!loading);
        btnChangeWifi.setEnabled(!loading);
        btnResetWifi.setEnabled(!loading);
        btnRestart.setEnabled(!loading);
        btnSaveTray.setEnabled(!loading);
    }
}
