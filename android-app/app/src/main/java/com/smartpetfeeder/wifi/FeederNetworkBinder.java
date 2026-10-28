package com.smartpetfeeder.wifi;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

/**
 * Routes this app's traffic over the feeder's own Wi-Fi (PetFeeder_Setup), which has no internet.
 * Without this, Android may send requests over mobile data and the feeder is never reached.
 * Call release() when done so Firebase and everything else can use the normal network again.
 */
public class FeederNetworkBinder {

    private static final String TAG = "FeederNetworkBinder";
    public static final String AP_SSID = "PetFeeder_Setup";

    public interface Listener {
        void onBound();
        void onFailed(String reason);
        void onLost();
    }

    private final Context context;
    private final ConnectivityManager cm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback callback;
    private boolean bound;

    public FeederNetworkBinder(Context context) {
        this.context = context.getApplicationContext();
        this.cm = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /**
     * @param forceRequest skip the "already on the feeder Wi-Fi" shortcut and ask Android to join it
     *                     (Android 10+ shows a one-time system prompt).
     */
    public void bind(boolean forceRequest, Listener listener) {
        if (cm == null) {
            listener.onFailed("Network service unavailable.");
            return;
        }
        if (forceRequest) release();
        if (bound) {
            listener.onBound();
            return;
        }

        String ssid = currentSsid();
        boolean unknown = ssid == null || ssid.isEmpty() || "<unknown ssid>".equals(ssid);
        if (!forceRequest && (AP_SSID.equals(ssid) || unknown) && bindToCurrentWifi()) {
            bound = true;
            listener.onBound();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestSpecifier(listener);
        } else {
            listener.onFailed("Connect your phone to the \"" + AP_SSID
                    + "\" Wi-Fi in Settings, then tap Connect again.");
        }
    }

    public void release() {
        if (cm == null) return;
        try {
            cm.bindProcessToNetwork(null);
        } catch (Exception e) {
            Log.w(TAG, "unbind failed", e);
        }
        if (callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (Exception e) {
                Log.w(TAG, "unregister failed", e);
            }
            callback = null;
        }
        bound = false;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private void requestSpecifier(Listener listener) {
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(AP_SSID)
                .build();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();

        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                cm.bindProcessToNetwork(network);
                bound = true;
                main.post(listener::onBound);
            }

            @Override
            public void onUnavailable() {
                main.post(() -> listener.onFailed("Could not join the \"" + AP_SSID + "\" Wi-Fi."));
            }

            @Override
            public void onLost(Network network) {
                bound = false;
                main.post(listener::onLost);
            }
        };

        try {
            cm.requestNetwork(request, callback, 30_000);
        } catch (Exception e) {
            Log.w(TAG, "requestNetwork failed", e);
            callback = null;
            listener.onFailed("Could not start the Wi-Fi connection request.");
        }
    }

    private boolean bindToCurrentWifi() {
        try {
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return cm.bindProcessToNetwork(network);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "bindToCurrentWifi failed", e);
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private String currentSsid() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null || info.getSSID() == null) return null;
            return info.getSSID().replace("\"", "");
        } catch (Exception e) {
            return null;
        }
    }
}
