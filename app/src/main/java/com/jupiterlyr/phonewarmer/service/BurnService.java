package com.jupiterlyr.phonewarmer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.jupiterlyr.phonewarmer.R;
import com.jupiterlyr.phonewarmer.workload.WorkloadEngine;

public class BurnService extends Service {

    public static final String ACTION_START = "burn_start";
    public static final String ACTION_STOP = "burn_stop";
    public static final String EXTRA_INTENSITY = "extra_intensity";
    public static final String CHANNEL_ID = "burn_service_channel";
    public static final int NOTIFICATION_ID = 1001;

    private final WorkloadEngine workloadEngine = new WorkloadEngine();

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if (ACTION_START.equals(action)) {
                int intensity = intent.getIntExtra(EXTRA_INTENSITY, 2);
                startForeground(
                        NOTIFICATION_ID,
                        buildNotification("高负载运行中，强度 " + intensity + " 档")
                );
                workloadEngine.start(intensity);
            } else if (ACTION_STOP.equals(action)) {
                stopSelf();
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        workloadEngine.stop();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("耗能测试运行中")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Burn Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            manager.createNotificationChannel(channel);
        }
    }
}
