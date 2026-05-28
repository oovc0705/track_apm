package com.example.trackserver.jvm.service;

import com.example.trackserver.jvm.dto.ThreadDumpResponse.DeadlockCycle;
import com.example.trackserver.jvm.dto.ThreadInfoSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.LockInfo;
import java.lang.management.ThreadInfo;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 线程转储分析器
 * <p>
 * 核心职责：
 * 1. 从全量 ThreadInfo[] 中自动找出所有 BLOCKED 状态的线程
 * 2. 通过构建 "线程 → 持有锁 → 等待锁" 依赖图，自动检测死锁环
 * 3. 定位导致死锁的罪魁祸首线程与代码行数
 */
@Slf4j
@Service
public class ThreadDumpAnalyzer {

    /**
     * 分析死锁，构建死锁环链路描述
     *
     * @param deadlockIds JVM 检测到的死锁线程 ID 数组（可能为 null）
     * @param allThreads  全量线程快照
     * @return 死锁环列表（可能为空列表）
     */
    public List<DeadlockCycle> analyzeDeadlocks(long[] deadlockIds, ThreadInfo[] allThreads) {
        if (deadlockIds == null || deadlockIds.length == 0 || allThreads == null) {
            return List.of();
        }

        // 构建 threadId -> ThreadInfo 的映射
        Map<Long, ThreadInfo> threadMap = Arrays.stream(allThreads)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(ThreadInfo::getThreadId, t -> t, (a, b) -> a));

        // 构建锁持有关系图: lockIdentityHashCode -> owningThreadId
        Map<String, Long> lockOwnerMap = new HashMap<>();
        for (ThreadInfo info : allThreads) {
            if (info == null) continue;
            if (info.getLockedMonitors() != null) {
                for (var monitor : info.getLockedMonitors()) {
                    String lockKey = monitor.getClassName() + "@" + monitor.getIdentityHashCode();
                    lockOwnerMap.put(lockKey, info.getThreadId());
                }
            }
            if (info.getLockedSynchronizers() != null) {
                for (var sync : info.getLockedSynchronizers()) {
                    String lockKey = sync.getClassName() + "@" + sync.getIdentityHashCode();
                    lockOwnerMap.put(lockKey, info.getThreadId());
                }
            }
        }

        // 构建等待图: waitingThreadId -> (lockedThreadId, lockDescription)
        // 然后沿等待图遍历，找出环
        Set<Long> deadlockSet = Arrays.stream(deadlockIds).boxed().collect(Collectors.toSet());
        List<DeadlockCycle> cycles = new ArrayList<>();
        Set<Long> visited = new HashSet<>();

        for (long startId : deadlockIds) {
            if (visited.contains(startId)) continue;

            List<Long> path = new ArrayList<>();
            Set<Long> pathSet = new HashSet<>();
            long current = startId;

            while (current != -1 && !visited.contains(current) && pathSet.add(current)) {
                path.add(current);
                ThreadInfo info = threadMap.get(current);
                if (info == null) break;

                String waitedLock = extractWaitedLockKey(info);
                if (waitedLock == null) break;

                Long next = lockOwnerMap.get(waitedLock);
                if (next == null || next.equals(current)) break;
                current = next;
            }

            // 检查是否形成环（回到起点）
            if (path.size() >= 2 && current == startId) {
                visited.addAll(path);
                DeadlockCycle cycle = buildCycleDescription(path, threadMap);
                cycles.add(cycle);
            } else {
                visited.addAll(path);
            }
        }

        return cycles;
    }

    /**
     * 统计各线程状态的数量分布
     */
    public Map<String, Long> summarizeThreadStates(List<ThreadInfoSnapshot> threads) {
        if (threads == null) return Map.of();
        return threads.stream()
                .collect(Collectors.groupingBy(ThreadInfoSnapshot::state, Collectors.counting()));
    }

    // ── 内部工具方法 ──

    /**
     * 从 ThreadInfo 提取线程当前等待的锁标识
     */
    private String extractWaitedLockKey(ThreadInfo info) {
        LockInfo lockInfo = info.getLockInfo();
        if (lockInfo != null) {
            return lockInfo.getClassName() + "@" + lockInfo.getIdentityHashCode();
        }
        // 某些情况下 lockName 非空但 lockInfo 为空
        if (info.getLockName() != null) {
            return info.getLockName();
        }
        return null;
    }

    /**
     * 构建死锁环的人类可读描述
     */
    private DeadlockCycle buildCycleDescription(List<Long> cycleIds, Map<Long, ThreadInfo> threadMap) {
        List<String> chain = new ArrayList<>();
        StringBuilder description = new StringBuilder();

        for (int i = 0; i < cycleIds.size(); i++) {
            long tid = cycleIds.get(i);
            ThreadInfo info = threadMap.get(tid);
            if (info == null) continue;

            String threadDesc = String.format("\"%s\" (id=%d)", info.getThreadName(), tid);
            chain.add(threadDesc);

            // 找到阻塞位置（栈顶帧）
            String blockLocation = "unknown";
            if (info.getStackTrace() != null && info.getStackTrace().length > 0) {
                StackTraceElement topFrame = info.getStackTrace()[0];
                blockLocation = topFrame.getClassName() + "." + topFrame.getMethodName()
                        + "(" + topFrame.getFileName() + ":" + topFrame.getLineNumber() + ")";
            }

            String lockDesc = "unknown lock";
            if (info.getLockInfo() != null) {
                lockDesc = info.getLockInfo().getClassName() + "@" + info.getLockInfo().getIdentityHashCode();
            }

            description.append(String.format("  [%d] %s 阻塞于 %s，等待锁 %s，阻塞位置: %s\n",
                    i + 1, threadDesc, lockDesc, lockDesc, blockLocation));

            // 找出该线程持有的锁（通过 locked monitors 和 locked synchronizers）
            if (info.getLockedMonitors() != null) {
                for (var monitor : info.getLockedMonitors()) {
                    String heldLock = monitor.getClassName() + "@" + monitor.getIdentityHashCode();
                    description.append(String.format("       └─ 持有 Monitor 锁: %s (深度=%d, 获取位置: %s)\n",
                            heldLock, monitor.getLockedStackDepth(),
                            monitor.getLockedStackFrame() != null ? monitor.getLockedStackFrame().toString() : "unknown"));
                }
            }
            if (info.getLockedSynchronizers() != null) {
                for (var sync : info.getLockedSynchronizers()) {
                    description.append(String.format("       └─ 持有 Synchronizer 锁: %s\n",
                            sync.getClassName() + "@" + sync.getIdentityHashCode()));
                }
            }
        }

        return new DeadlockCycle(chain, description.toString().trim(), "CRITICAL");
    }
}
