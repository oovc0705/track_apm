package com.example.trackserver.jvm.dto;

import java.lang.management.MonitorInfo;

public record MonitorInfoSnapshot(
        String className,
        String identityHashCode,
        int stackDepth,
        String stackFrame
) {
    public static MonitorInfoSnapshot fromMonitorInfo(MonitorInfo info) {
        return new MonitorInfoSnapshot(
                info.getClassName(),
                "@" + Integer.toHexString(System.identityHashCode(info)),
                info.getLockedStackDepth(),
                info.getLockedStackFrame() != null ? info.getLockedStackFrame().toString() : null
        );
    }
}
