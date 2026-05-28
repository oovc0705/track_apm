package com.example.trackserver.service;

import com.example.trackserver.alert.AlertEngine;
import com.example.trackserver.entity.SpanEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Span 消费者服务 —— 使用虚拟线程并发写入 PostgreSQL
 * <p>
 * 当 Redis 缓冲区有海量数据时，为每个批次开启虚拟线程去处理 I/O 阻塞，
 * 利用 Java 21 虚拟线程的轻量级特性实现高并发写入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpanConsumerService {

    private final StringRedisTemplate stringRedisTemplate;
    private final SpanBatchWriter spanBatchWriter;
    private final ObjectMapper objectMapper;
    private final AlertEngine alertEngine;

    private static final String QUEUE_KEY = "queue:apm:spans";
    private static final String PROCESSING_KEY = "queue:apm:spans:processing";

    @Value("${collector.batch-size:100}")
    private int batchSize;

    @Value("${collector.virtual-thread-pool-enabled:true}")
    private boolean virtualThreadEnabled;

    @Value("${queue-protection.max-queue-length:100000}")
    private long maxQueueLength;

    @Value("${queue-protection.alert-threshold:50000}")
    private long alertThreshold;

    /** 虚拟线程执行器 —— 用于并发写入 DB */
    private ExecutorService virtualThreadExecutor;

    private final DefaultRedisScript<Long> batchMoveScript = new DefaultRedisScript<>();

    @PostConstruct
    public void init() {
        if (virtualThreadEnabled) {
            // 使用虚拟线程执行器：可轻松创建数万个虚拟线程处理 I/O 阻塞
            virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            log.info("Virtual thread executor enabled for span consumption");
        }

        batchMoveScript.setScriptText("""
                local moved = 0
                for i = 1, tonumber(ARGV[1]) do
                    local item = redis.call('RPOPLPUSH', KEYS[1], KEYS[2])
                    if not item then break end
                    moved = moved + 1
                end
                return moved
                """);
        batchMoveScript.setResultType(Long.class);

        recoverOrphanedSpans();
    }

    private void recoverOrphanedSpans() {
        Long orphanCount = stringRedisTemplate.opsForList().size(PROCESSING_KEY);
        if (orphanCount != null && orphanCount > 0) {
            log.warn("Found {} orphaned spans in processing queue, recovering", orphanCount);
            @SuppressWarnings("unchecked")
            List<String> orphaned = stringRedisTemplate.opsForList()
                    .range(PROCESSING_KEY, 0, -1);
            if (orphaned != null) {
                processAndSave(orphaned);
            }
            stringRedisTemplate.delete(PROCESSING_KEY);
        }
    }

    @Scheduled(fixedDelayString = "${collector.poll-interval-ms:500}")
    public void consumeBatch() {
        // 0. 队列积压保护检查
        Long queueLength = stringRedisTemplate.opsForList().size(QUEUE_KEY);
        if (queueLength != null && queueLength > maxQueueLength) {
            log.warn("Queue backpressure protection triggered: queue length={} exceeds max={}. Dropping excess data.",
                    queueLength, maxQueueLength);
            // 截断队列尾部（保留最新的 maxQueueLength 条）
            stringRedisTemplate.opsForList().trim(QUEUE_KEY, 0, maxQueueLength - 1);
            return;
        }
        if (queueLength != null && queueLength > alertThreshold) {
            log.warn("Queue length {} exceeds alert threshold {}, consider scaling up consumers", queueLength, alertThreshold);
        }

        // 1. Lua 脚本原子移动：main queue -> processing queue
        Long movedCount = stringRedisTemplate.execute(
                batchMoveScript,
                List.of(QUEUE_KEY, PROCESSING_KEY),
                String.valueOf(batchSize)
        );

        if (movedCount == null || movedCount == 0) {
            return;
        }

        // 2. 从 processing queue 取出本次移动的数据
        List<String> jsonSpans = stringRedisTemplate.opsForList()
                .range(PROCESSING_KEY, 0, movedCount - 1);

        if (jsonSpans == null || jsonSpans.isEmpty()) {
            return;
        }

        if (virtualThreadEnabled && virtualThreadExecutor != null) {
            // 使用虚拟线程异步写入 DB，不阻塞调度线程
            virtualThreadExecutor.submit(() -> {
                try {
                    processAndSave(jsonSpans);
                    // 只删除本次消费的部分
                    stringRedisTemplate.opsForList().trim(PROCESSING_KEY, movedCount, -1);
                } catch (Exception e) {
                    log.error("Virtual thread batch write failed", e);
                }
            });
        } else {
            // 传统同步写入
            processAndSave(jsonSpans);
            stringRedisTemplate.opsForList().trim(PROCESSING_KEY, movedCount, -1);
        }
    }

    private void processAndSave(List<String> jsonSpans) {
        List<SpanEntity> entities = new ArrayList<>(jsonSpans.size());
        for (String json : jsonSpans) {
            try {
                SpanEntity entity = objectMapper.readValue(json, SpanEntity.class);
                entity.setId(null);
                entities.add(entity);
            } catch (Exception e) {
                log.error("Failed to deserialize span", e);
            }
        }

        if (!entities.isEmpty()) {
            spanBatchWriter.saveAll(entities);
            log.info("Batch inserted {} spans to PostgreSQL", entities.size());

            // 将每个 Span 喂入告警引擎，进行实时故障检测
            for (SpanEntity entity : entities) {
                alertEngine.recordSpan(entity);
            }
        }
    }
}
