package com.example.trackserver.jvm;

import com.example.trackserver.jvm.dto.JvmMetricsSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.*;
import java.util.List;

/**
 * JVM 健康快照 HTTP 接口（备选方案：不使用 WebSocket 时可轮询此接口）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/jvm")
public class JvmHealthController {

    @GetMapping("/metrics")
    public JvmMetricsSnapshot getMetrics() {
        MemoryMXBean memoryMxBean = ManagementFactory.getMemoryMXBean();
        var osMxBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();

        MemoryUsage heapUsage = memoryMxBean.getHeapMemoryUsage();
        long heapUsed = heapUsage.getUsed();
        long heapMax = heapUsage.getMax();

        long gcTotalCount = 0;
        long gcTotalTime = 0;
        for (var gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcTotalCount += gcBean.getCollectionCount();
            gcTotalTime += gcBean.getCollectionTime();
        }

        return new JvmMetricsSnapshot(
                runtimeMxBean.getName(),
                runtimeMxBean.getVmName(),
                System.currentTimeMillis(),
                heapUsed, heapMax,
                heapMax > 0 ? (double) heapUsed / heapMax * 100 : 0,
                memoryMxBean.getNonHeapMemoryUsage().getUsed(),
                memoryMxBean.getNonHeapMemoryUsage().getCommitted(),
                gcTotalCount, gcTotalTime, List.of(),
                osMxBean.getCpuLoad(),
                osMxBean.getProcessCpuLoad(),
                osMxBean.getAvailableProcessors(),
                runtimeMxBean.getUptime()
        );
    }
}
