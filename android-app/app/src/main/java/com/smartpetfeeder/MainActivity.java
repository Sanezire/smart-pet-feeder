package com.smartpetfeeder;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.smartpetfeeder.adapter.NotificationAdapter;
import com.smartpetfeeder.adapter.ScheduleAdapter;
import com.smartpetfeeder.auth.LoginActivity;
import com.smartpetfeeder.model.AppNotificationItem;
import com.smartpetfeeder.wifi.DeviceDiscovery;
import com.smartpetfeeder.wifi.EspApiClient;
import com.smartpetfeeder.wifi.WiFiConfigActivity;
import com.smartpetfeeder.wifi.SettingsActivity;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.firebase.messaging.FirebaseMessaging;
import com.smartpetfeeder.adapter.FeedingHistoryAdapter;
import com.smartpetfeeder.firebase.FirebaseService;
import com.smartpetfeeder.firebase.PetFeederMessagingService;
import com.smartpetfeeder.model.AnomalyAlert;
import com.smartpetfeeder.model.DashboardData;
import com.smartpetfeeder.model.FeedingRecord;
import com.smartpetfeeder.model.FeedingSchedule;
import com.smartpetfeeder.model.LiveFeedingStats;
import com.smartpetfeeder.model.PetProfile;
import com.smartpetfeeder.model.SummaryStats;
import com.smartpetfeeder.util.ChartHelper;
import com.smartpetfeeder.util.PetFeedingCalculator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.json.JSONObject;

public class MainActivity extends AppCompatActivity implements FirebaseService.DataUpdateListener {
    private enum Tab { HOME, NOTIFICATIONS, SCHEDULE, SETTINGS }
    private enum NotificationFilter { ALL, ANOMALY, FEEDING, SYSTEM }
    private enum HomeState { LOADING, ERROR, CONTENT }

    private TextView   tvStatusBanner, tvFeedingCount, tvLastFeedingTime;
    private TextView   tvTotalFood, tvSystemStatus, tvLastUpdated;
    private ImageView  ivStatusIcon;
    private LineChart  lineChart;
    private BarChart   barChart;
    private RecyclerView recyclerFeedingHistory, recyclerNotifications, recyclerHomeSchedules;
    private LinearLayout anomalyContainer, noAnomaliesState, emptyState;
    private View       sectionAnomalyAlerts, sectionFeedingHistory;
    private View       loadingState, errorState, contentContainer, notificationsContainer, scheduleContainer, settingsContainer;
    private View       noDataNoticeCard;
    private TextView   tvErrorMessage;
    private MaterialButton btnRetry, btnManualFeed, btnOpenTray, tabHome, tabNotifications, tabSchedule, tabSettings;
    private MaterialButton btnLogout, btnOpenDeviceSettings, btnShowMoreAnomalies, btnShowMoreHistory;
    private MaterialButtonToggleGroup toggleGroup, notificationFilterGroup;
    private TextView tvLiveWeightValue, tvLiveWeightTarget, tvLiveWeightDiff, tvLiveWeightMeta;
    private MaterialButton btnNotifSelect, btnNotifSelectAll, btnNotifDelete;
    private TextView   tvConnectionStatus;
    private TextView   tvRecommendedPortion;
    private SwitchMaterial switchThemeMode;

    private TextView tvPetName, tvPetInfo;
    private ImageView ivPetIcon;
    private MaterialButton btnEditPetProfile;
    private View layoutRecommendedFood;
    private TextView tvScheduleMismatch;
    private View layoutSchedulePlan, layoutPortionRecommended;
    private TextView tvSchedulePlanTitle, tvSchedulePlanDetail, tvSchedulePortionRecommended;
    private MaterialButton btnAddSuggestedSchedules, btnUseRecommendedPortion;

    private static final int[][][] SUGGESTED_TIMES = {
            {},
            {{8, 0}},
            {{7, 0}, {18, 0}},
            {{7, 0}, {13, 0}, {19, 0}},
            {{7, 0}, {12, 0}, {18, 0}, {21, 0}},
            {{6, 0}, {10, 0}, {14, 0}, {18, 0}, {22, 0}},
            {{6, 0}, {9, 0}, {12, 0}, {15, 0}, {18, 0}, {21, 0}}
    };
    private PetProfile currentPetProfile;
    private PetFeedingCalculator.Result lastRecommendation;
    private int enabledFeedingsPerDay = 0;
    private boolean petProfilePromptShown = false;
    private AlertDialog petProfileDialog;
    private String autoFilledSchedulePortion;

    private TextView tvScheduleCountNumber;
    private LinearLayout emptySchedulesState;

    private EditText etScheduleTime, etSchedulePortion;
    private LinearLayout scheduleRepeatContainer;
    private MaterialButton btnAddSchedule, btnScheduleSave;
    private RecyclerView recyclerSchedules;
    private List<FeedingSchedule> schedulesList;
    private final boolean[] selectedRepeatDays = new boolean[7];
    private static final String[] DAY_LABELS = {"Mon","Tue","Wed","Thu","Fri","Sat","Sun"};

    private FeedingHistoryAdapter historyAdapter;
    private NotificationAdapter   notificationAdapter;
    private ScheduleAdapter       scheduleAdapter, homeScheduleAdapter;
    private ChartHelper           chartHelper;
    private FirebaseService       firebaseService;
    private DeviceDiscovery       deviceDiscovery;
    private EspApiClient          espApiClient;

    private String  currentFilter = "DAY";
    private Tab currentTab = Tab.HOME;
    private NotificationFilter currentNotificationFilter = NotificationFilter.ALL;
    private boolean isFirstLoad   = true;
    private HomeState homeState = HomeState.LOADING;
    private boolean reconnectRetryDone = false;
    private boolean feederKnown = false;
    private boolean feederOnline = false;
    private Boolean lastChipOnline = null;
    private String renderedScheduleSig = null;
    private String renderedChartSig = null;
    private String renderedSummarySig = null;
    private String lastSystemNoticeMsg = null;
    private String lastSystemNoticeTime = "";
    private final Set<String> unsavedScheduleIds = new HashSet<>();
    private boolean isDeviceConfigured = false;
    private boolean isDeviceReachable = false;
    private String lastSyncedScheduleSignature = "";
    private DashboardData latestDashboardData;
    private boolean lanWeightPollInFlight = false;
    private long lastLanWeightPollAt = 0L;
    private boolean liveWeightKnown = false;
    private float liveWeightGrams = 0f;
    private long liveWeightSourceAt = 0L;
    private long liveWeightReceivedAt = 0L;
    private boolean liveWeightTickerRunning = false;
    private static final long WEIGHT_STALE_MS = 15_000L;
    private static final long LAST_GOOD_WEIGHT_HOLD_MS = 8_000L;
    private static final long LIVE_WEIGHT_TICK_MS = 1_000L;
    private static final long LAN_WEIGHT_POLL_MIN_MS = 1_000L;

    private TextView tvSyncIndicator, tvScheduleSyncStatus;
    private MaterialButton btnSyncNow;
    private FirebaseService.SyncState currentSyncState = FirebaseService.SyncState.SYNCED;
    private int  syncPendingCount = 0;
    private long syncLastSyncedAt = 0L;
    private long feederNoticeAt = 0L;
    private long lastDashboardUpdatedAt = 0L;
    private boolean staleDialogShowing = false;
    private static final long SYNC_UI_TICK_MS = 10_000L;
    private final Handler syncTickHandler = new Handler();
    private final Runnable syncTick = new Runnable() {
        @Override public void run() {
            renderSyncUi();
            if (historyAdapter != null) historyAdapter.refreshProgress();
            if (lastDashboardUpdatedAt > 0) updateLastUpdatedTime(lastDashboardUpdatedAt);
            syncTickHandler.postDelayed(this, SYNC_UI_TICK_MS);
        }
    };
    private final List<AppNotificationItem> allNotifications = new ArrayList<>();

    private static final long FEED_FINAL_NOTIFY_MAX_AGE_MS = 24 * 60 * 60_000L;
    private LiveFeedingStats liveFeeding;
    private View     cardLiveFeeding;
    private TextView tvLiveFeedTarget, tvLiveFeedConsumed, tvLiveFeedRemaining, tvLiveFeedMeta;
    private LinearProgressIndicator progressLiveFeed;
    private View skeletonLoading;
    private final Handler liveWeightHandler = new Handler();
    private final Runnable liveWeightTick = new Runnable() {
        @Override public void run() {
            if (!liveWeightTickerRunning) return;
            pollLanWeightFallback();
            renderLiveWeightCard();
            liveWeightHandler.postDelayed(this, LIVE_WEIGHT_TICK_MS);
        }
    };

    private static final long FIREBASE_TIMEOUT_MS = 8_000L;
    private final  Handler    timeoutHandler       = new Handler();

