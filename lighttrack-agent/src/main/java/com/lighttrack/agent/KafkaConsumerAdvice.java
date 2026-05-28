package com.lighttrack.agent;

import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka 消费者拦截切面
 * <p>
 * 拦截 Spring Kafka 的 {@code MessagingMessageListenerAdapter#onMessage} 方法，
 * 在消费线程执行业务逻辑（即 {@code @KafkaListener} 注解方法）之前，
 * 从 {@code ConsumerRecord} 的 Headers 中提取上游传递的 traceId，
 * 绑定到消费线程的 MDC 中，以此实现异步消息系统的全链路穿透。
 * <p>
 * 拦截目标：
 * <ul>
 *   <li>{@code org.springframework.kafka.listener.adapter.MessagingMessageListenerAdapter#onMessage(Object)}</li>
 *   <li>{@code org.springframework.kafka.listener.adapter.MessagingMessageListenerAdapter#onMessage(Object, Acknowledgment)}</li>
 * </ul>
 * <p>
 * 支持两种入参类型：
 * <ul>
 *   <li>{@code org.apache.kafka.clients.consumer.ConsumerRecord} — 直接从 Kafka Headers 提取</li>
 *   <li>{@code org.springframework.messaging.Message<?>} — 从 Spring Messaging Headers 提取（已被 Spring Kafka 自动映射）</li>
 * </ul>
 */
public class KafkaConsumerAdvice {

    private static final String MDC_TRACE_KEY = "traceId";

    // 反射缓存：ConsumerRecord.headers()
    private static volatile Method kafkaHeadersMethod;
    // 反射缓存：Header.key()
    private static volatile Method headerKeyMethod;
    // 反射缓存：Header.value()
    private static volatile Method headerValueMethod;
    // 反射缓存：Spring Message.getHeaders()
    private static volatile Method springGetHeadersMethod;
    private static final Object REFLECTION_LOCK = new Object();

    /**
     * 方法进入前：从消息 Headers 提取 traceId 并绑定 MDC
     * <p>
     * 优先从 ConsumerRecord / Spring Message 的 Headers 中提取上游 traceId；
     * 若不存在且当前线程 MDC 也为空，则生成全新的 traceId。
     *
     * @param data onMessage 的第一个参数（ConsumerRecord 或 Spring Message 实例）
     * @return 方法进入前的 MDC traceId 值，用于 onExit 时恢复（防止嵌套拦截污染）
     */
    @Advice.OnMethodEnter
    public static String onEnter(@Advice.Argument(0) Object data) {
        String previousTraceId = MDC.get(MDC_TRACE_KEY);
        try {
            String traceId = extractTraceId(data);
            if (traceId != null && !traceId.isBlank()) {
                MDC.put(MDC_TRACE_KEY, traceId);
            } else if (previousTraceId == null) {
                // 消息头无 traceId 且 MDC 为空时，生成新的 traceId（消费链路起点）
                MDC.put(MDC_TRACE_KEY, generateTraceId());
            }
            return previousTraceId;
        } catch (Throwable t) {
            System.err.println("[LightTrack Agent] Kafka Consumer traceId 提取异常: " + t.getMessage());
            return previousTraceId;
        }
    }

    /**
     * 方法退出后（含异常退出）：恢复 MDC 状态
     * <p>
     * 通过 {@code @Advice.Enter} 接收 onEnter 返回的 previousTraceId，
     * 恢复外层 MDC 上下文，防止线程池复用导致的 traceId 泄漏。
     *
     * @param previousTraceId 方法进入前的原始 traceId（可能为 null）
     * @param throwable       目标方法抛出的异常（可能为 null）
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter String previousTraceId,
                              @Advice.Thrown Throwable throwable) {
        try {
            if (throwable != null) {
                System.err.println("[LightTrack Agent] Kafka 消费异常: "
                        + throwable.getClass().getName() + " - " + throwable.getMessage());
            }
            if (previousTraceId != null) {
                MDC.put(MDC_TRACE_KEY, previousTraceId);
            } else {
                MDC.remove(MDC_TRACE_KEY);
            }
        } catch (Throwable t) {
            System.err.println("[LightTrack Agent] Kafka Consumer MDC 清理异常: " + t.getMessage());
        }
    }

    // ======================== 内部方法 ========================

    /**
     * 根据入参类型分发 traceId 提取逻辑
     */
    private static String extractTraceId(Object data) {
        if (data == null) {
            return null;
        }
        String className = data.getClass().getName();

        // Case 1: ConsumerRecord — 从 Kafka Headers 迭代查找
        if (className.contains("ConsumerRecord")) {
            return extractFromKafkaHeaders(data);
        }

        // Case 2: Spring Messaging Message<?> — 从 Spring Messaging Headers 提取
        if (className.startsWith("org.springframework.messaging")) {
            return extractFromSpringMessagingHeaders(data);
        }

        return null;
    }

    /**
     * 从 Kafka ConsumerRecord 的 Headers 中提取 traceId
     * <p>
     * 调用链：{@code record.headers()} → 遍历 Iterable&lt;Header&gt;
     * → 匹配 {@code header.key().equals("traceId")} → {@code new String(header.value())}
     */
    private static String extractFromKafkaHeaders(Object record) {
        try {
            // 反射调用 record.headers()
            Method hm = kafkaHeadersMethod;
            if (hm == null) {
                synchronized (REFLECTION_LOCK) {
                    hm = kafkaHeadersMethod;
                    if (hm == null) {
                        hm = record.getClass().getMethod("headers");
                        kafkaHeadersMethod = hm;
                    }
                }
            }
            Object headers = hm.invoke(record);
            if (!(headers instanceof Iterable<?> iterable)) {
                return null;
            }

            // 首次访问时缓存 Header.key() 和 Header.value() 方法
            Method km = headerKeyMethod;
            Method vm = headerValueMethod;
            if (km == null || vm == null) {
                synchronized (REFLECTION_LOCK) {
                    km = headerKeyMethod;
                    vm = headerValueMethod;
                    if (km == null || vm == null) {
                        for (Object header : iterable) {
                            if (header == null) continue;
                            km = header.getClass().getMethod("key");
                            vm = header.getClass().getMethod("value");
                            headerKeyMethod = km;
                            headerValueMethod = vm;
                            break;
                        }
                    }
                }
            }
            if (km == null || vm == null) {
                return null;
            }

            // 遍历所有 Header，查找 key == "traceId"
            for (Object header : (Iterable<?>) headers) {
                if (header == null) continue;
                String key = (String) km.invoke(header);
                if (MDC_TRACE_KEY.equals(key)) {
                    byte[] value = (byte[]) vm.invoke(header);
                    return value != null ? new String(value, StandardCharsets.UTF_8) : null;
                }
            }
        } catch (Exception e) {
            System.err.println("[LightTrack Agent] Kafka Headers 提取失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 从 Spring Messaging Message 的 Headers 中提取 traceId
     * <p>
     * Spring Kafka 在转换 ConsumerRecord 时，会将 Kafka Headers 映射到
     * Spring Messaging 的 {@code MessageHeaders} 中，key 保持原名。
     */
    @SuppressWarnings("unchecked")
    private static String extractFromSpringMessagingHeaders(Object message) {
        try {
            Method gm = springGetHeadersMethod;
            if (gm == null) {
                synchronized (REFLECTION_LOCK) {
                    gm = springGetHeadersMethod;
                    if (gm == null) {
                        gm = message.getClass().getMethod("getHeaders");
                        springGetHeadersMethod = gm;
                    }
                }
            }
            Object headers = gm.invoke(message);
            if (headers instanceof Map<?, ?> map) {
                Object traceId = map.get(MDC_TRACE_KEY);
                return traceId != null ? traceId.toString() : null;
            }
        } catch (Exception e) {
            System.err.println("[LightTrack Agent] Spring Messaging Headers 提取失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 生成 32 位十六进制 TraceId（与 HTTP 链路保持一致）
     */
    private static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
