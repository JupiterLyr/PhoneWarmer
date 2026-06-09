package com.jupiterlyr.phonewarmer.monitor;

public class BatterySnapshot {
    private final int batteryLevel;
    private final boolean isCharging;
    private final float batteryTempC;

    public BatterySnapshot(int batteryLevel, boolean isCharging, float batteryTempC) {
        this.batteryLevel = batteryLevel;
        this.isCharging = isCharging;
        this.batteryTempC = batteryTempC;
    }

    public int getBatteryLevel() {
        return batteryLevel;
    }

    public boolean isCharging() {
        return isCharging;
    }

    public float getBatteryTempC() {
        return batteryTempC;
    }
}
