package com.example.trackserver.concurrent;

import com.example.trackserver.context.TraceContext;
import org.slf4j.MDC;

import java.util.Map;

/**
 * 可跨线程透传 traceId 和 MDC 的 Runnable 包装器。
 * 在提交任务时捕获父线程的 TraceContext 和 MDC 快照，
 * 在 Worker 线程执行前还原，执行完毕后清理。
 */
public final class TraceRunnable implements Runnable {

    private final Runnable delegate;
    private final String traceId;
    private final Map<String, String> mdcContext;

    public TraceRunnable(Runnable delegate) {
        this.delegate = delegate;
        this.traceId = TraceContext.get();
        this.mdcContext = MDC.getCopyOfContextMap();
    }

    @Override
    public void run() {
        if (traceId != null) {
            TraceContext.set(traceId);
        }
        if (mdcContext != null) {
            MDC.setContextMap(mdcContext);
        }
        try {
            delegate.run();
        } finally {
            TraceContext.clear();
            MDC.clear();
        }
    }
}
