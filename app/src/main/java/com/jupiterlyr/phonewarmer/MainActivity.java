package com.jupiterlyr.phonewarmer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.jupiterlyr.phonewarmer.monitor.BatterySnapshot;
import com.jupiterlyr.phonewarmer.monitor.BatteryMonitor;
import com.jupiterlyr.phonewarmer.monitor.SystemMonitor;
import com.jupiterlyr.phonewarmer.monitor.SystemStats;
import com.jupiterlyr.phonewarmer.service.BurnService;
import com.jupiterlyr.phonewarmer.workload.WorkloadEngine;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView tvTime;
    private TextView tvBattery;
    private TextView tvCharging;
    private TextView tvBatteryTemp;
    private TextView tvStatus;
    private TextView tvIntensity;
    private ProgressBar progressBattery;
    private TextView tvCpuTemp;
    private TextView tvCpuLoad;
    private TextView tvGpuLoad;
    private Button btnStart;
    private Button btnStop;
    private Button btnPlus;
    private Button btnMinus;
    private GLSurfaceView glSurfaceView;
    private TextView tvGpuStatus;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private BatteryMonitor batteryMonitor;
    private SystemMonitor systemMonitor;
    private WorkloadEngine workloadEngine;

    private int intensity = 2;
    private boolean isBurning = false;
    private static final float AUTO_STOP_TEMP_C = 42.0f;
    private static final int REQ_POST_NOTIFICATIONS = 1001;
    /** 耗能强度档位上下限，需与 WorkloadEngine 内部允许的 worker 数范围保持一致。 */
    private static final int INTENSITY_MIN = 1;
    private static final int INTENSITY_MAX = 6;

    /** GPU 状态：0=未就绪，1=就绪/空闲，2=运行中，-1=初始化失败 */
    private int gpuState = 0;
    @Nullable
    private String gpuErrorMessage;

    private final Runnable timeTicker = new Runnable() {
        @Override
        public void run() {
            tvTime.setText(timeFormat.format(new Date()));
            mainHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initViews();

        batteryMonitor = new BatteryMonitor(this);
        systemMonitor = new SystemMonitor(this);
        workloadEngine = new WorkloadEngine();
        
        if (glSurfaceView != null) {
            try {
                workloadEngine.setGLSurfaceView(glSurfaceView, new com.jupiterlyr.phonewarmer.workload.GPURenderEngine.ErrorListener() {
                    @Override
                    public void onGlInitError(String message) {
                        runOnUiThread(() -> {
                            android.util.Log.e("MainActivity", "GL init error: " + message);
                            gpuState = -1;
                            gpuErrorMessage = message;
                            refreshGpuStatusText(0.0f);
                        });
                    }

                    @Override
                    public void onGlReady() {
                        runOnUiThread(() -> {
                            android.util.Log.i("MainActivity", "GL ready");
                            if (gpuState != -1) {
                                gpuState = 1;
                                refreshGpuStatusText(0.0f);
                            }
                        });
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "GPU initialization failed: " + e.getMessage());
                gpuState = -1;
                gpuErrorMessage = e.getMessage();
                refreshGpuStatusText(0.0f);
                glSurfaceView = null; // 禁用GPU功能
            }
        }

        bindActions();
        updateIntensityText();
        updateStatus("待机中");
        syncButtons();
        ensureNotificationPermission();
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

    private void initViews() {
        tvTime = findViewById(R.id.tvTime);
        tvBattery = findViewById(R.id.tvBattery);
        tvCharging = findViewById(R.id.tvCharging);
        tvBatteryTemp = findViewById(R.id.tvBatteryTemp);
        tvStatus = findViewById(R.id.tvStatus);
        tvIntensity = findViewById(R.id.tvIntensity);
        progressBattery = findViewById(R.id.progressBattery);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnPlus = findViewById(R.id.btnPlus);
        btnMinus = findViewById(R.id.btnMinus);
        tvCpuTemp = findViewById(R.id.tvCpuTemp);
        tvCpuLoad = findViewById(R.id.tvCpuLoad);
        tvGpuLoad = findViewById(R.id.tvGpuLoad);
        glSurfaceView = findViewById(R.id.glSurfaceView);
        tvGpuStatus = findViewById(R.id.tvGpuStatus);
    }

    private void bindActions() {
        btnStart.setOnClickListener(v -> startBurn());
        btnStop.setOnClickListener(v -> stopBurn("已手动停止"));

        btnPlus.setOnClickListener(v -> {
            if (intensity < INTENSITY_MAX) {
                intensity++;
                updateIntensityText();
                syncButtons();
                restartIfNeeded();
            }
        });

        btnMinus.setOnClickListener(v -> {
            if (intensity > INTENSITY_MIN) {
                intensity--;
                updateIntensityText();
                syncButtons();
                restartIfNeeded();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        mainHandler.post(timeTicker);

        batteryMonitor.start(snapshot -> runOnUiThread(() -> {
            renderBattery(snapshot);

            if (isBurning && snapshot.getBatteryTempC() >= AUTO_STOP_TEMP_C) {
                stopBurn("温度达到 " + AUTO_STOP_TEMP_C + "°C，已自动停止");
            }
        }));
        systemMonitor.start(stats -> runOnUiThread(() -> {
            renderSystemStats(stats);
        }));
    }

    @Override
    protected void onStop() {
        super.onStop();
        mainHandler.removeCallbacks(timeTicker);
        batteryMonitor.stop();
        systemMonitor.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // GLSurfaceView 要求与 Activity 生命周期成对调用，否则切出/切回后 GL 线程不会恢复绘制
        if (glSurfaceView != null) {
            glSurfaceView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (glSurfaceView != null) {
            glSurfaceView.onPause();
        }
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

    private void renderBattery(BatterySnapshot snapshot) {
        int level = snapshot.getBatteryLevel();
        tvBattery.setText(level + "%");
        progressBattery.setProgress(level);

        // 分档着色：<20% 红色告警，<30% 黄色提醒，其余绿色正常
        int progressColorRes;
        if (level < 20) {
            progressColorRes = R.color.red_main;
        } else if (level < 30) {
            progressColorRes = R.color.yellow_main;
        } else {
            progressColorRes = R.color.green_main;
        }
        progressBattery.setProgressTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, progressColorRes))
        );

        tvCharging.setText(
                "充电状态：" + (snapshot.isCharging() ?
                        (snapshot.getBatteryLevel() == 100 ? "充电已完成" : "充电中")
                        : (snapshot.getBatteryLevel() < 30 ? "电量过低" : "未充电"))
        );

        tvBatteryTemp.setText(
                String.format(Locale.getDefault(), "电池温度：%.1f°C", snapshot.getBatteryTempC())
        );
    }

    private void renderSystemStats(SystemStats stats) {
        tvCpuTemp.setText(
                String.format(Locale.getDefault(), "CPU温度：%.1f°C", stats.getCpuTemperature())
        );
        tvCpuLoad.setText(
                String.format(Locale.getDefault(), "CPU负荷：%.1f%%", stats.getCpuLoad())
        );
        // GPU 负荷反映的是本应用渲染器的吞吐率，未运行时显示占位以免误导
        if (isBurning) {
            tvGpuLoad.setText(
                    String.format(Locale.getDefault(), "GPU负荷：%.1f%%", stats.getGpuLoad())
            );
        } else {
            tvGpuLoad.setText("GPU负荷：—（未启动烤机）");
        }
        // 根据 GPU 负荷波动同步运行/空闲状态（但不覆盖初始化失败状态）
        if (gpuState != -1) {
            gpuState = stats.getGpuLoad() > 0 ? 2 : 1;
        }
        refreshGpuStatusText(stats.getGpuLoad());
    }

    private void refreshGpuStatusText(float gpuLoad) {
        if (tvGpuStatus == null) return;
        String text;
        switch (gpuState) {
            case -1:
                text = "GPU状态：初始化失败" + (gpuErrorMessage != null ? " - " + gpuErrorMessage : "");
                break;
            case 1:
                text = "GPU状态：就绪 · 空闲";
                break;
            case 2:
                text = "GPU状态：运行中";
                break;
            case 0:
            default:
                text = "GPU状态：未启动";
                break;
        }
        tvGpuStatus.setText(text);
    }

    private void startBurn() {
        workloadEngine.start(intensity);
        startBurnService(intensity);
        isBurning = true;
        updateStatus("高负载运行中");
        syncButtons();
    }

    private void stopBurn(String reason) {
        workloadEngine.stop();
        stopBurnService();
        isBurning = false;
        updateStatus(reason);
        syncButtons();
    }

    private void restartIfNeeded() {
        if (isBurning) {
            workloadEngine.start(intensity);
            // 重启前台服务以更新通知中显示的强度
            startBurnService(intensity);
            updateStatus("强度已调整为 " + intensity + " 级");
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

    private void updateIntensityText() {
        tvIntensity.setText(intensity + " 级");
    }

    private void updateStatus(String text) {
        tvStatus.setText("运行状态：" + text);
    }

    private void syncButtons() {
        // 同步按钮启停状态，并根据状态切换 backgroundTint：
        // - 启用时恢复成 layout 中定义的原色
        // - 禁用时统一切换为 @color/btn_disabled
        applyEnabled(btnStart, !isBurning, R.color.btn_warn);
        applyEnabled(btnStop, isBurning, R.color.btn_danger);
        // 强度档位边界处禁用对应的加/减按钮，避免无效点击
        applyEnabled(btnMinus, intensity > INTENSITY_MIN, R.color.btn_default);
        applyEnabled(btnPlus, intensity < INTENSITY_MAX, R.color.btn_default);
    }

    /**
     * 统一同步按钮的启用状态与背景色。
     *
     * @param button         目标按钮
     * @param enabled        是否启用
     * @param enabledColorRes 启用时使用的颜色资源（即 layout 中定义的原色）
     */
    private void applyEnabled(Button button, boolean enabled, int enabledColorRes) {
        if (button == null) return;
        button.setEnabled(enabled);
        int colorRes = enabled ? enabledColorRes : R.color.btn_disabled;
        button.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        );
    }
}
