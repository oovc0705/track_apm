package com.example.trackserver.controller;

import com.example.trackserver.entity.SpanEntity;
import com.example.trackserver.sampling.AdaptiveSampler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SpanCollectController {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AdaptiveSampler adaptiveSampler;

    private static final String QUEUE_KEY = "queue:apm:spans";

    @PostMapping("/collect")
    public ResponseEntity<String> collect(@RequestBody List<SpanEntity> spans) {
        if (spans == null || spans.isEmpty()) {
            return ResponseEntity.badRequest().body("spans must not be empty");
        }

        int accepted = 0;
        int sampled = 0;

        // 批量收集通过采样的 span JSON，一次性 Pipeline 写入 Redis
        List<String> batchJson = new ArrayList<>(spans.size());
        for (SpanEntity span : spans) {
            if (!adaptiveSampler.shouldSample(span)) {
                sampled++;
                continue;
            }

            try {
                String json = objectMapper.writeValueAsString(span);
                batchJson.add(json);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize span: {}", span.getSpanId(), e);
            }
        }

        // 使用 Redis Pipeline 批量 LPUSH，将 N 次网络往返合并为 1 次
        if (!batchJson.isEmpty()) {
            stringRedisTemplate.executePipelined(new org.springframework.data.redis.core.SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> Object execute(org.springframework.data.redis.core.RedisOperations<K, V> operations) {
                    org.springframework.data.redis.core.RedisOperations<String, String> ops =
                            (org.springframework.data.redis.core.RedisOperations<String, String>) operations;
                    for (String json : batchJson) {
                        ops.opsForList().leftPush(QUEUE_KEY, json);
                    }
                    return null;
                }
            });
            accepted = batchJson.size();
        }

        if (sampled > 0) {
            log.info("Adaptive sampling: accepted={}, dropped by sampling={}", accepted, sampled);
        } else {
            log.debug("Pipeline pushed {} spans to Redis queue", accepted);
        }

        return ResponseEntity.accepted().body("accepted " + accepted + " spans, sampled out " + sampled);
    }
}
