package com.simon.tinnitusrelief;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class TherapyPlaybackService extends Service {
    private static final String TAG = "TherapyPlaybackService";
    public static final String ACTION_START = "com.simon.tinnitusrelief.action.START";
    public static final String ACTION_STOP = "com.simon.tinnitusrelief.action.STOP";
    public static final String ACTION_UPDATE = "com.simon.tinnitusrelief.action.UPDATE";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_SUBTITLE = "extra_subtitle";

    private static final String CHANNEL_ID = "tinnitus_therapy_playback_channel";
    private static final int NOTIFICATION_ID = 2002;

    private NotificationManager notificationManager;
    private PowerManager.WakeLock serviceWakeLock;
    private boolean isForeground = false;
    private String currentTitle = "Notched Sound Therapy";
    private String currentSubtitle = "Playing in background";

    public static void startPlaybackService(Context context, String title, String subtitle) {
        try {
            Intent intent = new Intent(context, TherapyPlaybackService.class);
            intent.setAction(ACTION_START);
            if (title != null) intent.putExtra(EXTRA_TITLE, title);
            if (subtitle != null) intent.putExtra(EXTRA_SUBTITLE, subtitle);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start TherapyPlaybackService", e);
        }
    }

    public static void updatePlaybackService(Context context, String title, String subtitle) {
        try {
            Intent intent = new Intent(context, TherapyPlaybackService.class);
            intent.setAction(ACTION_UPDATE);
            if (title != null) intent.putExtra(EXTRA_TITLE, title);
            if (subtitle != null) intent.putExtra(EXTRA_SUBTITLE, subtitle);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to update TherapyPlaybackService", e);
        }
    }

    public static void stopPlaybackService(Context context) {
        try {
            Intent intent = new Intent(context, TherapyPlaybackService.class);
            intent.setAction(ACTION_STOP);
            context.startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop TherapyPlaybackService", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopPlayback();
            return START_NOT_STICKY;
        }

        boolean metadataChanged = false;
        if (intent.hasExtra(EXTRA_TITLE)) {
            String t = intent.getStringExtra(EXTRA_TITLE);
            if (t != null && !t.trim().isEmpty() && !t.trim().equals(currentTitle)) {
                currentTitle = t.trim();
                metadataChanged = true;
            }
        }
        if (intent.hasExtra(EXTRA_SUBTITLE)) {
            String s = intent.getStringExtra(EXTRA_SUBTITLE);
            if (s != null && !s.trim().isEmpty() && !s.trim().equals(currentSubtitle)) {
                currentSubtitle = s.trim();
                metadataChanged = true;
            }
        }

        if (isForeground && ACTION_UPDATE.equals(action)) {
            if (metadataChanged && notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, buildPlaybackNotification(currentTitle, currentSubtitle));
            }
            return START_NOT_STICKY;
        }

        Notification notification = buildPlaybackNotification(currentTitle, currentSubtitle);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            isForeground = true;
        } catch (Exception e) {
            Log.e(TAG, "Error starting foreground service for mediaPlayback", e);
        }

        acquireServiceWakeLock();
        return START_NOT_STICKY;
    }

    private void stopPlayback() {
        isForeground = false;
        releaseServiceWakeLock();
        stopForeground(true);
        stopSelf();
    }

    private Notification buildPlaybackNotification(String title, String subtitle) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(contentPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Sound Therapy Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains continuous audio playback when the screen is turned off");
            channel.enableVibration(false);
            channel.setSound(null, null);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void acquireServiceWakeLock() {
        if (serviceWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                serviceWakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "TinnitusRelief:TherapyPlaybackServiceWakeLock"
                );
                serviceWakeLock.setReferenceCounted(false);
            }
        }
        if (serviceWakeLock != null && !serviceWakeLock.isHeld()) {
            serviceWakeLock.acquire(12 * 60 * 60 * 1000L); // Safety timeout 12h
        }
    }

    private void releaseServiceWakeLock() {
        if (serviceWakeLock != null && serviceWakeLock.isHeld()) {
            try {
                serviceWakeLock.release();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseServiceWakeLock();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

