package com.jupiterlyr.phonewarmer.monitor;

public class SystemStats {

    /** 系统热状态未知/不支持（API < 29 或读取失败时使用） */
    public static final int THERMAL_UNKNOWN = -1;

    private final float cpuTemperature;
    private final float cpuLoad;
    private final float gpuLoad;
    private final long memoryUsedBytes;
    private final long memoryTotalBytes;
    private final float cpuFreqMhz;
    private final float cpuFreqRatio;
    private final float batteryCurrentMa;
    private final float batteryPowerW;
    private final int thermalStatus;

    public SystemStats(float cpuTemperature,
                       float cpuLoad,
                       float gpuLoad,
                       long memoryUsedBytes,
                       long memoryTotalBytes,
                       float cpuFreqMhz,
                       float cpuFreqRatio,
                       float batteryCurrentMa,
                       float batteryPowerW,
                       int thermalStatus) {
        this.cpuTemperature = cpuTemperature;
        this.cpuLoad = cpuLoad;
        this.gpuLoad = gpuLoad;
        this.memoryUsedBytes = memoryUsedBytes;
        this.memoryTotalBytes = memoryTotalBytes;
        this.cpuFreqMhz = cpuFreqMhz;
        this.cpuFreqRatio = cpuFreqRatio;
        this.batteryCurrentMa = batteryCurrentMa;
        this.batteryPowerW = batteryPowerW;
        this.thermalStatus = thermalStatus;
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

    /** 整机已占用内存（字节）。读取失败时为 0。 */
    public long getMemoryUsedBytes() {
        return memoryUsedBytes;
    }

    /** 整机总内存（字节）。读取失败时为 0。 */
    public long getMemoryTotalBytes() {
        return memoryTotalBytes;
    }

    /** 当前所有核心中最高的瞬时频率（MHz）。读取失败时为 0。 */
    public float getCpuFreqMhz() {
        return cpuFreqMhz;
    }

    /** 当前最高核频率相对于其最大可用频率的占比（0~100）。读取失败时为 0。 */
    public float getCpuFreqRatio() {
        return cpuFreqRatio;
    }

    /**
     * 电池瞬时电流（mA）。
     * <p>
     * 约定：放电时为<b>负数</b>，充电时为<b>正数</b>（与 Android 官方约定一致）。
     * 部分 ROM 符号反转，UI 层显示时取绝对值更稳妥。
     */
    public float getBatteryCurrentMa() {
        return batteryCurrentMa;
    }

    /** 电池瞬时功率（W），= 电流 × 电压，符号同电流。 */
    public float getBatteryPowerW() {
        return batteryPowerW;
    }

    /**
     * 系统热状态，取值与 {@link android.os.PowerManager#getCurrentThermalStatus()} 一致；
     * 在 API < 29 或读取失败时为 {@link #THERMAL_UNKNOWN}。
     */
    public int getThermalStatus() {
        return thermalStatus;
    }
}