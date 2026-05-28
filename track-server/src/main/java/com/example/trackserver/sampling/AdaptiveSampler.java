package com.example.trackserver.sampling;

import com.example.trackserver.entity.SpanEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 智能自适应采样器
 * <p>
 * 采样策略：
 * <ul>
 *   <li>病态请求（状态码 >= 500、耗时 > 阈值）-> 100% 强制采样</li>
 *   <li>健康请求（状态码 200 且耗时极短）-> 按低概率采样（默认 5%）</li>
 *   <li>其他情况 -> 100% 采样（保障可观测性）</li>
 * </ul>
 */
@Slf4j
@Component
public class AdaptiveSampler {

    @Value("${sampling.healthy-rate:0.05}")
    private double healthyRate;

    @Value("${sampling.slow-threshold-ms:500}")
    private long slowThresholdMs;

    /**
     * 判断是否应该采样该 Span
     *
     * @param span 待判断的 Span
     * @return true 表示采样（保留），false 表示丢弃
     */
    public boolean shouldSample(SpanEntity span) {
        // 病态请求 -> 100% 强制采样
        if (isUnhealthy(span)) {
            log.debug("Force sampling unhealthy span: spanId={}, statusCode={}, duration={}ms",
                    span.getSpanId(), span.getStatusCode(), span.getDuration());
            return true;
        }

        // 健康请求 -> 低概率采样
        if (isHealthy(span)) {
            boolean sampled = ThreadLocalRandom.current().nextDouble() < healthyRate;
            if (!sampled) {
                log.debug("Dropped healthy span by sampling: spanId={}, serviceName={}",
                        span.getSpanId(), span.getServiceName());
            }
            return sampled;
        }

        // 其他情况（3xx 重定向、4xx 客户端错误等）-> 100% 采样
        return true;
    }

    /**
     * 判断是否为病态请求：状态码 >= 500 或耗时超过阈值
     */
    private boolean isUnhealthy(SpanEntity span) {
        if (span.getStatusCode() != null && span.getStatusCode() >= 500) {
            return true;
        }
        if (span.getDuration() != null && span.getDuration() > slowThresholdMs) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否为健康请求：状态码 200 且耗时极短
     */
    private boolean isHealthy(SpanEntity span) {
        if (span.getStatusCode() == null) {
            return false;
        }
        return span.getStatusCode() == 200
                && (span.getDuration() == null || span.getDuration() <= slowThresholdMs);
    }
}
