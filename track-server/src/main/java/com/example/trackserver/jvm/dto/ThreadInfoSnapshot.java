package com.example.trackserver.jvm.dto;

import java.lang.management.LockInfo;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;

public record ThreadInfoSnapshot(
        long threadId,
        String threadName,
        String state,
        long cpuTimeNanos,
        long userTimeNanos,
        boolean isInNative,
        boolean isSuspended,
        int priority,
        String lockName,
        String lockOwnerName,
        long lockOwnerId,
        String waitedLockName,
        long waitedCount,
        long waitedTimeMs,
        long blockedCount,
        long blockedTimeMs,
        String[] stackTrace,
        LockInfoSnapshot[] lockedSynchronizers,
        MonitorInfoSnapshot[] lockedMonitors
) {
    public static ThreadInfoSnapshot fromThreadInfo(ThreadInfo info, ThreadMXBean threadMxBean) {
        LockInfo lockInfo = info.getLockInfo();
        MonitorInfo[] monitorInfos = info.getLockedMonitors();

        LockInfoSnapshot[] lockedSyncs = null;
        if (info.getLockedSynchronizers() != null) {
            lockedSyncs = Arrays.stream(info.getLockedSynchronizers())
                    .map(LockInfoSnapshot::fromLockInfo)
                    .toArray(LockInfoSnapshot[]::new);
        }

        MonitorInfoSnapshot[] lockedMonitors = null;
        if (monitorInfos != null) {
            lockedMonitors = Arrays.stream(monitorInfos)
                    .map(MonitorInfoSnapshot::fromMonitorInfo)
                    .toArray(MonitorInfoSnapshot[]::new);
        }

        // 将堆栈格式化为字符串数组（含行号）
        String[] stackTrace = null;
        if (info.getStackTrace() != null) {
            stackTrace = Arrays.stream(info.getStackTrace())
                    .map(ste -> ste.toString())
                    .toArray(String[]::new);
        }

        return new ThreadInfoSnapshot(
                info.getThreadId(),
                info.getThreadName(),
                info.getThreadState().name(),
                threadMxBean.isThreadCpuTimeSupported() ? threadMxBean.getThreadCpuTime(info.getThreadId()) : -1L,
                threadMxBean.isThreadCpuTimeSupported() ? threadMxBean.getThreadUserTime(info.getThreadId()) : -1L,
                info.isInNative(),
                info.isSuspended(),
                info.getPriority(),
                lockInfo != null ? lockInfo.toString() : null,
                info.getLockOwnerName(),
                info.getLockOwnerId(),
                info.getLockName(),  // waited on
                info.getWaitedCount(),
                info.getWaitedTime() > -1 ? info.getWaitedTime() : -1,
                info.getBlockedCount(),
                info.getBlockedTime() > -1 ? info.getBlockedTime() : -1,
                stackTrace,
                lockedSyncs,
                lockedMonitors
        );
    }

    /**
     * 获取栈顶帧（阻塞/等待位置），格式: "类名.方法名(文件名:行号)"
     */
    public String topFrame() {
        if (stackTrace != null && stackTrace.length > 0) {
            return stackTrace[0];
        }
        return null;
    }
}
