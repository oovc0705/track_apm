package com.example.trackserver.concurrent;

import com.alibaba.ttl.TtlCallable;
import com.alibaba.ttl.TtlRunnable;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 结合 TTL + 手动 MDC 传播的线程池工具类。
 *
 * <h3>使用方式一：直接包装任务</h3>
 * <pre>{@code
 * executor.submit(TraceExecutors.traceRunnable(() -> doWork()));
 * executor.submit(TraceExecutors.traceCallable(() -> fetchData()));
 * }</pre>
 *
 * <h3>使用方式二：包装整个线程池（推荐）</h3>
 * <pre>{@code
 * ExecutorService traceExecutor = TraceExecutors.wrap(Executors.newFixedThreadPool(10));
 * traceExecutor.submit(() -> doWork()); // 自动透传 traceId + MDC
 * }</pre>
 */
public final class TraceExecutors {

    private TraceExecutors() {
    }

    /**
     * 包装 Runnable，使其携带父线程的 traceId 和 MDC。
     */
    public static Runnable traceRunnable(Runnable task) {
        return TtlRunnable.get(new TraceRunnable(task));
    }

    /**
     * 包装 Callable，使其携带父线程的 traceId 和 MDC。
     */
    public static <T> Callable<T> traceCallable(Callable<T> task) {
        return TtlCallable.get(new TraceCallable<>(task));
    }

    /**
     * 包装 Executor，使通过它提交的任务自动携带 traceId 和 MDC。
     */
    public static Executor wrap(Executor executor) {
        return command -> executor.execute(traceRunnable(command));
    }

    /**
     * 包装 ExecutorService，使通过它提交的所有任务自动携带 traceId 和 MDC。
     */
    public static ExecutorService wrap(ExecutorService executor) {
        return new TraceDelegatingExecutorService(executor);
    }

    /**
     * 完整的 ExecutorService 装饰器，在所有任务提交时自动包装。
     */
    private static class TraceDelegatingExecutorService implements ExecutorService {

        private final ExecutorService delegate;

        TraceDelegatingExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(traceRunnable(command));
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return delegate.submit(traceCallable(task));
        }

        @Override
        public Future<?> submit(Runnable task) {
            return delegate.submit(traceRunnable(task));
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            return delegate.submit(traceRunnable(task), result);
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            return delegate.invokeAll(tasks.stream().map(TraceExecutors::traceCallable).toList());
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.invokeAll(tasks.stream().map(TraceExecutors::traceCallable).toList(), timeout, unit);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
            return delegate.invokeAny(tasks.stream().map(TraceExecutors::traceCallable).toList());
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.invokeAny(tasks.stream().map(TraceExecutors::traceCallable).toList(), timeout, unit);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
