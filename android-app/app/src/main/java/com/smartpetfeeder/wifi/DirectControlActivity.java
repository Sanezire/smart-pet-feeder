package com.smartpetfeeder.wifi;

import android.Manifest;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.auth.FirebaseAuth;
import com.smartpetfeeder.R;
import com.smartpetfeeder.adapter.ScheduleAdapter;
import com.smartpetfeeder.auth.LoginActivity;
import com.smartpetfeeder.model.FeedingSchedule;
import com.smartpetfeeder.util.BaseActivity;
import com.smartpetfeeder.util.LocalScheduleStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Controls the feeder straight over its own Wi-Fi (PetFeeder_Setup, 192.168.4.1) with no router,
 * no internet and no account. Schedules are kept on the phone and pushed to the feeder, which
 * stores and runs them itself.
 */
public class DirectControlActivity extends BaseActivity {

    private static final long   POLL_MS      = 3000L;
    private static final int    REQ_LOCATION = 4711;
    private static final String[] DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    private EspApiClient        api;
    private FeederNetworkBinder binder;
    private LocalScheduleStore  store;
    private ScheduleAdapter     adapter;
    private final List<FeedingSchedule> schedules = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean connected;
    private boolean connecting;
    private boolean forceRequestNext;
    private boolean initialSyncDone;
    private boolean pendingSync;
    private boolean updatingSwitch;
    private int     statusFailures;

