package com.jupiterlyr.phonewarmer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.jupiterlyr.phonewarmer.monitor.BatterySnapshot;
import com.jupiterlyr.phonewarmer.monitor.CpuSource;
import com.jupiterlyr.phonewarmer.monitor.SystemStats;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MainActivity 的视图绑定与渲染层，仅在主线程使用。
 */
public class MainViewBinder {

    public static final int INTENSITY_MIN = 1;
    public static final int INTENSITY_MAX = 9;

    public static final int GPU_STATE_IDLE_BEFORE_READY = 0;
    public static final int GPU_STATE_READY = 1;
    public static final int GPU_STATE_RUNNING = 2;
    public static final int GPU_STATE_ERROR = -1;

    private final Activity activity;

    private final TextView tvTime;
    private final TextView tvBattery;
    private final TextView tvCharging;
    private final TextView tvBatteryTemp;
    private final TextView tvStatus;
    private final TextView tvIntensity;
    private final ProgressBar progressBattery;
    private final TextView tvCpuTemp;
    private final TextView tvCpuLoad;
    private final TextView tvGpuLoad;
    private final TextView tvMemoryLoad;
    private final TextView tvCpuFreq;
    private final TextView tvBatteryCurrent;
    private final TextView tvBatteryVoltage;
    private final TextView tvBatteryPower;
    private final TextView tvThermalStatus;
    private final Button btnStart;
    private final Button btnStop;
    private final Button btnPlus;
    private final Button btnMinus;
    private final GLSurfaceView glSurfaceView;
    private final TextView tvGpuStatus;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private int gpuState = GPU_STATE_IDLE_BEFORE_READY;
    @Nullable
    private String gpuErrorMessage;

    @Nullable
    private CpuSource cpuLoadSource = null;

    private final Runnable timeTicker = new Runnable() {
        @Override
        public void run() {
            tvTime.setText(timeFormat.format(new Date()));
            mainHandler.postDelayed(this, 1000);
        }
    };

    public MainViewBinder(@NonNull Activity activity) {
        this.activity = activity;

        tvTime = activity.findViewById(R.id.tvTime);
        tvBattery = activity.findViewById(R.id.tvBattery);
        tvCharging = activity.findViewById(R.id.tvCharging);
        tvBatteryTemp = activity.findViewById(R.id.tvBatteryTemp);
        tvStatus = activity.findViewById(R.id.tvStatus);
        tvIntensity = activity.findViewById(R.id.tvIntensity);
        progressBattery = activity.findViewById(R.id.progressBattery);
        btnStart = activity.findViewById(R.id.btnStart);
        btnStop = activity.findViewById(R.id.btnStop);
        btnPlus = activity.findViewById(R.id.btnPlus);
        btnMinus = activity.findViewById(R.id.btnMinus);
        tvCpuTemp = activity.findViewById(R.id.tvCpuTemp);
        tvCpuLoad = activity.findViewById(R.id.tvCpuLoad);
        tvGpuLoad = activity.findViewById(R.id.tvGpuLoad);
        tvMemoryLoad = activity.findViewById(R.id.tvMemoryload);
        tvCpuFreq = activity.findViewById(R.id.tvCpuFreq);
        tvBatteryCurrent = activity.findViewById(R.id.tvBatteryCurrent);
        tvBatteryVoltage = activity.findViewById(R.id.tvBatteryVoltage);
        tvBatteryPower = activity.findViewById(R.id.tvBatteryPower);
        tvThermalStatus = activity.findViewById(R.id.tvThermalStatus);
        glSurfaceView = activity.findViewById(R.id.glSurfaceView);
        tvGpuStatus = activity.findViewById(R.id.tvGpuStatus);
    }

    public void setOnStartClickListener(@NonNull Runnable action) {
        btnStart.setOnClickListener(v -> action.run());
    }

    public void setOnStopClickListener(@NonNull Runnable action) {
        btnStop.setOnClickListener(v -> action.run());
    }

    public void setOnPlusClickListener(@NonNull Runnable action) {
        btnPlus.setOnClickListener(v -> action.run());
    }