    private final ActivityResultLauncher<String> requestNotificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    fetchFcmToken();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseService.currentUid() == null) {
            Intent toLogin = new Intent(this, LoginActivity.class);
            toLogin.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(toLogin);
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        deviceDiscovery = new DeviceDiscovery(this);

        PetFeederMessagingService.createNotificationChannels(this);

        initializeViews();
        setupRecyclerView();
        setupCharts();
        setupFilterToggle();
        setupRetryButton();
        setupNotificationRecyclerView();
        setupNotificationFilters();
        setupHomeSchedules();
        setupManualFeedButton();
        setupOpenTrayButton();
        setupDispenseWaterButton();
        setupScheduleTab();
        setupBottomNavigation();
        setupSettingsTab();

        firebaseService = new FirebaseService(getApplicationContext());
        firebaseService.setListener(this);
        firebaseService.setLiveWeightListener(this::onLiveWeightUpdated);
        firebaseService.setLiveFeedingListener(this::onLiveFeedingUpdated);
        firebaseService.setSyncListener(syncListener);
        firebaseService.setPetProfileListener(this::onPetProfileChanged);
        firebaseService.setFeederStatusListener(this::onFeederStatusChanged);
        firebaseService.seedDefaultSchedulesIfEmpty();
        if (btnEditPetProfile != null) {
            btnEditPetProfile.setOnClickListener(v -> showPetProfileDialog(currentPetProfile));
        }
        View.OnClickListener syncNowClick = v -> {
            firebaseService.retrySyncNow();
            syncSchedulesToDevice(false);
        };
        if (btnSyncNow != null) btnSyncNow.setOnClickListener(syncNowClick);
        if (tvSyncIndicator != null) tvSyncIndicator.setOnClickListener(syncNowClick);

        showLoadingState();
        startFirebaseTimeout();

        requestNotificationPermissionIfNeeded();

        if (tvStatusBanner != null) {
            tvStatusBanner.setOnLongClickListener(v -> {
                startActivity(new android.content.Intent(this, WiFiConfigActivity.class));
                return true;
            });
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (firebaseService != null) firebaseService.startListening();
        if (isFirstLoad) startFirebaseTimeout();
        syncTickHandler.removeCallbacks(syncTick);
        syncTickHandler.postDelayed(syncTick, SYNC_UI_TICK_MS);
        applyPhoneOnlineToChip();
        renderSyncUi();
        updateLiveWeightTicker();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateLiveWeightTicker();
    }

    @Override
    protected void onPause() {
        stopLiveWeightTicker();
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        timeoutHandler.removeCallbacksAndMessages(null);
        syncTickHandler.removeCallbacks(syncTick);
        stopLiveWeightTicker();
        if (firebaseService != null) firebaseService.stopListening();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        trayHandler.removeCallbacksAndMessages(null);
        if (firebaseService != null) firebaseService.stopWatchingRemoteCommand();
        if (petProfileDialog != null && petProfileDialog.isShowing()) petProfileDialog.dismiss();
        if (firebaseService != null) {
            firebaseService.setLiveWeightListener(null);
            firebaseService.setLiveFeedingListener(null);
            firebaseService.setFeederStatusListener(null);
            firebaseService.setSyncListener(null);
            firebaseService.shutdown();
        }
        syncTickHandler.removeCallbacksAndMessages(null);
        liveWeightHandler.removeCallbacksAndMessages(null);
        timeoutHandler.removeCallbacksAndMessages(null);
        if (espApiClient != null) espApiClient.shutdown();
        if (deviceDiscovery != null) deviceDiscovery.shutdown();
    }

    private void initializeViews() {
        tvStatusBanner      = findViewById(R.id.tvStatusBanner);
        tvFeedingCount      = findViewById(R.id.tvFeedingCount);
        tvLastFeedingTime   = findViewById(R.id.tvLastFeedingTime);
        tvTotalFood         = findViewById(R.id.tvTotalFood);
        tvSystemStatus      = findViewById(R.id.tvSystemStatus);
        tvLastUpdated       = findViewById(R.id.tvLastUpdated);
        ivStatusIcon        = findViewById(R.id.ivStatusIcon);
        lineChart           = findViewById(R.id.lineChart);
        barChart            = findViewById(R.id.barChart);
        recyclerFeedingHistory = findViewById(R.id.recyclerFeedingHistory);
        anomalyContainer    = findViewById(R.id.anomalyContainer);
        sectionAnomalyAlerts  = findViewById(R.id.sectionAnomalyAlerts);
        sectionFeedingHistory = findViewById(R.id.sectionFeedingHistory);
        noAnomaliesState    = findViewById(R.id.noAnomaliesState);
        emptyState          = findViewById(R.id.emptyState);
        loadingState        = findViewById(R.id.loadingState);
        errorState          = findViewById(R.id.errorState);
        tvErrorMessage      = findViewById(R.id.tvErrorMessage);
        btnRetry            = findViewById(R.id.btnRetry);
        toggleGroup         = findViewById(R.id.toggleGroup);
        contentContainer    = findViewById(R.id.contentContainer);
        notificationsContainer = findViewById(R.id.notificationsContainer);
        scheduleContainer   = findViewById(R.id.scheduleContainer);
        settingsContainer   = findViewById(R.id.settingsContainer);
        tvConnectionStatus  = findViewById(R.id.tvStatusIndicator);
        tvSyncIndicator     = findViewById(R.id.tvSyncIndicator);
        tvScheduleSyncStatus = findViewById(R.id.tvScheduleSyncStatus);
        btnSyncNow          = findViewById(R.id.btnSyncNow);
        noDataNoticeCard    = findViewById(R.id.noDataNoticeCard);
        recyclerNotifications = findViewById(R.id.recyclerNotifications);
        recyclerHomeSchedules = findViewById(R.id.recyclerHomeSchedules);
        notificationFilterGroup = findViewById(R.id.notificationFilterGroup);
        tvLiveWeightValue = findViewById(R.id.tvLiveWeightValue);
        tvLiveWeightTarget = findViewById(R.id.tvLiveWeightTarget);
        tvLiveWeightDiff = findViewById(R.id.tvLiveWeightDiff);
        tvLiveWeightMeta = findViewById(R.id.tvLiveWeightMeta);
        cardLiveFeeding = findViewById(R.id.cardLiveFeeding);
        tvLiveFeedTarget = findViewById(R.id.tvLiveFeedTarget);
        tvLiveFeedConsumed = findViewById(R.id.tvLiveFeedConsumed);
        tvLiveFeedRemaining = findViewById(R.id.tvLiveFeedRemaining);
        tvLiveFeedMeta = findViewById(R.id.tvLiveFeedMeta);
        progressLiveFeed = findViewById(R.id.progressLiveFeed);
        skeletonLoading = findViewById(R.id.skeletonLoading);
        if (skeletonLoading != null) {
            skeletonLoading.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.skeleton_pulse));
        }
        btnNotifSelect = findViewById(R.id.btnNotifSelect);
        btnNotifSelectAll = findViewById(R.id.btnNotifSelectAll);
        btnNotifDelete = findViewById(R.id.btnNotifDelete);
        tabHome = findViewById(R.id.tabHome);
        tabNotifications = findViewById(R.id.tabNotifications);
        tabSchedule = findViewById(R.id.tabSchedule);
        tabSettings = findViewById(R.id.tabSettings);
        switchThemeMode = findViewById(R.id.switchThemeMode);
        tvPetName = findViewById(R.id.tvPetName);
        tvPetInfo = findViewById(R.id.tvPetInfo);
        ivPetIcon = findViewById(R.id.ivPetIcon);
        tvRecommendedPortion = findViewById(R.id.tvRecommendedPortion);
        layoutRecommendedFood = findViewById(R.id.layoutRecommendedFood);
        tvScheduleMismatch = findViewById(R.id.tvScheduleMismatch);
        layoutSchedulePlan = findViewById(R.id.layoutSchedulePlan);
        layoutPortionRecommended = findViewById(R.id.layoutPortionRecommended);
        tvSchedulePlanTitle = findViewById(R.id.tvSchedulePlanTitle);
        tvSchedulePlanDetail = findViewById(R.id.tvSchedulePlanDetail);
        tvSchedulePortionRecommended = findViewById(R.id.tvSchedulePortionRecommended);
        btnAddSuggestedSchedules = findViewById(R.id.btnAddSuggestedSchedules);
        btnUseRecommendedPortion = findViewById(R.id.btnUseRecommendedPortion);
        btnEditPetProfile = findViewById(R.id.btnEditPetProfile);
        btnLogout = findViewById(R.id.btnLogout);
        btnOpenDeviceSettings = findViewById(R.id.btnOpenDeviceSettings);
        btnShowMoreAnomalies = findViewById(R.id.btnShowMoreAnomalies);
        btnShowMoreHistory = findViewById(R.id.btnShowMoreHistory);

        etScheduleTime = findViewById(R.id.etScheduleTime);
        etSchedulePortion = findViewById(R.id.etSchedulePortion);
        scheduleRepeatContainer = findViewById(R.id.scheduleRepeatContainer);
        btnAddSchedule = findViewById(R.id.btnAddSchedule);
        btnScheduleSave = findViewById(R.id.btnScheduleSave);
        recyclerSchedules = findViewById(R.id.recyclerSchedules);

        tvScheduleCountNumber = findViewById(R.id.tvScheduleCountNumber);
        emptySchedulesState = findViewById(R.id.emptySchedulesState);

        schedulesList = new ArrayList<>();
    }

    private void setupRecyclerView() {
        historyAdapter = new FeedingHistoryAdapter();
        recyclerFeedingHistory.setLayoutManager(new LinearLayoutManager(this));
        recyclerFeedingHistory.setAdapter(historyAdapter);
        recyclerFeedingHistory.setNestedScrollingEnabled(false);
        recyclerFeedingHistory.setHasFixedSize(false);

        historyAdapter.setOnItemClickListener(record ->
                Toast.makeText(this,
                        "Record: " + (record.getScheduledTimeDisplay() != null
                                ? record.getScheduledTimeDisplay() : "--:--"),
                        Toast.LENGTH_SHORT).show());
    }

    private void setupHomeSchedules() {
        recyclerHomeSchedules.setLayoutManager(new LinearLayoutManager(this));
        homeScheduleAdapter = new ScheduleAdapter();
        homeScheduleAdapter.setReadOnly(true);
        recyclerHomeSchedules.setAdapter(homeScheduleAdapter);

        updateScheduleEmptyState(schedulesList);

    }

    private void setupManualFeedButton() {
        btnManualFeed = findViewById(R.id.btnManualFeed);
        if (btnManualFeed != null) {
            btnManualFeed.setOnClickListener(v -> showManualFeedDialog());
        }
    }

    private void triggerManualFeedWithDeviceTest(float portionGrams) {
        if (btnManualFeed == null) return;

        btnManualFeed.setEnabled(false);
        btnManualFeed.setText("Sending...");

        final String actionId = "manual_" + System.currentTimeMillis();

        if (deviceDiscovery == null) {
            restoreManualFeedButton();
            sendRemoteManualFeedCommand(portionGrams, actionId, false);
            return;
        }

        deviceDiscovery.discover(new DeviceDiscovery.DiscoveryCallback() {
            @Override
            public void onFound(String baseUrl, String method) {
                if (espApiClient != null) espApiClient.shutdown();
                espApiClient = new EspApiClient(baseUrl);
                sendFeedNowCommand(portionGrams, actionId);
            }

            @Override
            public void onNotFound() {
                restoreManualFeedButton();
                sendRemoteManualFeedCommand(portionGrams, actionId, false);
            }
        });
    }

    private void sendFeedNowCommand(float portionGrams, String actionId) {
        if (espApiClient == null) {
            restoreManualFeedButton();
            sendRemoteManualFeedCommand(portionGrams, actionId, true);
            return;
        }

        espApiClient.sendManualFeedTest(portionGrams, actionId, new EspApiClient.Callback() {
            @Override
            public void onSuccess(org.json.JSONObject response) {
                restoreManualFeedButton();
                confirmHaptic();
                if (firebaseService != null) {
                    firebaseService.recordLocalManualFeed(portionGrams, actionId, null);
                }
                Toast.makeText(MainActivity.this,
                        String.format(Locale.US,
                                "Feeding %.0fg. Dispensing stops when the bowl reaches the target weight.",
                                portionGrams),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String errorMessage) {
                restoreManualFeedButton();
                sendRemoteManualFeedCommand(portionGrams, actionId, true);
            }
        });
    }

    private void sendRemoteManualFeedCommand(float portionGrams, String actionId, boolean afterLocalFail) {
        if (firebaseService == null) {
            Toast.makeText(this, "Remote command unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        if (!firebaseService.isPhoneOnline()) {
            firebaseService.sendRemoteManualFeedCommand(portionGrams, actionId, null);
            Toast.makeText(this, R.string.sync_feed_queued_offline, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.sync_saving, Toast.LENGTH_SHORT).show();
        firebaseService.sendRemoteManualFeedCommand(portionGrams, actionId,
                new FirebaseService.AckCallback() {
                    @Override public void onAcknowledged() {
                        Toast.makeText(MainActivity.this,
                                afterLocalFail ? R.string.sync_feed_sent_cloud_after_local_fail
                                               : R.string.sync_feed_sent_cloud,
                                Toast.LENGTH_LONG).show();
                    }
                    @Override public void onFailed(String error) {
                        if (!"discarded".equals(error)) {
                            Toast.makeText(MainActivity.this, R.string.sync_save_failed,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    private static final long TRAY_OPEN_RUN_MS  = 15000L;
    private static final boolean TRAY_AUTO_CLOSE = false;
    private static final long TRAY_HOLD_MS      = 5000L;   // only used when TRAY_AUTO_CLOSE is true
    private static final long TRAY_CLOSE_RUN_MS = 15000L;
    private static final long TRAY_SETTLE_MS    = 400L;
    private static final long TRAY_COOLDOWN_MS  = 2000L;
    private static final long TRAY_CLOUD_GRACE_MS = 8000L;
    private static final long TRAY_SEND_TIMEOUT_MS = 20_000L;
    private static final int  TRAY_VERIFY_TRIES = 5;

    private enum TrayUi { IDLE, SENDING, OPENING, OPEN, CLOSING, COOLDOWN }
    private TrayUi trayUi = TrayUi.IDLE;
    private TrayUi traySendFrom = TrayUi.IDLE;       // state to fall back to if a send fails
    private String trayActionId = null;              // id of the command this UI state belongs to
    private long trayOpenMs = TRAY_OPEN_RUN_MS, trayHoldMs = TRAY_HOLD_MS,
                 trayCloseMs = TRAY_CLOSE_RUN_MS, traySettleMs = TRAY_SETTLE_MS;
    private boolean trayViaCloud = false;
    private final android.os.Handler trayHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private void setupOpenTrayButton() {
        btnOpenTray = findViewById(R.id.btnOpenTray);
        if (btnOpenTray != null) {
            btnOpenTray.setOnClickListener(v -> triggerTrayToggle());
            renderTrayButton();
        }
    }

    private void renderTrayButton() {
        if (btnOpenTray == null) return;
        switch (trayUi) {
            case SENDING:
                btnOpenTray.setEnabled(false);
                btnOpenTray.setText(traySendFrom == TrayUi.OPEN ? R.string.close_tray_sending
                                                                 : R.string.open_tray_sending);
                break;
            case OPENING:
                btnOpenTray.setEnabled(false);
                btnOpenTray.setText(R.string.open_tray_sending);
                break;
            case OPEN:
                btnOpenTray.setEnabled(true);
                btnOpenTray.setText(R.string.close_tray);
                break;
            case CLOSING:
                btnOpenTray.setEnabled(false);
                btnOpenTray.setText(R.string.close_tray_sending);
                break;
            case COOLDOWN:
                btnOpenTray.setEnabled(false);
                btnOpenTray.setText(R.string.open_tray);
                break;
            default:
                btnOpenTray.setEnabled(true);
                btnOpenTray.setText(R.string.open_tray);
        }
    }

    private void setTrayUi(TrayUi state) {
        trayUi = state;
        renderTrayButton();
    }

    private void cancelTrayTimers() {
        trayHandler.removeCallbacksAndMessages(null);
    }

    private void stopTrayWatch() {
        if (firebaseService != null) firebaseService.stopWatchingRemoteCommand();
    }

    private void finishTrayCycle() {
        cancelTrayTimers();
        stopTrayWatch();
        trayActionId = null;
        setTrayUi(TrayUi.COOLDOWN);
        trayHandler.postDelayed(() -> setTrayUi(TrayUi.IDLE), TRAY_COOLDOWN_MS);
    }

    private void abortTraySend() {
        cancelTrayTimers();
        stopTrayWatch();
        trayActionId = null;
        setTrayUi(traySendFrom == TrayUi.OPEN ? TrayUi.OPEN : TrayUi.IDLE);
    }

    private void adoptTrayTimings(JSONObject r) {
        if (r == null) return;
        trayOpenMs   = r.optLong("openMs",   trayOpenMs);
        trayHoldMs   = r.optLong("holdMs",   trayHoldMs);
        trayCloseMs  = r.optLong("closeMs",  trayCloseMs);
        traySettleMs = r.optLong("settleMs", traySettleMs);
    }

    private void onTrayOpenAccepted() {
        cancelTrayTimers();
        confirmHaptic();
        setTrayUi(TrayUi.OPENING);
        trayHandler.postDelayed(() -> { if (trayUi == TrayUi.OPENING) setTrayUi(TrayUi.OPEN); }, trayOpenMs);
        if (!TRAY_AUTO_CLOSE) return;   // stays OPEN until the user taps "Close Tray"
        trayHandler.postDelayed(() -> { if (trayUi == TrayUi.OPEN) setTrayUi(TrayUi.CLOSING); },
                trayOpenMs + trayHoldMs);
        scheduleTrayCycleEnd(trayOpenMs + trayHoldMs + trayCloseMs + traySettleMs);
    }

    /** Device accepted "close_tray": the return starts now. */
    private void onTrayCloseAccepted() {
        cancelTrayTimers();
        confirmHaptic();
        setTrayUi(TrayUi.CLOSING);
        scheduleTrayCycleEnd(trayCloseMs + traySettleMs);
    }

    private void scheduleTrayCycleEnd(long expectedMs) {
        if (trayViaCloud) {
            trayHandler.postDelayed(this::finishTrayCycle, expectedMs + TRAY_CLOUD_GRACE_MS);
        } else {
            trayHandler.postDelayed(() -> verifyTrayClosed(TRAY_VERIFY_TRIES), expectedMs);
        }
    }

    private void verifyTrayClosed(int triesLeft) {
        if (espApiClient == null || triesLeft <= 0) { finishTrayCycle(); return; }
        espApiClient.getStatus(new EspApiClient.Callback() {
            @Override public void onSuccess(JSONObject r) {
                if (trayUi != TrayUi.OPENING && trayUi != TrayUi.OPEN && trayUi != TrayUi.CLOSING) return;
                String st = r.optString("tray_state", "closed");
                if ("closed".equals(st)) finishTrayCycle();
                else trayHandler.postDelayed(() -> verifyTrayClosed(triesLeft - 1), 1000L);
            }
            @Override public void onError(String m) {
                if (trayUi != TrayUi.OPENING && trayUi != TrayUi.OPEN && trayUi != TrayUi.CLOSING) return;
                trayHandler.postDelayed(() -> verifyTrayClosed(triesLeft - 1), 1000L);
            }
        });
    }

    private void followRunningTrayCycle(boolean requestedOpen) {
        cancelTrayTimers();
        if (!TRAY_AUTO_CLOSE && requestedOpen) {
            stopTrayWatch();
            trayActionId = null;
            setTrayUi(TrayUi.OPEN);
            return;
        }
        setTrayUi(TrayUi.CLOSING);
        scheduleTrayCycleEnd((TRAY_AUTO_CLOSE ? trayOpenMs + trayHoldMs : 0L) + trayCloseMs + traySettleMs);
    }

    private void triggerTrayToggle() {
        if (btnOpenTray == null || (trayUi != TrayUi.IDLE && trayUi != TrayUi.OPEN)) return;
        final boolean opening = (trayUi == TrayUi.IDLE);
        traySendFrom = trayUi;
        final String actionId = "tray_" + System.currentTimeMillis();
        trayActionId = actionId;
        trayViaCloud = false;
        setTrayUi(TrayUi.SENDING);
        // Safety net: never leave the button stuck if no callback ever arrives.
        cancelTrayTimers();
        trayHandler.postDelayed(() -> { if (trayUi == TrayUi.SENDING) abortTraySend(); }, TRAY_SEND_TIMEOUT_MS);

        if (deviceDiscovery == null) {
            sendRemoteTrayCommand(opening, actionId);
            return;
        }
        deviceDiscovery.discover(new DeviceDiscovery.DiscoveryCallback() {
            @Override
            public void onFound(String baseUrl, String method) {
                if (!actionId.equals(trayActionId)) return;
                if (espApiClient != null) espApiClient.shutdown();
                espApiClient = new EspApiClient(baseUrl);
                espApiClient.sendTrayCommand(opening, actionId, new EspApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        if (!actionId.equals(trayActionId)) return;
                        adoptTrayTimings(response);
                        trayViaCloud = false;
                        if (response != null && response.optBoolean("duplicate", false)) {
                            // Already accepted earlier: do not restart the UI clock, wait for the device.
                            followRunningTrayCycle(opening);
                            return;
                        }
                        Toast.makeText(MainActivity.this,
                                opening ? R.string.open_tray_opened : R.string.close_tray_closing,
                                Toast.LENGTH_LONG).show();
                        if (opening) onTrayOpenAccepted(); else onTrayCloseAccepted();
                    }

                    @Override
                    public void onError(String errorMessage) {
                        if (!actionId.equals(trayActionId)) return;
                        String msg = errorMessage == null ? "" : errorMessage.toLowerCase(java.util.Locale.US);
                        if (msg.contains("already in progress") && msg.contains("tray")) {
                            Toast.makeText(MainActivity.this, R.string.open_tray_busy, Toast.LENGTH_LONG).show();
                            // A cycle is running that this UI did not start.
                            followRunningTrayCycle(opening);
                            return;
                        }
                        if (msg.contains("cooling down")) {
                            Toast.makeText(MainActivity.this, R.string.open_tray_cooldown, Toast.LENGTH_LONG).show();
                            cancelTrayTimers();
                            setTrayUi(TrayUi.COOLDOWN);
                            trayHandler.postDelayed(() -> { trayActionId = null; setTrayUi(TrayUi.IDLE); },
                                    TRAY_COOLDOWN_MS);
                            return;
                        }
                        if (msg.contains("food dispensing")) {
                            Toast.makeText(MainActivity.this, R.string.open_tray_feeding, Toast.LENGTH_LONG).show();
                            abortTraySend();
                            return;
                        }
                        if (msg.contains("already closed")) {
                            // The device is closed (cycle ended on its own): trust the device.
                            Toast.makeText(MainActivity.this, R.string.tray_already_closed, Toast.LENGTH_LONG).show();
                            finishTrayCycle();
                            return;
                        }
                        sendRemoteTrayCommand(opening, actionId);
                    }
                });
            }

            @Override
            public void onNotFound() {
                if (!actionId.equals(trayActionId)) return;
                sendRemoteTrayCommand(opening, actionId);
            }
        });
    }

    private void sendRemoteTrayCommand(boolean opening, String actionId) {
        if (firebaseService == null) {
            Toast.makeText(this, "Remote command unavailable", Toast.LENGTH_LONG).show();
            abortTraySend();
            return;
        }
        trayViaCloud = true;
        firebaseService.sendRemoteTrayCommand(opening, actionId, new FirebaseService.AckCallback() {
            @Override public void onAcknowledged() {
                if (!actionId.equals(trayActionId)) return;
                Toast.makeText(MainActivity.this, R.string.open_tray_sent_cloud,
                        Toast.LENGTH_LONG).show();
                firebaseService.watchRemoteCommand(actionId, (status, error) -> {
                    if (!actionId.equals(trayActionId)) return;
                    switch (status) {
                        case "accepted":
                            if (trayUi == TrayUi.SENDING) {
                                if (opening) onTrayOpenAccepted(); else onTrayCloseAccepted();
                            }
                            break;
                        case "completed":
                            finishTrayCycle();
                            break;
                        case "rejected":
                        case "expired": {
                            String e = error == null ? "" : error.toLowerCase(java.util.Locale.US);
                            if (e.contains("already closed")) {
                                finishTrayCycle();
                            } else if (e.contains("already in progress")) {
                                Toast.makeText(MainActivity.this, R.string.open_tray_busy, Toast.LENGTH_LONG).show();
                                if (trayUi == TrayUi.SENDING) followRunningTrayCycle(opening);
                            } else {
                                int res = e.contains("cooling down") ? R.string.open_tray_cooldown
                                        : e.contains("food dispensing") ? R.string.open_tray_feeding
                                        : R.string.open_tray_failed;
                                Toast.makeText(MainActivity.this, res, Toast.LENGTH_LONG).show();
                                abortTraySend();
                            }
                            break;
                        }
                        default:
                            break;
                    }
                });
            }
            @Override public void onFailed(String error) {
                if (!actionId.equals(trayActionId)) return;
                Toast.makeText(MainActivity.this, R.string.open_tray_failed,
                        Toast.LENGTH_LONG).show();
                abortTraySend();
            }
        });
    }

    private void restoreManualFeedButton() {
        if (btnManualFeed == null) return;
        btnManualFeed.setEnabled(true);
        btnManualFeed.setText(R.string.manual_feed);
    }

    private void setupCharts() {
        chartHelper = new ChartHelper(this);
        chartHelper.setupLineChart(lineChart);
        chartHelper.setupBarChart(barChart);
    }

    private void setupNotificationRecyclerView() {
        notificationAdapter = new NotificationAdapter();
        recyclerNotifications.setLayoutManager(new LinearLayoutManager(this));
        recyclerNotifications.setAdapter(notificationAdapter);
        recyclerNotifications.setNestedScrollingEnabled(false);
        notificationAdapter.setSelectionListener((selectionMode, count) -> {
            btnNotifSelect.setText(selectionMode ? "Cancel" : "Select");
            btnNotifSelectAll.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            btnNotifDelete.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            btnNotifDelete.setEnabled(count > 0);
            btnNotifDelete.setText(count > 0 ? "Delete (" + count + ")" : "Delete");
        });
        btnNotifSelect.setOnClickListener(v ->
                notificationAdapter.setSelectionMode(!notificationAdapter.isSelectionMode()));
        btnNotifSelectAll.setOnClickListener(v -> notificationAdapter.selectAll());
        btnNotifDelete.setOnClickListener(v -> confirmDeleteSelectedNotifications());
    }

    private SharedPreferences notificationPrefs() {
        return getSharedPreferences("notification_prefs", MODE_PRIVATE);
    }

    private Set<String> dismissedNotificationIds() {
        return new HashSet<>(notificationPrefs().getStringSet("dismissed", new HashSet<>()));
    }

    private void confirmDeleteSelectedNotifications() {
        final List<String> ids = notificationAdapter.getSelectedIds();
        if (ids.isEmpty()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(ids.size() == 1 ? "Delete notification?" : "Delete " + ids.size() + " notifications?")
                .setMessage("They will be removed from this list. Feeding history is not affected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    Set<String> dismissed = dismissedNotificationIds();
                    dismissed.addAll(ids);
                    notificationPrefs().edit().putStringSet("dismissed", dismissed).apply();
                    List<AppNotificationItem> remaining = new ArrayList<>();
                    for (AppNotificationItem item : allNotifications) {
                        if (!dismissed.contains(item.getId())) remaining.add(item);
                    }
                    allNotifications.clear();
                    allNotifications.addAll(remaining);
                    notificationAdapter.setSelectionMode(false);
                    applyNotificationFilter();
                })
                .show();
    }

    private static int scheduleMinutes(FeedingSchedule s) {
        return s.getTimeHour() * 60 + s.getTimeMinute();
    }

    private FeedingSchedule findActiveSchedule(List<FeedingSchedule> schedules) {
        if (schedules == null) return null;
        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        String today = DAY_LABELS[(now.get(Calendar.DAY_OF_WEEK) + 5) % 7];
        FeedingSchedule latestPast = null, nextUpcoming = null, anyEnabled = null;
        for (FeedingSchedule s : schedules) {
            if (!s.isEnabled()) continue;
            if (anyEnabled == null) anyEnabled = s;
            List<String> days = s.getRepeatDays();
            if (days != null && !days.isEmpty() && !days.contains(today)) continue;
            int m = scheduleMinutes(s);
            if (m <= nowMin) {
                if (latestPast == null || m > scheduleMinutes(latestPast)) latestPast = s;
            } else if (nextUpcoming == null || m < scheduleMinutes(nextUpcoming)) {
                nextUpcoming = s;
            }
        }
        if (latestPast != null) return latestPast;
        if (nextUpcoming != null) return nextUpcoming;
        return anyEnabled;
    }

    private void updateLiveWeightCard(DashboardData data) {
        if (tvLiveWeightValue == null) return;
        FeedingSchedule active = findActiveSchedule(data.getSchedules());
        float target = active != null ? active.getPortionGrams() : 0f;

        if (active == null) {
            setTextIfChanged(tvLiveWeightTarget, "Target: no active schedule");
        } else {
            String label = active.getLabel() != null && !active.getLabel().isEmpty()
                    ? active.getLabel() : "Schedule";
            setTextIfChanged(tvLiveWeightTarget, String.format(Locale.US, "Target: %.1f g  (%s %s)",
                    target, label, active.getTimeDisplay()));
        }

        int neutral = ContextCompat.getColor(this, R.color.colorTextSecondary);
        boolean missingOrStale = isWeightMissingOrStale(data);
        if (missingOrStale) pollLanWeightFallback();
        if (!data.isWeightKnown()) {
            setTextIfChanged(tvLiveWeightValue, "-- g");
            setTextIfChanged(tvLiveWeightDiff, "Difference: --");
            tvLiveWeightDiff.setTextColor(neutral);
            setTextIfChanged(tvLiveWeightMeta, "Waiting for feeder reading");
            return;
        }

        float weight = data.getCurrentWeight();
        setTextIfChanged(tvLiveWeightValue, String.format(Locale.US, "%.1f g", weight));

        if (target > 0f) {
            double diff = weight - target;
            double pct = diff / target * 100.0;
            double ratio = weight / (double) target;
            boolean off = ratio < 0.8 || ratio > 1.2;
            setTextIfChanged(tvLiveWeightDiff, String.format(Locale.US, "Difference: %+.1f g (%+.1f%%)", diff, pct));
            tvLiveWeightDiff.setTextColor(ContextCompat.getColor(this,
                    off ? R.color.colorStatusAnomaly : R.color.colorStatusCompleted));
        } else {
            setTextIfChanged(tvLiveWeightDiff, "Difference: --");
            tvLiveWeightDiff.setTextColor(neutral);
        }

        long at = data.getWeightUpdatedAt();
        if (at <= 0) {
            setTextIfChanged(tvLiveWeightMeta, "Live reading from feeder");
        } else {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(at));
            boolean stale = System.currentTimeMillis() - at > WEIGHT_STALE_MS;
            setTextIfChanged(tvLiveWeightMeta, stale ? "Last reading " + time + " (feeder may be offline)"
                                           : "Updated " + time);
        }
    }

    private void onLiveWeightUpdated(boolean available, float grams, long updatedAt) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (available) {
                liveWeightKnown = true;
                liveWeightGrams = grams;
                liveWeightSourceAt = updatedAt;
                liveWeightReceivedAt = System.currentTimeMillis();
            }
            renderLiveWeightCard();
        });
    }

    private void renderLiveWeightCard() {
        DashboardData data = latestDashboardData;
        if (data == null) return;
        long now = System.currentTimeMillis();
        if (liveWeightKnown && now - liveWeightReceivedAt <= LAST_GOOD_WEIGHT_HOLD_MS) {
            data.setWeightKnown(true);
            data.setCurrentWeight(liveWeightGrams);
            data.setWeightUpdatedAt(liveWeightReceivedAt);
        } else {
            data.setWeightKnown(false);
            data.setWeightUpdatedAt(0L);
        }
        updateLiveWeightCard(data);
    }

    private void updateLiveWeightTicker() {
        if (currentTab == Tab.NOTIFICATIONS && !isFinishing()) startLiveWeightTicker();
        else stopLiveWeightTicker();
    }

    private void startLiveWeightTicker() {
        if (liveWeightTickerRunning) return;
        liveWeightTickerRunning = true;
        liveWeightHandler.removeCallbacks(liveWeightTick);
        liveWeightHandler.post(liveWeightTick);
    }

    private void stopLiveWeightTicker() {
        liveWeightTickerRunning = false;
        liveWeightHandler.removeCallbacks(liveWeightTick);
    }

    private boolean isWeightMissingOrStale(DashboardData data) {
        if (data == null || !data.isWeightKnown()) return true;
        long at = data.getWeightUpdatedAt();
        return at <= 0 || System.currentTimeMillis() - at > WEIGHT_STALE_MS;
    }

    private void pollLanWeightFallback() {
        long now = System.currentTimeMillis();
        if (lanWeightPollInFlight || now - lastLanWeightPollAt < LAN_WEIGHT_POLL_MIN_MS) return;
        lastLanWeightPollAt = now;
        lanWeightPollInFlight = true;

        if (espApiClient != null) {
            fetchLanStatusWeight();
            return;
        }

        if (deviceDiscovery != null) {
            String lastIp = deviceDiscovery.getLastKnownIp();
            if (lastIp != null && !lastIp.isEmpty()) {
                espApiClient = new EspApiClient("http://" + lastIp);
                fetchLanStatusWeight();
                return;
            }
        }

        if (deviceDiscovery == null) {
            lanWeightPollInFlight = false;
            return;
        }

        deviceDiscovery.discover(new DeviceDiscovery.DiscoveryCallback() {
            @Override
            public void onFound(String baseUrl, String method) {
                if (espApiClient != null) espApiClient.shutdown();
                espApiClient = new EspApiClient(baseUrl);
                fetchLanStatusWeight();
            }

            @Override
            public void onNotFound() {
                lanWeightPollInFlight = false;
            }
        });
    }

    private boolean feederLinkInFlight = false;

    private void linkFeederIfNeeded(JSONObject status) {
        String uid = FirebaseService.currentUid();
        if (uid == null || status == null || espApiClient == null || feederLinkInFlight) return;
        boolean linked = status.optBoolean("account_linked", false)
                && status.optLong("account_hash", 0L) == EspApiClient.uidHash(uid);
        if (linked) return;
        feederLinkInFlight = true;
        espApiClient.linkAccount(uid, new EspApiClient.Callback() {
            @Override public void onSuccess(JSONObject response) {
                feederLinkInFlight = false;
                syncSchedulesToDevice(false);
            }
            @Override public void onError(String errorMessage) {
                feederLinkInFlight = false;
            }
        });
    }

    private void fetchLanStatusWeight() {
        espApiClient.getStatus(new EspApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                lanWeightPollInFlight = false;
                setDeviceReachable(true);
                linkFeederIfNeeded(response);
                if (response == null
                        || !response.optBoolean("currentWeightAvailable",
                                response.optBoolean("scaleAvailable", false))
                        || !response.has("currentWeight")) {
                    DashboardData data = latestDashboardData;
                    if (data != null && isWeightMissingOrStale(data)) {
                        data.setWeightKnown(false);
                        data.setWeightUpdatedAt(0L);
                        updateLiveWeightCard(data);
                    }
                    return;
                }
                DashboardData data = latestDashboardData;
                if (data == null) return;
                liveWeightKnown = true;
                liveWeightGrams = (float) response.optDouble("currentWeight", 0.0);
                liveWeightSourceAt = response.optLong("weightUpdatedAtMs", 0L);
                liveWeightReceivedAt = System.currentTimeMillis();
                data.setWeightKnown(true);
                data.setCurrentWeight(liveWeightGrams);
                data.setWeightUpdatedAt(liveWeightReceivedAt);
                String ip = response.optString("ip", "");
                if (deviceDiscovery != null && ip.length() > 0 && !"192.168.4.1".equals(ip)) {
                    deviceDiscovery.saveLastKnownIp(ip);
                }
                updateLiveWeightCard(data);
            }

            @Override
            public void onError(String errorMessage) {
                lanWeightPollInFlight = false;
                setDeviceReachable(false);
                if (espApiClient != null) {
                    espApiClient.shutdown();
                    espApiClient = null;
                }
            }
        });
    }

    private void setupFilterToggle() {
        toggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if      (checkedId == R.id.btnDay)   currentFilter = "DAY";
            else if (checkedId == R.id.btnWeek)  currentFilter = "WEEK";
            else if (checkedId == R.id.btnMonth) currentFilter = "MONTH";
            DashboardData cached = latestDashboardData;
            if (cached != null) updateFeedingHistory(cached.getFeedingHistory());
        });
    }

    private void setupNotificationFilters() {
        notificationFilterGroup.check(R.id.btnFilterAll);
        notificationFilterGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btnFilterAnomaly) currentNotificationFilter = NotificationFilter.ANOMALY;
            else if (checkedId == R.id.btnFilterFeeding) currentNotificationFilter = NotificationFilter.FEEDING;
            else if (checkedId == R.id.btnFilterSystem) currentNotificationFilter = NotificationFilter.SYSTEM;
            else currentNotificationFilter = NotificationFilter.ALL;
            if (notificationAdapter.isSelectionMode()) notificationAdapter.setSelectionMode(false);
            applyNotificationFilter();
        });
    }

    private void setupBottomNavigation() {
        tabHome.setOnClickListener(v -> switchTab(Tab.HOME));
        tabNotifications.setOnClickListener(v -> switchTab(Tab.NOTIFICATIONS));
        tabSchedule.setOnClickListener(v -> switchTab(Tab.SCHEDULE));
        tabSettings.setOnClickListener(v -> switchTab(Tab.SETTINGS));
        switchTab(Tab.HOME);
    }

    private void setupSettingsTab() {
        android.content.SharedPreferences prefs =
                getSharedPreferences(com.smartpetfeeder.auth.AuthGateActivity.PREFS_NAME, MODE_PRIVATE);
        boolean isDark = prefs.getBoolean(com.smartpetfeeder.auth.AuthGateActivity.KEY_DARK_MODE, false);
        switchThemeMode.setOnCheckedChangeListener(null);
        switchThemeMode.setChecked(isDark);
        switchThemeMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(
                    com.smartpetfeeder.auth.AuthGateActivity.KEY_DARK_MODE, isChecked).apply();
            AppCompatDelegate.setDefaultNightMode(
                    isChecked ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });
        btnOpenDeviceSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        btnLogout.setOnClickListener(v -> {
            if (firebaseService != null) {
                firebaseService.stopListening();
                firebaseService.setPetProfileListener(null);
            }
            currentPetProfile = null;
            FirebaseAuth.getInstance().signOut();
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });

        btnShowMoreAnomalies.setOnClickListener(v ->
                openNotificationsWithFilter(NotificationFilter.ANOMALY));
        btnShowMoreHistory.setOnClickListener(v ->
                openNotificationsWithFilter(NotificationFilter.FEEDING));
    }

    private void setupScheduleTab() {
        etScheduleTime.setOnClickListener(v -> showTimePicker());

        setupRepeatButtons();

        btnAddSchedule.setOnClickListener(v -> {
            if (validateScheduleInput()) {
                addNewScheduleToList();
                clearScheduleInputs();
            }
        });

        btnScheduleSave.setOnClickListener(v -> saveAllSchedules());

        if (btnUseRecommendedPortion != null) {
            btnUseRecommendedPortion.setOnClickListener(v -> {
                if (lastRecommendation == null) return;
                autoFilledSchedulePortion = String.valueOf(Math.round(lastRecommendation.portionGrams));
                etSchedulePortion.setText(autoFilledSchedulePortion);
            });
        }
        if (btnAddSuggestedSchedules != null) {
            btnAddSuggestedSchedules.setOnClickListener(v -> addSuggestedSchedules());
        }

        if (recyclerSchedules != null) {
            scheduleAdapter = new ScheduleAdapter();
            recyclerSchedules.setLayoutManager(new LinearLayoutManager(this));
            recyclerSchedules.setAdapter(scheduleAdapter);
            recyclerSchedules.setNestedScrollingEnabled(false);

            scheduleAdapter.setOnDeleteListener((schedule, position) ->
                    confirmDeleteSchedule(schedule, position));

            scheduleAdapter.setOnToggleListener((schedule, enabled, position) -> {
                schedule.setEnabled(enabled);
                if (firebaseService != null) firebaseService.updateSchedule(schedule);
                syncSchedulesToDevice(false);
                updateScheduleRecommendationUi();
            });

            scheduleAdapter.setOnEditListener((schedule, position) ->
                    showEditScheduleDialog(schedule, position));
        }
    }

    private void setupRepeatButtons() {
        float density = getResources().getDisplayMetrics().density;
        int btnHeight  = (int) (52 * density);
        int btnWidth   = (int) (52 * density);
        int margin     = (int) (5 * density);

        for (int i = 0; i < DAY_LABELS.length; i++) {
            MaterialButton btn = new MaterialButton(
                    new android.view.ContextThemeWrapper(this, R.style.Widget_SmartPetFeeder_DayButton),
                    null, 0);
            btn.setText(DAY_LABELS[i]);
            btn.setTag(i);

            applyDayButtonState(btn, false);

            int finalI = i;
            btn.setOnClickListener(v -> {
                selectedRepeatDays[finalI] = !selectedRepeatDays[finalI];
                applyDayButtonState(btn, selectedRepeatDays[finalI]);
            });

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(btnWidth, btnHeight);
            params.setMargins(margin, 0, margin, 0);
            scheduleRepeatContainer.addView(btn, params);
        }
    }

    private void applyDayButtonState(MaterialButton btn, boolean selected) {
        int bgColor, textColor, strokeColor;
        if (selected) {
            bgColor     = ContextCompat.getColor(this, R.color.colorDayBtnSelectedBg);
            textColor   = ContextCompat.getColor(this, R.color.colorDayBtnSelectedText);
            strokeColor = bgColor;
        } else {
            bgColor     = ContextCompat.getColor(this, R.color.colorDayBtnUnselectedBg);
            textColor   = ContextCompat.getColor(this, R.color.colorDayBtnUnselectedText);
            strokeColor = ContextCompat.getColor(this, R.color.colorDayBtnStroke);
        }
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bgColor));
        btn.setTextColor(textColor);
        btn.setStrokeColor(android.content.res.ColorStateList.valueOf(strokeColor));
        btn.setRippleColor(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.colorDayBtnRipple)));
    }

    private void showTimePicker() {
        int hour = 8;
        int minute = 0;
        int[] cur = FeedingSchedule.parseTime(etScheduleTime.getText().toString());
        if (cur != null) { hour = cur[0]; minute = cur[1]; }

        android.app.TimePickerDialog.OnTimeSetListener listener = (view, selectedHour, selectedMinute) -> {
            etScheduleTime.setText(FeedingSchedule.formatTime12(selectedHour, selectedMinute));
        };

        new android.app.TimePickerDialog(this, listener, hour, minute, false).show();
    }

    private boolean validateScheduleInput() {
        String time = etScheduleTime.getText().toString().trim();
        String portion = etSchedulePortion.getText().toString().trim();

        if (time.isEmpty()) {
            Toast.makeText(this, "Please select a time", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (FeedingSchedule.parseTime(time) == null) {
            Toast.makeText(this, "Invalid time. Use hh:mm AM/PM", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (portion.isEmpty()) {
            Toast.makeText(this, "Please enter a portion size", Toast.LENGTH_SHORT).show();
            return false;
        }

        try {
            Float.parseFloat(portion);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid portion size", Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    private void addNewScheduleToList() {
        String time = etScheduleTime.getText().toString().trim();
        float portion = Float.parseFloat(etSchedulePortion.getText().toString().trim());

        int[] parsedTime = FeedingSchedule.parseTime(time);
        int hour   = parsedTime[0];
        int minute = parsedTime[1];

        List<String> repeatDays = new ArrayList<>();
        for (int i = 0; i < DAY_LABELS.length; i++) {
            if (selectedRepeatDays[i]) {
                repeatDays.add(DAY_LABELS[i]);
            }
        }

        String label = repeatDays.isEmpty()
                ? "Schedule " + (schedulesList.size() + 1)
                : String.format(Locale.US, "%s (%s)", FeedingSchedule.formatTime12(hour, minute),
                android.text.TextUtils.join(",", repeatDays));

        FeedingSchedule schedule = new FeedingSchedule(
                String.valueOf(System.currentTimeMillis()),
                hour, minute, portion, true, label, repeatDays);

        schedulesList.add(schedule);
        unsavedScheduleIds.add(schedule.getScheduleId());
        invalidateScheduleRender();
        if (scheduleAdapter != null) scheduleAdapter.setSchedules(new ArrayList<>(schedulesList));
        Toast.makeText(this, "Schedule added", Toast.LENGTH_SHORT).show();
        updateScheduleRecommendationUi();
    }

    private void clearScheduleInputs() {
        etScheduleTime.setText("");
        etSchedulePortion.setText("");
        autoFilledSchedulePortion = null;
        applyRecommendedPortionDefault();
        for (int i = 0; i < selectedRepeatDays.length; i++) {
            selectedRepeatDays[i] = false;
        }
        for (int i = 0; i < scheduleRepeatContainer.getChildCount(); i++) {
            View child = scheduleRepeatContainer.getChildAt(i);
            if (child instanceof MaterialButton) {
                applyDayButtonState((MaterialButton) child, false);
            }
        }
    }

    private void confirmDeleteSchedule(FeedingSchedule schedule, int position) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Delete Schedule")
                .setMessage("Remove the " + schedule.getTimeDisplay() + " feeding schedule?")
                .setPositiveButton("Delete", (d, w) -> {
                    schedulesList.remove(position);
                    unsavedScheduleIds.remove(schedule.getScheduleId());
                    invalidateScheduleRender();
                    if (scheduleAdapter != null) {
                        scheduleAdapter.setSchedules(new ArrayList<>(schedulesList));
                    }
                    if (firebaseService != null) {
                        firebaseService.deleteSchedule(schedule.getScheduleId());
                    }
                    syncSchedulesToDevice(true);
                    updateScheduleRecommendationUi();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditScheduleDialog(FeedingSchedule schedule, int position) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        EditText etTime = new EditText(this);
        etTime.setHint("Time (hh:mm AM/PM)");
        etTime.setText(FeedingSchedule.formatTime12(
                schedule.getTimeHour(), schedule.getTimeMinute()));
        etTime.setFocusable(false);
        etTime.setClickable(true);
        etTime.setCursorVisible(false);

        EditText etPortion = new EditText(this);
        etPortion.setHint(lastRecommendation != null
                ? getString(R.string.schedule_edit_portion_hint,
                        Math.round(lastRecommendation.portionGrams))
                : "Portion (grams)");
        etPortion.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etPortion.setText(String.format(Locale.US, "%.0f", schedule.getPortionGrams()));

        android.widget.LinearLayout dayRow = new android.widget.LinearLayout(this);
        dayRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams dayRowParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        dayRowParams.topMargin = pad / 2;
        dayRow.setLayoutParams(dayRowParams);

        float density = getResources().getDisplayMetrics().density;
        int btnHeight = (int) (44 * density);
        int btnMargin = (int) (2  * density);

        boolean[] editDays = new boolean[DAY_LABELS.length];
        List<String> existingDays = schedule.getRepeatDays();
        for (int i = 0; i < DAY_LABELS.length; i++) {
            editDays[i] = existingDays != null && existingDays.contains(DAY_LABELS[i]);
        }

        MaterialButton[] dayBtns = new MaterialButton[DAY_LABELS.length];
        for (int i = 0; i < DAY_LABELS.length; i++) {
            MaterialButton btn = new MaterialButton(
                    new android.view.ContextThemeWrapper(this, R.style.Widget_SmartPetFeeder_DayButton),
                    null, 0);
            btn.setText(DAY_LABELS[i]);
            btn.setPadding(0, 0, 0, 0);
            applyDayButtonState(btn, editDays[i]);
            int finalI = i;
            btn.setOnClickListener(v -> {
                editDays[finalI] = !editDays[finalI];
                applyDayButtonState(btn, editDays[finalI]);
            });
            android.widget.LinearLayout.LayoutParams p =
                    new android.widget.LinearLayout.LayoutParams(0, btnHeight, 1f);
            p.setMargins(btnMargin, 0, btnMargin, 0);
            dayRow.addView(btn, p);
            dayBtns[i] = btn;
        }

        etTime.setOnClickListener(v -> {
            int h = schedule.getTimeHour(), m = schedule.getTimeMinute();
            int[] parsed = FeedingSchedule.parseTime(etTime.getText().toString());
            if (parsed != null) { h = parsed[0]; m = parsed[1]; }
            int finalH = h, finalM = m;
            new android.app.TimePickerDialog(this,
                    (tp, selH, selM) -> etTime.setText(
                            FeedingSchedule.formatTime12(selH, selM)),
                    finalH, finalM, false).show();
        });

        android.widget.LinearLayout.LayoutParams fieldParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        fieldParams.topMargin = btnMargin;
        layout.addView(etTime,    fieldParams);
        layout.addView(etPortion, fieldParams);
        layout.addView(dayRow,    dayRowParams);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Edit Schedule")
                .setView(layout)
                .setPositiveButton("Save", (d, w) -> {
                    String timeStr   = etTime.getText().toString().trim();
                    String portionStr = etPortion.getText().toString().trim();

                    if (timeStr.isEmpty() || portionStr.isEmpty()) {
                        Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    float portion;
                    try {
                        portion = Float.parseFloat(portionStr);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid portion size", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int[] parsedTime = FeedingSchedule.parseTime(timeStr);
                    if (parsedTime == null) {
                        Toast.makeText(this, "Invalid time. Use hh:mm AM/PM", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    schedule.setTimeHour(parsedTime[0]);
                    schedule.setTimeMinute(parsedTime[1]);
                    schedule.setPortionGrams(portion);

                    List<String> newDays = new ArrayList<>();
                    for (int i = 0; i < DAY_LABELS.length; i++) {
                        if (editDays[i]) newDays.add(DAY_LABELS[i]);
                    }
                    schedule.setRepeatDays(newDays);

                    String updatedLabel = newDays.isEmpty()
                            ? FeedingSchedule.formatTime12(schedule.getTimeHour(), schedule.getTimeMinute())
                            : String.format(Locale.US, "%s (%s)",
                            FeedingSchedule.formatTime12(schedule.getTimeHour(), schedule.getTimeMinute()),
                            android.text.TextUtils.join(",", newDays));
                    schedule.setLabel(updatedLabel);

                    schedulesList.set(position, schedule);
                    invalidateScheduleRender();

                    if (scheduleAdapter != null) {
                        scheduleAdapter.notifyItemChanged(position);
                    }
                    updateScheduleRecommendationUi();
                    if (firebaseService != null) {
                        firebaseService.updateSchedule(schedule, saveAckToast(R.string.sync_saved));
                    }
                    syncSchedulesToDevice(false);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveAllSchedules() {
        if (schedulesList.isEmpty()) {
            Toast.makeText(this, "No schedules to save", Toast.LENGTH_SHORT).show();
            return;
        }

        if (firebaseService != null) {
            for (FeedingSchedule schedule : schedulesList) {
                if (schedule.getScheduleId() == null || schedule.getScheduleId().isEmpty()) {
                    schedule.setScheduleId(String.valueOf(System.currentTimeMillis()));
                }
            }
            firebaseService.saveSchedules(new ArrayList<>(schedulesList),
                    saveAckToast(R.string.sync_saved));
            syncSchedulesToDevice(true);
        }
    }

    private FirebaseService.AckCallback saveAckToast(int ackMsg) {
        if (firebaseService == null || !firebaseService.isPhoneOnline()) {
            Toast.makeText(this, R.string.sync_saved_offline, Toast.LENGTH_SHORT).show();
            return null;
        }
        Toast.makeText(this, R.string.sync_saving, Toast.LENGTH_SHORT).show();
        return new FirebaseService.AckCallback() {
            @Override public void onAcknowledged() {
                Toast.makeText(MainActivity.this, ackMsg, Toast.LENGTH_SHORT).show();
            }
            @Override public void onFailed(String error) {
                if (!"discarded".equals(error)) {
                    Toast.makeText(MainActivity.this, R.string.sync_save_failed,
                            Toast.LENGTH_SHORT).show();
                }
            }
        };
    }

    private void syncSchedulesToDevice(boolean showResultToast) {
        if (deviceDiscovery == null || schedulesList == null) return;

        List<FeedingSchedule> schedulesSnapshot = new ArrayList<>(schedulesList);
        final long versionAtCall = firebaseService != null ? firebaseService.getLatestScheduleVersion() : 0L;
        deviceDiscovery.discover(new DeviceDiscovery.DiscoveryCallback() {
            @Override
            public void onFound(String baseUrl, String method) {
                if (espApiClient != null) espApiClient.shutdown();
                espApiClient = new EspApiClient(baseUrl);
                espApiClient.syncSchedules(schedulesSnapshot, new EspApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        lastSyncedScheduleSignature = buildScheduleSignature(schedulesSnapshot);
                        if (firebaseService != null) firebaseService.markScheduleAppliedLocally(versionAtCall);
                        if (showResultToast) {
                            int saved = response.optInt("saved", schedulesSnapshot.size());
                            Toast.makeText(MainActivity.this,
                                    "Synced " + saved + " schedule(s) to feeder. Auto dispense is ready.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onError(String errorMessage) {
                        if (showResultToast) {
                            Toast.makeText(MainActivity.this,
                                    "Saved in app, but feeder sync failed: " + errorMessage,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }

            @Override
            public void onNotFound() {
                if (showResultToast) {
                    Toast.makeText(MainActivity.this,
                            "Saved in app, but feeder was not reachable for sync.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void syncSchedulesToDeviceIfChanged() {
        String signature = buildScheduleSignature(schedulesList);
        if (signature.equals(lastSyncedScheduleSignature)) return;
        syncSchedulesToDevice(false);
    }

    private String buildScheduleSignature(List<FeedingSchedule> schedules) {
        if (schedules == null || schedules.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (FeedingSchedule schedule : schedules) {
            if (schedule == null) continue;
            sb.append(schedule.getScheduleId()).append('|')
                    .append(schedule.getTimeHour()).append(':')
                    .append(schedule.getTimeMinute()).append('|')
                    .append(schedule.isEnabled()).append('|')
                    .append(schedule.getRepeatDisplay()).append(';');
        }
        return sb.toString();
    }

    private void setupRetryButton() {
        btnRetry.setOnClickListener(v -> {
            showLoadingState();
            isFirstLoad = true;
            startFirebaseTimeout();
            firebaseService.startListening();
        });
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                fetchFcmToken();
            }
        } else {
            fetchFcmToken();
        }
    }

    private void fetchFcmToken() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                firebaseService.updateFcmToken(task.getResult());
            }
        });
    }

    private void showLoadingState() {
        homeState = HomeState.LOADING;
        applyHomeState();
    }

    private void showErrorState(String message) {
        homeState = HomeState.ERROR;
        reconnectRetryDone = false;
        tvErrorMessage.setText(message);
        applyHomeState();
    }

    private void showContentState() {
        homeState = HomeState.CONTENT;
        applyHomeState();
    }

    private void applyHomeState() {
        boolean home = currentTab == Tab.HOME;
        setVisibleIfChanged(loadingState, home && homeState == HomeState.LOADING);
        setVisibleIfChanged(errorState, home && homeState == HomeState.ERROR);
        setVisibleIfChanged(contentContainer, home && homeState == HomeState.CONTENT);
    }

    private static void setVisibleIfChanged(View v, boolean visible) {
        if (v == null) return;
        int target = visible ? View.VISIBLE : View.GONE;
        if (v.getVisibility() != target) v.setVisibility(target);
    }

    private static void setTextIfChanged(TextView tv, CharSequence text) {
        if (tv == null || text == null) return;
        CharSequence cur = tv.getText();
        if (cur != null && cur.toString().contentEquals(text)) return;
        tv.setText(text);
    }

    private void switchTab(Tab tab) {
        currentTab = tab;
        updateTabUi();
        updateLiveWeightTicker();
        if (tab == Tab.HOME) {
            notificationsContainer.setVisibility(View.GONE);
            scheduleContainer.setVisibility(View.GONE);
            settingsContainer.setVisibility(View.GONE);
            applyHomeState();
        } else if (tab == Tab.NOTIFICATIONS) {
            applyHomeState();
            scheduleContainer.setVisibility(View.GONE);
            settingsContainer.setVisibility(View.GONE);
            notificationsContainer.setAlpha(0f);
            notificationsContainer.setVisibility(View.VISIBLE);
            notificationsContainer.animate().alpha(1f).setDuration(180).start();
        } else if (tab == Tab.SCHEDULE) {
            applyHomeState();
            notificationsContainer.setVisibility(View.GONE);
            settingsContainer.setVisibility(View.GONE);
            scheduleContainer.setAlpha(0f);
            scheduleContainer.setVisibility(View.VISIBLE);
            scheduleContainer.animate().alpha(1f).setDuration(180).start();
        } else {
            applyHomeState();
            notificationsContainer.setVisibility(View.GONE);
            scheduleContainer.setVisibility(View.GONE);
            settingsContainer.setAlpha(0f);
            settingsContainer.setVisibility(View.VISIBLE);
            settingsContainer.animate().alpha(1f).setDuration(180).start();
        }
    }

    private void updateTabUi() {
        styleTabButton(tabHome, currentTab == Tab.HOME);
        styleTabButton(tabNotifications, currentTab == Tab.NOTIFICATIONS);
        styleTabButton(tabSchedule, currentTab == Tab.SCHEDULE);
        styleTabButton(tabSettings, currentTab == Tab.SETTINGS);
    }

    private void styleTabButton(MaterialButton button, boolean active) {
        button.setBackgroundResource(active
                ? R.drawable.bg_bottom_nav_active
                : android.R.color.transparent);
        button.setIconTintResource(active ? R.color.colorNavActive : R.color.colorNavInactive);
        button.setTextColor(ContextCompat.getColor(this,
                active ? R.color.colorNavActive : R.color.colorNavInactive));
    }

    @Override
    public void onDataUpdated(DashboardData data) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || data == null) return;
            latestDashboardData = data;
            if (isFirstLoad) {
                timeoutHandler.removeCallbacksAndMessages(null);
                showContentState();
                isFirstLoad = false;
            } else if (homeState != HomeState.CONTENT) {
                showContentState();
            }
            setVisibleIfChanged(noDataNoticeCard, false);
            applyPhoneOnlineToChip();

            applySchedules(data.getSchedules());
            updateDashboard(data);
            updateNotificationsFromDashboard(data);
            renderSyncUi();
            notifyFinalizedFeedings();
        });
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (isFirstLoad) {
                showErrorState(error);
            } else {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String scheduleRenderSignature(List<FeedingSchedule> list) {
        StringBuilder sb = new StringBuilder();
        for (FeedingSchedule f : list) {
            if (f == null) continue;
            sb.append(f.getScheduleId()).append('|')
              .append(f.getTimeHour()).append(':').append(f.getTimeMinute()).append('|')
              .append(f.getPortionGrams()).append('|')
              .append(f.isEnabled()).append('|')
              .append(f.getLabel()).append('|')
              .append(f.getRepeatDays()).append(';');
        }
        return sb.toString();
    }

    private void invalidateScheduleRender() {
        renderedScheduleSig = null;
    }

    private void applySchedules(List<FeedingSchedule> incoming) {
        if (incoming == null) return;
        List<FeedingSchedule> merged = new ArrayList<>(incoming);
        Set<String> present = new HashSet<>();
        for (FeedingSchedule fs : incoming) {
            if (fs != null && fs.getScheduleId() != null) present.add(fs.getScheduleId());
        }
        unsavedScheduleIds.removeAll(present);
        for (FeedingSchedule local : schedulesList) {
            String id = local == null ? null : local.getScheduleId();
            if (id != null && unsavedScheduleIds.contains(id) && !present.contains(id)) merged.add(local);
        }
        String sig = scheduleRenderSignature(merged);
        if (sig.equals(renderedScheduleSig)) return;
        renderedScheduleSig = sig;

        schedulesList.clear();
        schedulesList.addAll(merged);
        if (scheduleAdapter != null) scheduleAdapter.setSchedules(new ArrayList<>(schedulesList));
        if (homeScheduleAdapter != null) {
            homeScheduleAdapter.setSchedules(new ArrayList<>(schedulesList));
            updateHomeScheduleUI(new ArrayList<>(schedulesList));
        }
        int enabledCount = 0;
        for (FeedingSchedule fs : incoming) {
            if (fs != null && fs.isEnabled()) enabledCount++;
        }
        enabledFeedingsPerDay = enabledCount;
        syncSchedulesToDeviceIfChanged();
        updateAdaptiveFeedUi();
    }

    private void updateDashboard(DashboardData data) {
        updateStatusBanner(data);
        updateSummaryCards(data.getSummaryStats());
        updateCharts(data);
        updateFeedingHistory(data.getFeedingHistory());
        updateAnomalyAlerts(data.getAnomalyAlerts());
        updateLastUpdatedTime(data.getLastUpdated());
    }

    private void updateNotificationsFromDashboard(DashboardData data) {
        updateLiveWeightCard(data);
        refreshSystemNotification();
    }

    private String systemNoticeMessage() {
        switch (currentSyncState) {
            case OFFLINE:
                return "Phone is offline. Changes are saved and will sync when reconnected.";
            case FAILED:
                return "Sync failed. Use Sync now to retry.";
            case PENDING_CLOUD:
            case PENDING_DEVICE:
                return "Syncing changes with feeder and Firebase.";
            case SYNCED:
            default:
                return feederKnown && !feederOnline
                        ? "Realtime sync active. Feeder is offline."
                        : "Realtime sync active with feeder and Firebase.";
        }
    }

    private void refreshSystemNotification() {
        if (notificationAdapter == null) return;
        final Set<String> dismissed = dismissedNotificationIds();
        allNotifications.clear();
        if (!dismissed.contains("system_status")) {
            String msg = systemNoticeMessage();
            if (!msg.equals(lastSystemNoticeMsg)) {
                lastSystemNoticeMsg = msg;
                lastSystemNoticeTime = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            }
            allNotifications.add(new AppNotificationItem(
                    "system_status",
                    AppNotificationItem.Type.SYSTEM,
                    "Device sync update",
                    msg,
                    lastSystemNoticeTime
            ));
        }
        applyNotificationFilter();
    }

    private void onFeederStatusChanged(boolean online, long lastSeen) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            feederKnown = lastSeen > 0L || online;
            feederOnline = online;
            applyPhoneOnlineToChip();
            refreshSystemNotification();
        });
    }

    private void onLiveFeedingUpdated(LiveFeedingStats stats) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            liveFeeding = stats;
            renderLiveFeedingCard();
        });
    }

    private void renderLiveFeedingCard() {
        if (cardLiveFeeding == null) return;
        LiveFeedingStats s = liveFeeding;
        if (s == null || !s.isActive()) {
            setVisibleIfChanged(cardLiveFeeding, false);
            return;
        }
        setVisibleIfChanged(cardLiveFeeding, true);
        setTextIfChanged(tvLiveFeedTarget, String.format(Locale.US, "%.1f g", s.getTarget()));
        setTextIfChanged(tvLiveFeedConsumed, String.format(Locale.US, "%.1f g", s.getConsumed()));
        setTextIfChanged(tvLiveFeedRemaining, String.format(Locale.US, "%.1f g", s.getRemaining()));
        if (progressLiveFeed != null) {
            float ratio = s.getTarget() > 0f ? s.getConsumed() / s.getTarget() : 0f;
            progressLiveFeed.setProgressCompat(Math.round(Math.max(0f, Math.min(1f, ratio)) * 100f), true);
        }
        setTextIfChanged(tvLiveFeedMeta, s.isBowlRemoved()
                ? "Bowl removed - readings paused"
                : "Live from the bowl scale");
    }

    private void notifyFinalizedFeedings() {
        DashboardData data = latestDashboardData;
        if (data == null || data.getFeedingHistory() == null) return;
        long now = System.currentTimeMillis();
        Set<String> notified = new HashSet<>(
                notificationPrefs().getStringSet("feeding_notified", new HashSet<>()));
        boolean changed = false;
        for (FeedingRecord r : data.getFeedingHistory()) {
            if (r == null || r.getFeedingId() == null || !r.isConsumptionRecorded()) continue;
            if (notified.contains(r.getFeedingId())) continue;
            long doneAt = r.getConsumptionTime() > 0L ? r.getConsumptionTime() : r.getActualTime();
            if (doneAt <= 0L || now - doneAt > FEED_FINAL_NOTIFY_MAX_AGE_MS) continue;
            PetFeederMessagingService.showFeedingFinal(this, r.getFeedingId(), "Feeding complete",
                    String.format(Locale.US, "Target %.1fg  |  Consumed %.1fg  |  Remaining %.1fg",
                            r.getScheduledPortion(), r.getDisplayConsumedWeight(), r.getRemainingWeight()));
            notified.add(r.getFeedingId());
            changed = true;
        }
        if (changed) notificationPrefs().edit().putStringSet("feeding_notified", notified).apply();
    }

    private void applyNotificationFilter() {
        List<AppNotificationItem> filtered = new ArrayList<>();
        for (AppNotificationItem item : allNotifications) {
            if (currentNotificationFilter == NotificationFilter.ALL) {
                filtered.add(item);
            } else if (currentNotificationFilter == NotificationFilter.ANOMALY
                    && item.getType() == AppNotificationItem.Type.ANOMALY) {
                filtered.add(item);
            } else if (currentNotificationFilter == NotificationFilter.FEEDING
                    && item.getType() == AppNotificationItem.Type.FEEDING) {
                filtered.add(item);
            } else if (currentNotificationFilter == NotificationFilter.SYSTEM
                    && item.getType() == AppNotificationItem.Type.SYSTEM) {
                filtered.add(item);
            }
        }
        notificationAdapter.setItems(filtered);
        applyAlertSectionVisibility();
    }

    private void applyAlertSectionVisibility() {
        boolean all = currentNotificationFilter == NotificationFilter.ALL;
        if (sectionAnomalyAlerts != null) {
            sectionAnomalyAlerts.setVisibility(
                    all || currentNotificationFilter == NotificationFilter.ANOMALY ? View.VISIBLE : View.GONE);
        }
        if (sectionFeedingHistory != null) {
            sectionFeedingHistory.setVisibility(
                    all || currentNotificationFilter == NotificationFilter.FEEDING ? View.VISIBLE : View.GONE);
        }
        if (recyclerNotifications != null) {
            recyclerNotifications.setVisibility(
                    all || currentNotificationFilter == NotificationFilter.SYSTEM ? View.VISIBLE : View.GONE);
        }
    }

    private void openNotificationsWithFilter(NotificationFilter filter) {
        switchTab(Tab.NOTIFICATIONS);
        if (filter == NotificationFilter.ANOMALY) {
            notificationFilterGroup.check(R.id.btnFilterAnomaly);
        } else if (filter == NotificationFilter.FEEDING) {
            notificationFilterGroup.check(R.id.btnFilterFeeding);
        } else if (filter == NotificationFilter.SYSTEM) {
            notificationFilterGroup.check(R.id.btnFilterSystem);
        } else {
            notificationFilterGroup.check(R.id.btnFilterAll);
        }
    }

    private void updatePetIcon(PetProfile profile) {
        if (ivPetIcon == null) return;
        int res = 0;
        if (profile != null) {
            res = getResources().getIdentifier(profile.isCat() ? "cat_icon" : "dog_icon",
                    "drawable", getPackageName());
        }
        if (res != 0) {
            ivPetIcon.setImageTintList(null);
            ivPetIcon.setImageResource(res);
        } else {
            ivPetIcon.setImageResource(R.drawable.ic_paw);
            ivPetIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.colorAccent)));
        }
    }

    private void updateAdaptiveFeedUi() {
        if (firebaseService == null) return;
        PetProfile profile = currentPetProfile;
        PetFeedingCalculator.Result result =
                PetFeedingCalculator.calculate(profile, enabledFeedingsPerDay);
        lastRecommendation = result;

        if (result == null) {
            if (tvPetName != null) tvPetName.setText(R.string.pet_profile_empty_title);
            if (tvPetInfo != null) tvPetInfo.setText(R.string.pet_profile_empty_hint);
            if (layoutRecommendedFood != null) layoutRecommendedFood.setVisibility(View.GONE);
            if (btnEditPetProfile != null) btnEditPetProfile.setText(R.string.pet_profile_setup);
            updatePetIcon(null);
            applyRecommendedPortionDefault();
            updateScheduleRecommendationUi();
            return;
        }

        String typeLabel = getString(profile.isCat() ? R.string.pet_type_cat : R.string.pet_type_dog);
        setTextIfChanged(tvPetName, profile.getName());
        updatePetIcon(profile);
        if (tvPetInfo != null) {
            setTextIfChanged(tvPetInfo, getString(R.string.pet_profile_type_age_format,
                    typeLabel, profile.getAgeLabel())
                    + "\n"
                    + getString(R.string.pet_profile_weight_activity_format,
                    profile.getWeightLabel(), activityLabel(profile.getActivity())));
        }
        if (tvRecommendedPortion != null) {
            setTextIfChanged(tvRecommendedPortion, getString(R.string.recommended_food_summary,
                    getString(R.string.recommended_food_per_day, Math.round(result.dailyGrams)),
                    getString(R.string.recommended_food_per_feeding, Math.round(result.portionGrams))));
        }
        setVisibleIfChanged(layoutRecommendedFood, true);
        if (tvScheduleMismatch != null) {
            boolean mismatch = enabledFeedingsPerDay > 0
                    && enabledFeedingsPerDay != result.feedingsPerDay;
            setVisibleIfChanged(tvScheduleMismatch, mismatch);
            if (mismatch) {
                setTextIfChanged(tvScheduleMismatch, getString(R.string.recommended_food_schedule_mismatch,
                        enabledFeedingsPerDay, result.feedingsPerDay));
            }
        }
        if (btnEditPetProfile != null) btnEditPetProfile.setText(R.string.pet_profile_edit);

        if (Math.abs(firebaseService.getRecommendedPortion() - result.portionGrams) > 0.01f) {
            firebaseService.updateRecommendedPortion(result.portionGrams);
        }
        applyRecommendedPortionDefault();
        updateScheduleRecommendationUi();
    }

    private int countActiveSchedules() {
        int n = 0;
        for (FeedingSchedule fs : schedulesList) if (fs != null && fs.isEnabled()) n++;
        return n;
    }

    private void updateScheduleRecommendationUi() {
        PetFeedingCalculator.Result r = lastRecommendation;
        if (scheduleAdapter != null) {
            scheduleAdapter.setRecommendedPortion(r == null ? 0f : r.portionGrams);
        }
        if (layoutSchedulePlan == null) return;
        if (r == null) {
            layoutSchedulePlan.setVisibility(View.GONE);
            if (layoutPortionRecommended != null) layoutPortionRecommended.setVisibility(View.GONE);
            return;
        }
        int grams  = Math.round(r.portionGrams);
        int active = countActiveSchedules();

        layoutSchedulePlan.setVisibility(View.VISIBLE);
        tvSchedulePlanTitle.setText(getString(R.string.schedule_plan_title, r.feedingsPerDay, grams));
        boolean needMore = active < r.feedingsPerDay;
        if (active == r.feedingsPerDay) {
            tvSchedulePlanDetail.setText(getString(R.string.schedule_plan_on_track, active));
        } else if (needMore) {
            tvSchedulePlanDetail.setText(getString(R.string.schedule_plan_need_more,
                    active, r.feedingsPerDay - active, r.feedingsPerDay));
        } else {
            tvSchedulePlanDetail.setText(getString(R.string.schedule_plan_too_many,
                    active, r.feedingsPerDay));
        }
        if (btnAddSuggestedSchedules != null) {
            btnAddSuggestedSchedules.setVisibility(needMore ? View.VISIBLE : View.GONE);
        }
        if (layoutPortionRecommended != null) {
            layoutPortionRecommended.setVisibility(View.VISIBLE);
            tvSchedulePortionRecommended.setText(
                    getString(R.string.schedule_portion_recommended_line, grams));
        }
    }

    private void addSuggestedSchedules() {
        PetFeedingCalculator.Result r = lastRecommendation;
        if (r == null) return;
        int missing = r.feedingsPerDay - countActiveSchedules();
        if (missing <= 0) return;

        java.util.Set<String> usedTimes = new java.util.HashSet<>();
        for (FeedingSchedule fs : schedulesList) {
            usedTimes.add(String.format(Locale.US, "%02d:%02d", fs.getTimeHour(), fs.getTimeMinute()));
        }
        List<int[]> candidates = new ArrayList<>();
        for (int[] t : SUGGESTED_TIMES[Math.max(1, Math.min(6, r.feedingsPerDay))]) candidates.add(t);
        for (int h = 6; h <= 22; h++) candidates.add(new int[]{h, 30});

        List<String> allDays = java.util.Arrays.asList(DAY_LABELS);
        int added = 0;
        long baseId = System.currentTimeMillis();
        for (int[] t : candidates) {
            if (added >= missing) break;
            String key = String.format(Locale.US, "%02d:%02d", t[0], t[1]);
            if (!usedTimes.add(key)) continue;
            String label = String.format(Locale.US, "%s (%s)",
                    FeedingSchedule.formatTime12(t[0], t[1]),
                    android.text.TextUtils.join(",", allDays));
            FeedingSchedule suggested = new FeedingSchedule(String.valueOf(baseId + added),
                    t[0], t[1], r.portionGrams, true, label, new ArrayList<>(allDays));
            schedulesList.add(suggested);
            unsavedScheduleIds.add(suggested.getScheduleId());
            added++;
        }
        invalidateScheduleRender();
        if (scheduleAdapter != null) scheduleAdapter.setSchedules(new ArrayList<>(schedulesList));
        updateScheduleRecommendationUi();
        Toast.makeText(this, getString(R.string.schedule_plan_added, added), Toast.LENGTH_SHORT).show();
    }

    private String activityLabel(String activity) {
        if (PetProfile.ACTIVITY_LOW.equals(activity)) return getString(R.string.activity_low);
        if (PetProfile.ACTIVITY_HIGH.equals(activity)) return getString(R.string.activity_high);
        return getString(R.string.activity_moderate);
    }

    private void applyRecommendedPortionDefault() {
        if (etSchedulePortion == null) return;
        if (lastRecommendation == null) {
            etSchedulePortion.setHint("80");
            String cur = etSchedulePortion.getText().toString().trim();
            if (autoFilledSchedulePortion != null && autoFilledSchedulePortion.equals(cur)) {
                etSchedulePortion.setText("");
            }
            autoFilledSchedulePortion = null;
            return;
        }
        int grams = Math.round(lastRecommendation.portionGrams);
        etSchedulePortion.setHint(getString(R.string.schedule_portion_hint_recommended, grams));
        String cur = etSchedulePortion.getText().toString().trim();
        boolean untouched = cur.isEmpty() || cur.equals(autoFilledSchedulePortion);
        if (untouched && !etSchedulePortion.hasFocus()) {
            autoFilledSchedulePortion = String.valueOf(grams);
            etSchedulePortion.setText(autoFilledSchedulePortion);
        }
    }

    private void onPetProfileChanged(PetProfile profile) {
        runOnUiThread(() -> {
            currentPetProfile = profile;
            updateAdaptiveFeedUi();
            if (profile == null && !petProfilePromptShown && !isFinishing() && !isDestroyed()) {
                petProfilePromptShown = true;
                showPetProfileDialog(null);
            }
        });
    }

    private static String formatNumber(float value) {
        if (Math.abs(value - Math.round(value)) < 0.005f) return String.valueOf(Math.round(value));
        return String.format(Locale.US, "%.1f", value);
    }

    private void showPetProfileDialog(PetProfile existing) {
        if (firebaseService == null) return;
        if (petProfileDialog != null && petProfileDialog.isShowing()) return;

        View view = getLayoutInflater().inflate(R.layout.dialog_pet_profile, null);
        EditText etName   = view.findViewById(R.id.etPetName);
        EditText etAge    = view.findViewById(R.id.etPetAge);
        MaterialButtonToggleGroup ageUnitGroup = view.findViewById(R.id.toggleGroupAgeUnit);
        EditText etWeight = view.findViewById(R.id.etPetWeight);
        MaterialButtonToggleGroup typeGroup     = view.findViewById(R.id.toggleGroupPetType);
        MaterialButtonToggleGroup activityGroup = view.findViewById(R.id.toggleGroupPetActivity);
        MaterialButtonToggleGroup feedingsGroup = view.findViewById(R.id.toggleGroupFeedings);
        TextView tvFeedingsPreview = view.findViewById(R.id.tvFeedingsPreview);
        final int[] feedBtnIds = {R.id.btnFeed1, R.id.btnFeed2, R.id.btnFeed3,
                R.id.btnFeed4, R.id.btnFeed5, R.id.btnFeed6};

        int initialFeedings = existing != null && existing.getFeedingsPerDay() >= 1
                ? existing.getFeedingsPerDay()
                : (enabledFeedingsPerDay >= 1
                        ? Math.min(enabledFeedingsPerDay, PetFeedingCalculator.MAX_FEEDINGS_PER_DAY)
                        : PetFeedingCalculator.DEFAULT_FEEDINGS_PER_DAY);
        feedingsGroup.check(feedBtnIds[initialFeedings - 1]);

        if (existing != null) {
            etName.setText(existing.getName());
            int existingMonths = existing.getAgeMonths();
            if (existingMonths < 12) {
                ageUnitGroup.check(R.id.btnAgeMonths);
                etAge.setText(String.valueOf(existingMonths));
            } else {
                ageUnitGroup.check(R.id.btnAgeYears);
                etAge.setText(formatNumber(Math.round(existingMonths / 12f * 100f) / 100f));
            }
            etWeight.setText(formatNumber(existing.getWeightKg()));
            typeGroup.check(existing.isCat() ? R.id.btnPetCat : R.id.btnPetDog);
            if (PetProfile.ACTIVITY_LOW.equals(existing.getActivity())) {
                activityGroup.check(R.id.btnActivityLow);
            } else if (PetProfile.ACTIVITY_HIGH.equals(existing.getActivity())) {
                activityGroup.check(R.id.btnActivityHigh);
            } else {
                activityGroup.check(R.id.btnActivityModerate);
            }
        } else {
            typeGroup.check(R.id.btnPetDog);
            activityGroup.check(R.id.btnActivityModerate);
            ageUnitGroup.check(R.id.btnAgeYears);
        }

        Runnable refreshPreview = () -> {
            PetProfile p = buildPetProfileQuietly(etName, etAge, ageUnitGroup, etWeight, typeGroup,
                    activityGroup, feedingsGroup, feedBtnIds);
            PetFeedingCalculator.Result r = PetFeedingCalculator.calculate(p, 0);
            if (r == null) {
                tvFeedingsPreview.setText("");
            } else {
                tvFeedingsPreview.setText(getString(R.string.pet_profile_feedings_preview,
                        Math.round(r.portionGrams), Math.round(r.dailyGrams), r.feedingsPerDay));
            }
        };
        android.text.TextWatcher previewWatcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { refreshPreview.run(); }
        };
        etAge.addTextChangedListener(previewWatcher);
        ageUnitGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            int m = parseAgeMonths(etAge.getText().toString(),
                    id == R.id.btnAgeYears);
            if (m > 0) {
                etAge.setText(id == R.id.btnAgeMonths
                        ? String.valueOf(m)
                        : formatNumber(Math.round(m / 12f * 100f) / 100f));
                etAge.setSelection(etAge.getText().length());
            }
            refreshPreview.run();
        });
        etWeight.addTextChangedListener(previewWatcher);
        feedingsGroup.addOnButtonCheckedListener((g, id, checked) -> { if (checked) refreshPreview.run(); });
        typeGroup.addOnButtonCheckedListener((g, id, checked) -> { if (checked) refreshPreview.run(); });
        activityGroup.addOnButtonCheckedListener((g, id, checked) -> { if (checked) refreshPreview.run(); });
        refreshPreview.run();

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.pet_profile_setup_title
                                           : R.string.pet_profile_edit_title)
                .setView(view)
                .setPositiveButton(R.string.pet_profile_save, null)
                .setNegativeButton(existing == null ? R.string.pet_profile_later
                                                    : R.string.pet_profile_cancel, null)
                .create();

        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String type = typeGroup.getCheckedButtonId() == R.id.btnPetCat
                            ? PetProfile.TYPE_CAT : PetProfile.TYPE_DOG;
                    String activity;
                    int actId = activityGroup.getCheckedButtonId();
                    if (actId == R.id.btnActivityLow) activity = PetProfile.ACTIVITY_LOW;
                    else if (actId == R.id.btnActivityHigh) activity = PetProfile.ACTIVITY_HIGH;
                    else activity = PetProfile.ACTIVITY_MODERATE;

                    int feedings = selectedFeedings(feedingsGroup, feedBtnIds);
                    PetProfile profile = validatePetProfileInput(
                            etName, etAge, ageUnitGroup, etWeight, type, activity, feedings);
                    if (profile == null) return;

                    if (firebaseService.isPhoneOnline()) {
                        Toast.makeText(MainActivity.this, R.string.sync_saving,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, R.string.sync_saved_offline,
                                Toast.LENGTH_SHORT).show();
                    }
                    firebaseService.savePetProfile(profile,
                            new FirebaseService.PetProfileSaveCallback() {
                                @Override public void onSaved() {
                                    Toast.makeText(MainActivity.this,
                                            R.string.pet_profile_saved, Toast.LENGTH_SHORT).show();
                                }
                                @Override public void onFailed(String error) {
                                    Toast.makeText(MainActivity.this,
                                            R.string.pet_profile_save_failed, Toast.LENGTH_SHORT).show();
                                }
                            });
                    dialog.dismiss();
                }));
        petProfileDialog = dialog;
        dialog.show();
    }

    private PetProfile validatePetProfileInput(EditText etName, EditText etAge,
                                               MaterialButtonToggleGroup ageUnitGroup, EditText etWeight,
                                               String type, String activity, int feedings) {
        boolean ok = true;

        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            etName.setError(getString(R.string.pet_error_name_required));
            ok = false;
        } else if (name.length() > PetFeedingCalculator.MAX_NAME_LENGTH) {
            etName.setError(getString(R.string.pet_error_name_too_long,
                    PetFeedingCalculator.MAX_NAME_LENGTH));
            ok = false;
        }

        int ageMonths = 0;
        boolean inMonths = ageUnitGroup.getCheckedButtonId() == R.id.btnAgeMonths;
        String ageText = etAge.getText().toString().trim().replace(',', '.');
        if (ageText.isEmpty()) {
            etAge.setError(getString(R.string.pet_error_age_required));
            ok = false;
        } else {
            ageMonths = parseAgeMonths(ageText, inMonths);
            if (ageMonths < 0) {
                etAge.setError(inMonths
                        ? getString(R.string.pet_error_months_range, MAX_AGE_MONTHS_INPUT)
                        : getString(R.string.pet_error_age_range,
                                (int) PetFeedingCalculator.MAX_AGE_YEARS));
                ok = false;
            }
        }

        float weight = 0f;
        float maxWeight = PetFeedingCalculator.maxWeightFor(type);
        String weightText = etWeight.getText().toString().trim().replace(',', '.');
        if (weightText.isEmpty()) {
            etWeight.setError(getString(R.string.pet_error_weight_required));
            ok = false;
        } else {
            try {
                weight = Float.parseFloat(weightText);
                if (Float.isNaN(weight) || weight < PetFeedingCalculator.MIN_WEIGHT_KG
                        || weight > maxWeight) {
                    etWeight.setError(getString(R.string.pet_error_weight_range,
                            formatNumber(PetFeedingCalculator.MIN_WEIGHT_KG), formatNumber(maxWeight)));
                    ok = false;
                }
            } catch (NumberFormatException e) {
                etWeight.setError(getString(R.string.pet_error_weight_range,
                        formatNumber(PetFeedingCalculator.MIN_WEIGHT_KG), formatNumber(maxWeight)));
                ok = false;
            }
        }

        if (!ok) return null;
        PetProfile profile = new PetProfile(name, type, ageMonths / 12f, weight, activity, feedings);
        profile.setAgeMonths(ageMonths);
        return profile;
    }

    private static final int MAX_AGE_MONTHS_INPUT = 24;

    private static int parseAgeMonths(String text, boolean inMonths) {
        try {
            float v = Float.parseFloat(text.trim().replace(',', '.'));
            if (Float.isNaN(v)) return -1;
            if (inMonths) {
                if (v != Math.floor(v) || v < 1f || v > MAX_AGE_MONTHS_INPUT) return -1;
                return (int) v;
            }
            if (v < 0.1f || v > PetFeedingCalculator.MAX_AGE_YEARS) return -1;
            return Math.max(1, Math.round(v * PetFeedingCalculator.MONTHS_PER_YEAR));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int selectedFeedings(MaterialButtonToggleGroup group, int[] ids) {
        int checked = group.getCheckedButtonId();
        for (int i = 0; i < ids.length; i++) if (ids[i] == checked) return i + 1;
        return PetFeedingCalculator.DEFAULT_FEEDINGS_PER_DAY;
    }

    private PetProfile buildPetProfileQuietly(EditText etName, EditText etAge,
                                              MaterialButtonToggleGroup ageUnitGroup, EditText etWeight,
                                              MaterialButtonToggleGroup typeGroup,
                                              MaterialButtonToggleGroup activityGroup,
                                              MaterialButtonToggleGroup feedingsGroup,
                                              int[] feedBtnIds) {
        try {
            int ageMonths = parseAgeMonths(etAge.getText().toString(),
                    ageUnitGroup.getCheckedButtonId() == R.id.btnAgeMonths);
            if (ageMonths < 0) return null;
            float weight = Float.parseFloat(etWeight.getText().toString().trim().replace(',', '.'));
            String type = typeGroup.getCheckedButtonId() == R.id.btnPetCat
                    ? PetProfile.TYPE_CAT : PetProfile.TYPE_DOG;
            if (weight < PetFeedingCalculator.MIN_WEIGHT_KG
                    || weight > PetFeedingCalculator.maxWeightFor(type)) return null;
            int actId = activityGroup.getCheckedButtonId();
            String activity = actId == R.id.btnActivityLow ? PetProfile.ACTIVITY_LOW
                    : actId == R.id.btnActivityHigh ? PetProfile.ACTIVITY_HIGH
                    : PetProfile.ACTIVITY_MODERATE;
            String name = etName.getText().toString().trim();
            PetProfile p = new PetProfile(name.isEmpty() ? "Pet" : name, type, ageMonths / 12f,
                    weight, activity, selectedFeedings(feedingsGroup, feedBtnIds));
            p.setAgeMonths(ageMonths);
            return p;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void confirmHaptic() {
        getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void setupDispenseWaterButton() {
        MaterialButton btn = findViewById(R.id.btnDispenseWater);
        if (btn != null) btn.setOnClickListener(v -> showDispenseWaterDialog());
    }

    private void showDispenseWaterDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("Volume in mL");
        input.setText("250");
        new MaterialAlertDialogBuilder(this)
                .setTitle("Dispense water")
                .setMessage("Set the amount of water to dispense (1-5000 mL).")
                .setView(input)
                .setPositiveButton("Dispense", (d, w) -> {
                    try {
                        float ml = Float.parseFloat(input.getText().toString());
                        if (ml < 1f || ml > 5000f) {
                            Toast.makeText(this, "Volume must be between 1 and 5000 mL", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        triggerWaterDispense(ml);
                    } catch (Exception ex) {
                        Toast.makeText(this, "Invalid volume", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void triggerWaterDispense(float ml) {
        MaterialButton btn = findViewById(R.id.btnDispenseWater);
        if (btn != null) btn.setEnabled(false);
        final String actionId = "water_" + System.currentTimeMillis();
        final Runnable reenable = () -> { if (btn != null) btn.setEnabled(true); };

        if (deviceDiscovery == null) {
            sendRemoteWater(ml, actionId, reenable);
            return;
        }
        deviceDiscovery.discover(new DeviceDiscovery.DiscoveryCallback() {
            @Override
            public void onFound(String baseUrl, String method) {
                if (espApiClient != null) espApiClient.shutdown();
                espApiClient = new EspApiClient(baseUrl);
                espApiClient.sendWaterCommand(ml, actionId, new EspApiClient.Callback() {
                    @Override public void onSuccess(JSONObject response) {
                        reenable.run();
                        confirmHaptic();
                        Toast.makeText(MainActivity.this,
                                String.format(Locale.US, "Dispensing %.0f mL of water", ml),
                                Toast.LENGTH_LONG).show();
                    }
                    @Override public void onError(String error) {
                        String msg = error == null ? "" : error.toLowerCase(Locale.US);
                        if (msg.contains("already in progress")) {
                            reenable.run();
                            Toast.makeText(MainActivity.this, "Water is already being dispensed",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            sendRemoteWater(ml, actionId, reenable);
                        }
                    }
                });
            }

            @Override
            public void onNotFound() {
                sendRemoteWater(ml, actionId, reenable);
            }
        });
    }

    private void sendRemoteWater(float ml, String actionId, Runnable reenable) {
        if (firebaseService == null) {
            reenable.run();
            Toast.makeText(this, "Remote command unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        firebaseService.sendRemoteWaterCommand(ml, actionId, new FirebaseService.AckCallback() {
            @Override public void onAcknowledged() {
                Toast.makeText(MainActivity.this, "Water command sent to the feeder",
                        Toast.LENGTH_LONG).show();
                firebaseService.watchRemoteCommand(actionId, (status, error) -> {
                    if ("completed".equals(status)) {
                        reenable.run();
                        Toast.makeText(MainActivity.this,
                                String.format(Locale.US, "Dispensing %.0f mL of water", ml),
                                Toast.LENGTH_LONG).show();
                        firebaseService.stopWatchingRemoteCommand();
                    } else if ("rejected".equals(status) || "expired".equals(status)) {
                        reenable.run();
                        Toast.makeText(MainActivity.this, "Water dispense was rejected by the feeder",
                                Toast.LENGTH_LONG).show();
                        firebaseService.stopWatchingRemoteCommand();
                    }
                });
            }
            @Override public void onFailed(String error) {
                reenable.run();
                Toast.makeText(MainActivity.this, "Could not send the water command",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showManualFeedDialog() {
        if (firebaseService == null) return;
        float rec = firebaseService.getRecommendedPortion();
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("Portion grams");
        input.setText(String.format(Locale.US, "%.0f", rec));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Manual feed")
                .setMessage("Set the portion amount before dispensing.")
                .setView(input)
                .setPositiveButton("Feed now", (d, w) -> {
                    try {
                        float grams = Float.parseFloat(input.getText().toString());
                        if (grams <= 0f) {
                            Toast.makeText(this, "Portion must be greater than 0g", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        triggerManualFeedWithDeviceTest(grams);
                    } catch (Exception ex) {
                        Toast.makeText(this, "Invalid grams value", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showConfigDialog() {
        if (firebaseService == null) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("Default portion grams");
        new MaterialAlertDialogBuilder(this)
                .setTitle("Remote feeder configuration")
                .setMessage("Set default portion and sync to ESP8266.")
                .setView(input)
                .setPositiveButton("Sync", (d, w) -> {
                    try {
                        float grams = Float.parseFloat(input.getText().toString());
                        firebaseService.updateRecommendedPortion(grams);
                        firebaseService.pushRemoteFeedConfig(grams,
                                saveAckToast(R.string.sync_config_synced));
                        updateAdaptiveFeedUi();
                    } catch (Exception ex) {
                        Toast.makeText(this, "Invalid grams value", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateStatusBanner(DashboardData data) {
        String text = "Last fed: " + data.getSummaryStats().getLastFeedingTime()
                + "  •  " + data.getPetName();
        setTextIfChanged(tvStatusBanner, text);
    }

    private void updateSummaryCards(SummaryStats stats) {
        String sig = stats.getTodayFeedingCount() + "|" + stats.getLastFeedingTime() + "|"
                + stats.getTotalFoodDispensedGrams() + "|" + stats.getSystemStatus();
        if (sig.equals(renderedSummarySig)) return;
        renderedSummarySig = sig;
        setTextIfChanged(tvFeedingCount, String.valueOf(stats.getTodayFeedingCount()));
        setTextIfChanged(tvLastFeedingTime, stats.getLastFeedingTime());
        setTextIfChanged(tvTotalFood, String.format(Locale.US, "%.1fg", stats.getTotalFoodDispensedGrams()));
        setTextIfChanged(tvSystemStatus, stats.getSystemStatusLabel());
        int color = ContextCompat.getColor(this, statusColor(stats.getSystemStatus()));
        tvSystemStatus.setTextColor(color);
        ivStatusIcon.setColorFilter(color);
    }

    private int statusColor(SummaryStats.SystemStatus s) {
        switch (s) {
            case NORMAL:  return R.color.colorStatusCompleted;
            case WARNING: return R.color.colorStatusMissed;
            case ANOMALY: return R.color.colorStatusAnomaly;
            default:      return R.color.colorTextSecondary;
        }
    }

    private String chartSignature(DashboardData data) {
        StringBuilder sb = new StringBuilder();
        if (data.getFoodTrendData() != null) {
            for (DashboardData.FoodTrendData t : data.getFoodTrendData()) {
                sb.append(t.getLabel()).append(',').append(t.getTargetWeight()).append(',')
                  .append(t.getActualWeight()).append(';');
            }
        }
        sb.append('#');
        if (data.getFrequencyData() != null) {
            for (DashboardData.FeedingFrequencyData f : data.getFrequencyData()) {
                sb.append(f.getLabel()).append(',').append(f.getScheduledCount()).append(',')
                  .append(f.getCompletedCount()).append(';');
            }
        }
        return sb.toString();
    }

    private void updateCharts(DashboardData data) {
        if (chartHelper == null) return;
        String sig = chartSignature(data);
        if (sig.equals(renderedChartSig)) return;
        renderedChartSig = sig;
        chartHelper.populateLineChart(lineChart, data.getFoodTrendData());
        chartHelper.populateBarChart(barChart, data.getFrequencyData());
    }

    private void updateFeedingHistory(List<FeedingRecord> records) {
        if (records == null || records.isEmpty()) {
            setVisibleIfChanged(emptyState, true);
            setVisibleIfChanged(recyclerFeedingHistory, false);
            historyAdapter.setRecords(new ArrayList<>());
        } else {
            setVisibleIfChanged(emptyState, false);
            setVisibleIfChanged(recyclerFeedingHistory, true);
            historyAdapter.setRecords(filterRecords(records));
        }
    }

    private List<FeedingRecord> filterRecords(List<FeedingRecord> records) {
        int limit;
        switch (currentFilter) {
            case "WEEK":  limit = 28; break;
            case "MONTH": limit = 120; break;
            default:      limit = 8;  break;
        }
        int capped = Math.min(limit, 10);
        return records.size() > capped ? records.subList(0, capped) : records;
    }

    private void updateAnomalyAlerts(List<AnomalyAlert> alerts) {
        int count = alerts == null ? 0 : Math.min(alerts.size(), 10);
        if (count == 0) {
            if (anomalyContainer.getChildCount() > 0) anomalyContainer.removeAllViews();
            setVisibleIfChanged(anomalyContainer, false);
            setVisibleIfChanged(noAnomaliesState, true);
            return;
        }
        setVisibleIfChanged(anomalyContainer, true);
        setVisibleIfChanged(noAnomaliesState, false);
        while (anomalyContainer.getChildCount() > count) {
            anomalyContainer.removeViewAt(anomalyContainer.getChildCount() - 1);
        }
        for (int i = 0; i < count; i++) {
            AnomalyAlert alert = alerts.get(i);
            String sig = alert.getId() + "|" + alert.getType() + "|" + alert.getSeverity() + "|"
                    + alert.getDetail() + "|" + alert.getTimestamp();
            if (i < anomalyContainer.getChildCount()) {
                View existing = anomalyContainer.getChildAt(i);
                if (!sig.equals(existing.getTag())) {
                    bindAlertView(existing, alert);
                    existing.setTag(sig);
                }
            } else {
                View created = createAlertView(alert);
                created.setTag(sig);
                anomalyContainer.addView(created);
            }
        }
    }

    private View createAlertView(AnomalyAlert alert) {
        View view = LayoutInflater.from(this)
                .inflate(R.layout.item_anomaly_alert, anomalyContainer, false);
        bindAlertView(view, alert);
        return view;
    }

    private void bindAlertView(View view, AnomalyAlert alert) {
        View     severityBar  = view.findViewById(R.id.severityIndicator);
        ImageView ivIcon      = view.findViewById(R.id.ivAlertIcon);
        TextView  tvType      = view.findViewById(R.id.tvAlertType);
        TextView  tvMsg       = view.findViewById(R.id.tvAlertMessage);
        TextView  tvTime      = view.findViewById(R.id.tvAlertTime);
        CardView  card        = view.findViewById(R.id.cardAnomalyAlert);

        int color = alert.getSeverityColor();
        severityBar.setBackgroundColor(color);
        ivIcon.setColorFilter(color);
        tvType.setTextColor(color);
        setTextIfChanged(tvType, alert.getTypeLabel());
        setTextIfChanged(tvMsg, alert.getDetail());
        setTextIfChanged(tvTime, alert.getTimestamp());

        if (card != null) {
            card.setCardBackgroundColor(alert.getSeverityBackgroundColor());
        }
    }

    private void updateLastUpdatedTime(long epochMs) {
        if (tvLastUpdated == null) return;
        lastDashboardUpdatedAt = epochMs;
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(epochMs));
        if (firebaseService != null && !firebaseService.isPhoneOnline() && syncLastSyncedAt > 0) {
            setTextIfChanged(tvLastUpdated, getString(R.string.sync_last_synced,
                    relativeSyncTime(syncLastSyncedAt)));
        } else if (System.currentTimeMillis() - feederNoticeAt < 60_000L) {
            setTextIfChanged(tvLastUpdated, getString(R.string.sync_updated_from_feeder_at, time));
        } else {
            setTextIfChanged(tvLastUpdated, "Updated: " + time);
        }
    }

    private void startFirebaseTimeout() {
        timeoutHandler.postDelayed(() -> {
            if (!isFirstLoad) return;
            runOnUiThread(this::showIdleState);
        }, FIREBASE_TIMEOUT_MS);
    }

    private void showIdleState() {
        isFirstLoad = false;
        renderedChartSig = null;
        renderedSummarySig = null;
        lastChipOnline = null;

        showContentState();

        tvConnectionStatus.setText(R.string.status_offline);
        tvConnectionStatus.setTextColor(
                ContextCompat.getColor(this, R.color.colorStatusMissed));
        tvLastUpdated.setText(R.string.last_updated_no_connection);

        tvStatusBanner.setText(R.string.no_feeder_data_banner);

        if (noDataNoticeCard != null) {
            noDataNoticeCard.setVisibility(View.VISIBLE);
        }

        tvFeedingCount.setText("0");
        tvLastFeedingTime.setText("--:--");
        tvTotalFood.setText("0.0g");
        tvSystemStatus.setText(R.string.no_feeder_data_status);
        tvSystemStatus.setTextColor(
                ContextCompat.getColor(this, R.color.colorTextMuted));
        ivStatusIcon.setColorFilter(
                ContextCompat.getColor(this, R.color.colorTextMuted));

        if (lineChart != null) { lineChart.clear(); lineChart.invalidate(); }
        if (barChart  != null) { barChart.clear();  barChart.invalidate();  }

        emptyState.setVisibility(View.VISIBLE);
        recyclerFeedingHistory.setVisibility(View.GONE);
        TextView tvEmptyLabel = emptyState.findViewById(android.R.id.text1);
        if (tvEmptyLabel == null) {
            for (int i = 0; i < emptyState.getChildCount(); i++) {
                View child = emptyState.getChildAt(i);
                if (child instanceof TextView) {
                    ((TextView) child).setText(R.string.no_feeder_data_history);
                    break;
                }
            }
        }

        anomalyContainer.removeAllViews();
        anomalyContainer.setVisibility(View.GONE);
        noAnomaliesState.setVisibility(View.VISIBLE);
    }

    private void updateScheduleCountBadge(List<FeedingSchedule> schedules) {
        if (tvScheduleCountNumber != null && schedules != null) {
            int activeCount = (int) schedules.stream()
                    .filter(FeedingSchedule::isEnabled)
                    .count();

            tvScheduleCountNumber.setText(String.valueOf(activeCount));

            if (activeCount == 0) {
                tvScheduleCountNumber.setTextColor(
                        ContextCompat.getColor(this, R.color.colorTextMuted));
            } else {
                tvScheduleCountNumber.setTextColor(
                        ContextCompat.getColor(this, R.color.colorTextOnAccent));
            }
        }
    }

    private void updateScheduleEmptyState(List<FeedingSchedule> schedules) {
        if (emptySchedulesState == null || recyclerHomeSchedules == null) {
            return;
        }

        if (schedules == null || schedules.isEmpty()) {
            emptySchedulesState.setVisibility(View.VISIBLE);
            recyclerHomeSchedules.setVisibility(View.GONE);
        } else {
            emptySchedulesState.setVisibility(View.GONE);
            recyclerHomeSchedules.setVisibility(View.VISIBLE);
        }
    }

    private void updateHomeScheduleUI(List<FeedingSchedule> schedules) {
        updateScheduleCountBadge(schedules);
        updateScheduleEmptyState(schedules);
    }

    private final FirebaseService.SyncListener syncListener = new FirebaseService.SyncListener() {
        @Override
        public void onSyncStateChanged(FirebaseService.SyncState state, int pendingCount, long lastSyncedAt) {
            currentSyncState = state;
            syncPendingCount = pendingCount;
            syncLastSyncedAt = lastSyncedAt;
            if (isFinishing() || isDestroyed()) return;
            applyPhoneOnlineToChip();
            renderSyncUi();
            refreshSystemNotification();
            if (state != FirebaseService.SyncState.OFFLINE && homeState == HomeState.ERROR
                    && !reconnectRetryDone && firebaseService != null) {
                reconnectRetryDone = true;
                syncTickHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed() && homeState == HomeState.ERROR) {
                        firebaseService.startListening();
                    }
                });
            }
        }

        @Override
        public void onSyncNotice(int notice) {
            if (notice == FirebaseService.NOTICE_FROM_FEEDER) {
                feederNoticeAt = System.currentTimeMillis();
                if (lastDashboardUpdatedAt > 0) updateLastUpdatedTime(lastDashboardUpdatedAt);
                Toast.makeText(MainActivity.this, R.string.sync_updated_from_feeder,
                        Toast.LENGTH_SHORT).show();
            } else if (notice == FirebaseService.NOTICE_FROM_OTHER_DEVICE) {
                Toast.makeText(MainActivity.this, R.string.sync_updated_from_other_device,
                        Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onStaleManualFeed(String actionId, float grams, long createdAt) {
            if (isFinishing() || staleDialogShowing) return;
            staleDialogShowing = true;
            int minutes = (int) Math.max(1, (System.currentTimeMillis() - createdAt) / 60_000L);
            new MaterialAlertDialogBuilder(MainActivity.this)
                    .setTitle(R.string.sync_stale_title)
                    .setMessage(getString(R.string.sync_stale_message, Math.round(grams), minutes))
                    .setCancelable(false)
                    .setPositiveButton(R.string.sync_stale_send, (d, w) -> {
                        staleDialogShowing = false;
                        firebaseService.resolveStaleManualFeed(actionId, true);
                    })
                    .setNegativeButton(R.string.sync_stale_discard, (d, w) -> {
                        staleDialogShowing = false;
                        firebaseService.resolveStaleManualFeed(actionId, false);
                    })
                    .show();
        }
    };

    private void applyPhoneOnlineToChip() {
        if (tvConnectionStatus == null || firebaseService == null) return;
        boolean phoneOnline = firebaseService.isPhoneOnline();
        boolean online = isDeviceReachable || (phoneOnline && (!feederKnown || feederOnline));
        if (lastChipOnline != null && lastChipOnline == online) return;
        lastChipOnline = online;
        if (online) {
            tvConnectionStatus.setText(R.string.status_online);
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.colorStatusCompleted));
            tvConnectionStatus.setBackgroundResource(R.drawable.bg_status_online);
        } else {
            tvConnectionStatus.setText(R.string.status_offline);
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.colorStatusMissed));
            tvConnectionStatus.setBackgroundResource(R.drawable.bg_status_offline);
        }
    }

    private String relativeSyncTime(long then) {
        long diff = Math.max(0L, System.currentTimeMillis() - then);
        long min = diff / 60_000L;
        if (min < 1) return getString(R.string.sync_just_now);
        if (min < 60) return getString(R.string.sync_ago_min, (int) min);
        long hours = min / 60;
        if (hours < 24) return getString(R.string.sync_ago_hour, (int) hours);
        return getString(R.string.sync_ago_day, (int) (hours / 24));
    }

    private void renderSyncUi() {
        int textRes, colorRes, bgRes;
        switch (currentSyncState) {
            case PENDING_CLOUD:
                textRes = R.string.sync_state_pending_cloud;
                colorRes = R.color.colorStatusWarning; bgRes = R.drawable.bg_status_offline; break;
            case PENDING_DEVICE:
                textRes = R.string.sync_state_pending_device;
                colorRes = R.color.colorStatusWarning; bgRes = R.drawable.bg_status_offline; break;
            case FAILED:
                textRes = R.string.sync_state_failed;
                colorRes = R.color.colorStatusAnomaly; bgRes = R.drawable.bg_status_offline; break;
            case OFFLINE:
                textRes = R.string.sync_state_offline;
                colorRes = R.color.colorStatusMissed; bgRes = R.drawable.bg_status_offline; break;
            case SYNCED:
            default:
                textRes = R.string.sync_state_synced;
                colorRes = R.color.colorStatusCompleted; bgRes = R.drawable.bg_status_online; break;
        }
        if (tvSyncIndicator != null) {
            tvSyncIndicator.setText(textRes);
            tvSyncIndicator.setTextColor(ContextCompat.getColor(this, colorRes));
            tvSyncIndicator.setBackgroundResource(bgRes);
        }

        if (tvScheduleSyncStatus != null) {
            String line;
            if (currentSyncState == FirebaseService.SyncState.FAILED) {
                line = getString(R.string.sync_failed_line);
            } else if (syncPendingCount > 0) {
                line = getResources().getQuantityString(R.plurals.sync_changes_waiting,
                        syncPendingCount, syncPendingCount);
            } else if (currentSyncState == FirebaseService.SyncState.PENDING_DEVICE) {
                line = getString(R.string.sync_waiting_feeder);
            } else if (syncLastSyncedAt > 0) {
                line = getString(R.string.sync_last_synced, relativeSyncTime(syncLastSyncedAt));
            } else {
                line = getString(R.string.sync_never_synced);
            }
            tvScheduleSyncStatus.setText(line);
        }

        if (btnSyncNow != null) {
            boolean show = currentSyncState == FirebaseService.SyncState.FAILED
                    || currentSyncState == FirebaseService.SyncState.PENDING_CLOUD
                    || currentSyncState == FirebaseService.SyncState.PENDING_DEVICE;
            btnSyncNow.setVisibility(show ? View.VISIBLE : View.GONE);
            btnSyncNow.setText(currentSyncState == FirebaseService.SyncState.FAILED
                    ? R.string.sync_retry : R.string.sync_now);
        }

        if (scheduleAdapter != null && firebaseService != null) {
            scheduleAdapter.setUnappliedIds(firebaseService.getUnappliedScheduleIds());
        }
    }

    private void updateDeviceStatusBadge() {
        applyPhoneOnlineToChip();
    }

    private void setDeviceConfigured(boolean configured) {
        isDeviceConfigured = configured;
        updateDeviceStatusBadge();
    }

    private void setDeviceReachable(boolean reachable) {
        isDeviceReachable = reachable;
        updateDeviceStatusBadge();
    }

    private void checkDeviceReachability() {
        if (espApiClient == null) {
            setDeviceReachable(false);
            return;
        }

        espApiClient.ping(new EspApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                setDeviceReachable(true);
                String uid = FirebaseService.currentUid();
                if (uid != null && response != null
                        && (!response.optBoolean("account_linked", false)
                            || response.optLong("account_hash", 0L) != EspApiClient.uidHash(uid))) {
                    espApiClient.linkAccount(uid, null);
                }
            }

            @Override
            public void onError(String errorMessage) {
                setDeviceReachable(false);
            }
        });
    }
}
