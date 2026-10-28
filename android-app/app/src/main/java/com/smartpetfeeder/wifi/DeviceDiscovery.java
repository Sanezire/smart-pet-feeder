package com.smartpetfeeder.wifi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class DeviceDiscovery {

    private static final String TAG           = "DeviceDiscovery";
    private static final String PREFS_NAME    = "petfeeder_prefs";
    private static final String KEY_LAST_IP   = "last_device_ip";
    private static final String MDNS_HOSTNAME = "petfeeder.local";
    private static final int    HTTP_CONNECT_TIMEOUT = 3000;
    private static final int    HTTP_READ_TIMEOUT = 5000;
    private static final int    NSD_WAIT_MS = 2500;
    private static final int    NSD_WAIT_OTHER_NETWORK_MS = 1500;
    private static final long   SUBNET_SCAN_BUDGET_MS = 20_000L;

    private final Context         context;
    private final ExecutorService executor    = Executors.newFixedThreadPool(2);
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private volatile String       lastDiscoveryTrace = "";

    public interface DiscoveryCallback {
        void onFound(String baseUrl, String method);

        void onNotFound();
    }

    public DeviceDiscovery(Context context) {
        this.context = context.getApplicationContext();
    }

    public void discover(DiscoveryCallback callback) {
        executor.execute(() -> runDiscovery(callback));
    }

    public String getLastDiscoveryTrace() {
        return lastDiscoveryTrace;
    }

    public void saveLastKnownIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty() || "192.168.4.1".equals(ipAddress)) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_IP, ipAddress).apply();
        Log.d(TAG, "Saved last known IP: " + ipAddress);
    }

    public String getLastKnownIp() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LAST_IP, null);
    }

    public void clearLastKnownIp() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_LAST_IP).apply();
    }

    public boolean isConnectedToFeederAP() {
        WifiManager wifiManager =
                (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return false;

        android.net.wifi.WifiInfo info = wifiManager.getConnectionInfo();
        if (info == null) return false;

        String ssid = info.getSSID().replace("\"", "");
        return "PetFeeder_Setup".equals(ssid);
    }

    private void runDiscovery(DiscoveryCallback callback) {
        StringBuilder trace = new StringBuilder();

        // Mobile data (or no WiFi at all): the feeder cannot be on this network, so skip the
        // local search and let the caller send the command through the cloud right away.
        if (!isOnWifi() && !isConnectedToFeederAP()) {
            trace.append("no_wifi;");
            lastDiscoveryTrace = trace.toString();
            mainHandler.post(callback::onNotFound);
            return;
        }

        if (isConnectedToFeederAP()) {
            trace.append("on_ap_ssid;");
            Log.d(TAG, "Phone is connected to PetFeeder_Setup AP.");
            String url = EspApiClient.AP_BASE_URL;
            if (isReachable(url)) {
                trace.append("ap_probe_ok;");
                lastDiscoveryTrace = trace.toString();
                deliverFound(callback, url, "ap_mode");
                return;
            }
            trace.append("ap_probe_fail;");
        } else {
            trace.append("ssid_check_fail;");
        }

        if (isReachable(EspApiClient.AP_BASE_URL, 1200, 1500)) {
            trace.append("ap_direct_probe_ok;");
            lastDiscoveryTrace = trace.toString();
            deliverFound(callback, EspApiClient.AP_BASE_URL, "ap_direct");
            return;
        }
        trace.append("ap_direct_probe_fail;");

        String lastIp = getLastKnownIp();

        // On a different WiFi than the one the feeder was last seen on (cafe, office, a friend's
        // place): only a quick multicast check, then hand over to the cloud instead of probing
        // that network's addresses one by one.
        String phonePrefix = phoneSubnetPrefix();
        if (lastIp != null && !lastIp.isEmpty() && phonePrefix != null && !lastIp.startsWith(phonePrefix)) {
            trace.append("other_network;");
            String quickNsd = tryNsdDiscovery(NSD_WAIT_OTHER_NETWORK_MS);
            if (quickNsd != null) {
                trace.append("nsd_ok;");
                lastDiscoveryTrace = trace.toString();
                deliverFound(callback, quickNsd, "nsd");
                return;
            }
            trace.append("nsd_fail;");
            lastDiscoveryTrace = trace.toString();
            mainHandler.post(callback::onNotFound);
            return;
        }

        if (lastIp != null && !lastIp.isEmpty()) {
            String url = "http://" + lastIp;
            trace.append("try_last_ip=").append(lastIp).append(";");
            Log.d(TAG, "Trying last known IP: " + url);
            if (isReachable(url)) {
                trace.append("last_ip_ok;");
                lastDiscoveryTrace = trace.toString();
                deliverFound(callback, url, "stored_ip");
                return;
            }
            Log.d(TAG, "Last known IP unreachable.");
            trace.append("last_ip_fail;");
        }

        String nsdUrl = tryNsdDiscovery();
        if (nsdUrl != null) {
            trace.append("nsd_ok;");
            lastDiscoveryTrace = trace.toString();
            deliverFound(callback, nsdUrl, "nsd");
            return;
        }
        trace.append("nsd_fail;");

        if (isReachable(EspApiClient.MDNS_BASE_URL)) {
            trace.append("mdns_ok;");
            lastDiscoveryTrace = trace.toString();
            deliverFound(callback, EspApiClient.MDNS_BASE_URL, "mdns");
            return;
        }
        trace.append("mdns_fail;");

        String mdnsUrl = tryMdnsResolvedIp();
        if (mdnsUrl != null) {
            trace.append("mdns_resolved_ok;");
            lastDiscoveryTrace = trace.toString();
            deliverFound(callback, mdnsUrl, "mdns_resolved");
            return;
        }
        trace.append("mdns_resolved_fail;");

        String subnetUrl = trySubnetScan();
        if (subnetUrl != null) {
            trace.append("subnet_ok=").append(subnetUrl).append(";");
            lastDiscoveryTrace = trace.toString();
            deliverFound(callback, subnetUrl, "subnet_scan");
            return;
        }
        trace.append("subnet_fail;");

        Log.d(TAG, "Device not found via any method.");
        lastDiscoveryTrace = trace.toString();
        mainHandler.post(callback::onNotFound);
    }

    private String tryMdnsResolvedIp() {
        try {
            Log.d(TAG, "Resolving mDNS: " + MDNS_HOSTNAME);
            InetAddress addr = InetAddress.getByName(MDNS_HOSTNAME);
            String resolvedIp = addr.getHostAddress();
            String url = "http://" + resolvedIp;
            Log.d(TAG, "mDNS resolved to: " + resolvedIp);
            if (isReachable(url)) {
                return url;
            }
        } catch (Exception e) {
            Log.d(TAG, "mDNS resolution failed: " + e.getMessage());
        }
        return null;
    }

    private String tryNsdDiscovery() {
        return tryNsdDiscovery(NSD_WAIT_MS);
    }

    private String tryNsdDiscovery(int waitMs) {
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return null;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> found = new AtomicReference<>(null);
        final NsdManager.DiscoveryListener[] holder = new NsdManager.DiscoveryListener[1];

        holder[0] = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) { }
            @Override public void onDiscoveryStopped(String serviceType) { }
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) { latch.countDown(); }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) { }

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!"_http._tcp.".equals(serviceInfo.getServiceType())) return;
                if (serviceInfo.getServiceName() == null
                        || !serviceInfo.getServiceName().toLowerCase().contains("petfeeder")) return;
                nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) { }
                    @Override public void onServiceResolved(NsdServiceInfo resolved) {
                        if (resolved.getHost() == null) return;
                        String url = "http://" + resolved.getHost().getHostAddress();
                        if (isReachable(url, 900, 1200)) {
                            found.set(url);
                            latch.countDown();
                        }
                    }
                });
            }

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) { }
        };

        try {
            nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, holder[0]);
            latch.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.d(TAG, "NSD discovery failed: " + e.getMessage());
        } finally {
            try { nsd.stopServiceDiscovery(holder[0]); } catch (Exception ignored) { }
        }
        return found.get();
    }

    private String trySubnetScan() {
        try {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) return null;

            DhcpInfo dhcp = wifiManager.getDhcpInfo();
            if (dhcp == null || dhcp.gateway == 0) return null;

            String gateway = intToIp(dhcp.gateway);
            String[] parts = gateway.split("\\.");
            if (parts.length != 4) return null;
            String prefix = parts[0] + "." + parts[1] + "." + parts[2] + ".";

            int currentIpHost = (dhcp.ipAddress & 0xFF);
            final long scanDeadline = System.currentTimeMillis() + SUBNET_SCAN_BUDGET_MS;

            for (int host = 100; host <= 200; host++) {
                if (System.currentTimeMillis() > scanDeadline) return null;
                if (host == currentIpHost) continue;
                String candidate = "http://" + prefix + host;
                if (isReachable(candidate, 900, 1100)) return candidate;
            }

            for (int host = 2; host <= 254; host++) {
                if (System.currentTimeMillis() > scanDeadline) return null;
                if (host == currentIpHost) continue;
                String candidate = "http://" + prefix + host;
                if (isReachable(candidate, 500, 700)) return candidate;
            }
        } catch (Exception e) {
            Log.d(TAG, "Subnet scan failed: " + e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private boolean isOnWifi() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
            }
            return false;
        } catch (Exception e) {
            return true;   // unsure: keep the normal local search
        }
    }

    private String phoneSubnetPrefix() {
        try {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) return null;
            DhcpInfo dhcp = wifiManager.getDhcpInfo();
            if (dhcp == null || dhcp.ipAddress == 0) return null;
            int ip = dhcp.ipAddress;
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + ".";
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isReachable(String baseUrl) {
        return isReachable(baseUrl, HTTP_CONNECT_TIMEOUT, HTTP_READ_TIMEOUT);
    }

    private boolean isReachable(String baseUrl, int connectTimeout, int readTimeout) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/status");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) return false;

            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            return json.has("mode") && json.has("connected");
        } catch (Exception e) {
            Log.d(TAG, "isReachable(" + baseUrl + ") exception: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String intToIp(int ip) {
        return (ip & 0xFF) + "." +
                ((ip >> 8) & 0xFF) + "." +
                ((ip >> 16) & 0xFF) + "." +
                ((ip >> 24) & 0xFF);
    }

    private void deliverFound(DiscoveryCallback callback, String url, String method) {
        Log.d(TAG, "Device found: " + url + " via " + method);
        mainHandler.post(() -> callback.onFound(url, method));
    }

    public void shutdown() {
        executor.shutdown();
    }
}
