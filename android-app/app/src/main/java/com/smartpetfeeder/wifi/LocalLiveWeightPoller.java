package com.smartpetfeeder.wifi;

import android.os.Handler;
import android.os.Looper;

import com.smartpetfeeder.model.DeviceFeedSummary;

import org.json.JSONObject;

/**
 * Reads the live bowl weight (and live feeding figures) straight from the feeder's /status while the
 * app is open and on the same network. The feeder pauses its Firebase live-weight uploads while it
 * is being read this way; away from home nothing is found here and the Firebase values are used.
 */
public class LocalLiveWeightPoller {

    public interface Listener {
        void onWeight(boolean available, float grams);

        void onLiveFeeding(boolean active, String feedingId, float target,
                           float consumed, float remaining, boolean bowlRemoved);

        void onLastFeed(DeviceFeedSummary summary);
    }

    private static final long POLL_MS       = 2000L;   // gentle on the ESP8266's small heap
    private static final long REDISCOVER_MS = 30000L;  // while the feeder cannot be found (e.g. away from home)
    private static final int  MAX_FAILURES  = 3;

    private final DeviceDiscovery discovery;
    private final Listener        listener;
    private final Handler         handler = new Handler(Looper.getMainLooper());
    private final Runnable        tick    = this::poll;

    private volatile EspApiClient api;
    private volatile boolean running;
    private volatile boolean busy;
    private int failures;

    public LocalLiveWeightPoller(DeviceDiscovery discovery, Listener listener) {
        this.discovery = discovery;
        this.listener  = listener;
    }

    public void start() {
        if (running) return;
        running  = true;
        failures = 0;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    public void shutdown() {
        stop();
        EspApiClient client = api;
        api = null;
        if (client != null) client.shutdown();
    }

    private void schedule(long delayMs) {
        if (running) handler.postDelayed(tick, delayMs);
    }

    private void poll() {
        if (!running || busy) return;
        EspApiClient client = api;
        if (client == null) {
            findDevice();
            return;
        }
        busy = true;
        client.getStatus(new EspApiClient.Callback() {
            @Override public void onSuccess(JSONObject r) {
                busy = false;
                failures = 0;
                if (!running) return;
                dispatch(r);
                schedule(POLL_MS);
            }

            @Override public void onError(String message) {
                busy = false;
                if (++failures >= MAX_FAILURES) {
                    EspApiClient old = api;
                    api = null;
                    if (old != null) old.shutdown();
                    failures = 0;
                    schedule(REDISCOVER_MS);
                } else {
                    schedule(POLL_MS);
                }
            }
        });
    }

    private void findDevice() {
        busy = true;
        discovery.discover(new DeviceDiscovery.DiscoveryCallback() {
            @Override public void onFound(String baseUrl, String method) {
                busy = false;
                if (!running) return;
                api = new EspApiClient(baseUrl);
                failures = 0;
                handler.post(tick);
            }

            @Override public void onNotFound() {
                busy = false;
                schedule(REDISCOVER_MS);
            }
        });
    }

    private void dispatch(JSONObject r) {
        listener.onWeight(r.optBoolean("currentWeightAvailable", false),
                (float) r.optDouble("currentWeight", 0.0));
        if (r.has("live_feeding")) {   // older firmware does not report it: leave the Firebase value alone
            listener.onLiveFeeding(r.optBoolean("live_feeding", false),
                    r.optString("live_feeding_id", ""),
                    (float) r.optDouble("live_target", 0.0),
                    (float) r.optDouble("live_consumed", 0.0),
                    (float) r.optDouble("live_remaining", 0.0),
                    r.optBoolean("live_bowl_removed", false));
        }
        DeviceFeedSummary summary = DeviceFeedSummary.fromJson(r.optJSONObject("last_feed"));
        if (summary != null) listener.onLastFeed(summary);
    }
}
