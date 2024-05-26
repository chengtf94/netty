package io.netty.util.concurrent;

import io.netty.util.internal.ObjectUtil;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * ThreadPerTaskExecutor：每个任务新建一个线程执行
 */
public final class ThreadPerTaskExecutor implements Executor {
    private final ThreadFactory threadFactory;

    public ThreadPerTaskExecutor(ThreadFactory threadFactory) {
        this.threadFactory = ObjectUtil.checkNotNull(threadFactory, "threadFactory");
    }

    @Override
    public void execute(Runnable command) {
        threadFactory.newThread(command).start();
    }
}
