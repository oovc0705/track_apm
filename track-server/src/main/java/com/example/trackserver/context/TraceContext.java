package com.example.trackserver.context;

import com.alibaba.ttl.TransmittableThreadLocal;

public final class TraceContext {

    private static final TransmittableThreadLocal<String> TRACE_ID_HOLDER = new TransmittableThreadLocal<>();

    private TraceContext() {
    }

    public static void set(String traceId) {
        TRACE_ID_HOLDER.set(traceId);
    }

    public static String get() {
        return TRACE_ID_HOLDER.get();
    }

    public static void clear() {
        TRACE_ID_HOLDER.remove();
    }
}
