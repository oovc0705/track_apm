package com.example.trackserver.alert;

import com.example.trackserver.entity.SpanEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/**
 * 故障主动告警引擎
 * <p>
 * 基于内存滑动窗口，实时监控每个服务的错误率和平均耗时。
 * 当某服务在过去 1 分钟内错误率连续超过阈值，或平均耗时突增时，
 * 立即触发 Webhook 告警推送到钉钉/企业微信/飞书群。
 * <p>
 * 窗口机制：每个服务维护一个环形缓冲区，每 10 秒一个桶，共 6 个桶覆盖 60 秒。
 */
@Slf4j
@Component
public class AlertEngine {

    /** 告警总开关 */
    @Value("${alert.enabled:true}")
    private boolean enabled;

    /** 告警 Webhook URL（支持钉钉/企业微信/飞书格式） */
    @Value("${alert.webhook-url:}")
    private String webhookUrl;

    /** 错误率告警阈值（0.05 = 5%） */
    @Value("${alert.error-rate-threshold:0.05}")
    private double errorRateThreshold;

    /** 耗时突增倍数阈值（与过去窗口平均值比较） */
    @Value("${alert.latency-spike-factor:2.0}")
    private double latencySpikeFactor;

    /** 同一告警的静默期（秒），避免重复轰炸 */
    @Value("${alert.silence-period-seconds:300}")
    private long silencePeriodSeconds;

    /** 窗口大小：桶数 */
    private static final int BUCKET_COUNT = 6;

    /** 每个桶覆盖的时间（秒） */
    private static final int BUCKET_DURATION_SECONDS = 10;

    /** 每个服务的滑动窗口数据 */
    private final ConcurrentHashMap<String, ServiceWindow> serviceWindows = new ConcurrentHashMap<>();

    /** 每个服务的告警静默截止时间 */
    private final ConcurrentHashMap<String, Instant> silenceUntil = new ConcurrentHashMap<>();

    /** 异步推送线程池（虚拟线程） */
    private final ExecutorService alertExecutor;

