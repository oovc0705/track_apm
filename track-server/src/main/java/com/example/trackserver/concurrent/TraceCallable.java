package com.example.trackserver.concurrent;

import com.example.trackserver.context.TraceContext;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 可跨线程透传 traceId 和 MDC 的 Callable 包装器。
 */
public final class TraceCallable<T> implements Callable<T> {

    private final Callable<T> delegate;
    private final String traceId;
    private final Map<String, String> mdcContext;

    public TraceCallable(Callable<T> delegate) {
        this.delegate = delegate;
        this.traceId = TraceContext.get();
        this.mdcContext = MDC.getCopyOfContextMap();
    }

    @Override
    public T call() throws Exception {
        if (traceId != null) {
            TraceContext.set(traceId);
        }
        if (mdcContext != null) {
            MDC.setContextMap(mdcContext);
        }
        try {
            return delegate.call();
        } finally {
            TraceContext.clear();
            MDC.clear();
        }
    }
}
