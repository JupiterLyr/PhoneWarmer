package com.jupiterlyr.phonewarmer.monitor;

/**
 * 标识 {@link SystemMonitor} 当前 CPU 负荷数据的来源。
 * <ul>
 *   <li>{@link #SYSTEM}：来自 {@code /proc/stat} 的整机 CPU 使用率</li>
 *   <li>{@link #PROCESS}：本进程级 CPU 使用率（fallback）</li>
 * </ul>
 */
public enum CpuSource {
    SYSTEM,
    PROCESS
}
