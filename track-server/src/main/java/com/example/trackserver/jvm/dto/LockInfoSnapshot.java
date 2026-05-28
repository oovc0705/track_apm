package com.example.trackserver.jvm.dto;

import java.lang.management.LockInfo;

public record LockInfoSnapshot(
        String className,
        String identityHashCode,
        String lockString
) {
    public static LockInfoSnapshot fromLockInfo(LockInfo info) {
        return new LockInfoSnapshot(
                info.getClassName(),
                "@" + Integer.toHexString(System.identityHashCode(info)),
                info.toString()
        );
    }
}
