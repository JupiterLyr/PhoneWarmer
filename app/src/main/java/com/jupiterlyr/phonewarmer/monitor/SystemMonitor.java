package com.jupiterlyr.phonewarmer.monitor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.jupiterlyr.phonewarmer.workload.GPURenderEngine;

import java.io.BufferedReader;
import java.io.FileReader;

public class SystemMonitor {

    public interface Listener {
        void onSystemStatsChanged(SystemStats stats);
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

        return new SystemStats(cpuTemp, cpuLoad, gpuLoad);
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
     * 计算本进程在过去采样间隔内的 CPU 占用率（百分比，0~100）。
     *
     * Android 8.0+ 限制了对 /proc/stat 等全局信息的访问（EACCES），因此这里改为
     * 通过 {@link android.os.Process#getElapsedCpuTime()} 读取本进程已累计的 CPU 时间，
     * 与墙钟流逝时间的比值即为本进程的 CPU 占用，再除以核心数归一化到“总体百分比”。
     */
    private float getCpuLoad() {
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
}