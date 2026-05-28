package com.lighttrack.agent;

import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * TraceId 注入切面逻辑
 * <p>
 * 通过 ByteBuddy Advice 机制，在目标方法前后自动执行：
 * <ul>
 *   <li>{@link OnMethodEnter}：解析 {@code X-Trace-Id} 请求头，生成全局唯一 TraceId 并绑定至 MDC</li>
 *   <li>{@link OnMethodExit}：强制清理 MDC，防止线程池复用导致的 TraceId 泄漏</li>
 * </ul>
 * <p>
 * 兼容性设计：
 * <ul>
 *   <li>使用反射访问 {@code HttpServletRequest#getHeader}，兼容 jakarta.servlet / javax.servlet 两种命名空间</li>
 *   <li>嵌套拦截防重入：当外层拦截已设置 TraceId 时，内层不再重复生成</li>
 *   <li>异常安全：所有探针逻辑均在 try-catch 中执行，不影响业务请求</li>
 * </ul>
 */
public class TraceAdvice {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_KEY = "traceId";

    /** 反射缓存：HttpServletRequest.getHeader 方法（避免每次请求都反射查找） */
    private static volatile Method getHeaderMethod;
    private static final Object REFLECTION_LOCK = new Object();

    /**
     * 方法进入前：解析 TraceId 并绑定 MDC
     * <p>
     * 执行逻辑：
     * <ol>
     *   <li>尝试从 {@code X-Trace-Id} 请求头获取上游传递的 TraceId</li>
     *   <li>若无请求头，检查 MDC 是否已有值（防嵌套拦截重复生成）</li>
     *   <li>若 MDC 也为空，生成全新的 32 位 TraceId</li>
     * </ol>
     *
     * @param request 目标方法的第一个参数（{@code HttpServletRequest} 或 Tomcat {@code Request} 实例）
     */
    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Object request) {
        try {
            // 优先从请求头获取上游 TraceId（链路追踪场景）
            String traceId = extractTraceId(request);
            if (traceId != null && !traceId.isBlank()) {
                MDC.put(MDC_TRACE_KEY, traceId);
                return;
            }

            // 请求头无 TraceId 时，仅在 MDC 为空时生成（避免嵌套拦截重复生成不同 UUID）
            if (MDC.get(MDC_TRACE_KEY) == null) {
                MDC.put(MDC_TRACE_KEY, generateTraceId());
            }
        } catch (Throwable t) {
            // 探针绝对不能影响业务流程，异常仅打印不抛出
            System.err.println("[LightTrack Agent] onEnter 异常: " + t.getMessage());
        }
    }

    /**
     * 方法退出后（含异常退出）：强制清理 MDC
     * <p>
     * 即使目标方法抛出异常，此方法也会被调用，确保 MDC 不泄漏到线程池复用的下一个请求。
     *
     * @param throwable 目标方法抛出的异常（可能为 null）
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Thrown Throwable throwable) {
        try {
            if (throwable != null) {
                System.err.println("[LightTrack Agent] 检测到请求处理异常: "
                        + throwable.getClass().getName() + " - " + throwable.getMessage());
            }
            MDC.remove(MDC_TRACE_KEY);
        } catch (Throwable t) {
            System.err.println("[LightTrack Agent] onExit 异常: " + t.getMessage());
        }
    }

    // ======================== 内部工具方法 ========================

    /**
     * 从 HttpServletRequest 中提取 {@code X-Trace-Id} 请求头
     * <p>
     * 使用反射调用 {@code getHeader(String)}，兼容 jakarta.servlet 和 javax.servlet 两种包名。
     * 反射结果会被缓存，后续调用不再查找 Method 对象。
     *
     * @param request HTTP 请求对象
     * @return 请求头中的 TraceId，若不存在或非 HTTP 请求则返回 null
     */
    private static String extractTraceId(Object request) {
        if (request == null) {
            return null;
        }
        try {
            Method method = getHeaderMethod;
            if (method == null) {
                synchronized (REFLECTION_LOCK) {
                    method = getHeaderMethod;
                    if (method == null) {
                        method = request.getClass().getMethod("getHeader", String.class);
                        getHeaderMethod = method;
                    }
                }
            }
            Object result = method.invoke(request, TRACE_ID_HEADER);
            return result != null ? result.toString() : null;
        } catch (NoSuchMethodException e) {
            // 非 HTTP 请求对象（理论上不会发生，但做防御性处理）
            return null;
        } catch (Exception e) {
            System.err.println("[LightTrack Agent] 提取 TraceId 异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 生成全局唯一的 TraceId
     * <p>
     * 格式：32 位十六进制字符串（去除 UUID 横线），
     * 与现有 {@code track-server} 的 {@code TraceInterceptor} 保持一致。
     *
     * @return 32 位 TraceId，例如 "a1b2c3d4e5f6789012345678901234ab"
     */
    private static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
