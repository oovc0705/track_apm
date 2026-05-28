package com.example.trackserver.jvm.dto;

import java.util.List;
import java.util.Map;

/**
 * 线程诊断 API 响应体
 */
public record ThreadDumpResponse(
        long timestamp,
        int totalThreadCount,
        List<ThreadInfoSnapshot> allThreads,
        List<ThreadInfoSnapshot> blockedThreads,
        List<ThreadInfoSnapshot> deadlockThreads,
        List<DeadlockCycle> deadlockCycles,
        int deadlockCycleCount,
        Map<String, Long> stateSummary
) {
    /**
     * 死锁环描述：一组互相等待的线程构成一个环。
     */
    public record DeadlockCycle(
            List<String> cycleChain,
            String description,
            String severity
    ) {}
}
