package com.jupiterlyr.phonewarmer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.jupiterlyr.phonewarmer.monitor.BatteryMonitor;
import com.jupiterlyr.phonewarmer.monitor.SystemMonitor;
import com.jupiterlyr.phonewarmer.service.BurnService;
import com.jupiterlyr.phonewarmer.workload.GPURenderEngine;
import com.jupiterlyr.phonewarmer.workload.WorkloadEngine;

public class MainActivity extends AppCompatActivity {

    private MainViewBinder viewBinder;

    private BatteryMonitor batteryMonitor;
    private SystemMonitor systemMonitor;
    private WorkloadEngine workloadEngine;

    private int intensity = 2;
    private boolean isBurning = false;
    private static final float AUTO_STOP_TEMP_C = 42.0f;
    private static final int REQ_POST_NOTIFICATIONS = 1001;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        viewBinder = new MainViewBinder(this);

        batteryMonitor = new BatteryMonitor(this);
        systemMonitor = new SystemMonitor(this);
        workloadEngine = new WorkloadEngine();

        setupGpuEngine();
        bindActions();

        // 初始 UI
        viewBinder.renderIntensity(intensity);
        viewBinder.renderRunningStatus("待机中");
        viewBinder.syncButtons(isBurning, intensity);

        ensureNotificationPermission();
    }

    private void setupGpuEngine() {
        GLSurfaceView glSurfaceView = viewBinder.getGLSurfaceView();
        if (glSurfaceView == null) return;

        try {
            workloadEngine.setGLSurfaceView(glSurfaceView, new GPURenderEngine.ErrorListener() {
                @Override
                public void onGlInitError(String message) {
                    runOnUiThread(() -> {
                        android.util.Log.e("MainActivity", "GL init error: " + message);
                        viewBinder.markGpuError(message);
                    });
                }

                @Override
                public void onGlReady() {
                    runOnUiThread(() -> {
                        android.util.Log.i("MainActivity", "GL ready");
                        viewBinder.markGpuReady();
                    });
                }
            });
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "GPU initialization failed: " + e.getMessage());
            viewBinder.markGpuError(e.getMessage());
        }
    }

    private void bindActions() {
        viewBinder.setOnStartClickListener(this::startBurn);
        viewBinder.setOnStopClickListener(() -> stopBurn("已手动停止"));

        viewBinder.setOnPlusClickListener(() -> {
            if (intensity < MainViewBinder.INTENSITY_MAX) {
                intensity++;
                viewBinder.renderIntensity(intensity);
                viewBinder.syncButtons(isBurning, intensity);
                restartIfNeeded();
            }
        });

        viewBinder.setOnMinusClickListener(() -> {
            if (intensity > MainViewBinder.INTENSITY_MIN) {
                intensity--;
                viewBinder.renderIntensity(intensity);
                viewBinder.syncButtons(isBurning, intensity);
                restartIfNeeded();
            }
        });
    }

    private void ensureNotificationPermission() {
        // Android 13+ 需要运行时申请通知权限，否则前台服务的通知不会展示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_POST_NOTIFICATIONS
                );
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        viewBinder.startTimeTicker();

        batteryMonitor.start(snapshot -> runOnUiThread(() -> {
            viewBinder.renderBattery(snapshot);

            if (isBurning && snapshot.getBatteryTempC() >= AUTO_STOP_TEMP_C) {
                stopBurn("温度达到 " + AUTO_STOP_TEMP_C + "°C，已自动停止");
            }
        }));
        systemMonitor.start(new SystemMonitor.Listener() {
            @Override
            public void onSystemStatsChanged(com.jupiterlyr.phonewarmer.monitor.SystemStats stats) {
                runOnUiThread(() -> viewBinder.renderSystemStats(stats, isBurning));
            }

            @Override
            public void onCpuLoadSourceChanged(com.jupiterlyr.phonewarmer.monitor.CpuSource source) {
                runOnUiThread(() -> viewBinder.setCpuLoadSource(source));
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        viewBinder.stopTimeTicker();
        batteryMonitor.stop();
        systemMonitor.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // GLSurfaceView 要求与 Activity 生命周期成对调用，否则切出/切回后 GL 线程不会恢复绘制
        viewBinder.onResumeGl();
    }

    @Override
    protected void onPause() {
        viewBinder.onPauseGl();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        workloadEngine.stop();
        // Activity 被销毁时一并停掉前台服务，避免重新进入后出现残留通知
        if (isBurning) {
            stopBurnService();
            isBurning = false;
        }
        super.onDestroy();
    }

    private void startBurn() {
        workloadEngine.start(intensity);
        startBurnService(intensity);
        isBurning = true;
        viewBinder.renderRunningStatus("高负载运行中");
        viewBinder.syncButtons(isBurning, intensity);
    }

    private void stopBurn(String reason) {
        workloadEngine.stop();
        stopBurnService();
        isBurning = false;
        viewBinder.renderRunningStatus(reason);
        viewBinder.syncButtons(isBurning, intensity);
    }

    private void restartIfNeeded() {
        if (isBurning) {
            workloadEngine.start(intensity);
            // 重启前台服务以更新通知中显示的强度
            startBurnService(intensity);
            viewBinder.renderRunningStatus("强度已调整为 " + intensity + " 级");
        }
    }

    private void startBurnService(int level) {
        Intent intent = new Intent(this, BurnService.class);
        intent.setAction(BurnService.ACTION_START);
        intent.putExtra(BurnService.EXTRA_INTENSITY, level);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopBurnService() {
        Intent intent = new Intent(this, BurnService.class);
        stopService(intent);
    }
}