    public AlertEngine() {
        this.alertExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 记录一个 Span 到对应服务的滑动窗口
     */
    public void recordSpan(SpanEntity span) {
        if (!enabled) return;
        if (span.getServiceName() == null) return;

        ServiceWindow window = serviceWindows.computeIfAbsent(
                span.getServiceName(), k -> new ServiceWindow());

        window.record(span);
    }

    /**
     * 定时检查（每 10 秒），评估所有服务窗口是否需要告警
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
    public void evaluateWindows() {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        Instant now = Instant.now();
        long windowDurationMs = (long) BUCKET_COUNT * BUCKET_DURATION_SECONDS * 1000;

        for (Map.Entry<String, ServiceWindow> entry : serviceWindows.entrySet()) {
            String serviceName = entry.getKey();
            ServiceWindow window = entry.getValue();

            WindowStats stats = window.aggregate(windowDurationMs, now);

            // 样本太少跳过
            if (stats.totalCount() < 10) continue;

            double errorRate = (double) stats.errorCount() / stats.totalCount();

            // 错误率超阈值
            if (errorRate >= errorRateThreshold) {
                String message = String.format(
                        "🔴 [%s] 错误率告警：%.1f%%（阈值 %.1f%%），最近60秒 %d 次请求中 %d 次异常，平均耗时 %dms",
                        serviceName,
                        errorRate * 100,
                        errorRateThreshold * 100,
                        stats.totalCount(),
                        stats.errorCount(),
                        stats.avgDuration()
                );
                tryTriggerAlert(serviceName, message, now);
                continue;
            }

            // 耗时突增检测
            if (stats.prevAvgDuration() > 0 && stats.avgDuration() > stats.prevAvgDuration() * latencySpikeFactor) {
                String message = String.format(
                        "🟡 [%s] 耗时突增告警：平均耗时 %dms（前窗口 %dms，增幅 %.1fx），最近60秒 %d 次请求",
                        serviceName,
                        stats.avgDuration(),
                        stats.prevAvgDuration(),
                        stats.avgDuration() / (double) stats.prevAvgDuration(),
                        stats.totalCount()
                );
                tryTriggerAlert(serviceName, message, now);
            }
        }
    }

    private void tryTriggerAlert(String serviceName, String message, Instant now) {
        Instant silentUntil = silenceUntil.get(serviceName);
        if (silentUntil != null && now.isBefore(silentUntil)) {
            log.debug("Alert silenced for service: {}", serviceName);
            return;
        }

        // 设置静默期
        silenceUntil.put(serviceName, now.plusSeconds(silencePeriodSeconds));

        // 异步推送告警
        alertExecutor.submit(() -> {
            try {
                sendWebhook(message);
                log.warn("Alert triggered: {}", message);
            } catch (Exception e) {
                log.error("Failed to send alert webhook for service: {}", serviceName, e);
            }
        });
    }

    /**
     * 发送 Webhook 告警（兼容钉钉/企业微信/飞书 JSON 格式）
     */
    private void sendWebhook(String message) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            // 通用 Markdown 格式，兼容钉钉/企业微信/飞书
            String body = """
                    {
                        "msgtype": "markdown",
                        "markdown": {
                            "title": "APM 告警",
                            "text": "## LightTrack APM 告警\\n\\n%s\\n\\n> 时间: %s"
                        }
                    }
                    """.formatted(message, Instant.now().toString());

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Webhook response error: status={}, body={}",
                        response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Webhook send failed", e);
        }
    }

    // ========== 内部数据结构 ==========

    /**
     * 单个服务的滑动窗口：环形桶数组
     */
    private static class ServiceWindow {
        private final Bucket[] buckets = new Bucket[BUCKET_COUNT];
        private volatile int currentBucketIndex = 0;
        private volatile Instant bucketStartTime = Instant.now();

        ServiceWindow() {
            for (int i = 0; i < BUCKET_COUNT; i++) {
                buckets[i] = new Bucket();
            }
        }

        // 无锁记录：使用 LongAdder 替代 synchronized，避免虚拟线程 pinning
        void record(SpanEntity span) {
            advanceBucket();
            Bucket bucket = buckets[currentBucketIndex];
            boolean isError = span.getStatusCode() != null && span.getStatusCode() >= 500;
            boolean hasDuration = span.getDuration() != null;
            bucket.record(isError, hasDuration ? span.getDuration() : 0, hasDuration);
        }

        /**
         * 推进桶指针（每 BUCKET_DURATION_SECONDS 秒推进一次）
         */
        private void advanceBucket() {
            Instant now = Instant.now();
            long elapsedSeconds = Duration.between(bucketStartTime, now).getSeconds();
            int steps = (int) (elapsedSeconds / BUCKET_DURATION_SECONDS);

            if (steps >= BUCKET_COUNT) {
                // 整个窗口都过期了，全部清零
                for (Bucket b : buckets) {
                    b.reset();
                }
                currentBucketIndex = 0;
                bucketStartTime = now;
            } else if (steps > 0) {
                // 部分桶过期，清零并推进
                for (int i = 0; i < steps; i++) {
                    currentBucketIndex = (currentBucketIndex + 1) % BUCKET_COUNT;
                    buckets[currentBucketIndex].reset();
                }
                bucketStartTime = bucketStartTime.plusSeconds(steps * BUCKET_DURATION_SECONDS);
            }
        }

        /**
         * 聚合整个窗口的统计数据
         */
        WindowStats aggregate(long windowDurationMs, Instant now) {
            long totalCount = 0;
            long errorCount = 0;
            long totalDuration = 0;
            long durationCount = 0;
            long prevAvgDurationSum = 0;
            int prevBucketCount = 0;

            for (Bucket b : buckets) {
                totalCount += b.getTotalCount();
                errorCount += b.getErrorCount();
                totalDuration += b.getTotalDuration();
                durationCount += b.getDurationCount();
                // 用每个桶的历史快照来计算前窗口的平均耗时
                if (b.snapshotAvgDuration > 0) {
                    prevAvgDurationSum += b.snapshotAvgDuration;
                    prevBucketCount++;
                }
            }

            long avgDuration = durationCount > 0 ? totalDuration / durationCount : 0;
            long prevAvgDuration = prevBucketCount > 0 ? prevAvgDurationSum / prevBucketCount : 0;

            return new WindowStats(totalCount, errorCount, avgDuration, prevAvgDuration);
        }
    }

    private static class Bucket {
        private final LongAdder totalCount = new LongAdder();
        private final LongAdder errorCount = new LongAdder();
        private final LongAdder totalDuration = new LongAdder();
        private final LongAdder durationCount = new LongAdder();

        // 保存上一轮的快照用于耗时突增比较（仅单线程访问，不需要原子类）
        volatile long snapshotAvgDuration = 0;

        void record(boolean isError, long duration, boolean hasDuration) {
            totalCount.increment();
            if (isError) errorCount.increment();
            if (hasDuration) {
                totalDuration.add(duration);
                durationCount.increment();
            }
        }

        long getTotalCount() { return totalCount.sum(); }
        long getErrorCount() { return errorCount.sum(); }
        long getTotalDuration() { return totalDuration.sum(); }
        long getDurationCount() { return durationCount.sum(); }

        void reset() {
            snapshotAvgDuration = getDurationCount() > 0 ? getTotalDuration() / getDurationCount() : 0;
            totalCount.reset();
            errorCount.reset();
            totalDuration.reset();
            durationCount.reset();
        }
    }

    record WindowStats(long totalCount, long errorCount, long avgDuration, long prevAvgDuration) {}
}
