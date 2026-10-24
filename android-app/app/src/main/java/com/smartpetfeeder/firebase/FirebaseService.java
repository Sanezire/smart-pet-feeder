package com.smartpetfeeder.firebase;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Query;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.smartpetfeeder.model.AnomalyAlert;
import com.smartpetfeeder.model.DashboardData;
import com.smartpetfeeder.model.FeedingRecord;
import com.smartpetfeeder.model.FeedingSchedule;
import com.smartpetfeeder.model.PetProfile;
import com.smartpetfeeder.model.SummaryStats;
import com.smartpetfeeder.util.AnomalyDetector;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class FirebaseService {

    private static final String TAG = "FirebaseService";

    public  static final String PATH_USERS            = "users";
    private static final String PATH_FEEDING_RECORDS  = "feeding_records";
    private static final String PATH_SCHEDULES        = "feeding_schedules";
    private static final String PATH_DEVICE_STATUS    = "device_status";
    private static final String PATH_META             = "meta";
    private static final String PATH_PET_PROFILE      = "pet_profile";

    private static final int[][] MOCK_SLOTS = {
            {7,  0,  80},
            {12, 0,  75},
            {18, 0,  85},
            {21, 0,  60},
    };
    private static final int MOCK_DAYS = 7;

    public interface DataUpdateListener {
        void onDataUpdated(DashboardData dashboardData);
        void onError(String error);
    }

    public interface LiveWeightListener {
        void onLiveWeight(boolean available, float grams, long updatedAt);
    }

    public interface LiveFeedingListener {
        void onLiveFeeding(com.smartpetfeeder.model.LiveFeedingStats stats);
    }

    public interface FeederStatusListener {
        void onFeederStatus(boolean online, long lastSeen);
    }

    public interface PetProfileListener {
        void onPetProfileChanged(PetProfile profile);
    }

    public interface PetProfileSaveCallback {
        void onSaved();
        void onFailed(String error);
    }

    public enum SyncState { SYNCED, PENDING_CLOUD, PENDING_DEVICE, FAILED, OFFLINE }

    public static final int NOTICE_FROM_FEEDER       = 1;
    public static final int NOTICE_FROM_OTHER_DEVICE = 2;

    public interface SyncListener {
        void onSyncStateChanged(SyncState state, int pendingCount, long lastSyncedAt);
        void onSyncNotice(int notice);
        void onStaleManualFeed(String actionId, float grams, long createdAt);
    }

    public interface AckCallback {
        void onAcknowledged();
        void onFailed(String error);
    }

    private static final class PendingAction {
        String     id;
        String     type;
        long       createdAt;
        long       version;
        int        attempts;
        JSONObject payload;
        boolean    heldForDecision;
        final List<AckCallback> callbacks = new ArrayList<>();

        JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", id); o.put("type", type); o.put("createdAt", createdAt);
                o.put("version", version); o.put("attempts", attempts);
                o.put("payload", payload);
                return o;
            } catch (Exception e) { return null; }
        }
        static PendingAction fromJson(JSONObject o) {
            PendingAction a = new PendingAction();
            a.id = o.optString("id"); a.type = o.optString("type");
            a.createdAt = o.optLong("createdAt"); a.version = o.optLong("version");
            a.attempts = o.optInt("attempts");
            a.payload = o.optJSONObject("payload");
            if (a.payload == null) a.payload = new JSONObject();
            return a;
        }
    }

    private interface StepDone { void done(boolean ok, String error); }

    private static final String ACT_SCHEDULE_UPSERT = "schedule_upsert";
    private static final String ACT_SCHEDULE_DELETE = "schedule_delete";
    private static final String ACT_REMOTE_CONFIG   = "remote_config";
    private static final String ACT_REMOTE_SCHEDULE = "remote_schedule";
    private static final String ACT_REMOTE_COMMAND  = "remote_command";
    private static final String ACT_MANUAL_LOG      = "manual_feed_log";

    private static final String PREFS_SYNC_BASE     = "sync_queue_prefs";
    private static final String PREF_QUEUE          = "queue";
    private static final String PREF_LAST_SYNCED    = "last_synced_at";
    private static final String PREF_LOCAL_VERSION  = "local_schedule_version";
    private static final String PREF_OWN_VERSIONS   = "own_versions";
    private static final String PREF_SEEN_VERSION   = "seen_schedule_version";
    private static final String PREF_CHANGED_IDS    = "changed_schedule_ids";

    public static final long STALE_MANUAL_FEED_MS = 15 * 60_000L;
    private static final long DEVICE_ACK_TIMEOUT_MS = 2 * 60_000L;
    private static final long BACKOFF_BASE_MS = 2_000L;
    private static final long BACKOFF_MAX_MS  = 60_000L;
    private static final int  FAIL_THRESHOLD  = 3;
    private static final long CATCH_UP_WINDOW_MS = 30_000L;

    private final FirebaseDatabase  database;
    private final String            uid;
    private final DatabaseReference userRef;
    private final DatabaseReference recordsRef;
    private final DatabaseReference schedulesRef;
    private final DatabaseReference deviceStatusRef;
    private final DatabaseReference metaRef;
    private final DatabaseReference petProfileRef;

    private DataUpdateListener   listener;
    private ValueEventListener   recordsListener;
    private ValueEventListener   schedulesListener;
    private ValueEventListener   petProfileValueListener;
    private PetProfileListener   petProfileListener;
    private PetProfile           petProfile;
    private final AnomalyDetector anomalyDetector = new AnomalyDetector();
    private final Random          rng             = new Random();

    private List<FeedingRecord>   cachedRecords   = new ArrayList<>();
    private List<FeedingSchedule> cachedSchedules = new ArrayList<>();
    private String                petName         = "Buddy";
    private boolean               deviceConnected = false;
    private float                 recommendedPortion = 80f;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final DatabaseReference rootRef;
    private final DatabaseReference connectedRef;
    private final SharedPreferences syncPrefs;
    private final ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ValueEventListener connectedListener;
    private ValueEventListener deviceStatusListener;
    private Query              recordsQuery;
    private SyncListener       syncListener;
    private LiveWeightListener liveWeightListener;
    private LiveFeedingListener liveFeedingListener;
    private FeederStatusListener feederStatusListener;
    private boolean feederOnline          = false;
    private boolean feederStatusDispatched = false;
    private long    feederSeenChangedAt   = 0L;
    private float   lastBaseRecommended   = -1f;
    private String  lastBasePetName       = null;
    private boolean lastBaseConnected     = false;
    private String  lastLiveFeedKey       = null;
    private String  lastLiveWeightKey     = null;
    private static final long FEEDER_PRESENCE_MS = 60_000L;
    private final Runnable feederPresenceTick = this::evaluateFeederPresence;

    private final List<PendingAction> queue = new ArrayList<>();
    private final Map<String, Long>   changedScheduleIds = new HashMap<>();
    private boolean cloudOnline       = false;
    private boolean everDisconnected  = false;
    private boolean flushing          = false;
    private String  inFlightActionId  = null;
    private int     consecutiveFailures = 0;
    private int     directInFlight    = 0;
    private long    lastSyncedAt      = 0L;
    private long    localScheduleVersion  = 0L;
    private long    remoteScheduleVersion = 0L;
    private long    appliedScheduleVersion = 0L;
    private long    appliedLocallyVersion = 0L;
    private long    deviceWaitStartedAt   = 0L;
    private long    feederLastSeen        = 0L;
    private boolean deviceWeightKnown     = false;
    private float   deviceCurrentWeight   = 0f;
    private long    deviceWeightAt        = 0L;
    private long    catchUpUntil          = 0L;
    private boolean catchUpNoticeSent     = true;
    private String  lastRecordKey         = null;
    private boolean deviceStatusLoaded    = false;
    private SyncState lastDispatchedState = null;
    private int       lastDispatchedPending = -1;
    private boolean   staleAsked          = false;

    private static boolean sPersistenceEnabled = false;

    public FirebaseService() { this(null); }

    public static String currentUid() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        return u != null ? u.getUid() : null;
    }

    public static DatabaseReference userRef(FirebaseDatabase db, String uid) {
        return db.getReference(PATH_USERS).child(uid);
    }

    public boolean isSessionValid() {
        return uid.equals(currentUid());
    }

    public FirebaseService(Context context) {
        uid = currentUid();
        if (uid == null) throw new IllegalStateException("FirebaseService requires a signed-in user");
        database = FirebaseDatabase.getInstance(
                "https://smart-ped-feeder-default-rtdb.firebaseio.com");
        if (!sPersistenceEnabled) {
            database.setPersistenceEnabled(true);
            sPersistenceEnabled = true;
        }
        userRef         = userRef(database, uid);
        recordsRef      = userRef.child(PATH_FEEDING_RECORDS);
        schedulesRef    = userRef.child(PATH_SCHEDULES);
        deviceStatusRef = userRef.child(PATH_DEVICE_STATUS);
        metaRef         = userRef.child(PATH_META);
        petProfileRef   = userRef.child(PATH_PET_PROFILE);

        recordsRef.keepSynced(true);
        schedulesRef.keepSynced(true);

        rootRef      = userRef;
        connectedRef = database.getReference(".info/connected");
        if (context != null) {
            Context app = context.getApplicationContext();
            syncPrefs = app.getSharedPreferences(PREFS_SYNC_BASE + "_" + uid, Context.MODE_PRIVATE);
            connectivityManager = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        } else {
            syncPrefs = null;
            connectivityManager = null;
        }
        loadSyncPrefs();
        registerNetworkCallback();
    }

    public void setListener(DataUpdateListener listener) {
        this.listener = listener;
    }

    public void setLiveWeightListener(LiveWeightListener listener) {
        this.liveWeightListener = listener;
    }

    public void setLiveFeedingListener(LiveFeedingListener listener) {
        this.liveFeedingListener = listener;
    }

    public void setFeederStatusListener(FeederStatusListener listener) {
        this.feederStatusListener = listener;
        if (listener != null && feederStatusDispatched) listener.onFeederStatus(feederOnline, feederLastSeen);
    }

    public boolean isFeederOnline() { return feederOnline; }

    public long getFeederLastSeen() { return feederLastSeen; }

    public void startListening() {
        if (!isSessionValid()) return;
        stopListening();
        attachConnectionListener();
        attachRecordsListener();
        attachSchedulesListener();
        listenForDeviceStatus();
        attachPetProfileListener();
        flushQueue();
        dispatchSyncState(true);
    }

    public void stopListening() {
        if (recordsListener != null) {
            if (recordsQuery != null) recordsQuery.removeEventListener(recordsListener);
            recordsRef.removeEventListener(recordsListener);
            recordsListener = null;
        }
        if (schedulesListener != null) {
            schedulesRef.removeEventListener(schedulesListener);
            schedulesListener = null;
        }
        if (deviceStatusListener != null) {
            deviceStatusRef.removeEventListener(deviceStatusListener);
            deviceStatusListener = null;
        }
        if (petProfileValueListener != null) {
            petProfileRef.removeEventListener(petProfileValueListener);
            petProfileValueListener = null;
        }
        if (connectedListener != null) {
            connectedRef.removeEventListener(connectedListener);
            connectedListener = null;
        }
        uiHandler.removeCallbacks(feederPresenceTick);
        lastBaseRecommended = -1f;
        lastBasePetName = null;
        lastLiveFeedKey = null;
        lastLiveWeightKey = null;
    }

    public void seedDefaultSchedulesIfEmpty() {
        metaRef.child("schedules_seeded").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                Boolean alreadySeeded = snapshot.getValue(Boolean.class);
                if (Boolean.TRUE.equals(alreadySeeded)) {
                    seedMockFeedingRecordsIfEmpty();
                    seedDeviceStatusIfEmpty();
                    return;
                }
                schedulesRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot schedulesSnap) {
                        if (!schedulesSnap.exists()) {
                            writeScheduleDirect(new FeedingSchedule(null, 7,  0, 80f, true, "Morning"));
                            writeScheduleDirect(new FeedingSchedule(null, 12, 0, 75f, true, "Noon"));
                            writeScheduleDirect(new FeedingSchedule(null, 18, 0, 85f, true, "Evening"));
                            writeScheduleDirect(new FeedingSchedule(null, 21, 0, 60f, true, "Night"));
                        }
                        metaRef.child("schedules_seeded").setValue(true);
                        seedMockFeedingRecordsIfEmpty();
                        seedDeviceStatusIfEmpty();
                    }
                    @Override public void onCancelled(DatabaseError error) {
                        Log.w(TAG, "seedDefaultSchedules schedule check cancelled", error.toException());
                        seedMockFeedingRecordsIfEmpty();
                        seedDeviceStatusIfEmpty();
                    }
                });
            }
            @Override public void onCancelled(DatabaseError error) {
                Log.w(TAG, "seedDefaultSchedules meta check cancelled", error.toException());
                seedMockFeedingRecordsIfEmpty();
                seedDeviceStatusIfEmpty();
            }
        });
    }

    public void saveFeedingRecord(FeedingRecord record) {
        if (record.getFeedingId() == null || record.getFeedingId().isEmpty()) {
            record.setFeedingId(recordsRef.push().getKey());
        }
        recordsRef.child(record.getFeedingId())
                .setValue(record)
                .addOnSuccessListener(u -> Log.d(TAG, "Record saved: " + record.getFeedingId()))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to save record", e));
    }

    private void writeScheduleDirect(FeedingSchedule schedule) {
        if (schedule.getScheduleId() == null || schedule.getScheduleId().isEmpty()) {
            schedule.setScheduleId(schedulesRef.push().getKey());
        }
        schedulesRef.child(schedule.getScheduleId())
                .setValue(schedule)
                .addOnFailureListener(e -> Log.e(TAG, "Failed to save schedule", e));
    }

    public void saveFeedingSchedule(FeedingSchedule schedule) {
        saveFeedingSchedule(schedule, null);
    }

    public void saveFeedingSchedule(FeedingSchedule schedule, AckCallback cb) {
        if (schedule.getScheduleId() == null || schedule.getScheduleId().isEmpty()) {
            schedule.setScheduleId(schedulesRef.push().getKey());
        }
        enqueueScheduleUpsert(schedule, cb);
    }

    public void saveSchedules(List<FeedingSchedule> schedules, AckCallback cb) {
        List<FeedingSchedule> copy = new ArrayList<>(schedules);
        for (FeedingSchedule fs : copy) {
            if (fs.getScheduleId() == null || fs.getScheduleId().isEmpty()) {
                fs.setScheduleId(schedulesRef.push().getKey());
            }
            enqueueScheduleUpsert(fs, null);
        }
        enqueueRemoteSchedule(copy, cb);
    }

    public void updateSchedule(FeedingSchedule schedule) {
        saveFeedingSchedule(schedule, null);
    }

    public void updateSchedule(FeedingSchedule schedule, AckCallback cb) {
        saveFeedingSchedule(schedule, cb);
    }

    public void deleteSchedule(String scheduleId) {
        deleteSchedule(scheduleId, null);
    }

    public void deleteSchedule(String scheduleId, AckCallback cb) {
        if (scheduleId == null || scheduleId.isEmpty()) return;
        try {
            JSONObject p = new JSONObject();
            p.put("scheduleId", scheduleId);
            long v = nextScheduleVersion();
            PendingAction a = newAction(ACT_SCHEDULE_DELETE, p, v);
            markScheduleChanged(scheduleId, v);
            enqueue(a, cb, "sched:" + scheduleId);
        } catch (Exception e) {
            Log.e(TAG, "deleteSchedule enqueue failed", e);
        }
    }

    public void updateFcmToken(String token) {
        deviceStatusRef.child("fcmToken").setValue(token);
        deviceStatusRef.child("lastSeen").setValue(ServerValue.TIMESTAMP);
    }

    public void setPetProfileListener(PetProfileListener listener) {
        this.petProfileListener = listener;
    }

    public PetProfile getPetProfile() {
        return petProfile;
    }

    public void loadPetProfile(PetProfileListener callback) {
        petProfileRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                PetProfile p = parsePetProfile(snapshot);
                if (callback != null) callback.onPetProfileChanged(p);
            }
            @Override public void onCancelled(DatabaseError error) {
                Log.w(TAG, "loadPetProfile cancelled", error.toException());
                if (callback != null) callback.onPetProfileChanged(null);
            }
        });
    }

    public void savePetProfile(PetProfile profile, PetProfileSaveCallback callback) {
        if (profile == null || !profile.isValid()) {
            if (callback != null) callback.onFailed("Invalid pet profile");
            return;
        }
        if (!isSessionValid()) {
            if (callback != null) callback.onFailed("Not signed in");
            return;
        }
        profile.setOwnerUid(uid);
        profile.setUpdatedAt(System.currentTimeMillis());
        petProfile = profile;
        petName    = profile.getName();
        directInFlight++;
        dispatchSyncState(false);
        petProfileRef.setValue(profile)
                .addOnSuccessListener(u -> {
                    directInFlight = Math.max(0, directInFlight - 1);
                    deviceStatusRef.child("petName").setValue(profile.getName());
                    markSynced();
                    if (callback != null) callback.onSaved();
                })
                .addOnFailureListener(e -> {
                    directInFlight = Math.max(0, directInFlight - 1);
                    Log.e(TAG, "Failed to save pet profile", e);
                    dispatchSyncState(false);
                    if (callback != null) callback.onFailed(e.getMessage());
                });
        if (petProfileListener != null) petProfileListener.onPetProfileChanged(profile);
        onNewData();
    }

    public void updatePetProfile(PetProfile profile, PetProfileSaveCallback callback) {
        savePetProfile(profile, callback);
    }

    private PetProfile parsePetProfile(DataSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return null;
        try {
            PetProfile p = snapshot.getValue(PetProfile.class);
            return (p != null && p.isValid() && p.isOwnedBy(uid)) ? p : null;
        } catch (Exception e) {
            Log.w(TAG, "Could not parse pet profile", e);
            return null;
        }
    }

    private void attachPetProfileListener() {
        if (petProfileValueListener != null) return;
        petProfileValueListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                petProfile = parsePetProfile(snapshot);
                if (petProfile != null) petName = petProfile.getName();
                if (petProfileListener != null) petProfileListener.onPetProfileChanged(petProfile);
                onNewData();
            }
            @Override public void onCancelled(DatabaseError error) {
                Log.w(TAG, "Pet profile listener cancelled", error.toException());
            }
        };
        petProfileRef.addValueEventListener(petProfileValueListener);
    }

    public float getRecommendedPortion() {
        return recommendedPortion;
    }

    public void updateRecommendedPortion(float grams) {
        recommendedPortion = grams;
        deviceStatusRef.child("recommendedPortion").setValue(grams);
    }

    public void pushRemoteFeedConfig(float portionGrams) {
        pushRemoteFeedConfig(portionGrams, null);
    }

    public void pushRemoteFeedConfig(float portionGrams, AckCallback cb) {
        try {
            JSONObject p = new JSONObject();
            p.put("portionGrams", portionGrams);
            enqueue(newAction(ACT_REMOTE_CONFIG, p, 0L), cb, "remote_config");
        } catch (Exception e) {
            Log.e(TAG, "pushRemoteFeedConfig enqueue failed", e);
        }
    }

    public void pushRemoteSchedule(List<FeedingSchedule> schedules) {
        enqueueRemoteSchedule(new ArrayList<>(schedules), null);
    }

    public void sendRemoteManualFeedCommand(float portionGrams) {
        sendRemoteManualFeedCommand(portionGrams, "manual_" + System.currentTimeMillis(), null);
    }

    public void sendRemoteManualFeedCommand(float portionGrams, String actionId, AckCallback cb) {
        try {
            JSONObject p = new JSONObject();
            p.put("portionGrams", portionGrams);
            PendingAction a = newAction(ACT_REMOTE_COMMAND, p, 0L);
            a.id = actionId;
            enqueue(a, cb, null);
        } catch (Exception e) {
            Log.e(TAG, "manual feed enqueue failed", e);
        }
    }

    public static final long TRAY_COMMAND_EXPIRY_MS = 30_000L;

    /** Reports remote_command.status transitions (accepted / completed / rejected / expired) for one command id. */
    public interface CommandStatusListener {
        void onStatus(String status, String error);
    }

    private ValueEventListener commandWatchListener;

    /** Watches remote_command for the given command id only; replaces any previous watch. */
    public void watchRemoteCommand(final String commandId, final CommandStatusListener l) {
        stopWatchingRemoteCommand();
        if (deviceStatusRef == null || commandId == null || l == null) return;
        commandWatchListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String id = snap.child("commandId").getValue(String.class);
                if (!commandId.equals(id)) return;
                String status = snap.child("status").getValue(String.class);
                if (status == null) return;
                l.onStatus(status, snap.child("error").getValue(String.class));
            }
            @Override public void onCancelled(DatabaseError error) {
                Log.w(TAG, "remote_command watch cancelled", error.toException());
            }
        };
        deviceStatusRef.child("remote_command").addValueEventListener(commandWatchListener);
    }

    public void stopWatchingRemoteCommand() {
        if (commandWatchListener != null && deviceStatusRef != null) {
            deviceStatusRef.child("remote_command").removeEventListener(commandWatchListener);
        }
        commandWatchListener = null;
    }

    /** Sends a one-shot "open_tray" / "close_tray" command through Firebase. Not queued offline:
     *  a stale tray command must never run later. The device acknowledges by moving
     *  remote_command.status through accepted -> completed (or rejected + error).
     *  "open_tray" runs a full open -> hold -> automatic close cycle on the device;
     *  "close_tray" ends the open/hold phase early and returns the tray. */
    public void sendRemoteTrayCommand(boolean open, String actionId, AckCallback cb) {
        if (deviceStatusRef == null) {
            if (cb != null) cb.onFailed("not_ready");
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, Object> command = new HashMap<>();
        command.put("commandId", actionId);
        command.put("type", open ? "open_tray" : "close_tray");
        command.put("createdAt", now);
        command.put("expiresAt", now + TRAY_COMMAND_EXPIRY_MS);
        command.put("status", "pending");
        deviceStatusRef.child("remote_command").setValue(command, (err, ref) -> {
            if (cb == null) return;
            if (err != null) cb.onFailed(err.getMessage());
            else cb.onAcknowledged();
        });
    }

    /** Sends a one-shot "dispense_water" command through Firebase (not queued offline, so a stale
     *  command never runs later). The device answers through remote_command.status. */
    public void sendRemoteWaterCommand(float volumeMl, String actionId, AckCallback cb) {
        if (deviceStatusRef == null) {
            if (cb != null) cb.onFailed("not_ready");
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, Object> command = new HashMap<>();
        command.put("commandId", actionId);
        command.put("type", "dispense_water");
        command.put("volumeMl", (double) volumeMl);
        command.put("createdAt", now);
        command.put("expiresAt", now + TRAY_COMMAND_EXPIRY_MS);
        command.put("status", "pending");
        deviceStatusRef.child("remote_command").setValue(command, (err, ref) -> {
            if (cb == null) return;
            if (err != null) cb.onFailed(err.getMessage());
            else cb.onAcknowledged();
        });
    }

    public void recordLocalManualFeed(float portionGrams, String actionId, AckCallback cb) {
        try {
            JSONObject p = new JSONObject();
            p.put("portionGrams", portionGrams);
            PendingAction a = newAction(ACT_MANUAL_LOG, p, 0L);
            a.id = actionId;
            enqueue(a, cb, null);
        } catch (Exception e) {
            Log.e(TAG, "manual feed log enqueue failed", e);
        }
    }

    public void triggerManualFeed(float portionGrams) {
        recordLocalManualFeed(portionGrams, "manual_" + System.currentTimeMillis(), null);
    }

    private void seedMockFeedingRecordsIfEmpty() {
        metaRef.child("mock_seeded").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot metaSnap) {
                Boolean alreadySeeded = metaSnap.getValue(Boolean.class);
                if (Boolean.TRUE.equals(alreadySeeded)) {
                    Log.d(TAG, "Mock data already seeded — skipping.");
                    return;
                }
                recordsRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot recordsSnap) {
                        if (recordsSnap.exists() && recordsSnap.getChildrenCount() > 5) {
                            Log.d(TAG, "Firebase already has records — skipping mock seed.");
                            metaRef.child("mock_seeded").setValue(true);
                            return;
                        }
                        writeMockRecords();
                    }
                    @Override public void onCancelled(DatabaseError error) {
                        Log.w(TAG, "records check cancelled", error.toException());
                    }
                });
            }
            @Override public void onCancelled(DatabaseError error) {
                Log.w(TAG, "meta check cancelled", error.toException());
            }
        });
    }

    private void writeMockRecords() {
        Log.d(TAG, "Seeding mock feeding records to Firebase…");
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat dateFmt = new SimpleDateFormat("MM/dd", Locale.US);
        int written = 0;

        for (int dayOffset = MOCK_DAYS - 1; dayOffset >= 0; dayOffset--) {
            cal.setTimeInMillis(System.currentTimeMillis());
            cal.add(Calendar.DAY_OF_YEAR, -dayOffset);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            String dateStr = dateFmt.format(cal.getTime());

            for (int s = 0; s < MOCK_SLOTS.length; s++) {
                int hour   = MOCK_SLOTS[s][0];
                int minute = MOCK_SLOTS[s][1];
                float target = MOCK_SLOTS[s][2];

                Calendar slotCal = (Calendar) cal.clone();
                slotCal.set(Calendar.HOUR_OF_DAY, hour);
                slotCal.set(Calendar.MINUTE, minute);
                long scheduledMs = slotCal.getTimeInMillis();

                if (scheduledMs > System.currentTimeMillis()) continue;

                double roll = rng.nextDouble();
                FeedingRecord.Status status;
                float dispensed;
                long  actualMs;
                boolean hasDeviation;

                if (roll < 0.15) {
                    status       = FeedingRecord.Status.MISSED;
                    dispensed    = 0f;
                    actualMs     = 0L;
                    hasDeviation = true;
                } else if (roll < 0.30) {
                    status       = FeedingRecord.Status.ANOMALY;
                    hasDeviation = true;
                    int jitter   = rng.nextInt(15) - 7;
                    actualMs     = scheduledMs + jitter * 60_000L;
                    if (rng.nextBoolean()) {
                        dispensed = target * (0.40f + rng.nextFloat() * 0.30f);
                    } else {
                        dispensed = target * (1.30f + rng.nextFloat() * 0.40f);
                    }
                    dispensed = Math.round(dispensed * 10f) / 10f;
                } else {
                    status       = FeedingRecord.Status.COMPLETED;
                    hasDeviation = false;
                    int jitter   = rng.nextInt(25) - 12;
                    actualMs     = scheduledMs + jitter * 60_000L;
                    float variance = 0.92f + rng.nextFloat() * 0.16f;
                    dispensed = Math.round(target * variance * 10f) / 10f;
                }

                String id = String.format(Locale.US, "mock_%s_%02d%02d",
                        dateStr.replace("/", ""), hour, minute);

                FeedingRecord record = new FeedingRecord(
                        id, scheduledMs, actualMs,
                        target, dispensed,
                        Math.max(0f, dispensed * (0.75f + rng.nextFloat() * 0.2f)),
                        Math.max(0f, (target * MOCK_SLOTS.length) - dispensed),
                        status, hasDeviation, dateStr
                );
                record.setScheduledTimeDisplay(
                        String.format(Locale.US, "%02d:%02d", hour, minute));
                record.setActualTimeDisplay(
                        actualMs > 0 ? epochToTime(actualMs) : "--:--");

                saveFeedingRecord(record);
                written++;
            }
        }

        Log.d(TAG, "Mock seed complete — " + written + " records written.");
        metaRef.child("mock_seeded").setValue(true);
        metaRef.child("seed_timestamp").setValue(ServerValue.TIMESTAMP);
    }

    private void seedDeviceStatusIfEmpty() {
        deviceStatusRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.hasChild("petName")) {
                    deviceStatusRef.child("petName").setValue("Buddy");
                }
            }
            @Override public void onCancelled(DatabaseError error) {
                Log.w(TAG, "device_status seed cancelled", error.toException());
            }
        });
    }

    private void attachRecordsListener() {
        recordsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                List<FeedingRecord> records = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    FeedingRecord r = child.getValue(FeedingRecord.class);
                    if (r != null) {
                        r.setScheduledTimeDisplay(epochToTime(r.getScheduledTime()));
                        r.setActualTimeDisplay(
                                r.getActualTime() > 0 ? epochToTime(r.getActualTime()) : "--:--");
                        records.add(r);
                    }
                }
                Collections.sort(records,
                        (a, b) -> Long.compare(b.getScheduledTime(), a.getScheduledTime()));
                cachedRecords = records;
                noteFeederData(records.isEmpty() ? null
                        : records.get(0).getFeedingId() + "@" + records.size());
                onNewData();
            }
            @Override public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Records listener cancelled", error.toException());
                if (listener != null)
                    listener.onError("Database error: " + error.getMessage());
            }
        };
        recordsQuery = recordsRef.limitToLast(120);
        recordsQuery.addValueEventListener(recordsListener);
    }

    private void attachSchedulesListener() {
        schedulesListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                List<FeedingSchedule> schedules = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    FeedingSchedule s = child.getValue(FeedingSchedule.class);
                    if (s != null) schedules.add(s);
                }
                cachedSchedules = filterPendingDeletedSchedules(schedules);
                onNewData();
            }
            @Override public void onCancelled(DatabaseError error) {
                Log.w(TAG, "Schedules listener cancelled", error.toException());
            }
        };
        schedulesRef.addValueEventListener(schedulesListener);
    }

    private Float parseFloatValue(Object value) {
        if (value instanceof Number) return ((Number) value).floatValue();
        if (value instanceof String) {
            try { return Float.parseFloat((String) value); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private Long parseLongValue(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try { return Long.parseLong((String) value); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private void listenForDeviceStatus() {
        if (deviceStatusListener != null) return;
        deviceStatusListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                readSyncFieldsFromDeviceStatus(snapshot);
                if (snapshot.hasChild("connected")) {
                    Boolean c = snapshot.child("connected").getValue(Boolean.class);
                    deviceConnected = Boolean.TRUE.equals(c);
                }
                if (snapshot.hasChild("petName")) {
                    String name = snapshot.child("petName").getValue(String.class);
                    if (name != null && !name.isEmpty() && petProfile == null) petName = name;
                }
                if (snapshot.hasChild("recommendedPortion")) {
                    Float rp = snapshot.child("recommendedPortion").getValue(Float.class);
                    if (rp != null && rp > 0f) recommendedPortion = rp;
                }
                boolean weightChanged = readLiveWeight(snapshot);
                dispatchLiveFeeding(snapshot);
                evaluateFeederPresence();
                boolean baseChanged = deviceConnected != lastBaseConnected
                        || Float.compare(recommendedPortion, lastBaseRecommended) != 0
                        || (petName == null ? lastBasePetName != null : !petName.equals(lastBasePetName));
                lastBaseConnected = deviceConnected;
                lastBaseRecommended = recommendedPortion;
                lastBasePetName = petName;
                if (baseChanged) onNewData();
                else if (weightChanged && liveWeightListener == null) onNewData();
            }
            @Override public void onCancelled(DatabaseError error) {
                Log.w(TAG, "Device status listener cancelled", error.toException());
            }
        };
        deviceStatusRef.addValueEventListener(deviceStatusListener);
    }

    private boolean readLiveWeight(DataSnapshot snapshot) {
        Boolean available = snapshot.child("currentWeightAvailable").getValue(Boolean.class);
        Float parsedWeight = parseFloatValue(snapshot.child("currentWeight").getValue());
        boolean known = !Boolean.FALSE.equals(available) && parsedWeight != null;
        if (known) {
            deviceWeightKnown = true;
            deviceCurrentWeight = parsedWeight;
            Long parsedAt = parseLongValue(snapshot.child("weightUpdatedAt").getValue());
            deviceWeightAt = parsedAt == null ? 0L : parsedAt;
        } else if (Boolean.FALSE.equals(available) || available != null || !snapshot.hasChild("currentWeight")) {
            deviceWeightKnown = false;
            deviceCurrentWeight = 0f;
            deviceWeightAt = 0L;
        }
        String key = deviceWeightKnown + "|" + deviceCurrentWeight + "|" + deviceWeightAt;
        boolean changed = !key.equals(lastLiveWeightKey);
        lastLiveWeightKey = key;
        if (changed && liveWeightListener != null) {
            liveWeightListener.onLiveWeight(deviceWeightKnown, deviceCurrentWeight, deviceWeightAt);
        }
        return changed;
    }

    private void dispatchLiveFeeding(DataSnapshot snapshot) {
        if (liveFeedingListener == null) return;
        DataSnapshot lf = snapshot.child("liveFeeding");
        Boolean active = lf.child("active").getValue(Boolean.class);
        Float lfTarget = parseFloatValue(lf.child("target").getValue());
        Float lfConsumed = parseFloatValue(lf.child("consumed").getValue());
        Float lfRemaining = parseFloatValue(lf.child("remaining").getValue());
        Boolean lfRemoved = lf.child("bowlRemoved").getValue(Boolean.class);
        com.smartpetfeeder.model.LiveFeedingStats stats = null;
        if (Boolean.TRUE.equals(active) && lfConsumed != null && lfRemaining != null) {
            stats = new com.smartpetfeeder.model.LiveFeedingStats(
                    true, lf.child("feedingId").getValue(String.class),
                    lfTarget == null ? 0f : lfTarget, lfConsumed, lfRemaining,
                    Boolean.TRUE.equals(lfRemoved));
        }
        String key = stats == null ? "none"
                : stats.getFeedingId() + "|" + stats.getTarget() + "|" + stats.getConsumed()
                + "|" + stats.getRemaining() + "|" + stats.isBowlRemoved();
        if (key.equals(lastLiveFeedKey)) return;
        lastLiveFeedKey = key;
        liveFeedingListener.onLiveFeeding(stats);
    }

    private void evaluateFeederPresence() {
        uiHandler.removeCallbacks(feederPresenceTick);
        long now = System.currentTimeMillis();
        boolean online;
        if (!deviceStatusLoaded || feederLastSeen <= 0L) {
            online = deviceConnected;
        } else {
            long age = now - feederSeenChangedAt;
            online = age < FEEDER_PRESENCE_MS;
        }
        if (online) {
            long remaining = FEEDER_PRESENCE_MS - (now - feederSeenChangedAt);
            if (feederSeenChangedAt > 0L) uiHandler.postDelayed(feederPresenceTick, Math.max(1_000L, remaining + 500L));
        }
        if (!feederStatusDispatched || online != feederOnline) {
            feederOnline = online;
            feederStatusDispatched = true;
            if (feederStatusListener != null) feederStatusListener.onFeederStatus(online, feederLastSeen);
        }
    }

    public void setSyncListener(SyncListener l) {
        this.syncListener = l;
        if (l != null) dispatchSyncState(true);
    }

    public boolean isPhoneOnline() { return cloudOnline; }

    public SyncState getSyncState() { return computeSyncState(); }

    public int getPendingCount() { return queue.size() + directInFlight; }

    public long getLastSyncedAt() { return lastSyncedAt; }

    public long getLatestScheduleVersion() { return Math.max(localScheduleVersion, remoteScheduleVersion); }

    public void markScheduleAppliedLocally(long version) {
        if (version <= 0L) return;
        appliedLocallyVersion = Math.max(appliedLocallyVersion, version);
        clearAppliedScheduleMarks();
        dispatchSyncState(false);
    }

    public java.util.Set<String> getUnappliedScheduleIds() {
        return new java.util.HashSet<>(changedScheduleIds.keySet());
    }

    public void retrySyncNow() {
        consecutiveFailures = 0;
        for (PendingAction a : queue) a.attempts = 0;
        try { database.goOnline(); } catch (Exception ignored) {}
        boolean feederTimedOut = cloudOnline && queue.isEmpty()
                && deviceVersionPending() && deviceTimedOut();
        if (feederTimedOut && !cachedSchedules.isEmpty()) {
            enqueueRemoteSchedule(filterPendingDeletedSchedules(cachedSchedules), null);
        }
        flushQueue();
        dispatchSyncState(false);
    }

    public void resolveStaleManualFeed(String actionId, boolean send) {
        staleAsked = false;
        PendingAction target = null;
        for (PendingAction a : queue) if (a.id.equals(actionId)) { target = a; break; }
        if (target == null) { flushQueue(); return; }
        if (send) {
            target.heldForDecision = false;
            target.createdAt = System.currentTimeMillis();
            persistQueue();
        } else {
            queue.remove(target);
            persistQueue();
            for (AckCallback c : target.callbacks) c.onFailed("discarded");
        }
        flushQueue();
        dispatchSyncState(false);
    }

    public void shutdown() {
        stopListening();
        syncListener = null;
        liveWeightListener = null;
        liveFeedingListener = null;
        feederStatusListener = null;
        petProfileListener = null;
        listener = null;
        uiHandler.removeCallbacksAndMessages(null);
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (Exception ignored) {}
            networkCallback = null;
        }
    }

    private void attachConnectionListener() {
        if (connectedListener != null) return;
        connectedListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                boolean now = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                boolean was = cloudOnline;
                cloudOnline = now;
                if (!now) {
                    everDisconnected = true;
                } else if (!was) {
                    if (everDisconnected) {
                        catchUpUntil = System.currentTimeMillis() + CATCH_UP_WINDOW_MS;
                        catchUpNoticeSent = false;
                    }
                    consecutiveFailures = 0;
                    flushQueue();
                }
                dispatchSyncState(false);
            }
            @Override public void onCancelled(DatabaseError error) {
                Log.w(TAG, ".info/connected cancelled", error.toException());
            }
        };
        connectedRef.addValueEventListener(connectedListener);
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null) return;
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    uiHandler.post(() -> {
                        try { database.goOnline(); } catch (Exception ignored) {}
                        flushQueue();
                    });
                }
            };
            connectivityManager.registerNetworkCallback(req, networkCallback);
        } catch (SecurityException e) {
            Log.w(TAG, "ACCESS_NETWORK_STATE missing; relying on .info/connected only");
            networkCallback = null;
        } catch (Exception e) {
            Log.w(TAG, "Could not register network callback", e);
            networkCallback = null;
        }
    }

    private PendingAction newAction(String type, JSONObject payload, long version) {
        PendingAction a = new PendingAction();
        a.id = UUID.randomUUID().toString();
        a.type = type;
        a.createdAt = System.currentTimeMillis();
        a.version = version;
        a.payload = payload;
        return a;
    }

    private void enqueueScheduleUpsert(FeedingSchedule s, AckCallback cb) {
        try {
            JSONObject p = new JSONObject();
            p.put("scheduleId", s.getScheduleId());
            p.put("timeHour", s.getTimeHour());
            p.put("timeMinute", s.getTimeMinute());
            p.put("portionGrams", s.getPortionGrams());
            p.put("enabled", s.isEnabled());
            p.put("label", s.getLabel() == null ? JSONObject.NULL : s.getLabel());
            JSONArray days = new JSONArray();
            if (s.getRepeatDays() != null) for (String d : s.getRepeatDays()) days.put(d);
            p.put("repeatDays", days);
            long v = nextScheduleVersion();
            markScheduleChanged(s.getScheduleId(), v);
            enqueue(newAction(ACT_SCHEDULE_UPSERT, p, v), cb, "sched:" + s.getScheduleId());
        } catch (Exception e) {
            Log.e(TAG, "enqueueScheduleUpsert failed", e);
        }
    }

    private void enqueueRemoteSchedule(List<FeedingSchedule> schedules, AckCallback cb) {
        try {
            schedules = filterPendingDeletedSchedules(schedules);
            JSONArray arr = new JSONArray();
            for (FeedingSchedule s : schedules) {
                JSONObject p = new JSONObject();
                p.put("scheduleId", s.getScheduleId());
                p.put("timeHour", s.getTimeHour());
                p.put("timeMinute", s.getTimeMinute());
                p.put("portionGrams", s.getPortionGrams());
                p.put("enabled", s.isEnabled());
                p.put("label", s.getLabel() == null ? JSONObject.NULL : s.getLabel());
                JSONArray days = new JSONArray();
                if (s.getRepeatDays() != null) for (String d : s.getRepeatDays()) days.put(d);
                p.put("repeatDays", days);
                arr.put(p);
            }
            JSONObject payload = new JSONObject();
            payload.put("schedules", arr);
            long v = nextScheduleVersion();
            for (FeedingSchedule s : schedules) markScheduleChanged(s.getScheduleId(), v);
            enqueue(newAction(ACT_REMOTE_SCHEDULE, payload, v), cb, "remote_schedule");
        } catch (Exception e) {
            Log.e(TAG, "enqueueRemoteSchedule failed", e);
        }
    }

    private void enqueue(PendingAction a, AckCallback cb, String coalesceKey) {
        if (cb != null) a.callbacks.add(cb);
        for (PendingAction q : queue) {
            if (q.id.equals(a.id)) { if (cb != null) q.callbacks.add(cb); flushQueue(); return; }
        }
        if (coalesceKey != null) {
            for (int i = queue.size() - 1; i >= 0; i--) {
                PendingAction q = queue.get(i);
                if (q.id.equals(inFlightActionId)) continue;
                if (coalesceKey.equals(coalesceKeyOf(q))) {
                    a.callbacks.addAll(q.callbacks);
                    queue.remove(i);
                }
            }
        }
        queue.add(a);
        persistQueue();
        dispatchSyncState(false);
        flushQueue();
    }

    private String coalesceKeyOf(PendingAction a) {
        switch (a.type) {
            case ACT_SCHEDULE_UPSERT:
            case ACT_SCHEDULE_DELETE: return "sched:" + a.payload.optString("scheduleId");
            case ACT_REMOTE_CONFIG:   return "remote_config";
            case ACT_REMOTE_SCHEDULE: return "remote_schedule";
            default: return null;
        }
    }

    private boolean isStale(PendingAction a) {
        return ACT_REMOTE_COMMAND.equals(a.type)
                && System.currentTimeMillis() - a.createdAt > STALE_MANUAL_FEED_MS;
    }

    private void flushQueue() {
        if (!cloudOnline || flushing || !isSessionValid()) return;
        PendingAction next = null;
        for (PendingAction a : queue) {
            if (a.heldForDecision) continue;
            if (isStale(a)) {
                a.heldForDecision = true;
                if (!staleAsked && syncListener != null) {
                    staleAsked = true;
                    syncListener.onStaleManualFeed(a.id,
                            (float) a.payload.optDouble("portionGrams", 0), a.createdAt);
                }
                continue;
            }
            next = a;
            break;
        }
        if (next == null) { dispatchSyncState(false); return; }

        flushing = true;
        inFlightActionId = next.id;
        final PendingAction sending = next;
        execute(sending, (ok, error) -> {
            flushing = false;
            inFlightActionId = null;
            if (ok) {
                if (ACT_SCHEDULE_DELETE.equals(sending.type)) {
                    cachedSchedules = filterPendingDeletedSchedules(cachedSchedules);
                    onNewData();
                }
                queue.remove(sending);
                persistQueue();
                consecutiveFailures = 0;
                if (sending.version > 0) deviceWaitStartedAt = System.currentTimeMillis();
                markSynced();
                for (AckCallback c : sending.callbacks) c.onAcknowledged();
                sending.callbacks.clear();
                dispatchSyncState(false);
                flushQueue();
            } else {
                sending.attempts++;
                consecutiveFailures++;
                persistQueue();
                for (AckCallback c : sending.callbacks) c.onFailed(error);
                sending.callbacks.clear();
                long delay = Math.min(BACKOFF_MAX_MS,
                        BACKOFF_BASE_MS << Math.min(sending.attempts, 5));
                uiHandler.postDelayed(this::flushQueue, delay);
                dispatchSyncState(false);
            }
        });
    }

    private void execute(PendingAction a, StepDone done) {
        try {
            switch (a.type) {
                case ACT_SCHEDULE_UPSERT: {
                    Map<String, Object> up = new HashMap<>();
                    up.put(PATH_SCHEDULES + "/" + a.payload.optString("scheduleId"), scheduleFromJson(a.payload));
                    up.put(PATH_DEVICE_STATUS + "/schedule_version", a.version);
                    rootUpdate(up, done);
                    break;
                }
                case ACT_SCHEDULE_DELETE: {
                    Map<String, Object> up = new HashMap<>();
                    up.put(PATH_SCHEDULES + "/" + a.payload.optString("scheduleId"), null);
                    up.put(PATH_DEVICE_STATUS + "/schedule_version", a.version);
                    rootUpdate(up, done);
                    break;
                }
                case ACT_REMOTE_CONFIG: {
                    Map<String, Object> up = new HashMap<>();
                    up.put(PATH_DEVICE_STATUS + "/remote_config/portionGrams",
                            a.payload.optDouble("portionGrams", 0));
                    up.put(PATH_DEVICE_STATUS + "/remote_config/updatedAt", ServerValue.TIMESTAMP);
                    rootUpdate(up, done);
                    break;
                }
                case ACT_REMOTE_SCHEDULE: {
                    List<FeedingSchedule> list = new ArrayList<>();
                    JSONArray arr = a.payload.optJSONArray("schedules");
                    if (arr != null) for (int i = 0; i < arr.length(); i++)
                        list.add(scheduleFromJson(arr.getJSONObject(i)));
                    Map<String, Object> up = new HashMap<>();
                    up.put(PATH_DEVICE_STATUS + "/remote_config/schedule", list);
                    up.put(PATH_DEVICE_STATUS + "/remote_config/updatedAt", ServerValue.TIMESTAMP);
                    up.put(PATH_DEVICE_STATUS + "/schedule_version", a.version);
                    rootUpdate(up, done);
                    break;
                }
                case ACT_MANUAL_LOG:
                    writeManualRecordAndConfig(a, done);
                    break;
                case ACT_REMOTE_COMMAND:
                    writeManualRecordAndConfig(a, (ok, err) -> {
                        if (!ok) { done.done(false, err); return; }
                        sendCommandOnce(a, done);
                    });
                    break;
                default:
                    Log.w(TAG, "Unknown queued action " + a.type + " — dropping");
                    done.done(true, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "execute failed for " + a.type, e);
            done.done(false, e.getMessage());
        }
    }

    private void rootUpdate(Map<String, Object> updates, StepDone done) {
        rootRef.updateChildren(updates)
                .addOnSuccessListener(v -> done.done(true, null))
                .addOnFailureListener(e -> done.done(false, e.getMessage()));
    }

    private FeedingSchedule scheduleFromJson(JSONObject o) {
        List<String> days = new ArrayList<>();
        JSONArray arr = o.optJSONArray("repeatDays");
        if (arr != null) for (int i = 0; i < arr.length(); i++) days.add(arr.optString(i));
        String label = o.isNull("label") ? null : o.optString("label");
        return new FeedingSchedule(o.optString("scheduleId"), o.optInt("timeHour"),
                o.optInt("timeMinute"), (float) o.optDouble("portionGrams"),
                o.optBoolean("enabled"), label, days);
    }

    private void writeManualRecordAndConfig(PendingAction a, StepDone done) {
        final float grams = (float) a.payload.optDouble("portionGrams", 0);
        final long  when  = a.createdAt;
        FeedingRecord manual = new FeedingRecord(
                a.id, when, when, grams, grams, 0f, 0f,
                FeedingRecord.Status.COMPLETED, false,
                new SimpleDateFormat("MM/dd", Locale.US).format(new Date(when)));
        manual.setScheduledTimeDisplay(epochToTime(when));
        manual.setActualTimeDisplay(epochToTime(when));

        recordsRef.child(a.id).runTransaction(new Transaction.Handler() {
            @Override public Transaction.Result doTransaction(MutableData d) {
                if (d.getValue() != null) return Transaction.abort();
                d.setValue(manual);
                return Transaction.success(d);
            }
            @Override public void onComplete(DatabaseError err, boolean committed, DataSnapshot snap) {
                if (err != null) { done.done(false, err.getMessage()); return; }
                Map<String, Object> up = new HashMap<>();
                up.put(PATH_DEVICE_STATUS + "/remote_config/portionGrams", (double) grams);
                up.put(PATH_DEVICE_STATUS + "/remote_config/updatedAt", ServerValue.TIMESTAMP);
                rootUpdate(up, done);
            }
        });
    }

    private void sendCommandOnce(PendingAction a, StepDone done) {
        final float grams = (float) a.payload.optDouble("portionGrams", 0);
        final long  now   = a.createdAt;
        Map<String, Object> command = new HashMap<>();
        command.put("commandId", a.id);
        command.put("type", "manual_feed");
        command.put("portionGrams", (double) grams);
        command.put("createdAt", now);
        command.put("expiresAt", now + STALE_MANUAL_FEED_MS);
        command.put("status", "pending");

        deviceStatusRef.child("remote_command").runTransaction(new Transaction.Handler() {
            @Override public Transaction.Result doTransaction(MutableData d) {
                Object cur = d.getValue();
                if (cur instanceof Map && a.id.equals(((Map<?, ?>) cur).get("commandId")))
                    return Transaction.abort();
                d.setValue(command);
                return Transaction.success(d);
            }
            @Override public void onComplete(DatabaseError err, boolean committed, DataSnapshot snap) {
                if (err != null) done.done(false, err.getMessage());
                else done.done(true, null);
            }
        });
    }

    private void loadSyncPrefs() {
        if (syncPrefs == null) return;
        lastSyncedAt = syncPrefs.getLong(PREF_LAST_SYNCED, 0L);
        localScheduleVersion = syncPrefs.getLong(PREF_LOCAL_VERSION, 0L);
        try {
            JSONArray arr = new JSONArray(syncPrefs.getString(PREF_QUEUE, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                PendingAction a = PendingAction.fromJson(arr.getJSONObject(i));
                if (a.id != null && !a.id.isEmpty()) queue.add(a);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read sync queue", e);
        }
        try {
            JSONObject o = new JSONObject(syncPrefs.getString(PREF_CHANGED_IDS, "{}"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); changedScheduleIds.put(k, o.optLong(k)); }
        } catch (Exception ignored) {}
    }

    private void persistQueue() {
        if (syncPrefs == null) return;
        JSONArray arr = new JSONArray();
        for (PendingAction a : queue) { JSONObject o = a.toJson(); if (o != null) arr.put(o); }
        syncPrefs.edit().putString(PREF_QUEUE, arr.toString()).apply();
    }

    private void persistChangedIds() {
        if (syncPrefs == null) return;
        JSONObject o = new JSONObject();
        try { for (Map.Entry<String, Long> e : changedScheduleIds.entrySet()) o.put(e.getKey(), e.getValue()); }
        catch (Exception ignored) {}
        syncPrefs.edit().putString(PREF_CHANGED_IDS, o.toString()).apply();
    }

    private java.util.Set<String> pendingDeletedScheduleIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (PendingAction a : queue) {
            if (ACT_SCHEDULE_DELETE.equals(a.type)) {
                String id = a.payload.optString("scheduleId");
                if (id != null && !id.isEmpty()) ids.add(id);
            }
        }
        return ids;
    }

    private List<FeedingSchedule> filterPendingDeletedSchedules(List<FeedingSchedule> schedules) {
        java.util.Set<String> deleted = pendingDeletedScheduleIds();
        if (deleted.isEmpty()) return new ArrayList<>(schedules);
        List<FeedingSchedule> filtered = new ArrayList<>();
        for (FeedingSchedule s : schedules) {
            if (s == null) continue;
            String id = s.getScheduleId();
            if (id == null || !deleted.contains(id)) filtered.add(s);
        }
        return filtered;
    }

    private long nextScheduleVersion() {
        long v = Math.max(System.currentTimeMillis(), Math.max(localScheduleVersion, remoteScheduleVersion) + 1);
        localScheduleVersion = v;
        if (syncPrefs != null) syncPrefs.edit().putLong(PREF_LOCAL_VERSION, v).apply();
        rememberOwnVersion(v);
        return v;
    }

    private void markScheduleChanged(String id, long version) {
        if (id == null) return;
        changedScheduleIds.put(id, version);
        persistChangedIds();
    }

    private void clearAppliedScheduleMarks() {
        if (changedScheduleIds.isEmpty()) return;
        boolean applied = remoteScheduleVersion > 0
                && (appliedScheduleVersion == remoteScheduleVersion
                    || appliedLocallyVersion >= remoteScheduleVersion);
        if (applied && queue.isEmpty()) {
            changedScheduleIds.clear();
            persistChangedIds();
        }
    }

    private void rememberOwnVersion(long v) {
        if (syncPrefs == null) return;
        String cur = syncPrefs.getString(PREF_OWN_VERSIONS, "");
        String[] parts = cur.isEmpty() ? new String[0] : cur.split(",");
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, parts.length - 19);
        for (int i = start; i < parts.length; i++) sb.append(parts[i]).append(',');
        sb.append(v);
        syncPrefs.edit().putString(PREF_OWN_VERSIONS, sb.toString()).apply();
    }

    private boolean isOwnVersion(long v) {
        if (v == localScheduleVersion) return true;
        if (syncPrefs == null) return false;
        String cur = syncPrefs.getString(PREF_OWN_VERSIONS, "");
        for (String p : cur.split(",")) if (p.equals(String.valueOf(v))) return true;
        return false;
    }

    private void readSyncFieldsFromDeviceStatus(DataSnapshot snapshot) {
        Long ver     = parseLongValue(snapshot.child("schedule_version").getValue());
        Long applied = parseLongValue(snapshot.child("applied_schedule_version").getValue());
        Long seen    = parseLongValue(snapshot.child("lastSeen").getValue());

        long newRemote  = ver == null ? 0L : ver;
        long newApplied = applied == null ? 0L : applied;
        boolean versionChanged = newRemote != remoteScheduleVersion;

        if (versionChanged && newRemote > 0) {
            deviceWaitStartedAt = System.currentTimeMillis();
            long baseline = syncPrefs == null ? 0L : syncPrefs.getLong(PREF_SEEN_VERSION, 0L);
            boolean baselineKnown = deviceStatusLoaded || baseline > 0;
            if (baselineKnown && newRemote != baseline && !isOwnVersion(newRemote)) {
                notifyNotice(NOTICE_FROM_OTHER_DEVICE);
            }
            if (syncPrefs != null) syncPrefs.edit().putLong(PREF_SEEN_VERSION, newRemote).apply();
        }
        if (!deviceStatusLoaded && newRemote > 0 && newRemote != newApplied
                && deviceWaitStartedAt == 0L) {
            deviceWaitStartedAt = System.currentTimeMillis();
        }
        remoteScheduleVersion = newRemote;
        if (newApplied != appliedScheduleVersion && catchUpActive()) {
            noteFeederChangeDuringCatchUp();
        }
        appliedScheduleVersion = newApplied;
        if (seen != null && seen != feederLastSeen) {
            if (feederLastSeen != 0L && catchUpActive()) noteFeederChangeDuringCatchUp();
            boolean firstSnapshot = feederLastSeen == 0L;
            feederLastSeen = seen;
            long nowMs = System.currentTimeMillis();
            feederSeenChangedAt = firstSnapshot && nowMs - seen > FEEDER_PRESENCE_MS ? seen : nowMs;
        }
        deviceStatusLoaded = true;
        clearAppliedScheduleMarks();
        dispatchSyncState(false);
    }

    private boolean catchUpActive() {
        return !catchUpNoticeSent && System.currentTimeMillis() <= catchUpUntil;
    }

    private void noteFeederChangeDuringCatchUp() {
        catchUpNoticeSent = true;
        notifyNotice(NOTICE_FROM_FEEDER);
    }

    private void noteFeederData(String key) {
        boolean changed = lastRecordKey != null && key != null && !key.equals(lastRecordKey);
        lastRecordKey = key;
        if (changed && catchUpActive()) noteFeederChangeDuringCatchUp();
    }

    private void notifyNotice(int notice) {
        if (syncListener != null) syncListener.onSyncNotice(notice);
    }

    private void markSynced() {
        if (!cloudOnline) return;
        lastSyncedAt = System.currentTimeMillis();
        if (syncPrefs != null) syncPrefs.edit().putLong(PREF_LAST_SYNCED, lastSyncedAt).apply();
    }

    private boolean deviceVersionPending() {
        return remoteScheduleVersion > 0
                && appliedScheduleVersion != remoteScheduleVersion
                && appliedLocallyVersion < remoteScheduleVersion;
    }

    private boolean deviceTimedOut() {
        return deviceWaitStartedAt > 0
                && System.currentTimeMillis() - deviceWaitStartedAt > DEVICE_ACK_TIMEOUT_MS;
    }

    private SyncState computeSyncState() {
        if (!cloudOnline) return SyncState.OFFLINE;
        if (consecutiveFailures >= FAIL_THRESHOLD) return SyncState.FAILED;
        if (queue.size() + directInFlight > 0) return SyncState.PENDING_CLOUD;
        if (deviceVersionPending()) {
            return deviceTimedOut() ? SyncState.FAILED : SyncState.PENDING_DEVICE;
        }
        return SyncState.SYNCED;
    }

    private final Runnable deviceTimeoutTick = () -> dispatchSyncState(false);

    private void dispatchSyncState(boolean force) {
        SyncState st = computeSyncState();
        int pending = getPendingCount();
        uiHandler.removeCallbacks(deviceTimeoutTick);
        if (st == SyncState.PENDING_DEVICE && deviceWaitStartedAt > 0) {
            long remaining = DEVICE_ACK_TIMEOUT_MS - (System.currentTimeMillis() - deviceWaitStartedAt);
            uiHandler.postDelayed(deviceTimeoutTick, Math.max(500L, remaining + 500L));
        }
        if (!force && st == lastDispatchedState && pending == lastDispatchedPending) return;
        lastDispatchedState = st;
        lastDispatchedPending = pending;
        if (syncListener != null) syncListener.onSyncStateChanged(st, pending, lastSyncedAt);
    }

    private void onNewData() {
        if (cloudOnline && directInFlight == 0 && queue.isEmpty()) markSynced();
        if (listener == null) return;
        try {
            List<AnomalyAlert> alerts = anomalyDetector.detectAnomalies(
                    cachedRecords, cachedSchedules, recordsRef);

            SummaryStats stats      = buildSummaryStats(cachedRecords, alerts);
            List<DashboardData.FoodTrendData>      trendData = buildTrendData(cachedRecords);
            List<DashboardData.FeedingFrequencyData> freqData = buildFreqData(cachedRecords);

            DashboardData data = new DashboardData();
            data.setPetName(petName);
            data.setLastUpdated(System.currentTimeMillis());
            data.setWeightKnown(deviceWeightKnown);
            data.setCurrentWeight(deviceCurrentWeight);
            data.setWeightUpdatedAt(deviceWeightAt);
            data.setFeedingHistory(cachedRecords);
            data.setAnomalyAlerts(alerts);
            data.setSummaryStats(stats);
            data.setFoodTrendData(trendData);
            data.setFrequencyData(freqData);
            data.setSchedules(new ArrayList<>(cachedSchedules));

            listener.onDataUpdated(data);
        } catch (Exception e) {
            Log.e(TAG, "onNewData error", e);
            listener.onError("Data processing error: " + e.getMessage());
        }
    }

    private SummaryStats buildSummaryStats(List<FeedingRecord> records,
                                           List<AnomalyAlert> alerts) {
        SummaryStats stats  = new SummaryStats();
        Calendar today      = Calendar.getInstance();
        int completed = 0, missed = 0, anomaly = 0, inProgress = 0;
        long nowMs = System.currentTimeMillis();
        double totalDispensed = 0;
        String lastTime = "--:--";

        for (FeedingRecord r : records) {
            Calendar rc = Calendar.getInstance();
            rc.setTimeInMillis(r.getScheduledTime());
            boolean isToday = rc.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                    && rc.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
            if (!isToday) continue;

            switch (r.getStatusEnum()) {
                case COMPLETED:
                    if (r.isInProgress(nowMs)) inProgress++; else completed++;
                    totalDispensed += r.getDispensedWeight();
                    break;
                case MISSED:    missed++;    break;
                case ANOMALY:   anomaly++;   totalDispensed += r.getDispensedWeight(); break;
            }
            if (r.getStatusEnum() != FeedingRecord.Status.MISSED && r.getActualTime() > 0) {
                lastTime = epochToTime(r.getActualTime());
            }
        }

        stats.setTodayFeedingCount(completed + missed + anomaly + inProgress);
        stats.setCompletedFeedings(completed);
        stats.setMissedFeedings(missed);
        stats.setAnomalyFeedings(anomaly);
        stats.setTotalFoodDispensedGrams(Math.round(totalDispensed * 10.0) / 10.0);
        stats.setLastFeedingTime(lastTime);
        stats.setSystemStatus(anomalyDetector.determineSystemStatus(alerts));
        if (petProfile == null) {
            recommendedPortion = computeAdaptiveRecommendation(records);
            deviceStatusRef.child("recommendedPortion").setValue(recommendedPortion);
        }
        return stats;
    }

    private float computeAdaptiveRecommendation(List<FeedingRecord> records) {
        if (records == null || records.isEmpty()) return recommendedPortion;
        int count = 0;
        float totalRatio = 0f;
        for (FeedingRecord r : records) {
            if (r.getScheduledPortion() <= 0 || r.getDisplayConsumedWeight() <= 0) continue;
            totalRatio += (r.getDisplayConsumedWeight() / r.getScheduledPortion());
            count++;
            if (count >= 12) break;
        }
        if (count == 0) return recommendedPortion;
        float avgRatio = totalRatio / count;
        float next = recommendedPortion * (0.9f + (avgRatio * 0.1f));
        return Math.max(40f, Math.min(160f, Math.round(next)));
    }

    private List<DashboardData.FoodTrendData> buildTrendData(List<FeedingRecord> records) {
        List<DashboardData.FoodTrendData> trend = new ArrayList<>();
        int count = Math.min(records.size(), 8);
        for (int i = count - 1; i >= 0; i--) {
            FeedingRecord r = records.get(i);
            trend.add(new DashboardData.FoodTrendData(
                    r.getScheduledTimeDisplay() != null ? r.getScheduledTimeDisplay() : "--:--",
                    r.getScheduledPortion(),
                    r.getDispensedWeight()
            ));
        }
        return trend;
    }

    private List<DashboardData.FeedingFrequencyData> buildFreqData(List<FeedingRecord> records) {
        java.util.LinkedHashMap<String, int[]> map = new java.util.LinkedHashMap<>();
        for (FeedingRecord r : records) {
            String date = r.getDate() != null ? r.getDate() : "?";
            if (!map.containsKey(date)) map.put(date, new int[]{0, 0});
            map.get(date)[0]++;
            if (r.getStatusEnum() == FeedingRecord.Status.COMPLETED
                    && !r.isInProgress(System.currentTimeMillis())) map.get(date)[1]++;
        }
        List<DashboardData.FeedingFrequencyData> freq = new ArrayList<>();
        int n = 0;
        for (Map.Entry<String, int[]> e : map.entrySet()) {
            if (n++ >= 7) break;
            freq.add(new DashboardData.FeedingFrequencyData(
                    e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        return freq;
    }

    private static String epochToTime(long epochMs) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(epochMs));
    }
}
