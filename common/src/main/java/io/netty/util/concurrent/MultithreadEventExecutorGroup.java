/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MultithreadEventExecutorGroup：用于定义创建和管理Reactor的行为，handles their tasks with multiple threads atthe same time.
 */
public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {

    /**
     * Reactor线程组中的Reactor集合、只读集合
     */
    private final EventExecutor[] children;
    private final Set<EventExecutor> readonlyChildren;
    /**
     * 选择策略：从Reactor group中选择一个特定的Reactor，用于channel注册绑定到一个固定的Reactor上
     */
    private final EventExecutorChooserFactory.EventExecutorChooser chooser;
    /**
     * 关闭的Reactor个数：当Reactor全部关闭后，才可以认为关闭成功
     */
    private final AtomicInteger terminatedChildren = new AtomicInteger();
    /**
     * 关闭future
     */
    private final Promise<?> terminationFuture = new DefaultPromise(GlobalEventExecutor.INSTANCE);

    /**
     * 构造方法
     */
    protected MultithreadEventExecutorGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        this(nThreads, threadFactory == null ? null : new ThreadPerTaskExecutor(threadFactory), args);
    }
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor, Object... args) {
        // EventExecutorChooserFactory：绑定策略，当客户端连接完成三次握手后，Main Reactor会创建客户端连接NioSocketChannel，
        // 并将其绑定到Sub Reactor Group中的一个固定Reactor，那么具体要绑定到哪个具体的Sub Reactor上由绑定策略指定（算法为求模取余）
        this(nThreads, executor, DefaultEventExecutorChooserFactory.INSTANCE, args);
    }
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor,
                                            EventExecutorChooserFactory chooserFactory, Object... args) {
        if (nThreads <= 0) {
            throw new IllegalArgumentException(String.format("nThreads: %d (expected: > 0)", nThreads));
        }

        // #1 创建ThreadPerTaskExecutor：用于创建Reactor线程，来一个任务就创建一个线程执行，而创建的这个线程正是Netty的核心引擎Reactor线程
        if (executor == null) {
            executor = new ThreadPerTaskExecutor(newDefaultThreadFactory());
        }

        // #2 循环创建NioEventLoop：也就是Reactor Group中的Reactor
        children = new EventExecutor[nThreads];
        for (int i = 0; i < nThreads; i++) {
            boolean success = false;
            try {
                children[i] = newChild(executor, args);
                success = true;
            } catch (Exception e) {
                // TODO: Think about if this is a good exception type
                throw new IllegalStateException("failed to create a child event loop", e);
            } finally {
                if (!success) {
                    for (int j = 0; j < i; j++) {
                        children[j].shutdownGracefully();
                    }
                    for (int j = 0; j < i; j++) {
                        EventExecutor e = children[j];
                        try {
                            while (!e.isTerminated()) {
                                e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
                            }
                        } catch (InterruptedException interrupted) {
                            // Let the caller handle the interruption.
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        // #3 创建Channel到Reactor的绑定策略：也就是线程选择器，确定每次如何从NioEventLoopGroup中选择一个NioEventLoop
        chooser = chooserFactory.newChooser(children);

        // #4 创建terminationListener：也就是Reactor关闭的回调函数，在Reactor关闭时回调
        final FutureListener<Object> terminationListener = new FutureListener<Object>() {
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
                // 当所有Reactor关闭后 才认为是关闭成功
                if (terminatedChildren.incrementAndGet() == children.length) {
                    terminationFuture.setSuccess(null);
                }
            }
        };

        // #5 为所有Reactor添加terminationListener
        for (EventExecutor e : children) {
            e.terminationFuture().addListener(terminationListener);
        }

        Set<EventExecutor> childrenSet = new LinkedHashSet<EventExecutor>(children.length);
        Collections.addAll(childrenSet, children);
        readonlyChildren = Collections.unmodifiableSet(childrenSet);
    }

    /**
     * 默认线程工厂
     */
    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass());
    }

    /**
     * 创建Reactor
     */
    protected abstract EventExecutor newChild(Executor executor, Object... args) throws Exception;

    @Override
    public EventExecutor next() {
        return chooser.next();
    }









    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        for (EventExecutor l : children) {
            l.shutdownGracefully(quietPeriod, timeout, unit);
        }
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        for (EventExecutor l : children) {
            l.shutdown();
        }
    }

    @Override
    public boolean isShuttingDown() {
        for (EventExecutor l : children) {
            if (!l.isShuttingDown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isShutdown() {
        for (EventExecutor l : children) {
            if (!l.isShutdown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isTerminated() {
        for (EventExecutor l : children) {
            if (!l.isTerminated()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        loop:
        for (EventExecutor l : children) {
            for (;;) {
                long timeLeft = deadline - System.nanoTime();
                if (timeLeft <= 0) {
                    break loop;
                }
                if (l.awaitTermination(timeLeft, TimeUnit.NANOSECONDS)) {
                    break;
                }
            }
        }
        return isTerminated();
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return readonlyChildren.iterator();
    }

    public final int executorCount() {
        return children.length;
    }

}