    public void setOnMinusClickListener(@NonNull Runnable action) {
        btnMinus.setOnClickListener(v -> action.run());
    }

    @Nullable
    public GLSurfaceView getGLSurfaceView() {
        return glSurfaceView;
    }

    public void onResumeGl() {
        if (glSurfaceView != null) glSurfaceView.onResume();
    }

    public void onPauseGl() {
        if (glSurfaceView != null) glSurfaceView.onPause();
    }

    public void startTimeTicker() {
        mainHandler.post(timeTicker);
    }

    public void stopTimeTicker() {
        mainHandler.removeCallbacks(timeTicker);
    }

    @SuppressLint("SetTextI18n")
    public void renderBattery(@NonNull BatterySnapshot snapshot) {
        int level = snapshot.getBatteryLevel();
        tvBattery.setText(level + "%");
        progressBattery.setProgress(level);
        progressBattery.setProgressTintList(
                ColorStateList.valueOf(ContextCompat.getColor(activity, batteryProgressColorRes(level)))
        );

        tvCharging.setText("充电状态：" + chargingText(snapshot.isCharging(), level));
        tvBatteryTemp.setText(
                String.format(Locale.getDefault(), "电池温度：%.1f°C", snapshot.getBatteryTempC())
        );
    }

    @ColorRes
    private static int batteryProgressColorRes(int level) {
        if (level <= 20) return R.color.red_main;
        if (level <= 40) return R.color.yellow_main;
        return R.color.green_main;
    }

    private static String chargingText(boolean charging, int level) {
        if (charging) return level == 100 ? "已充满" : "充电中";
        return level < 30 ? "电量过低" : "未充电";
    }

    @SuppressLint("SetTextI18n")
    public void renderSystemStats(@NonNull SystemStats stats, boolean isBurning) {
        tvCpuTemp.setText(
                String.format(Locale.getDefault(), "CPU温度：%.1f°C", stats.getCpuTemperature())
        );
        tvCpuLoad.setText(
                String.format(Locale.getDefault(), "%s：%.1f%%", cpuLoadLabel(), stats.getCpuLoad())
        );

        if (isBurning) {
            tvGpuLoad.setText(
                    String.format(Locale.getDefault(), "进程GPU吞吐量：%.1f%%", stats.getGpuLoad())
            );
        } else {
            tvGpuLoad.setText("进程GPU吞吐量：—（未启动烧机）");
        }

        long memUsed = stats.getMemoryUsedBytes();
        long memTotal = stats.getMemoryTotalBytes();
        if (memTotal > 0L) {
            tvMemoryLoad.setText(
                    String.format(Locale.getDefault(),
                            "内存占用：%s / %s",
                            formatMb(memUsed),
                            formatMb(memTotal))
            );
        } else {
            tvMemoryLoad.setText("内存占用：—");
        }

        if (stats.getCpuFreqMhz() > 0f) {
            tvCpuFreq.setText(
                    String.format(Locale.getDefault(),
                            "CPU频率：%.0f MHz（%.0f%%）",
                            stats.getCpuFreqMhz(), stats.getCpuFreqRatio())
            );
        } else {
            tvCpuFreq.setText("CPU频率：—（设备受限）");
        }

        float currentMa = stats.getBatteryCurrentMa();
        if (currentMa == 0f) {
            tvBatteryCurrent.setText("电池电流：—");
        } else {
            String dir = currentMa > 0f ? "充电" : "放电";
            tvBatteryCurrent.setText(
                    String.format(Locale.getDefault(),
                            "电池电流：%,.0f mA（%s）", Math.abs(currentMa), dir)
            );
        }

        float voltageMv = stats.getBatteryVoltageMv();
        if (voltageMv == 0f) {
            tvBatteryVoltage.setText("电池电压：—");
        } else {
            tvBatteryVoltage.setText(
                    String.format(Locale.getDefault(),
                            "电池电压：%,.0f mV", Math.abs(voltageMv))
            );
        }

        float powerMw = stats.getBatteryPowerMw();
        if (powerMw == 0f) {
            tvBatteryPower.setText("瞬时功率：—");
        } else {
            tvBatteryPower.setText(
                    String.format(Locale.getDefault(),
                            "瞬时功率：%,.0f mW", Math.abs(powerMw))
            );
        }

        tvThermalStatus.setText("系统热状态：" + thermalStatusText(stats.getThermalStatus()));

        if (gpuState != GPU_STATE_ERROR) {
            gpuState = stats.getGpuLoad() > 0 ? GPU_STATE_RUNNING : GPU_STATE_READY;
        }
        refreshGpuStatusText();
    }

