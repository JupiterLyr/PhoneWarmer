package com.jupiterlyr.phonewarmer;

import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.jupiterlyr.phonewarmer.monitor.BatterySnapshot;
import com.jupiterlyr.phonewarmer.monitor.BatteryMonitor;
import com.jupiterlyr.phonewarmer.monitor.SystemMonitor;
import com.jupiterlyr.phonewarmer.monitor.SystemStats;
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
        WindowManager.LayoutParams lp = getWindow().getAttributes();
//        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);

        initViews();

        batteryMonitor = new BatteryMonitor(this);
        systemMonitor = new SystemMonitor(this);
        workloadEngine = new WorkloadEngine();
        // 设置GLSurfaceView（如果布局中有的话）
        if (glSurfaceView != null) {
            try {
                workloadEngine.setGLSurfaceView(glSurfaceView);
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "GPU initialization failed: " + e.getMessage());
                tvGpuStatus.setText("GPU初始化失败，已禁用GPU功能");
                glSurfaceView = null; // 禁用GPU功能
            }
        }
        workloadEngine.setSystemMonitor(systemMonitor);

        bindActions();
        updateIntensityText();
        updateStatus("待机中");
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
            if (intensity < 4) {
                intensity++;
                updateIntensityText();
                restartIfNeeded();
            }
        });

        btnMinus.setOnClickListener(v -> {
            if (intensity > 1) {
                intensity--;
                updateIntensityText();
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
    protected void onDestroy() {
        workloadEngine.stop();
        super.onDestroy();
    }

    private void renderBattery(BatterySnapshot snapshot) {
        tvBattery.setText("电量：" + snapshot.getBatteryLevel() + "%");
        progressBattery.setProgress(snapshot.getBatteryLevel());

        tvCharging.setText(
                "充电状态：" + (snapshot.isCharging() ? "充电中" : "未充电")
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
        tvGpuLoad.setText(
                String.format(Locale.getDefault(), "GPU负荷：%.1f%%", stats.getGpuLoad())
        );
        tvGpuStatus.setText("GPU状态：" + (stats.getGpuLoad() > 0 ? "运行中" : "空闲"));
    }

    private void startBurn() {
        workloadEngine.start(intensity);
        isBurning = true;
        updateStatus("高负载运行中");
        syncButtons();
    }

    private void stopBurn(String reason) {
        workloadEngine.stop();
        isBurning = false;
        updateStatus(reason);
        syncButtons();
    }

    private void restartIfNeeded() {
        if (isBurning) {
            workloadEngine.start(intensity);
            updateStatus("强度已调整为 " + intensity + " 级");
        }
    }

    private void updateIntensityText() {
        tvIntensity.setText(intensity + " 级");
    }

    private void updateStatus(String text) {
        tvStatus.setText("运行状态：" + text);
    }

    private void syncButtons() {
        btnStart.setEnabled(!isBurning);
        btnStop.setEnabled(isBurning);
    }
}
