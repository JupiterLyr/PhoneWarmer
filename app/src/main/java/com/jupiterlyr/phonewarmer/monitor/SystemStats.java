package com.jupiterlyr.phonewarmer.monitor;

public class SystemStats {
    private final float cpuTemperature;
    private final float cpuLoad;
    private final float gpuLoad;

    public SystemStats(float cpuTemperature, float cpuLoad, float gpuLoad) {
        this.cpuTemperature = cpuTemperature;
        this.cpuLoad = cpuLoad;
        this.gpuLoad = gpuLoad;
    }

    public float getCpuTemperature() {
        return cpuTemperature;
    }

    public float getCpuLoad() {
        return cpuLoad;
    }

    public float getGpuLoad() {
        return gpuLoad;
    }
}