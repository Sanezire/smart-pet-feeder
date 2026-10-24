package com.smartpetfeeder.wifi;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.smartpetfeeder.R;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class WiFiConfigActivity extends AppCompatActivity {

    private TextView     tvModeLabel;
    private TextView     tvDeviceStatus;
    private EditText     etSsid;
    private EditText     etPassword;
    private ImageButton  btnTogglePassword;
    private ImageButton  btnBack;
    private Button       btnAutoDetectSsid;
    private Button       btnScanWifiQr;
    private Button       btnConfigure;
    private Button       btnResetWifi;
    private Button       btnFindDevice;
    private ProgressBar  progressBar;
    private CardView     layoutConfigForm;
    private CardView     cardInstructions;
    private TextView     tvInstructions;

    private EspApiClient    espClient;
    private DeviceDiscovery discovery;
    private String          deviceBaseUrl  = null;
    private boolean         passwordVisible = false;
    private int             discoveryRequestId = 0;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback feederNetworkCallback;
    private boolean feederNetworkRequestActive = false;

    private static final int REQ_WIFI_PERMISSION = 101;
    private static final String TAG = "WiFiConfigActivity";

    private final ActivityResultLauncher<ScanOptions> qrScanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                String contents = result.getContents();
                if (contents == null) {
                    Toast.makeText(this, "QR scan cancelled.", Toast.LENGTH_SHORT).show();
                    return;
                }
                WifiQrCredentials credentials = parseWifiQr(contents);
                if (credentials == null) {
                    Toast.makeText(this, "This QR code is not a supported WiFi network code.", Toast.LENGTH_LONG).show();
                    return;
                }
                etSsid.setText(credentials.ssid);
                etPassword.setText(credentials.password);
                etPassword.requestFocus();
                Toast.makeText(this, "WiFi details filled from QR code.", Toast.LENGTH_SHORT).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_config);

        initViews();
        setupListeners();

        discovery = new DeviceDiscovery(this);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        bindToFeederApIfPossible();
        startDiscovery();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (espClient != null) espClient.shutdown();
        unbindFromFeederAp();
        if (discovery != null) discovery.shutdown();
    }

    private void initViews() {
        tvModeLabel       = findViewById(R.id.tvWifiModeLabel);
        tvDeviceStatus    = findViewById(R.id.tvDeviceStatus);
        etSsid            = findViewById(R.id.etWifiSsid);
        etPassword        = findViewById(R.id.etWifiPassword);
        btnTogglePassword = findViewById(R.id.btnTogglePassword);
        btnBack           = findViewById(R.id.btnBack);
        btnAutoDetectSsid = findViewById(R.id.btnAutoDetectSsid);
        btnScanWifiQr     = findViewById(R.id.btnScanWifiQr);
        btnConfigure      = findViewById(R.id.btnConfigureDevice);
        btnResetWifi      = findViewById(R.id.btnResetWifi);
        btnFindDevice     = findViewById(R.id.btnFindDevice);
        progressBar       = findViewById(R.id.progressBarWifi);
        layoutConfigForm  = findViewById(R.id.layoutConfigForm);
        cardInstructions  = findViewById(R.id.cardInstructions);
        tvInstructions    = findViewById(R.id.tvInstructions);
    }

    private void setupListeners() {

        btnTogglePassword.setOnClickListener(v -> {
            passwordVisible = !passwordVisible;
            etPassword.setTransformationMethod(
                    passwordVisible
                            ? HideReturnsTransformationMethod.getInstance()
                            : PasswordTransformationMethod.getInstance());
            etPassword.setSelection(etPassword.getText().length());
            btnTogglePassword.setImageResource(
                    passwordVisible ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
        });

        btnAutoDetectSsid.setOnClickListener(v -> autoFillCurrentSsid());
        btnScanWifiQr.setOnClickListener(v -> scanWifiQr());
        btnBack.setOnClickListener(v -> finish());

        btnConfigure.setOnClickListener(v -> {
            if (validateInput()) sendConfiguration();
        });

        btnResetWifi.setOnClickListener(v -> confirmResetWifi());
        btnFindDevice.setOnClickListener(v -> startDiscovery());
    }

    private void startDiscovery() {
        bindToFeederApIfPossible();
        final int requestId = ++discoveryRequestId;
        setLoading(true);
        tvDeviceStatus.setText("Searching for device…");
        layoutConfigForm.setVisibility(View.GONE);
        cardInstructions.setVisibility(View.GONE);
        btnResetWifi.setVisibility(View.GONE);

        discovery.discover(new DeviceDiscovery.DiscoveryCallback() {

            @Override
            public void onFound(String baseUrl, String method) {
                if (requestId != discoveryRequestId) return;
                if (espClient != null) espClient.shutdown();
                deviceBaseUrl = baseUrl;
                espClient     = new EspApiClient(baseUrl);
                setLoading(false);

                String modeHint;
                switch (method) {
                    case "ap_mode":   modeHint = "AP Setup Mode (PetFeeder_Setup)"; break;
                    case "mdns":      modeHint = "Found via petfeeder.local";       break;
                    case "stored_ip": modeHint = "Found via last known IP";         break;
                    default:          modeHint = "Device found";
                }
                tvModeLabel.setText(modeHint);
                cardInstructions.setVisibility(View.GONE);
                fetchDeviceStatus();
            }

            @Override
            public void onNotFound() {
                if (requestId != discoveryRequestId) return;
                setLoading(false);
                tvModeLabel.setText("Device Not Found on Network");
                tvDeviceStatus.setText(
                        "Please connect your phone to the \"PetFeeder_Setup\" WiFi network, " +
                                "then tap \"Find Device\" again.");
                tvInstructions.setVisibility(View.VISIBLE);
                cardInstructions.setVisibility(View.VISIBLE);
                layoutConfigForm.setVisibility(View.VISIBLE);

                deviceBaseUrl = EspApiClient.AP_BASE_URL;
                if (espClient != null) espClient.shutdown();
                espClient     = new EspApiClient(deviceBaseUrl);
                btnResetWifi.setVisibility(View.GONE);
            }
        });
    }

    private void fetchDeviceStatus() {
        if (espClient == null) return;

        espClient.getStatus(new EspApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                boolean connected = response.optBoolean("connected", false);
                String  ip        = response.optString("ip", "—");
                String  ssid      = response.optString("ssid", "—");
                int     rssi      = response.optInt("rssi", 0);

                String statusText = connected
                        ? "Connected to: " + ssid + "\nIP: " + ip + "   Signal: " + rssi + " dBm"
                        : "In setup mode — ready to configure";

                tvDeviceStatus.setText(statusText);
                layoutConfigForm.setVisibility(View.VISIBLE);
                btnResetWifi.setVisibility(connected ? View.VISIBLE : View.GONE);

                if (connected && !ip.isEmpty() && !"—".equals(ip)) {
                    discovery.saveLastKnownIp(ip);
                }
            }

            @Override
            public void onError(String errorMessage) {
                tvDeviceStatus.setText("Could not read device status.\n" + errorMessage);
                layoutConfigForm.setVisibility(View.VISIBLE);
            }
        });
    }

    private void sendConfiguration() {
        String ssid     = etSsid.getText().toString().trim();
        String password = etPassword.getText().toString();

        if (espClient == null) {
            Toast.makeText(this, "Device not found. Tap \"Find Device\" first.", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        btnConfigure.setEnabled(false);
        tvDeviceStatus.setText(
                "Sending credentials and verifying connection…\n(This may take up to 15 seconds)");

        espClient.configureWifi(ssid, password, new EspApiClient.ProgressCallback() {

            @Override
            public void onProgress(String statusMessage) {
                tvDeviceStatus.setText(statusMessage);
            }

            @Override
            public void onSuccess(JSONObject response) {
                setLoading(false);
                btnConfigure.setEnabled(true);

                String ip = response.optString("ip", "");
                if (TextUtils.isEmpty(ip) || !response.optBoolean("connected", false)) {
                    showWiFiErrorDialog(ssid, "Device did not return a verified network address.");
                    tvDeviceStatus.setText("Failed: device did not return a verified network address.");
                    return;
                }
                discovery.saveLastKnownIp(ip);
                unbindFromFeederAp();

                showWiFiSuccessDialog(ssid, ip);

                tvDeviceStatus.setText("Connected to: " + ssid + "\nIP: " + ip);
                tvModeLabel.setText("Station Mode");
                btnResetWifi.setVisibility(View.VISIBLE);
            }

            @Override
            public void onError(String errorMessage) {
                setLoading(false);
                btnConfigure.setEnabled(true);

                showWiFiErrorDialog(ssid, errorMessage);

                tvDeviceStatus.setText("Failed: " + errorMessage);
            }
        });
    }

    private void confirmResetWifi() {
        new AlertDialog.Builder(this)
                .setTitle("Reset Device WiFi?")
                .setMessage(
                        "This will erase the saved WiFi credentials on the device " +
                                "and reboot it into setup mode.\n\nYou will need to reconnect " +
                                "to \"PetFeeder_Setup\" to configure it again.")
                .setPositiveButton("Reset", (dialog, which) -> performWifiReset())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performWifiReset() {
        if (espClient == null) return;

        setLoading(true);
        tvDeviceStatus.setText("Sending reset command…");

        espClient.resetWifi(new EspApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                setLoading(false);
                discovery.clearLastKnownIp();
                if (espClient != null) espClient.shutdown();
                tvModeLabel.setText("AP Setup Mode");
                tvDeviceStatus.setText(
                        "Device is rebooting to setup mode.\n\n" +
                                "Please connect your phone to \"PetFeeder_Setup\" WiFi, " +
                                "then configure it with your home network.");
                btnResetWifi.setVisibility(View.GONE);
                deviceBaseUrl = EspApiClient.AP_BASE_URL;
                espClient     = new EspApiClient(deviceBaseUrl);

                new AlertDialog.Builder(WiFiConfigActivity.this)
                        .setTitle("Device Reset")
                        .setMessage(
                                "The device has been reset to setup mode.\n\n" +
                                        "1. Open your phone's WiFi settings\n" +
                                        "2. Connect to \"PetFeeder_Setup\"\n" +
                                        "3. Return here and tap \"Find Device\"")
                        .setPositiveButton("OK", null)
                        .show();
            }

            @Override
            public void onError(String errorMessage) {
                setLoading(false);
                Toast.makeText(WiFiConfigActivity.this,
                        "Reset failed: " + errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void autoFillCurrentSsid() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQ_WIFI_PERMISSION);
                return;
            }
        }
        fillSsid();
    }

    private void scanWifiQr() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("Scan WiFi QR code");
        options.setBeepEnabled(false);
        options.setOrientationLocked(true);
        options.setCaptureActivity(PortraitQrCaptureActivity.class);
        qrScanLauncher.launch(options);
    }

    private void bindToFeederApIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (connectivityManager == null || feederNetworkRequestActive) return;
        if (discovery != null && discovery.isConnectedToFeederAP()) return;

        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid("PetFeeder_Setup")
                .build();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();

        feederNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                connectivityManager.bindProcessToNetwork(network);
                Log.d(TAG, "Bound process to PetFeeder_Setup AP");
                runOnUiThread(() -> {
                    if (!isFinishing()) startDiscovery();
                });
            }

            @Override
            public void onLost(Network network) {
                Log.d(TAG, "PetFeeder_Setup AP lost");
            }
        };

        try {
            connectivityManager.requestNetwork(request, feederNetworkCallback);
            feederNetworkRequestActive = true;
        } catch (Exception e) {
            Log.w(TAG, "Feeder AP bind request failed", e);
        }
    }

    private void unbindFromFeederAp() {
        if (connectivityManager == null) return;
        try {
            connectivityManager.bindProcessToNetwork(null);
            if (feederNetworkCallback != null) {
                connectivityManager.unregisterNetworkCallback(feederNetworkCallback);
            }
        } catch (Exception e) {
            Log.w(TAG, "Feeder AP unbind failed", e);
        }
        feederNetworkCallback = null;
        feederNetworkRequestActive = false;
    }

    private WifiQrCredentials parseWifiQr(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (!trimmed.startsWith("WIFI:")) return null;
        String body = trimmed.substring(5);
        Map<String, String> fields = parseWifiQrFields(body);
        String ssid = fields.get("S");
        if (TextUtils.isEmpty(ssid)) return null;
        String type = fields.containsKey("T") ? fields.get("T") : "WPA";
        if (type == null) type = "";
        String normalizedType = type.trim().toUpperCase();
        if (!normalizedType.isEmpty()
                && !"NOPASS".equals(normalizedType)
                && !"WEP".equals(normalizedType)
                && !normalizedType.contains("WPA")) {
            return null;
        }
        String password = fields.get("P");
        if ("NOPASS".equals(normalizedType)) password = "";
        return new WifiQrCredentials(ssid, password == null ? "" : password);
    }

    private Map<String, String> parseWifiQrFields(String body) {
        Map<String, String> fields = new HashMap<>();
        StringBuilder segment = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escaped) {
                segment.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == ';') {
                addWifiQrField(fields, segment.toString());
                segment.setLength(0);
            } else {
                segment.append(c);
            }
        }
        addWifiQrField(fields, segment.toString());
        return fields;
    }

    private void addWifiQrField(Map<String, String> fields, String segment) {
        if (TextUtils.isEmpty(segment)) return;
        int colon = segment.indexOf(':');
        if (colon <= 0) return;
        fields.put(segment.substring(0, colon), segment.substring(colon + 1));
    }

    private void fillSsid() {
        WifiManager wifiManager =
                (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return;

        android.net.wifi.WifiInfo info = wifiManager.getConnectionInfo();
        if (info == null) return;

        String ssid = info.getSSID().replace("\"", "");
        if (!TextUtils.isEmpty(ssid)
                && !"<unknown ssid>".equals(ssid)
                && !"PetFeeder_Setup".equals(ssid)) {
            etSsid.setText(ssid);
            etPassword.requestFocus();
            Toast.makeText(this, "SSID filled: " + ssid, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Use QR scan or enter your home WiFi name.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WIFI_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            fillSsid();
        }
    }

    private boolean validateInput() {
        String ssid = etSsid.getText().toString().trim();
        if (TextUtils.isEmpty(ssid)) {
            etSsid.setError("WiFi name is required");
            etSsid.requestFocus();
            return false;
        }
        if (ssid.equals("PetFeeder_Setup")) {
            etSsid.setError("Cannot connect device to its own AP — enter your home WiFi name");
            etSsid.requestFocus();
            return false;
        }
        return true;
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnConfigure.setEnabled(!loading);
        btnFindDevice.setEnabled(!loading);
        btnAutoDetectSsid.setEnabled(!loading);
        btnScanWifiQr.setEnabled(!loading);
    }

    private void showWiFiSuccessDialog(String ssid, String ip) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_wifi_success, null);

        TextView tvWifiSsid = dialogView.findViewById(R.id.tvWifiSsid);
        TextView tvDeviceIp = dialogView.findViewById(R.id.tvDeviceIp);
        Button btnStayHere = dialogView.findViewById(R.id.btnStayHere);
        Button btnDone = dialogView.findViewById(R.id.btnDone);

        tvWifiSsid.setText(ssid);
        tvDeviceIp.setText(ip);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        btnStayHere.setOnClickListener(v -> dialog.dismiss());
        btnDone.setOnClickListener(v -> {
            dialog.dismiss();
            finish();
        });

        dialog.show();
    }

    private void showWiFiErrorDialog(String ssid, String errorMessage) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_wifi_error, null);

        TextView tvWifiSsid = dialogView.findViewById(R.id.tvWifiSsid);
        TextView tvErrorReason = dialogView.findViewById(R.id.tvErrorReason);
        Button btnRetry = dialogView.findViewById(R.id.btnRetry);

        tvWifiSsid.setText(ssid);
        tvErrorReason.setText(errorMessage);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        btnRetry.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private static class WifiQrCredentials {
        final String ssid;
        final String password;

        WifiQrCredentials(String ssid, String password) {
            this.ssid = ssid;
            this.password = password;
        }
    }
}
