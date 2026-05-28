package com.example.trackserver.config;

import com.alibaba.ttl.threadpool.TtlExecutors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * TTL 集成配置：提供开箱即用的 TTL 增强线程池。
 *
 * <p>如果需要完整 MDC 传播，请使用 {@code TraceExecutors.wrap()} 替代。
 * 本配置仅提供基础的 TTL 传播（TransmittableThreadLocal 级别）。
 */
@Configuration
public class TtlTraceConfig {

    /**
     * 创建一个 TTL 增强的线程池。
     * 所有提交到此线程池的任务，子线程自动继承父线程的 TransmittableThreadLocal 值。
     *
     * <p>替换为你自己的线程池参数即可获得 TTL 能力：
     * <pre>{@code
     * ThreadPoolExecutor raw = new ThreadPoolExecutor(4, 8, 60, TimeUnit.SECONDS, ...);
     * ExecutorService ttlWrapped = TtlExecutors.getTtlExecutorService(raw);
     * }</pre>
     */
    @Bean("ttlExecutorService")
    public ExecutorService ttlExecutorService() {
        ThreadPoolExecutor raw = new ThreadPoolExecutor(
                4, 8,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutorService(raw);
    }
}
