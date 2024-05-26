package io.netty.channel;

import io.netty.util.concurrent.OrderedEventExecutor;

/**
 * EventLoop：Netty中的Reactor，可以说它就是Netty的引擎，负责Channel上IO就绪事件的监听，IO就绪事件的处理，异步任务的执行驱动着整个Netty的运转。
 * Reactor理解成为一个单线程的线程池，类似于JDK中的SingleThreadExecutor，仅用一个线程来执行轮询IO就绪事件，处理IO就绪事件，执行异步任务，
 * 同时待执行的异步任务保存在Reactor里的taskQueue中。
 */
public interface EventLoop extends OrderedEventExecutor, EventLoopGroup {
    @Override
    EventLoopGroup parent();
}
