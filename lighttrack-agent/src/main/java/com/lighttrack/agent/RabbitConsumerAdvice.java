package com.lighttrack.agent;

import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/**
 * RabbitMQ 消费者拦截切面
 * <p>
 * 拦截 Spring AMQP 的 {@code MessagingMessageListenerAdapter#onMessage} 方法，
 * 在消费线程执行业务逻辑（即 {@code @RabbitListener} 注解方法）之前，
 * 从 AMQP {@code Message} 的 {@code MessageProperties.headers} 中提取上游传递的 traceId，
 * 绑定到消费线程的 MDC 中，以此实现异步消息系统的全链路穿透。
 * <p>
 * 拦截目标：
 * <ul>
 *   <li>{@code org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter#onMessage(Message)}</li>
 *   <li>{@code org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter#onMessage(Message, Channel)}</li>
 * </ul>
 * <p>
 * 在两种重载中，第一个参数始终是 AMQP {@code Message} 对象。
 */
public class RabbitConsumerAdvice {

    private static final String MDC_TRACE_KEY = "traceId";

    // 反射缓存：Message.getMessageProperties()
    private static volatile Method getMessagePropertiesMethod;
    // 反射缓存：MessageProperties.getHeaders()
    private static volatile Method getHeadersMethod;
    private static final Object REFLECTION_LOCK = new Object();

    /**
     * 方法进入前：从 AMQP Message Headers 提取 traceId 并绑定 MDC
     * <p>
     * 优先从消息头中提取上游 traceId；
     * 若不存在且当前线程 MDC 也为空，则生成全新的 traceId。
     *
     * @param message onMessage 的第一个参数（AMQP Message 实例）
     * @return 方法进入前的 MDC traceId 值，用于 onExit 时恢复
     */
    @Advice.OnMethodEnter
    public static String onEnter(@Advice.Argument(0) Object message) {
        String previousTraceId = MDC.get(MDC_TRACE_KEY);
        try {
            String traceId = extractTraceId(message);
            if (traceId != null && !traceId.isBlank()) {
                MDC.put(MDC_TRACE_KEY, traceId);
            } else if (previousTraceId == null) {
                // 消息头无 traceId 且 MDC 为空时，生成新的 traceId（消费链路起点）
                MDC.put(MDC_TRACE_KEY, generateTraceId());
            }
            return previousTraceId;
        } catch (Throwable t) {
            System.err.println("[LightTrack Agent] RabbitMQ Consumer traceId 提取异常: " + t.getMessage());
            return previousTraceId;
        }
    }

    /**
     * 方法退出后（含异常退出）：恢复 MDC 状态
     *
     * @param previousTraceId 方法进入前的原始 traceId（可能为 null）
     * @param throwable       目标方法抛出的异常（可能为 null）
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter String previousTraceId,
                              @Advice.Thrown Throwable throwable) {
        try {
            if (throwable != null) {
                System.err.println("[LightTrack Agent] RabbitMQ 消费异常: "
                        + throwable.getClass().getName() + " - " + throwable.getMessage());
            }
            if (previousTraceId != null) {
                MDC.put(MDC_TRACE_KEY, previousTraceId);
            } else {
                MDC.remove(MDC_TRACE_KEY);
            }
        } catch (Throwable t) {
            System.err.println("[LightTrack Agent] RabbitMQ Consumer MDC 清理异常: " + t.getMessage());
        }
    }

    // ======================== 内部方法 ========================

    /**
     * 从 AMQP Message 的 MessageProperties.headers 中提取 traceId
     * <p>
     * 调用链：{@code message.getMessageProperties().getHeaders().get("traceId")}
     * <p>
     * RabbitMQ 消费者收到的 Message 对象，其 MessageProperties.headers
     * 中包含生产者通过 {@code headers.put("traceId", traceId)} 注入的值。
     */
    @SuppressWarnings("unchecked")
    private static String extractTraceId(Object message) {
        if (message == null) {
            return null;
        }
        try {
            // 1. 调用 message.getMessageProperties()
            Method mpm = getMessagePropertiesMethod;
            if (mpm == null) {
                synchronized (REFLECTION_LOCK) {
                    mpm = getMessagePropertiesMethod;
                    if (mpm == null) {
                        mpm = message.getClass().getMethod("getMessageProperties");
                        getMessagePropertiesMethod = mpm;
                    }
                }
            }
            Object props = mpm.invoke(message);

            // 2. 调用 props.getHeaders()
            Method hm = getHeadersMethod;
            if (hm == null) {
                synchronized (REFLECTION_LOCK) {
                    hm = getHeadersMethod;
                    if (hm == null) {
                        hm = props.getClass().getMethod("getHeaders");
                        getHeadersMethod = hm;
                    }
                }
            }
            Map<String, Object> headers = (Map<String, Object>) hm.invoke(props);

            // 3. 提取 traceId
            Object traceId = headers.get(MDC_TRACE_KEY);
            return traceId != null ? traceId.toString() : null;
        } catch (Exception e) {
            System.err.println("[LightTrack Agent] RabbitMQ Message Headers 提取失败: " + e.getMessage());
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
