package com.smartpetfeeder.firebase;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.smartpetfeeder.MainActivity;
import com.smartpetfeeder.R;

import java.util.Map;

public class PetFeederMessagingService extends FirebaseMessagingService {

    private static final String TAG = "PetFeederFCM";

    public static final String CHANNEL_ANOMALY   = "pet_feeder_anomaly";
    public static final String CHANNEL_REMINDER  = "pet_feeder_reminder";
    public static final String CHANNEL_DEFAULT   = "pet_feeder_default";
    public static final String CHANNEL_FEEDING_PROGRESS = "pet_feeder_feeding_progress";
    private static final int   FEEDING_PROGRESS_NOTIF_ID = 2001;

    private static final String KEY_TYPE      = "type";
    private static final String KEY_TITLE     = "title";
    private static final String KEY_MESSAGE   = "message";
    private static final String KEY_RECORD_ID = "recordId";

    private static final String TYPE_MISSED_FEEDING   = "MISSED_FEEDING";
    private static final String TYPE_PORTION_ANOMALY  = "PORTION_ANOMALY";
    private static final String TYPE_FREQUENCY_ALERT  = "FREQUENCY_ALERT";

    private static int notificationIdCounter = 1000;

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.d(TAG, "FCM token refreshed: " + token.substring(0, 12) + "...");

        String uid = FirebaseService.currentUid();
        if (uid == null) return;
        FirebaseService.userRef(FirebaseDatabase.getInstance(
                        "https://smart-ped-feeder-default-rtdb.firebaseio.com"), uid)
                .child("device_status")
                .child("fcmToken")
                .setValue(token)
                .addOnSuccessListener(u -> Log.d(TAG, "FCM token saved to Firebase"))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to save FCM token", e));
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Log.d(TAG, "Message from: " + remoteMessage.getFrom());

        createNotificationChannels();

        if (!remoteMessage.getData().isEmpty()) {
            Map<String, String> data = remoteMessage.getData();
            handleDataMessage(data);
            return;
        }

        if (remoteMessage.getNotification() != null) {
            RemoteMessage.Notification n = remoteMessage.getNotification();
            showNotification(
                    n.getTitle() != null ? n.getTitle() : getString(R.string.app_name),
                    n.getBody()  != null ? n.getBody()  : "",
                    CHANNEL_DEFAULT,
                    R.drawable.ic_paw
            );
        }
    }

    private void handleDataMessage(Map<String, String> data) {
        String type     = data.getOrDefault(KEY_TYPE,    "");
        String title    = data.getOrDefault(KEY_TITLE,   getString(R.string.app_name));
        String message  = data.getOrDefault(KEY_MESSAGE, "");

        String channel;
        int    icon;

        switch (type) {
            case TYPE_MISSED_FEEDING:
                channel = CHANNEL_ANOMALY;
                icon    = R.drawable.ic_warning;
                if (title.isEmpty()) title   = getString(R.string.fcm_missed_title);
                if (message.isEmpty()) message = getString(R.string.fcm_missed_body);
                break;

            case TYPE_PORTION_ANOMALY:
                channel = CHANNEL_ANOMALY;
                icon    = R.drawable.ic_scale;
                if (title.isEmpty()) title   = getString(R.string.fcm_portion_title);
                if (message.isEmpty()) message = getString(R.string.fcm_portion_body);
                break;

            case TYPE_FREQUENCY_ALERT:
                channel = CHANNEL_REMINDER;
                icon    = R.drawable.ic_clock;
                if (title.isEmpty()) title   = getString(R.string.fcm_frequency_title);
                if (message.isEmpty()) message = getString(R.string.fcm_frequency_body);
                break;

            default:
                channel = CHANNEL_DEFAULT;
                icon    = R.drawable.ic_paw;
                break;
        }

        showNotification(title, message, channel, icon);
    }

    private void showNotification(String title, String message, String channelId, int iconRes) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT
                : PendingIntent.FLAG_ONE_SHOT;

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, notificationIdCounter, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(iconRes)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent);

        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(notificationIdCounter++, builder.build());
        }
    }

    public static void createNotificationChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        NotificationChannel anomalyChannel = new NotificationChannel(
                CHANNEL_ANOMALY,
                "Anomaly Alerts",
                NotificationManager.IMPORTANCE_HIGH);
        anomalyChannel.setDescription("Alerts for missed feedings and portion deviations");
        anomalyChannel.enableVibration(true);

        NotificationChannel reminderChannel = new NotificationChannel(
                CHANNEL_REMINDER,
                "Feeding Reminders",
                NotificationManager.IMPORTANCE_DEFAULT);
        reminderChannel.setDescription("Feeding schedule reminders");

        NotificationChannel defaultChannel = new NotificationChannel(
                CHANNEL_DEFAULT,
                "General",
                NotificationManager.IMPORTANCE_DEFAULT);
        defaultChannel.setDescription("General app notifications");

        NotificationChannel progressChannel = new NotificationChannel(
                CHANNEL_FEEDING_PROGRESS,
                "Feeding Progress",
                NotificationManager.IMPORTANCE_LOW);
        progressChannel.setDescription("Consumed and remaining food of a finished feeding");
        manager.createNotificationChannel(progressChannel);

        manager.createNotificationChannel(anomalyChannel);
        manager.createNotificationChannel(reminderChannel);
        manager.createNotificationChannel(defaultChannel);
    }

    private static NotificationManager progressManager(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && androidx.core.content.ContextCompat.checkSelfPermission(context,
                        android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void postFeedingNotification(Context context, String recordId, String title,
                                                String message, boolean ongoing) {
        NotificationManager manager = progressManager(context);
        if (manager == null) return;
        createNotificationChannels(context);
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(context, FEEDING_PROGRESS_NOTIF_ID, intent, flags);
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, CHANNEL_FEEDING_PROGRESS)
                        .setSmallIcon(R.drawable.ic_paw)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .setOnlyAlertOnce(true)
                        .setOngoing(ongoing)
                        .setAutoCancel(!ongoing)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setContentIntent(pi);
        manager.notify(recordId, FEEDING_PROGRESS_NOTIF_ID, builder.build());
    }

    public static void showFeedingFinal(Context context, String recordId, String title, String message) {
        postFeedingNotification(context, recordId, title, message, false);
    }

    public static void cancelFeedingProgress(Context context, String recordId) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(recordId, FEEDING_PROGRESS_NOTIF_ID);
    }

    private void createNotificationChannels() {
        createNotificationChannels(this);
    }
}
