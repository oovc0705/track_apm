package com.example.trackserver.jvm.dto;

import java.util.List;

public record JvmMetricsSnapshot(
        String pid,
        String jvmName,
        long timestamp,
        long heapUsed,
        long heapMax,
        double heapUsagePercent,
        long nonHeapUsed,
        long nonHeapCommitted,
        long gcTotalCount,
        long gcTotalTimeMs,
        List<GcStat> gcDetails,
        double systemCpuLoad,
        double processCpuLoad,
        int availableProcessors,
        long uptimeMs
) {
    /**
     * 将 -1 的 CPU 值（表示不可用）转换为 null，前端据此做降级展示。
     */
    public Double systemCpuLoadSafe() {
        return systemCpuLoad >= 0 ? systemCpuLoad : null;
    }

    public Double processCpuLoadSafe() {
        return processCpuLoad >= 0 ? processCpuLoad : null;
    }

    public double heapUsedMb() {
        return heapUsed / 1024.0 / 1024.0;
    }

    public double heapMaxMb() {
        return heapMax / 1024.0 / 1024.0;
    }

    public record GcStat(
            String name,
            long count,
            long timeMs
    ) {}
}
