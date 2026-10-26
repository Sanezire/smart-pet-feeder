package com.smartpetfeeder.wifi;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.smartpetfeeder.firebase.FirebaseService;
import com.smartpetfeeder.model.FeedingSchedule;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EspApiClient {

    private static final String TAG               = "EspApiClient";
    private static final int    TIMEOUT_CONNECT   = 5_000;
    private static final int    TIMEOUT_READ      = 8_000;
    private static final int    TIMEOUT_CONFIGURE = 8_000;
    private static final int    PROVISION_POLL_MS = 2_000;
    private static final int    PROVISION_TOTAL_MS = 45_000;

    public static final String AP_BASE_URL   = "http://192.168.4.1";

    public static final String MDNS_BASE_URL = "http://petfeeder.local";

    private final String          baseUrl;
    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onSuccess(JSONObject response);
        void onError(String errorMessage);
    }

    public interface ProgressCallback extends Callback {
        void onProgress(String statusMessage);
    }

    public EspApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    public void configureWifi(String ssid, String password, ProgressCallback callback) {
        executor.execute(() -> {
            try {
                postProgress(callback, "Starting secure provisioning session…");
                JSONObject session = get("/provisioning/session", TIMEOUT_CONNECT, TIMEOUT_READ);
                String token = session.optString("token", "");
                if (token.isEmpty()) {
                    throw new Exception("Provisioning token not available");
                }

                JSONObject body = new JSONObject();
                body.put("ssid",     ssid);
                body.put("password", password);
                body.put("token", token);
                String uid = FirebaseService.currentUid();
                if (uid != null) body.put("uid", uid);

                postProgress(callback, "Sending credentials to feeder…");
                JSONObject response = post("/configure", body.toString(),
                        TIMEOUT_CONNECT, TIMEOUT_CONFIGURE);

                if (!"connecting".equals(response.optString("status", ""))
                        && response.optBoolean("connected", false)
                        && !response.optString("ip", "").isEmpty()) {
                    mainHandler.post(() -> callback.onSuccess(response));
                    return;
                }

                postProgress(callback, "Feeder is joining WiFi…");
                long deadline = System.currentTimeMillis() + PROVISION_TOTAL_MS;
                Exception lastError = null;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(PROVISION_POLL_MS);
                    try {
                        JSONObject status = get("/status", TIMEOUT_CONNECT, TIMEOUT_READ);
                        if (status.optBoolean("connected", false)
                                && !status.optString("sta_ip", status.optString("ip", "")).isEmpty()) {
                            postProgress(callback, "Device connected. Finishing provisioning…");
                            mainHandler.post(() -> callback.onSuccess(status));
                            return;
                        }
                        String error = status.optString("connection_error", "");
                        if (!error.isEmpty()
                                && (error.toLowerCase(Locale.US).contains("wrong password")
                                || error.toLowerCase(Locale.US).contains("not found")
                                || error.toLowerCase(Locale.US).contains("5 ghz"))) {
                            mainHandler.post(() -> callback.onError(error));
                            return;
                        }
                    } catch (Exception e) {
                        lastError = e;
                    }
                }
                String reason = lastError == null ? "Connection timed out" : friendlyError(lastError.getMessage());
                mainHandler.post(() -> callback.onError(reason));

            } catch (Exception e) {
                Log.e(TAG, "configureWifi error", e);
                mainHandler.post(() -> callback.onError(
                        friendlyError(e.getMessage())));
            }
        });
    }

    public void getStatus(Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject response = get("/status", TIMEOUT_CONNECT, TIMEOUT_READ);
                mainHandler.post(() -> callback.onSuccess(response));
            } catch (Exception e) {
                Log.e(TAG, "getStatus error", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void resetWifi(Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject response = post("/reset-wifi", "{}",
                        TIMEOUT_CONNECT, TIMEOUT_READ);
                mainHandler.post(() -> callback.onSuccess(response));
            } catch (Exception e) {
                Log.e(TAG, "resetWifi error", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void restartDevice(Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject response = post("/restart", "{}",
                        TIMEOUT_CONNECT, TIMEOUT_READ, "X-Feeder-Restart", "1");
                mainHandler.post(() -> callback.onSuccess(response));
            } catch (Exception e) {
                Log.e(TAG, "restartDevice error", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void feedNow(Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject response = post("/feed-now", "{}",
                        TIMEOUT_CONNECT, TIMEOUT_READ);
                mainHandler.post(() -> callback.onSuccess(response));
            } catch (Exception e) {
                Log.e(TAG, "feedNow error", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void sendManualFeedTest(Callback callback) {
        sendManualFeedTest(0f, null, callback);
    }

    public void sendManualFeedTest(float portionGrams, String actionId, Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("command", "manual_feed_test");
                if (portionGrams > 0f) body.put("portionGrams", portionGrams);
                if (actionId != null && !actionId.isEmpty()) body.put("commandId", actionId);
                JSONObject response = post("/command", body.toString(),
                        TIMEOUT_CONNECT, TIMEOUT_READ);
                mainHandler.post(() -> callback.onSuccess(response));
            } catch (Exception e) {
                Log.e(TAG, "sendManualFeedTest error", e);
                mainHandler.post(() -> callback.onError(friendlyError(e.getMessage())));
            }
        });
    }

    public void sendWaterCommand(float volumeMl, String actionId, Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("command", "dispense_water");
                body.put("volumeMl", volumeMl);
                if (actionId != null && !actionId.isEmpty()) body.put("commandId", actionId);
                JSONObject response = post("/command", body.toString(),
                        TIMEOUT_CONNECT, TIMEOUT_READ);
                mainHandler.post(() -> callback.onSuccess(response));
            } catch (Exception e) {
                Log.e(TAG, "sendWaterCommand error", e);
                mainHandler.post(() -> callback.onError(friendlyError(e.getMessage())));
            }
        });
    }

    public void sendTrayCommand(boolean open, String actionId, Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("command", open ? "open_tray" : "close_tray");
                if (actionId != null && !actionId.isEmpty()) body.put("commandId", actionId);
                JSONObject response = post("/command", body.toString(),
                        TIMEOUT_CONNECT, TIMEOUT_READ);
                mainHandler.post(() -> callback.onSuccess(response));
            } catch (Exception e) {
                Log.e(TAG, "sendTrayCommand error", e);
                mainHandler.post(() -> callback.onError(friendlyError(e.getMessage())));
            }
        });
    }

    public static long uidHash(String uid) {
        long h = 2166136261L;
        for (byte b : uid.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xFFL);
            h = (h * 16777619L) & 0xFFFFFFFFL;
        }
        return h;
    }

    public void linkAccount(String uid, Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("uid", uid);
                JSONObject response = post("/link-account", body.toString(),
                        TIMEOUT_CONNECT, TIMEOUT_READ);
                if (callback != null) mainHandler.post(() -> callback.onSuccess(response));
            } catch (Exception e) {
                Log.e(TAG, "linkAccount error", e);
                if (callback != null) mainHandler.post(() -> callback.onError(friendlyError(e.getMessage())));
            }
        });
    }

    public void syncSchedules(List<FeedingSchedule> schedules, Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                JSONArray array = new JSONArray();
                if (schedules != null) {
                    for (FeedingSchedule schedule : schedules) {
                        if (schedule == null) continue;
                        JSONObject item = new JSONObject();
                        item.put("id", schedule.getScheduleId());
                        item.put("hour", schedule.getTimeHour());
                        item.put("minute", schedule.getTimeMinute());
                        item.put("portionGrams", schedule.getPortionGrams());
                        item.put("enabled", schedule.isEnabled());
                        JSONArray repeatDays = new JSONArray();
                        if (schedule.getRepeatDays() != null) {
                            for (String day : schedule.getRepeatDays()) {
                                repeatDays.put(day);
                            }
                        }
                        item.put("repeatDays", repeatDays);
                        array.put(item);
                    }
                }
                body.put("schedules", array);
                body.put("nowEpochSeconds", System.currentTimeMillis() / 1000L);

                JSONObject response = post("/schedules", body.toString(),
                        TIMEOUT_CONNECT, TIMEOUT_READ);
                mainHandler.post(() -> callback.onSuccess(response));
            } catch (Exception e) {
                Log.e(TAG, "syncSchedules error", e);
                mainHandler.post(() -> callback.onError(friendlyError(e.getMessage())));
            }
        });
    }

    public void ping(Callback callback) {
        getStatus(callback);
    }

    private JSONObject get(String path, int connectTimeout, int readTimeout) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        return readResponse(conn);
    }

    private JSONObject post(String path, String jsonBody,
                            int connectTimeout, int readTimeout) throws Exception {
        return post(path, jsonBody, connectTimeout, readTimeout, null, null);
    }

    private JSONObject post(String path, String jsonBody,
                            int connectTimeout, int readTimeout,
                            String extraHeader, String extraValue) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept",       "application/json");
        if (extraHeader != null) conn.setRequestProperty(extraHeader, extraValue);
        conn.setDoOutput(true);

        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bodyBytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }

        return readResponse(conn);
    }

    private JSONObject readResponse(HttpURLConnection conn) throws Exception {
        int statusCode = conn.getResponseCode();
        Log.d(TAG, "HTTP " + statusCode + " ← " + conn.getURL());

        java.io.InputStream stream = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) stream = conn.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                stream, StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        conn.disconnect();

        String body = sb.toString();
        Log.d(TAG, "Response body: " + body);

        if (body.isEmpty()) return new JSONObject("{\"status\":\"ok\"}");

        JSONObject json = new JSONObject(body);

        if (statusCode >= 400) {
            String error = json.optString("error", "HTTP " + statusCode);
            throw new Exception(error);
        }
        return json;
    }

    private void postProgress(ProgressCallback callback, String message) {
        mainHandler.post(() -> callback.onProgress(message));
    }

    private String friendlyError(String raw) {
        if (raw == null) return "Unknown error";
        String lower = raw.toLowerCase(Locale.US);
        if (lower.contains("invalid provisioning token"))
            return "Provisioning session expired. Please tap Configure again.";
        if (raw.contains("timeout") || raw.contains("timed out"))
            return "Device did not respond in time. Check that you are on PetFeeder_Setup WiFi.";
        if (raw.contains("refused"))
            return "Connection refused — device may have rebooted. Try finding device again.";
        if (raw.contains("host"))
            return "Device not reachable. Make sure your phone is on PetFeeder_Setup WiFi.";
        return raw;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
