package io.netty.channel;

import java.util.Queue;

/**
 * EventLoopTaskQueueFactory：用于创建队列来保存Reactor中待执行的异步任务
 */
public interface EventLoopTaskQueueFactory {
    /**
     * Returns a new {@link Queue} to use.
     *
     * @param maxCapacity the maximum amount of elements that can be stored in the {@link Queue} at a given point
     *                    in time.
     * @return the new queue.
     */
    Queue<Runnable> newTaskQueue(int maxCapacity);
}