    @SuppressLint("SetTextI18n")
    public void setCpuLoadSource(@NonNull CpuSource source) {
        this.cpuLoadSource = source;
        CharSequence existing = tvCpuLoad.getText();
        if (existing != null) {
            String text = existing.toString();
            int idx = text.indexOf('：');
            if (idx >= 0) {
                tvCpuLoad.setText(cpuLoadLabel() + text.substring(idx));
            }
        }
    }

    private String cpuLoadLabel() {
        if (cpuLoadSource == CpuSource.SYSTEM) return "系统CPU负荷";
        if (cpuLoadSource == CpuSource.PROCESS) return "进程CPU负荷";
        return "CPU负荷";
    }

    private String thermalStatusText(int status) {
        switch (status) {
            case 0: return "正常";
            case 1: return "轻度发热";
            case 2: return "中度发热";
            case 3: return "重度发热";
            case 4: return "危险";
            case 5: return "紧急限流";
            case 6: return "即将关机";
            case SystemStats.THERMAL_UNKNOWN:
            default:
                return "未知（需 Android 10+）";
        }
    }

    private static String formatMb(long bytes) {
        if (bytes <= 0L) return "0.0 MB";
        final float MB = 1024f * 1024f;
        return String.format(Locale.getDefault(), "%.1f MB", bytes / MB);
    }

    public void markGpuReady() {
        if (gpuState != GPU_STATE_ERROR) {
            gpuState = GPU_STATE_READY;
            refreshGpuStatusText();
        }
    }

    public void markGpuError(@Nullable String message) {
        gpuState = GPU_STATE_ERROR;
        gpuErrorMessage = message;
        refreshGpuStatusText();
    }

    private void refreshGpuStatusText() {
        if (tvGpuStatus == null) return;
        String text;
        switch (gpuState) {
            case GPU_STATE_ERROR:
                text = "GPU状态：初始化失败" + (gpuErrorMessage != null ? " - " + gpuErrorMessage : "");
                break;
            case GPU_STATE_READY:
                text = "GPU状态：就绪 · 空闲";
                break;
            case GPU_STATE_RUNNING:
                text = "GPU状态：运行中";
                break;
            case GPU_STATE_IDLE_BEFORE_READY:
            default:
                text = "GPU状态：未启动";
                break;
        }
        tvGpuStatus.setText(text);
    }

    @SuppressLint("SetTextI18n")
    public void renderIntensity(int intensity) {
        tvIntensity.setText(intensity + " 级");
    }

    @SuppressLint("SetTextI18n")
    public void renderRunningStatus(@NonNull String status) {
        tvStatus.setText("运行状态：" + status);
    }

    public void syncButtons(boolean isBurning, int intensity) {
        applyEnabled(btnStart, !isBurning, R.color.btn_warn);
        applyEnabled(btnStop, isBurning, R.color.btn_danger);
        applyEnabled(btnMinus, intensity > INTENSITY_MIN, R.color.btn_default);
        applyEnabled(btnPlus, intensity < INTENSITY_MAX, R.color.btn_default);
    }

    private void applyEnabled(Button button, boolean enabled, @ColorRes int enabledColorRes) {
        if (button == null) return;
        button.setEnabled(enabled);
        int colorRes = enabled ? enabledColorRes : R.color.btn_disabled;
        button.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(activity, colorRes))
        );
    }
}
