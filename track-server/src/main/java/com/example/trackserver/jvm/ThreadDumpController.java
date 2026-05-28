package com.example.trackserver.jvm;

import com.example.trackserver.jvm.dto.ThreadDumpResponse;
import com.example.trackserver.jvm.dto.ThreadInfoSnapshot;
import com.example.trackserver.jvm.service.ThreadDumpAnalyzer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 线程诊断控制器
 * <p>
 * 暴露 HTTP 接口，供前端"一键线程诊断"按钮调用。
 * 通过 JMX {@link ThreadMXBean#dumpAllThreads(boolean, boolean)} 获取全量线程快照，
 * 并交由 {@link ThreadDumpAnalyzer} 进行 BLOCKED 线程过滤和死锁检测。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/profiling")
public class ThreadDumpController {

    private final ThreadDumpAnalyzer threadDumpAnalyzer;

    public ThreadDumpController(ThreadDumpAnalyzer threadDumpAnalyzer) {
        this.threadDumpAnalyzer = threadDumpAnalyzer;
    }

    /**
     * 获取全量线程快照并分析诊断结果
     *
     * @return 诊断结果 JSON（含所有线程快照 + BLOCKED 线程 + 死锁信息）
     */
    @GetMapping("/thread-dump")
    public ResponseEntity<ThreadDumpResponse> dumpThreads(
            @RequestParam(defaultValue = "true") boolean lockedMonitors,
            @RequestParam(defaultValue = "true") boolean lockedSynchronizers) {

        log.info("Thread dump requested: lockedMonitors={}, lockedSynchronizers={}", lockedMonitors, lockedSynchronizers);

        ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
        long[] threadIds = threadMxBean.getAllThreadIds();
        ThreadInfo[] allThreads = threadMxBean.dumpAllThreads(lockedMonitors, lockedSynchronizers);

        // 将 ThreadInfo[] 转换为前端友好结构
        List<ThreadInfoSnapshot> allThreadSnippets = Arrays.stream(allThreads)
                .filter(t -> t != null)
                .map(t -> ThreadInfoSnapshot.fromThreadInfo(t, threadMxBean))
                .toList();

        // 查找死锁线程
        long[] deadlockIds = threadMxBean.findDeadlockedThreads();
        List<ThreadInfoSnapshot> deadlockThreads = List.of();

        if (deadlockIds != null && deadlockIds.length > 0) {
            ThreadInfo[] deadlockInfos = threadMxBean.getThreadInfo(deadlockIds, lockedMonitors, lockedSynchronizers);
            deadlockThreads = Arrays.stream(deadlockInfos)
                    .filter(t -> t != null)
                    .map(t -> ThreadInfoSnapshot.fromThreadInfo(t, threadMxBean))
                    .toList();
        }

        // BLOCKED 线程
        List<ThreadInfoSnapshot> blockedThreads = allThreadSnippets.stream()
                .filter(t -> "BLOCKED".equals(t.state()))
                .toList();

        // 死锁分析
        List<ThreadDumpResponse.DeadlockCycle> deadlockCycles = threadDumpAnalyzer.analyzeDeadlocks(deadlockIds, allThreads);

        // 线程统计摘要
        Map<String, Long> stateSummary = threadDumpAnalyzer.summarizeThreadStates(allThreadSnippets);

        ThreadDumpResponse response = new ThreadDumpResponse(
                System.currentTimeMillis(),
                allThreadSnippets.size(),
                allThreadSnippets,
                blockedThreads,
                deadlockThreads,
                deadlockCycles,
                deadlockIds != null ? deadlockIds.length : 0,
                stateSummary
        );

        return ResponseEntity.ok(response);
    }
}
