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
 * MainActivity 的视图绑定/渲染层（仅在主线程使用，不做线程安全保护）
 */
public class MainViewBinder {

    /** 强度档位上下限，与 WorkloadEngine 内部允许的 worker 数范围保持一致。 */
    public static final int INTENSITY_MIN = 1;
    public static final int INTENSITY_MAX = 9;

    /** GPU 状态：0=未就绪，1=就绪/空闲，2=运行中，-1=初始化失败 */
    public static final int GPU_STATE_IDLE_BEFORE_READY = 0;
    public static final int GPU_STATE_READY = 1;
    public static final int GPU_STATE_RUNNING = 2;
    public static final int GPU_STATE_ERROR = -1;

    private final Activity activity;

    // ---------- Views ----------
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
    private final TextView tvBatteryPower;
    private final TextView tvThermalStatus;
    private final Button btnStart;
    private final Button btnStop;
    private final Button btnPlus;
    private final Button btnMinus;
    private final GLSurfaceView glSurfaceView;
    private final TextView tvGpuStatus;

    // ---------- 渲染所需状态（由 Activity 通过 setter 同步） ----------
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private int gpuState = GPU_STATE_IDLE_BEFORE_READY;
    @Nullable
    private String gpuErrorMessage;

    /**
     * 当前 CPU 负荷数据的来源，决定 tvCpuLoad 开头显示“系统”还是“进程”。
     * {@code null} 表示尚未探测出来源（首帧采样前），此时使用中性文案。
     */
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
        tvBatteryPower = activity.findViewById(R.id.tvBatteryPower);
        tvThermalStatus = activity.findViewById(R.id.tvThermalStatus);
        glSurfaceView = activity.findViewById(R.id.glSurfaceView);
        tvGpuStatus = activity.findViewById(R.id.tvGpuStatus);
    }

    // ---------- 按钮事件接入（让 Activity 不直接持有 Button 引用） ----------

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

    // ---------- GLSurfaceView 暴露 / 生命周期 ----------

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

    // ---------- 时间 Ticker ----------

    public void startTimeTicker() {
        mainHandler.post(timeTicker);
    }

    public void stopTimeTicker() {
        mainHandler.removeCallbacks(timeTicker);
    }

    // ---------- 电量 ----------

    @SuppressLint("SetTextI18n")
    public void renderBattery(@NonNull BatterySnapshot snapshot) {
        int level = snapshot.getBatteryLevel();
        tvBattery.setText(level + "%");
        progressBattery.setProgress(level);

        // 分档着色：<=20% 红色告警，<=40% 黄色提醒，其余绿色正常
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
        if (charging) return level == 100 ? "充电已完成" : "充电中";
        return level < 30 ? "电量过低" : "未充电";
    }

    // ---------- CPU / GPU 负荷 ----------

    /**
     * @param stats     最新系统数据
     * @param isBurning 当前是否处于烤机运行状态（GPU 负荷文案需要据此切占位）
     */
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
            tvGpuLoad.setText("进程GPU吞吐量：—（未启动烤机）");
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

        // ---- CPU 频率：读不到时显示“—” ----
        if (stats.getCpuFreqMhz() > 0f) {
            tvCpuFreq.setText(
                    String.format(Locale.getDefault(),
                            "CPU频率：%.0f MHz（%.0f%%）",
                            stats.getCpuFreqMhz(), stats.getCpuFreqRatio())
            );
        } else {
            tvCpuFreq.setText("CPU频率：—（设备受限）");
        }

        // ---- 电池电流：保留方向（充电为正、放电为负），部分国产 ROM 符号反转，可由用户自行解读 ----
        float currentUa = stats.getBatteryCurrentMa();
        if (currentUa == 0f) {
            tvBatteryCurrent.setText("电池电流：—");
        } else {
            String dir = currentUa > 0f ? "充电" : "放电";
            tvBatteryCurrent.setText(
                    String.format(Locale.getDefault(),
                            "电池电流：%,.0f μA（%s）", Math.abs(currentUa), dir)
            );
        }

        // ---- 瞬时功率：μW 数量级较大，使用千位分隔提升可读性 ----
        float powerUw = stats.getBatteryPowerW();
        if (powerUw == 0f) {
            tvBatteryPower.setText("瞬时功率：—");
        } else {
            tvBatteryPower.setText(
                    String.format(Locale.getDefault(),
                            "瞬时功率：%,.0f μW", Math.abs(powerUw))
            );
        }

        // ---- 系统热状态 ----
        tvThermalStatus.setText("系统热状态：" + thermalStatusText(stats.getThermalStatus()));

        // 根据 GPU 负荷波动同步运行/空闲状态（但不覆盖初始化失败状态）
        if (gpuState != GPU_STATE_ERROR) {
            gpuState = stats.getGpuLoad() > 0 ? GPU_STATE_RUNNING : GPU_STATE_READY;
        }
        refreshGpuStatusText();
    }

    /**
     * 设定 CPU 负荷的数据来源。调用后下一次 {@link #renderSystemStats} 会使用新文案。
     * <p>
     * 为避免"在首帧 SystemStats 到达之前不会变色"，本方法会立即刷新一次 tvCpuLoad 的文案
     * （保留原有数值，只替换前缀）。
     */
    @SuppressLint("SetTextI18n")
    public void setCpuLoadSource(@NonNull CpuSource source) {
        this.cpuLoadSource = source;
        // 立即更新前缀：从 tvCpuLoad 现有文案中按全角冒号切分，仅替换冒号之前的标签部分，保留数值不变。
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

    /**
     * 将 {@link android.os.PowerManager#getCurrentThermalStatus()} 的常量映射为中文档位名。
     * 不依赖 PowerManager 的常量值（避免低版本设备上加载常量报错），直接使用文档中的整数取值。
     */
    private String thermalStatusText(int status) {
        switch (status) {
            case 0: return "正常";
            case 1: return "轻度发热";
            case 2: return "中度发热";
            case 3: return "严重发热";
            case 4: return "危险";
            case 5: return "紧急限流";
            case 6: return "即将关机";
            case SystemStats.THERMAL_UNKNOWN:
            default:
                return "未知（需 Android 10+）";
        }
    }

    /**
     * 将字节数格式化为以 MB 为单位的字符串，保留一位小数。
     * 1024 进制（与 Android Settings 中"已用内存"展示的口径一致）。
     */
    private static String formatMb(long bytes) {
        if (bytes <= 0L) return "0.0 MB";
        final float MB = 1024f * 1024f;
        return String.format(Locale.getDefault(), "%.1f MB", bytes / MB);
    }

    // ---------- GPU 状态机 ----------

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

    // ---------- 强度 / 运行状态 文案 ----------

    @SuppressLint("SetTextI18n")
    public void renderIntensity(int intensity) {
        tvIntensity.setText(intensity + " 级");
    }

    @SuppressLint("SetTextI18n")
    public void renderRunningStatus(@NonNull String status) {
        tvStatus.setText("运行状态：" + status);
    }

    // ---------- 按钮启停 + 颜色 ----------

    /**
     * 同步按钮启用状态与背景色：
     * - 启用时恢复成 layout 中定义的原色
     * - 禁用时统一切换为 @color/btn_disabled
     */
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
