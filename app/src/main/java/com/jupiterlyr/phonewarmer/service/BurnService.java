package com.jupiterlyr.phonewarmer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.jupiterlyr.phonewarmer.MainActivity;
import com.jupiterlyr.phonewarmer.R;

/**
 * Foreground service that owns the active burn workload so it can continue while the app is backgrounded.
 */
public class BurnService extends Service {

    public static final String ACTION_START = "burn_start";
    public static final String ACTION_STOP = "burn_stop";
    public static final String EXTRA_INTENSITY = "extra_intensity";
    public static final String CHANNEL_ID = "burn_service_channel";
    public static final int NOTIFICATION_ID = 1001;

    private final BurnSession burnSession = BurnSession.getInstance();

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            burnSession.stop();
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            burnSession.stop();
            stopSelf();
            return START_NOT_STICKY;
        }

        int intensity = intent.getIntExtra(EXTRA_INTENSITY, burnSession.getIntensity());
        burnSession.start(intensity);

        Notification notification = buildNotification("高负载运行中，强度 " + intensity + " 级");
        int type = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        burnSession.stop();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification(String text) {
        Intent contentIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, contentIntent, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("耗能测试运行中")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentPi)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Burn Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }
}
