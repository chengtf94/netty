package io.netty.util.concurrent;

/**
 * 当向Reactor添加异步任务添加失败时，采用的拒绝策略；Reactor的任务不只是监听IO活跃事件和IO任务的处理，还包括对异步任务的处理。
 * Similar to {@link java.util.concurrent.RejectedExecutionHandler} but specific to {@link SingleThreadEventExecutor}.
 */
public interface RejectedExecutionHandler {
    void rejected(Runnable task, SingleThreadEventExecutor executor);
}
