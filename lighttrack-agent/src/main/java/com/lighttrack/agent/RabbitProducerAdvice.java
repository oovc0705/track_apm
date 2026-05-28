package com.lighttrack.agent;

import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * RabbitMQ 生产者拦截切面
 * <p>
 * 拦截 {@code org.springframework.amqp.rabbit.core.RabbitTemplate#send} 方法，
 * 在消息发送前从当前线程的 MDC 中提取 traceId，注入到
 * AMQP {@code Message} 的 {@code MessageProperties.headers} 中。
 * <p>
 * 由于 {@code RabbitTemplate#convertAndSend} 内部最终会调用 {@code send}，
 * 所以拦截 {@code send} 可同时覆盖 {@code send} 和 {@code convertAndSend} 两种发送路径。
 * <p>
 * 拦截目标（RabbitTemplate 中所有 send 重载）：
 * <ul>
 *   <li>{@code send(Message message)}</li>
 *   <li>{@code send(String routingKey, Message message)}</li>
 *   <li>{@code send(String exchange, String routingKey, Message message)}</li>
 *   <li>{@code send(String exchange, String routingKey, Message message, CorrelationData)}</li>
 * </ul>
 * <p>
 * 注意：不同重载中 {@code Message} 参数的位置不同（第 0/1/2 个参数），
 * 因此使用 {@code @Advice.AllArguments} 在运行时按类型匹配 Message 对象。
 */
public class RabbitProducerAdvice {

    private static final String MDC_TRACE_KEY = "traceId";
    private static final String AMQP_MESSAGE_CLASS = "org.springframework.amqp.core.Message";

    // 反射缓存：Message.getMessageProperties()
    private static volatile Method getMessagePropertiesMethod;
    // 反射缓存：MessageProperties.getHeaders()
    private static volatile Method getHeadersMethod;
    private static final Object REFLECTION_LOCK = new Object();

    /**
     * 方法进入前：从 MDC 提取 traceId 并注入 AMQP Message Headers
     *
     * @param args RabbitTemplate#send 的所有参数（通过 @Advice.AllArguments 获取）
     */
    @Advice.OnMethodEnter
    public static void onEnter(@Advice.AllArguments Object[] args) {
        try {
            String traceId = MDC.get(MDC_TRACE_KEY);
            if (traceId == null || traceId.isBlank()) {
                return;
            }

            // 在不同 send 重载中，Message 参数位置不同，通过全类名精确匹配
            for (Object arg : args) {
                if (arg != null && arg.getClass().getName().equals(AMQP_MESSAGE_CLASS)) {
                    injectTraceId(arg, traceId);
                    break;
                }
            }
        } catch (Throwable t) {
            System.err.println("[LightTrack Agent] RabbitMQ Producer traceId 注入异常: " + t.getMessage());
        }
    }

    /**
     * 将 traceId 注入到 AMQP Message 的 MessageProperties.headers 中
     * <p>
     * 调用链：{@code message.getMessageProperties().getHeaders().put("traceId", traceId)}
     * <p>
     * RabbitMQ 的 MessageProperties.headers 是一个 {@code Map<String, Object>}，
     * 直接 put 即可，最终会作为 AMQP headers 传递给消费者。
     */
    @SuppressWarnings("unchecked")
    private static void injectTraceId(Object message, String traceId) {
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

            // 3. 注入 traceId
            headers.put(MDC_TRACE_KEY, traceId);
        } catch (Exception e) {
            System.err.println("[LightTrack Agent] RabbitMQ Message Headers 注入失败: " + e.getMessage());
        }
    }
}
