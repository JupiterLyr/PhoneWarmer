package com.jupiterlyr.phonewarmer.monitor;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.jupiterlyr.phonewarmer.workload.GPURenderEngine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class SystemMonitor {

    public interface Listener {
        void onSystemStatsChanged(SystemStats stats);

        /**
         * 当 CPU 负荷的数据来源发生变化时回调。
         * <p>
         * 触发时机：
         * <ul>
         *   <li>首次成功采样后，从 {@code null} 切换为 {@link CpuSource#SYSTEM} 或 {@link CpuSource#PROCESS}</li>
         *   <li>原本走系统模式但中途读取失败，永久降级为 {@link CpuSource#PROCESS}</li>
         * </ul>
         * 由于来源不会频繁变化，此回调不会每秒触发。
         * <p>
         * 提供 {@code default} 空实现以兼容旧的 lambda Listener。
         */
        default void onCpuLoadSourceChanged(CpuSource source) {
            // no-op by default
        }
    }

    private final Context context;
    private final Handler handler;
    private Listener listener;
    private boolean monitoring = false;
    private static final long UPDATE_INTERVAL = 1000; // 1秒更新一次
    /** 上一次采样时本进程已消耗的 CPU 时间（毫秒），用于计算 1s 内的进程级 CPU 占用 */
    private long prevProcCpuMs = 0L;
    /** 上一次采样的墙钟时间戳（毫秒） */
    private long prevSampleWallMs = 0L;
    /** 用于将 CPU 占用率归一化到 100% 的核心数（按可用核心数估算） */
    private final int cpuCoreCount = Math.max(1, Runtime.getRuntime().availableProcessors());

    /**
     * /proc/stat 整机 CPU 采样是否可用。
     * <ul>
     *   <li>{@code null}：尚未探测，下一次 {@link #getCpuLoad()} 调用时会探测一次。</li>
     *   <li>{@code true}：可读，使用整机模式。</li>
     *   <li>{@code false}：不可读（权限/格式异常），永久 fallback 到进程级算法。</li>
     * </ul>
     */
    private Boolean procStatAvailable = null;
    /** 上一次 /proc/stat 采样得到的 busy 累计值（user+nice+system+irq+softirq+steal） */
    private long prevSysBusy = 0L;
    /** 上一次 /proc/stat 采样得到的 total 累计值（busy + idle + iowait） */
    private long prevSysTotal = 0L;
    /** 上一次通过回调通知出去的 CPU 数据来源；{@code null} 表示尚未通知过任何来源。 */
    private CpuSource lastNotifiedSource = null;

    /** 缓存每个 CPU 核心的最大频率（kHz），仅在首次访问时读一次。-1 表示该核心不可读。 */
    private long[] cpuMaxFreqKhzCache = null;
    /** 缓存的可用核心数（按照 sysfs 节点存在与否确定）。 */
    private int sysfsCpuCount = -1;

    // CPU温度文件路径（不同设备可能不同）
    private static final String[] CPU_TEMP_PATHS = {
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/class/hwmon/hwmon0/temp1_input"
    };

    public SystemMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void start(Listener listener) {
        if (monitoring) return;

        this.listener = listener;
        this.monitoring = true;
        handler.post(monitorRunnable);
    }

    public void stop() {
        monitoring = false;
        handler.removeCallbacks(monitorRunnable);
    }

    private final Runnable monitorRunnable = new Runnable() {
        @Override
        public void run() {
            if (!monitoring || listener == null) return;

            SystemStats stats = collectSystemStats();
            listener.onSystemStatsChanged(stats);

            if (monitoring) {
                handler.postDelayed(this, UPDATE_INTERVAL);
            }
        }
    };

    private SystemStats collectSystemStats() {
        float cpuTemp = getCpuTemperature();
        float cpuLoad = getCpuLoad();
        float gpuLoad = getGpuLoad();
        long[] memory = getMemoryInfo(); // {used, total}
        float memoryLoad = memory[1] > 0L
                ? (float) memory[0] * 100.0f / (float) memory[1]
                : 0.0f;

        float[] cpuFreq = getCpuFrequency(); // {mhz, ratio}
        float[] battery = getBatteryCVP(); // {mA, mV, mW}
        int thermalStatus = getThermalStatus();

        // getCpuLoad() 执行后，procStatAvailable 必然已被探测/更新，此处可以安全推导当前来源
        notifyCpuSourceIfChanged();

        return new SystemStats(
                cpuTemp,
                cpuLoad,
                gpuLoad,
                memoryLoad,
                memory[0],
                memory[1],
                cpuFreq[0],
                cpuFreq[1],
                battery[0],
                battery[1],
                battery[2],
                thermalStatus
        );
    }

    /**
     * 根据当前 {@link #procStatAvailable} 的状态推导 CPU 数据来源，与上次通知值不同时回调一次。
     * 这样调用方不需要每秒处理来源信息，仅在变化时才会收到一次通知。
     */
    private void notifyCpuSourceIfChanged() {
        if (listener == null || procStatAvailable == null) return;
        CpuSource current = Boolean.TRUE.equals(procStatAvailable)
                ? CpuSource.SYSTEM
                : CpuSource.PROCESS;
        if (current != lastNotifiedSource) {
            lastNotifiedSource = current;
            listener.onCpuLoadSourceChanged(current);
        }
    }

    private float getCpuTemperature() {
        for (String path : CPU_TEMP_PATHS) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(path));
                String line = reader.readLine();
                reader.close();
                if (line != null) {
                    float temp = Float.parseFloat(line.trim());
                    // 有些设备返回的是摄氏度*1000，有些是摄氏度*100
                    if (temp > 1000) temp /= 1000.0f;
                    else if (temp > 100) temp /= 100.0f;
                    return temp;
                }
            } catch (Exception e) {
                // 继续尝试下一个路径
            }
        }
        return 0.0f;
    }

    /**
     * 计算 CPU 占用率（百分比，0~100）。
     * <p>
     * 优先尝试读取 {@code /proc/stat} 计算<b>整机</b> CPU 使用率；
     * 若该文件在当前设备/Android 版本上不可读（Android 8 之后大多数机型受 SELinux 限制），
     * 则 fallback 到基于 {@link android.os.Process#getElapsedCpuTime()} 的<b>本进程</b>级算法。
     * <p>
     * 两种模式对外都返回 0~100 的百分比，调用方无需关心来源。
     */
    private float getCpuLoad() {
        // 首次调用时探测 /proc/stat 是否可用
        if (procStatAvailable == null) {
            long[] sample = readProcStatTotals();
            if (sample != null) {
                procStatAvailable = Boolean.TRUE;
                prevSysBusy = sample[0];
                prevSysTotal = sample[1];
            } else {
                procStatAvailable = Boolean.FALSE;
            }
        }

        if (Boolean.TRUE.equals(procStatAvailable)) {
            return getSystemCpuLoad();
        }
        return getProcessCpuLoad();
    }

    /**
     * 基于 {@code /proc/stat} 的整机 CPU 使用率。读取失败时会把 {@link #procStatAvailable}
     * 永久标记为 false，后续调用直接走进程级 fallback。
     */
    private float getSystemCpuLoad() {
        long[] sample = readProcStatTotals();
        if (sample == null) {
            // 之前能读但这次读失败：保守起见整体降级
            procStatAvailable = Boolean.FALSE;
            return getProcessCpuLoad();
        }

        long busyDelta = sample[0] - prevSysBusy;
        long totalDelta = sample[1] - prevSysTotal;
        prevSysBusy = sample[0];
        prevSysTotal = sample[1];

        if (totalDelta <= 0L || busyDelta < 0L) {
            return 0.0f;
        }
        float ratio = (float) busyDelta / (float) totalDelta;
        if (ratio < 0f) ratio = 0f;
        if (ratio > 1f) ratio = 1f;
        return ratio * 100.0f;
    }

    /**
     * 读取 {@code /proc/stat} 的首行 {@code cpu ...}，解析为累计的 busy/total 时钟节拍数。
     * 返回 {@code [busy, total]}；任何异常（权限、格式、被截断）都返回 {@code null}。
     */
    private long[] readProcStatTotals() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/stat"));
            String line = reader.readLine();
            if (line == null || !line.startsWith("cpu ")) {
                return null;
            }
            // 形如："cpu  user nice system idle iowait irq softirq steal guest guest_nice"
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 5) {
                return null;
            }
            long total = 0L;
            for (int i = 1; i < parts.length; i++) {
                total += Long.parseLong(parts[i]);
            }
            // idle = parts[4]，iowait = parts[5]（若存在）。两者都视为非 busy。
            long idle = Long.parseLong(parts[4]);
            if (parts.length > 5) {
                idle += Long.parseLong(parts[5]);
            }
            long busy = total - idle;
            return new long[]{busy, total};
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 进程级 CPU 占用率（保留原有实现，作为 /proc/stat 不可用时的 fallback）。
     */
    private float getProcessCpuLoad() {
        long nowProcCpuMs = android.os.Process.getElapsedCpuTime();
        long nowWallMs = android.os.SystemClock.elapsedRealtime();

        if (prevSampleWallMs == 0L) {
            // 首次采样还没有差值可计算，先记录基准
            prevProcCpuMs = nowProcCpuMs;
            prevSampleWallMs = nowWallMs;
            return 0.0f;
        }

        long cpuDelta = nowProcCpuMs - prevProcCpuMs;
        long wallDelta = nowWallMs - prevSampleWallMs;

        prevProcCpuMs = nowProcCpuMs;
        prevSampleWallMs = nowWallMs;

        if (wallDelta <= 0L || cpuDelta < 0L) {
            return 0.0f;
        }

        // 进程在 wallDelta 内最多可用 wallDelta * cpuCoreCount 毫秒 CPU 时间
        float ratio = (float) cpuDelta / (float) (wallDelta * cpuCoreCount);
        if (ratio < 0f) ratio = 0f;
        if (ratio > 1f) ratio = 1f;
        return ratio * 100.0f;
    }

    private float getGpuLoad() {
        return GPURenderEngine.getGpuLoad();
    }

    /**
     * 读取所有核心的当前频率，取最高值返回。
     * <p>
     * 路径：{@code /sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq}（kHz）。
     * 这些节点在大多数 Android 设备上对普通 App 可读，比 {@code /proc/stat} 兼容性好。
     * 但仍可能因 SELinux 拒读或机型差异失败，全部失败时返回 {@code {0f, 0f}}。
     *
     * @return {@code [maxCurMhz, maxRatio]}，{@code maxRatio} 为最高核当前频率 / 该核最大频率（0~100）。
     */
    private float[] getCpuFrequency() {
        if (sysfsCpuCount < 0) {
            sysfsCpuCount = detectSysfsCpuCount();
        }
        if (sysfsCpuCount == 0) {
            return new float[]{0f, 0f};
        }
        if (cpuMaxFreqKhzCache == null) {
            cpuMaxFreqKhzCache = new long[sysfsCpuCount];
            for (int i = 0; i < sysfsCpuCount; i++) {
                cpuMaxFreqKhzCache[i] = readCpuFreqKhz(
                        "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            }
        }

        long maxCurKhz = 0L;
        float maxRatio = 0f;
        for (int i = 0; i < sysfsCpuCount; i++) {
            long cur = readCpuFreqKhz(
                    "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            if (cur <= 0L) continue;
            if (cur > maxCurKhz) {
                maxCurKhz = cur;
            }
            long maxKhz = cpuMaxFreqKhzCache[i];
            if (maxKhz > 0L) {
                float ratio = (float) cur / (float) maxKhz;
                if (ratio > maxRatio) maxRatio = ratio;
            }
        }
        if (maxRatio < 0f) maxRatio = 0f;
        if (maxRatio > 1f) maxRatio = 1f;
        return new float[]{maxCurKhz / 1000.0f, maxRatio * 100.0f};
    }

    /** 探测 sysfs 上可见的 CPU 核心数，最多探测到 64 个核心。 */
    private static int detectSysfsCpuCount() {
        int count = 0;
        for (int i = 0; i < 64; i++) {
            File f = new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq");
            if (f.exists()) count++;
            else if (i > 0) break; // cpu0 不存在直接放弃；之后遇到第一个不存在的核就停止
        }
        return count;
    }

    /** 读取一个 cpufreq sysfs 节点（kHz），失败返回 -1。 */
    private static long readCpuFreqKhz(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String line = r.readLine();
            if (line == null) return -1L;
            return Long.parseLong(line.trim());
        } catch (Exception e) {
            return -1L;
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 读取电池瞬时电流（mA）、瞬时电压（mV）和瞬时功率（mW）
     * <p>
     * 电流通过 {@link BatteryManager#BATTERY_PROPERTY_CURRENT_NOW} 获取（mA），
     * 电压通过 sticky broadcast {@code ACTION_BATTERY_CHANGED} 的 {@code EXTRA_VOLTAGE} 获取（mV）。
     * 二者相乘即为功率。
     * <p>
     * 符号约定：充电为正、放电为负。部分国产 ROM 符号反转，UI 层取绝对值更稳妥。
     *
     * @return {@code [currentMa, voltageMv, powerMw]}
     */
    private float[] getBatteryCVP() {
        long currentMa = 0L;
        int voltageMv = 0;
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                long raw_currentUa = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (raw_currentUa != Long.MIN_VALUE) {  // 仅当返回 Long.MIN_VALUE 时视为不可用
                    currentMa = raw_currentUa;
                }
            }
        } catch (Exception ignored) {}

        try {
            Intent batteryIntent = context.registerReceiver(
                    null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int v = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                if (v > 0) voltageMv = v;
            }
        } catch (Exception ignored) {}

        // mA * mV / 1000.0 = mW；正负号沿用电流方向（充电为正、放电为负）
        float powerMw = (float) currentMa * voltageMv / 1000.0f;
        return new float[]{(float) currentMa, (float) voltageMv, powerMw};
    }

    /**
     * 读取系统热状态
     */
    private int getThermalStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return SystemStats.THERMAL_UNKNOWN;
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return SystemStats.THERMAL_UNKNOWN;
            return pm.getCurrentThermalStatus();
        } catch (Exception e) {
            return SystemStats.THERMAL_UNKNOWN;
        }
    }

    /**
     * 读取整机内存信息：已用字节数与总字节数。
     * <p>
     * 注意 {@code availMem} 已经把可回收的 cache/buffer 计入可用，因此 used = total - avail
     * 反映的是"真正给系统/前台 App 让路后的占用量"，更接近 Settings 中的展示口径。
     *
     * @return {@code [used, total]}（字节）；读取失败返回 {@code {0L, 0L}}。
     */
    private long[] getMemoryInfo() {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return new long[]{0L, 0L};
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(memInfo);
            if (memInfo.totalMem <= 0L) return new long[]{0L, 0L};
            long used = memInfo.totalMem - memInfo.availMem;
            if (used < 0L) used = 0L;
            if (used > memInfo.totalMem) used = memInfo.totalMem;
            return new long[]{used, memInfo.totalMem};
        } catch (Exception e) {
            return new long[]{0L, 0L};
        }
    }
}