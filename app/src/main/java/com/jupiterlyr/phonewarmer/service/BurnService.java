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
 * 用于在 Activity 切到后台时，通过前台服务+常驻通知防止进程被系统回收，
 * 从而保证 {@link com.jupiterlyr.phonewarmer.workload.WorkloadEngine} 能持续运行。
 *
 * <p>本服务自身不再持有 WorkloadEngine 实例，负载由 Activity 侧统一驱动；
 * 服务的唯一职责是承担前台通知 + 提升进程优先级。
 */
public class BurnService extends Service {

    public static final String ACTION_START = "burn_start";
    public static final String ACTION_STOP = "burn_stop";
    public static final String EXTRA_INTENSITY = "extra_intensity";
    public static final String CHANNEL_ID = "burn_service_channel";
    public static final int NOTIFICATION_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // 系统重启服务时无 intent，直接停止
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 默认以及 ACTION_START：启动前台
        int intensity = intent.getIntExtra(EXTRA_INTENSITY, 2);
        Notification notification = buildNotification("高负载运行中，强度 " + intensity + " 档");
        int type = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 必须显式声明前台服务类型
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);

        // 不再 START_STICKY，避免被系统重启后空跑（用户可能已经停止 burn）
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
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
