package com.simon.tinnitusrelief;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class MusicIndexService extends Service {
    public static final String EXTRA_FOLDER_NAME = "extra_folder_name";
    private static final String CHANNEL_ID = "tinnitus_music_index_channel";
    private static final int NOTIFICATION_ID = 2001;

    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private MusicIndexManager.ScanListener scanListener;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();

        scanListener = new MusicIndexManager.ScanListener() {
            @Override
            public void onProgress(String status, int count, boolean isScanning) {
                if (isScanning && notificationBuilder != null && notificationManager != null) {
                    notificationBuilder.setContentText(count > 0 ? "Indexed " + count + " tracks..." : status);
                    notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
                }
            }

            @Override
            public void onCompleted(String folderName, String json, int totalTracks, int totalArtists) {
                stopSelf();
            }

            @Override
            public void onError(String error) {
                stopSelf();
            }
        };

        MusicIndexManager.getInstance(this).addListener(scanListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String folderName = "Music Collection";
        if (intent != null && intent.hasExtra(EXTRA_FOLDER_NAME)) {
            folderName = intent.getStringExtra(EXTRA_FOLDER_NAME);
        }

        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Indexing Music Collection")
                .setContentText("Reading tags for " + folderName + "...")
                .setSmallIcon(android.R.drawable.ic_menu_rotate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        Notification notification = notificationBuilder.build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Music Library Indexing",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows background progress when indexing music audio tags");
            channel.enableVibration(false);
            channel.setSound(null, null);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scanListener != null) {
            MusicIndexManager.getInstance(this).removeListener(scanListener);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

