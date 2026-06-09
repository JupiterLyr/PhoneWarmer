package com.jupiterlyr.phonewarmer.monitor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.jupiterlyr.phonewarmer.workload.GPURenderEngine;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.regex.Pattern;

public class SystemMonitor {

    public interface Listener {
        void onSystemStatsChanged(SystemStats stats);
    }

    private final Context context;
    private final Handler handler;
    private Listener listener;
    private boolean monitoring = false;
    private static final long UPDATE_INTERVAL = 1000; // 1秒更新一次
    private long prevIdle = 0;
    private long prevTotal = 0;

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

    private float getCpuLoad() {
        try {
            // 读取/proc/stat获取CPU使用率
            BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"));
            String line = reader.readLine();
            reader.close();

            if (line != null && line.startsWith("cpu ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 8) {
                    long user = Long.parseLong(parts[1]);
                    long nice = Long.parseLong(parts[2]);
                    long system = Long.parseLong(parts[3]);
                    long idle = Long.parseLong(parts[4]);
                    long iowait = Long.parseLong(parts[5]);
                    long irq = Long.parseLong(parts[6]);
                    long softirq = Long.parseLong(parts[7]);

                    long total = user + nice + system + idle + iowait + irq + softirq;

                    // 计算增量
                    long totalDiff = total - prevTotal;
                    long idleDiff = idle - prevIdle;

                    // 保存当前值供下次使用
                    prevTotal = total;
                    prevIdle = idle;

                    if (totalDiff > 0) {
                        long usedDiff = totalDiff - idleDiff;
                        return (float) usedDiff / totalDiff * 100.0f;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0.0f;
    }

    private float getGpuLoad() {
        return GPURenderEngine.getGpuLoad();
    }
}