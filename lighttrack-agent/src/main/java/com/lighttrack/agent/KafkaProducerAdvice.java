package com.lighttrack.agent;

import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Kafka 生产者拦截切面
 * <p>
 * 拦截 {@code org.apache.kafka.clients.producer.KafkaProducer#send} 方法，
 * 在消息发送前从当前线程的 MDC 中提取 traceId，注入到
 * {@code ProducerRecord} 的 Headers 中，实现异步消息链路传递。
 * <p>
 * 拦截目标：
 * <ul>
 *   <li>{@code KafkaProducer#send(ProducerRecord)}</li>
 *   <li>{@code KafkaProducer#send(ProducerRecord, Callback)}</li>
 * </ul>
 */
public class KafkaProducerAdvice {

    private static final String MDC_TRACE_KEY = "traceId";

    // 反射缓存：ProducerRecord.headers() → Headers
    private static volatile Method headersMethod;
    // 反射缓存：Headers.add(String, byte[])
    private static volatile Method addHeaderMethod;
    private static final Object REFLECTION_LOCK = new Object();

    /**
     * 方法进入前：从 MDC 提取 traceId 并注入 ProducerRecord Headers
     *
     * @param record KafkaProducer#send 的第一个参数（ProducerRecord 实例）
     */
    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Object record) {
        try {
            String traceId = MDC.get(MDC_TRACE_KEY);
            if (traceId == null || traceId.isBlank()) {
                return;
            }
            injectTraceId(record, traceId);
        } catch (Throwable t) {
            System.err.println("[LightTrack Agent] Kafka Producer traceId 注入异常: " + t.getMessage());
        }
    }

    /**
     * 将 traceId 注入到 ProducerRecord 的 Headers 中
     * <p>
     * 调用链：{@code record.headers().add("traceId", traceIdBytes)}
     * <p>
     * 使用双重检查锁 + 反射缓存，避免每次发送消息都执行反射查找。
     */
    private static void injectTraceId(Object record, String traceId) {
        try {
            // 1. 调用 record.headers() 获取 Headers 对象
            Method hm = headersMethod;
            if (hm == null) {
                synchronized (REFLECTION_LOCK) {
                    hm = headersMethod;
                    if (hm == null) {
                        hm = record.getClass().getMethod("headers");
                        headersMethod = hm;
                    }
                }
            }
            Object headers = hm.invoke(record);

            // 2. 调用 headers.add("traceId", traceId.getBytes())
            Method am = addHeaderMethod;
            if (am == null) {
                synchronized (REFLECTION_LOCK) {
                    am = addHeaderMethod;
                    if (am == null) {
                        // Headers 接口的 add 方法签名
                        am = headers.getClass().getMethod("add", String.class, byte[].class);
                        addHeaderMethod = am;
                    }
                }
            }
            am.invoke(headers, MDC_TRACE_KEY, traceId.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[LightTrack Agent] Kafka Headers 注入失败: " + e.getMessage());
        }
    }
}