    private TextView       tvStatus;
    private TextView       tvDetails;
    private TextView       tvNoSchedules;
    private TextView       tvSyncInfo;
    private MaterialButton btnConnect;
    private SwitchMaterial switchKeepAp;
    private EditText       etFeedPortion;
    private EditText       etWaterMl;

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (connected) refreshStatus();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_direct_control);

        api    = new EspApiClient(EspApiClient.AP_BASE_URL);
        binder = new FeederNetworkBinder(this);
        store  = new LocalScheduleStore(this);

        tvStatus      = findViewById(R.id.tvDirectStatus);
        tvDetails     = findViewById(R.id.tvDirectDetails);
        tvNoSchedules = findViewById(R.id.tvDirectNoSchedules);
        tvSyncInfo    = findViewById(R.id.tvDirectSyncInfo);
        btnConnect    = findViewById(R.id.btnDirectConnect);
        switchKeepAp  = findViewById(R.id.switchDirectKeepAp);
        etFeedPortion = findViewById(R.id.etFeedPortion);
        etWaterMl     = findViewById(R.id.etWaterMl);

        schedules.addAll(store.load());
        sortSchedules();

        RecyclerView rv = findViewById(R.id.rvDirectSchedules);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ScheduleAdapter();
        adapter.setOnToggleListener((s, enabled, pos) -> onSchedulesChanged());
        adapter.setOnDeleteListener((s, pos) -> confirmDelete(s));
        adapter.setOnEditListener((s, pos) -> showTimePicker(s));
        rv.setAdapter(adapter);
        refreshScheduleList();

        btnConnect.setOnClickListener(v -> connect());
        findViewById(R.id.btnDirectFeed).setOnClickListener(v -> feedNow());
        findViewById(R.id.btnDirectWater).setOnClickListener(v -> dispenseWater());
        findViewById(R.id.btnDirectTrayOpen).setOnClickListener(v -> trayCommand(true));
        findViewById(R.id.btnDirectTrayClose).setOnClickListener(v -> trayCommand(false));
        findViewById(R.id.btnDirectAddSchedule).setOnClickListener(v -> addSchedule());

        switchKeepAp.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingSwitch) return;
            if (!requireConnected()) { revertSwitch(!checked); return; }
            api.setDirectMode(checked, new EspApiClient.Callback() {
                @Override public void onSuccess(JSONObject r) {
                    toast(checked ? "Feeder Wi-Fi will stay on." : "Feeder Wi-Fi will turn off after it joins a router.");
                }
                @Override public void onError(String m) {
                    revertSwitch(!checked);
                    toast(m);
                }
            });
        });

        MaterialButton btnSignIn = findViewById(R.id.btnDirectSignIn);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            btnSignIn.setVisibility(View.VISIBLE);
            btnSignIn.setOnClickListener(v -> {
                LocalScheduleStore.setDirectEntry(this, false);
                startActivity(new Intent(this, LoginActivity.class));
                finish();
            });
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Only used to read the current Wi-Fi name on older Android versions.
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        }

        updatePendingText();
        connect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, POLL_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(pollRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // Give the normal network back so Firebase and everything else work again.
        binder.release();
        api.shutdown();
        super.onDestroy();
    }

    // ---- connection -------------------------------------------------------------------------

    private void connect() {
        if (connecting) return;
        connecting = true;
        updateConnectionUi();
        binder.bind(forceRequestNext, new FeederNetworkBinder.Listener() {
            @Override public void onBound() { probe(); }
            @Override public void onFailed(String reason) {
                connecting = false;
                setConnected(false);
                tvStatus.setText(reason);
            }
            @Override public void onLost() { setConnected(false); }
        });
    }

    private void probe() {
        api.getStatus(new EspApiClient.Callback() {
            @Override public void onSuccess(JSONObject r) {
                if (isFinishing()) return;
                connecting = false;
                forceRequestNext = false;
                statusFailures = 0;
                setConnected(true);
                showStatus(r);
                if (!initialSyncDone) initialSync();
            }
            @Override public void onError(String m) {
                if (isFinishing()) return;
                connecting = false;
                forceRequestNext = true;
                setConnected(false);
                tvStatus.setText("Feeder not reachable. Make sure your phone is on the "
                        + FeederNetworkBinder.AP_SSID + " Wi-Fi, then tap Connect.");
            }
        });
    }

    private void refreshStatus() {
        api.getStatus(new EspApiClient.Callback() {
            @Override public void onSuccess(JSONObject r) {
                if (isFinishing()) return;
                statusFailures = 0;
                if (!connected) setConnected(true);
                showStatus(r);
                if (pendingSync) pushSchedules(false);
            }
            @Override public void onError(String m) {
                if (isFinishing()) return;
                if (++statusFailures >= 2) setConnected(false);
            }
        });
    }

    private void setConnected(boolean value) {
        connected = value;
        if (!value) {
            initialSyncDone = false;
            tvDetails.setVisibility(View.GONE);
        }
        updateConnectionUi();
    }

    private void updateConnectionUi() {
        if (connecting) {
            tvStatus.setText("Connecting to the feeder...");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
            btnConnect.setEnabled(false);
            return;
        }
        btnConnect.setEnabled(true);
        if (connected) {
            tvStatus.setText("Connected to the feeder");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.colorStatusCompleted));
            btnConnect.setText("Reconnect");
        } else {
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.colorStatusMissed));
            if (tvStatus.getText().toString().startsWith("Connect")) tvStatus.setText("Not connected");
            btnConnect.setText("Connect to feeder");
        }
    }

    private void showStatus(JSONObject r) {
        boolean weightOk = r.optBoolean("currentWeightAvailable", false);
        String weight = weightOk
                ? String.format(Locale.US, "%.0f g", r.optDouble("currentWeight", 0)) : "n/a";
        boolean clockSet = r.optBoolean("time_synced", false);
        tvDetails.setText(String.format(Locale.US,
                "Bowl: %s\nTray: %s\nClock: %s\nSchedules on feeder: %d",
                weight, r.optString("tray_state", "-"),
                clockSet ? "set" : "not set", r.optInt("schedule_count", 0)));
        tvDetails.setVisibility(View.VISIBLE);

        revertSwitch(r.optBoolean("ap_always_on", false));

        // The feeder forgets the time when it loses power, so set it again whenever it is missing.
        if (!clockSet) api.setTime(null);
    }

    private void revertSwitch(boolean value) {
        updatingSwitch = true;
        switchKeepAp.setChecked(value);
        updatingSwitch = false;
    }

    private boolean requireConnected() {
        if (connected) return true;
        toast("Connect to the feeder first.");
        return false;
    }

    // ---- controls ---------------------------------------------------------------------------

    private void feedNow() {
        if (!requireConnected()) return;
        api.sendManualFeedTest(parseFloat(etFeedPortion, 0f), null, simple("Feeding started."));
    }

    private void dispenseWater() {
        if (!requireConnected()) return;
        float ml = parseFloat(etWaterMl, 0f);
        if (ml <= 0f) { toast("Enter an amount in ml."); return; }
        api.sendWaterCommand(ml, null, simple("Dispensing water."));
    }

    private void trayCommand(boolean open) {
        if (!requireConnected()) return;
        api.sendTrayCommand(open, null, simple(open ? "Tray opening." : "Tray closing."));
    }

    private EspApiClient.Callback simple(String okMessage) {
        return new EspApiClient.Callback() {
            @Override public void onSuccess(JSONObject r) { toast(okMessage); }
            @Override public void onError(String m) { toast(m); }
        };
    }

    private static float parseFloat(EditText et, float fallback) {
        try {
            return Float.parseFloat(et.getText().toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    // ---- schedules --------------------------------------------------------------------------

    private void initialSync() {
        initialSyncDone = true;
        api.setTime(null);
        api.getSchedules(new EspApiClient.Callback() {
            @Override public void onSuccess(JSONObject r) {
                if (isFinishing()) return;
                if (schedules.isEmpty()) importFromFeeder(r);
                else pushSchedules(false);
            }
            @Override public void onError(String m) {
                if (isFinishing()) return;
                if (!schedules.isEmpty()) pushSchedules(false);
            }
        });
    }

    /** Nothing saved on this phone yet: show what the feeder already has instead of wiping it. */
    private void importFromFeeder(JSONObject r) {
        JSONArray array = r.optJSONArray("schedules");
        if (array == null || array.length() == 0) return;
        for (int i = 0; i < array.length() && schedules.size() < LocalScheduleStore.MAX_SCHEDULES; i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            int mask = o.optInt("daysMask", 0);
            List<String> days = new ArrayList<>();
            for (int d = 0; d < 7; d++) if (mask == 0 || (mask & (1 << d)) != 0) days.add(DAYS[d]);
            schedules.add(new FeedingSchedule(UUID.randomUUID().toString(),
                    o.optInt("hour"), o.optInt("minute"),
                    (float) o.optDouble("portionGrams", 0), o.optBoolean("enabled", true),
                    null, days));
        }
        sortSchedules();
        store.save(schedules);
        refreshScheduleList();
        toast("Loaded " + schedules.size() + " schedule(s) from the feeder.");
    }

    private void pushSchedules(boolean showToast) {
        pendingSync = false;
        api.syncSchedules(new ArrayList<>(schedules), new EspApiClient.Callback() {
            @Override public void onSuccess(JSONObject r) {
                if (isFinishing()) return;
                tvSyncInfo.setText("Saved on the feeder. It runs these on its own.");
                if (showToast) toast("Schedules saved to the feeder.");
            }
            @Override public void onError(String m) {
                if (isFinishing()) return;
                pendingSync = true;
                tvSyncInfo.setText("Could not reach the feeder. Will retry automatically.");
                if (showToast) toast(m);
            }
        });
    }

    private void onSchedulesChanged() {
        sortSchedules();
        store.save(schedules);
        refreshScheduleList();
        if (connected) {
            pushSchedules(true);
        } else {
            pendingSync = true;
            updatePendingText();
        }
    }

    private void updatePendingText() {
        tvSyncInfo.setText(connected ? "" : "Saved on this phone. Will sync to the feeder when connected.");
    }

    private void refreshScheduleList() {
        adapter.setSchedules(new ArrayList<>(schedules));
        tvNoSchedules.setVisibility(schedules.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void sortSchedules() {
        Collections.sort(schedules, new Comparator<FeedingSchedule>() {
            @Override public int compare(FeedingSchedule a, FeedingSchedule b) {
                int ta = a.getTimeHour() * 60 + a.getTimeMinute();
                int tb = b.getTimeHour() * 60 + b.getTimeMinute();
                return Integer.compare(ta, tb);
            }
        });
    }

    private void addSchedule() {
        if (schedules.size() >= LocalScheduleStore.MAX_SCHEDULES) {
            toast("The feeder holds up to " + LocalScheduleStore.MAX_SCHEDULES + " schedules.");
            return;
        }
        showTimePicker(null);
    }

    private void showTimePicker(FeedingSchedule existing) {
        int h = existing != null ? existing.getTimeHour() : 8;
        int m = existing != null ? existing.getTimeMinute() : 0;
        new TimePickerDialog(this, (view, hour, minute) -> showDetails(existing, hour, minute),
                h, m, false).show();
    }

    private void showDetails(FeedingSchedule existing, int hour, int minute) {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText etPortion = new EditText(this);
        etPortion.setHint("Portion (grams)");
        etPortion.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etPortion.setText(existing != null
                ? String.format(Locale.US, "%.0f", existing.getPortionGrams()) : "50");
        box.addView(etPortion);

        CheckBox[] checks = new CheckBox[7];
        for (int i = 0; i < 7; i++) {
            checks[i] = new CheckBox(this);
            checks[i].setText(DAYS[i]);
            boolean on = existing == null || existing.getRepeatDays() == null
                    || existing.getRepeatDays().isEmpty()
                    || existing.getRepeatDays().contains(DAYS[i]);
            checks[i].setChecked(on);
            box.addView(checks[i]);
        }

        new AlertDialog.Builder(this)
                .setTitle(FeedingSchedule.formatTime12(hour, minute))
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    float portion = parseFloat(etPortion, 0f);
                    if (portion < 1f || portion > 1000f) {
                        toast("Portion must be between 1 and 1000 g.");
                        return;
                    }
                    List<String> days = new ArrayList<>();
                    for (int i = 0; i < 7; i++) if (checks[i].isChecked()) days.add(DAYS[i]);
                    if (days.isEmpty()) days.addAll(java.util.Arrays.asList(DAYS)); // none ticked = every day
                    String id = existing != null ? existing.getScheduleId() : UUID.randomUUID().toString();
                    boolean enabled = existing == null || existing.isEnabled();
                    FeedingSchedule updated = new FeedingSchedule(id, hour, minute, portion, enabled, null, days);
                    if (existing != null) {
                        int idx = schedules.indexOf(existing);
                        if (idx >= 0) schedules.set(idx, updated); else schedules.add(updated);
                    } else {
                        schedules.add(updated);
                    }
                    onSchedulesChanged();
                })
                .show();
    }

    private void confirmDelete(FeedingSchedule schedule) {
        new AlertDialog.Builder(this)
                .setTitle("Delete schedule?")
                .setMessage(schedule.getTimeDisplay() + " • "
                        + String.format(Locale.US, "%.0f g", schedule.getPortionGrams()))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    schedules.remove(schedule);
                    onSchedulesChanged();
                })
                .show();
    }

    private void toast(String message) {
        if (!isFinishing()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
