package com.example.trackserver.jvm;

import com.example.trackserver.jvm.dto.JvmMetricsSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.*;
import java.util.ArrayList;
import java.util.List;

/**
 * JVM 指标采集器
 * <p>
 * 每 10 秒通过 JMX 抓取受控端微服务的：
 * - 堆内存使用率（Heap Memory）
 * - 垃圾回收耗时与次数（GC Count / Time）
 * - CPU 负载（SystemCpuLoad / ProcessCpuLoad）
 * <p>
 * 采集结果发布到 {@link JvmMetricsBroadcaster}，由其通过 WebSocket 推送给前端。
 */
@Slf4j
@Component
public class JvmMetricsCollector {

    private final MemoryMXBean memoryMxBean = ManagementFactory.getMemoryMXBean();
    private final List<GarbageCollectorMXBean> gcMxBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private final com.sun.management.OperatingSystemMXBean osMxBean =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();

    private final JvmMetricsBroadcaster broadcaster;

    public JvmMetricsCollector(JvmMetricsBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Scheduled(fixedRate = 10_000, initialDelay = 5_000)
    public void collectMetrics() {
        try {
            JvmMetricsSnapshot snapshot = buildSnapshot();
            broadcaster.broadcast(snapshot);
            log.debug("JVM metrics collected: heapUsed={}MB, cpuLoad={}%, gcCount={}",
                    String.format("%.1f", snapshot.heapUsedMb()),
                    String.format("%.1f", snapshot.systemCpuLoad() * 100),
                    snapshot.gcTotalCount());
        } catch (Exception e) {
            log.error("Failed to collect JVM metrics", e);
        }
    }

    private JvmMetricsSnapshot buildSnapshot() {
        // —— 堆内存 ——
        MemoryUsage heapUsage = memoryMxBean.getHeapMemoryUsage();
        long heapUsed = heapUsage.getUsed();
        long heapMax = heapUsage.getMax();
        double heapUsagePercent = heapMax > 0 ? (double) heapUsed / heapMax * 100 : 0;

        // —— GC 统计 ——
        long gcTotalCount = 0;
        long gcTotalTime = 0;
        List<JvmMetricsSnapshot.GcStat> gcStats = new ArrayList<>();
        for (GarbageCollectorMXBean gcBean : gcMxBeans) {
            long count = gcBean.getCollectionCount();
            long time = gcBean.getCollectionTime();
            gcTotalCount += count;
            gcTotalTime += time;
            gcStats.add(new JvmMetricsSnapshot.GcStat(gcBean.getName(), count, time));
        }

        // —— CPU 负载 ——
        double systemCpuLoad = osMxBean.getCpuLoad();          // -1 = 不可用
        double processCpuLoad = osMxBean.getProcessCpuLoad();   // -1 = 不可用
        int availableProcessors = osMxBean.getAvailableProcessors();

        // —— 非堆内存 ——
        MemoryUsage nonHeapUsage = memoryMxBean.getNonHeapMemoryUsage();

        return new JvmMetricsSnapshot(
                runtimeMxBean.getName(),
                runtimeMxBean.getVmName(),
                System.currentTimeMillis(),
                heapUsed, heapMax, heapUsagePercent,
                nonHeapUsage.getUsed(), nonHeapUsage.getCommitted(),
                gcTotalCount, gcTotalTime, gcStats,
                systemCpuLoad, processCpuLoad, availableProcessors,
                runtimeMxBean.getUptime()
        );
    }
}
