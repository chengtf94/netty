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
package io.netty.channel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.ChannelOutputShutdownEvent;
import io.netty.channel.socket.ChannelOutputShutdownException;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * A skeletal {@link Channel} implementation.
 */
public abstract class AbstractChannel extends DefaultAttributeMap implements Channel {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannel.class);

    /**
     * 父Channel：Channel是有创建层次的，例如ServerSocketChannel 是 SocketChannel的 parent
     */
    private final Channel parent;
    /**
     * 全局唯一ID：machineId + processId + sequence + timestamp + random
     */
    private final ChannelId id;
    /**
     * unsafe：用于封装对底层socket的相关操作
     */
    private final Unsafe unsafe;
    /**
     * pipeline：为channel分配独立的pipeline用于IO事件编排
     */
    private final DefaultChannelPipeline pipeline;
    private volatile SocketAddress localAddress;
    private volatile SocketAddress remoteAddress;
    /**
     * 负责当前Channel的某个Reactor
     */
    private volatile EventLoop eventLoop;
    /**
     * 是否已注册当前Channel到某个Reactor上
     */
    private volatile boolean registered;
    /**
     * unsafeVoidPromise：表示调用方对处理结果并不关心，不可添加 Listener ，不可修改操作结果状态。
     */
    private final VoidChannelPromise unsafeVoidPromise = new VoidChannelPromise(this, false);
    /**
     * 当前Channel的关闭流程是否已经开始：防止 Reactor 线程来重复执行关闭流程，因为关闭操作可以在多个业务线程中发起
     */
    private boolean closeInitiated;
    /**
     * 关闭当前Channel操作的指定future：用于判断关闭流程进度 每个channel对应一个CloseFuture，连接关闭之后，netty 会通知这个CloseFuture
     */
    private final CloseFuture closeFuture = new CloseFuture(this);
    private Throwable initialCloseCause;

    /** Cache for the string representation of this channel */
    private boolean strValActive;
    private String strVal;

    /**
     * 构造方法
     */
    protected AbstractChannel(Channel parent) {
        this.parent = parent;
        id = newId();
        unsafe = newUnsafe();
        pipeline = newChannelPipeline();
    }

    protected AbstractChannel(Channel parent, ChannelId id) {
        this.parent = parent;
        this.id = id;
        unsafe = newUnsafe();
        pipeline = newChannelPipeline();
    }

    /**
     * 创建Channel的全局唯一ID
     */
    protected ChannelId newId() {
        return DefaultChannelId.newInstance();
    }

    /**
     * 创建Channel的unsafe
     */
    protected abstract AbstractUnsafe newUnsafe();

    /**
     * 创建Channel的pipeline
     */
    protected DefaultChannelPipeline newChannelPipeline() {
        return new DefaultChannelPipeline(this);
    }

    @Override
    public boolean isRegistered() {
        return registered;
    }

    /**
     * 是否兼容
     */
    protected abstract boolean isCompatible(EventLoop loop);

    /**
     * 向Reactor注册Channel
     */
    protected void doRegister() throws Exception {
        // NOOP
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        // bind事件为outbound事件，在pipeline中是反向传播。先从TailContext开始反向传播直到HeadContext。
        return pipeline.bind(localAddress, promise);
    }

    @Override
    public Channel read() {
        pipeline.read();
        return this;
    }

    /**
     * 向Selector注册监听事件OP_ACCEPT 或 读事件OP_READ
     */
    protected abstract void doBeginRead() throws Exception;

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ChannelFuture write(Object msg) {
        return pipeline.write(msg);
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) {
        return pipeline.write(msg, promise);
    }

    @Override
    public Channel flush() {
        pipeline.flush();
        return this;
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return pipeline.writeAndFlush(msg);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return pipeline.writeAndFlush(msg, promise);
    }

    /**
     * AbstractUnsafe
     */
    protected abstract class AbstractUnsafe implements Unsafe {
        /**
         * 待发送数据缓冲队列：Netty是全异步框架，所以这里需要一个缓冲队列来缓存用户需要发送的数据
         * 每个客户端 NioSocketChannel 对应一个 ChannelOutboundBuffer 待发送数据缓冲队列
         */
        private volatile ChannelOutboundBuffer outboundBuffer = new ChannelOutboundBuffer(AbstractChannel.this);
        private RecvByteBufAllocator.Handle recvHandle;
        /**
         * 是否正在进行flush操作
         */
        private boolean inFlush0;
        /** 是否从来没注册过*/
        private boolean neverRegistered = true;

        private void assertEventLoop() {
            assert !registered || eventLoop.inEventLoop();
        }

        /**
         * 向Reactor注册Channel
         */
        @Override
        public final void register(EventLoop eventLoop, final ChannelPromise promise) {
            ObjectUtil.checkNotNull(eventLoop, "eventLoop");
            if (isRegistered()) {
                promise.setFailure(new IllegalStateException("registered to an event loop already"));
                return;
            }
            if (!isCompatible(eventLoop)) {
                promise.setFailure(
                        new IllegalStateException("incompatible event loop type: " + eventLoop.getClass().getName()));
                return;
            }
            // #1 在channel上设置绑定的Reactor
            AbstractChannel.this.eventLoop = eventLoop;
            // #2 执行channel注册的操作必须是Reactor线程来完成：若当前线程是Reactor线程，则直接执行register0进行注册，
            // 否则需要将register0注册操作 封装程异步Task 由Reactor线程执行
            if (eventLoop.inEventLoop()) {
                // #2.1 当前线程是Reactor线程则直接执行注册动作register0
                register0(promise);
            } else {
                // #2.2 当前线程不是Reactor线程，则需要将注册动作register0封装成异步任务，存放在Reactor中的taskQueue中，等待Reactor线程执行
                // 注意：Reactor线程的启动是在向Reactor提交第一个异步任务的时候启动的，该处是第一个异步任务
                try {
                    eventLoop.execute(new Runnable() {
                        @Override
                        public void run() {
                            register0(promise);
                        }
                    });
                } catch (Throwable t) {
                    logger.warn(
                            "Force-closing a channel whose registration task was not accepted by an event loop: {}",
                            AbstractChannel.this, t);
                    closeForcibly();
                    closeFuture.setClosed();
                    safeSetFailure(promise, t);
                }
            }
        }

        /**
         * 向Reactor注册Channel
         */
        private void register0(ChannelPromise promise) {
            try {
                // 查看注册操作是否已经取消，或者对应channel已经关闭
                if (!promise.setUncancellable() || !ensureOpen(promise)) {
                    return;
                }
                boolean firstRegistration = neverRegistered;
                // 执行真正的注册操作
                doRegister();
                neverRegistered = false;
                registered = true;
                // 回调pipeline中添加的ChannelInitializer的handlerAdded方法，在这里初始化channelPipeline
                pipeline.invokeHandlerAddedIfNeeded();
                // 设置regFuture为success，触发operationComplete回调，将bind操作放入Reactor的任务队列中，等待Reactor线程执行
                safeSetSuccess(promise);
                // 触发channelRegister事件
                pipeline.fireChannelRegistered();
                // 对于服务端ServerSocketChannel来说 只有绑定端口地址成功后 channel的状态才是active的。
                // 此时绑定操作作为异步任务在Reactor的任务队列中，绑定操作还没开始，所以这里的isActive()是false
                if (isActive()) {
                    if (firstRegistration) {
                        pipeline.fireChannelActive();
                    } else if (config().isAutoRead()) {
                        beginRead();
                    }
                }
            } catch (Throwable t) {
                // Close the channel directly to avoid FD leak.
                closeForcibly();
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }

        protected final void safeSetSuccess(ChannelPromise promise) {
            if (!(promise instanceof VoidChannelPromise) && !promise.trySuccess()) {
                logger.warn("Failed to mark a promise as success because it is done already: {}", promise);
            }
        }

        @Override
        public final void bind(final SocketAddress localAddress, final ChannelPromise promise) {
            assertEventLoop();

            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            // See: https://github.com/netty/netty/issues/576
            if (Boolean.TRUE.equals(config().getOption(ChannelOption.SO_BROADCAST)) &&
                    localAddress instanceof InetSocketAddress &&
                    !((InetSocketAddress) localAddress).getAddress().isAnyLocalAddress() &&
                    !PlatformDependent.isWindows() && !PlatformDependent.maybeSuperUser()) {
                logger.warn(
                        "A non-root user can't receive a broadcast packet if the socket " +
                                "is not bound to a wildcard address; binding to a non-wildcard " +
                                "address (" + localAddress + ") anyway as requested.");
            }

            // channel还未激活  wasActive = false
            boolean wasActive = isActive();
            try {
                // 调用具体channel实现类：io.netty.channel.socket.nio.NioServerSocketChannel.doBind
                doBind(localAddress);
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            // 绑定成功后 channel激活 触发channelActive事件传播
            if (!wasActive && isActive()) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        // channelActive事件为inbound事件，在pipeline中的传播为正向传播，从HeadContext一直到TailContext为止。
                        pipeline.fireChannelActive();
                    }
                });
            }

            // 回调注册在promise上的ChannelFutureListener
            safeSetSuccess(promise);
        }

        private void invokeLater(Runnable task) {
            try {
                eventLoop().execute(task);
            } catch (RejectedExecutionException e) {
                logger.warn("Can't invoke task later as EventLoop rejected it", e);
            }
        }

        @Override
        public final void beginRead() {
            assertEventLoop();
            if (!isActive()) {
                return;
            }
            try {
                // 触发在selector上注册channel感兴趣的监听事件
                doBeginRead();
            } catch (final Exception e) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireExceptionCaught(e);
                    }
                });
                close(voidPromise());
            }
        }

        @Override
        public final void write(Object msg, ChannelPromise promise) {
            assertEventLoop();

            // 获取当前channel对应的待发送数据缓冲队列（支持用户异步写入的核心关键）
            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                // outboundBuffer == null：说明channel准备关闭了，直接标记发送失败
                try {
                    // release message now to prevent resource-leak
                    ReferenceCountUtil.release(msg);
                } finally {
                    safeSetFailure(promise,
                            newClosedChannelException(initialCloseCause, "write(Object, ChannelPromise)"));
                }
                return;
            }

            int size;
            try {
                // 过滤message类型：这里只会接受DirectBuffer或者fileRegion类型的msg
                msg = filterOutboundMessage(msg);
                // 计算当前msg的大小
                size = pipeline.estimatorHandle().size(msg);
                if (size < 0) {
                    size = 0;
                }
            } catch (Throwable t) {
                try {
                    ReferenceCountUtil.release(msg);
                } finally {
                    safeSetFailure(promise, t);
                }
                return;
            }

            // 将msg 加入到Netty中的待写入数据缓冲队列ChannelOutboundBuffer中
            outboundBuffer.addMessage(msg, size, promise);

        }

        @Override
        public final void flush() {
            assertEventLoop();
            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                // channel已关闭
                return;
            }
            // 将flushedEntry指针指向ChannelOutboundBuffer头结点，此时变为即将要flush进Socket的数据队列
            outboundBuffer.addFlush();
            // 将待写数据写进Socket
            flush0();
        }

        /**
         * 检查当 channel 的状态是否正常，如果 channel 状态一切正常，则调用 doWrite 方法发送数据
         */
        @SuppressWarnings("deprecation")
        protected void flush0() {
            if (inFlush0) {
                return;
            }

            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null || outboundBuffer.isEmpty()) {
                // channel已经关闭或者outboundBuffer为空
                return;
            }

            inFlush0 = true;

            // 判断当前channel是否处于活跃状态
            if (!isActive()) {
                try {
                    // Check if we need to generate the exception at all.
                    if (!outboundBuffer.isEmpty()) {
                        if (isOpen()) {
                            // 当前channel处于disConnected状态：通知promise 写入失败 并触发channelWritabilityChanged事件
                            outboundBuffer.failFlushed(new NotYetConnectedException(), true);
                        } else {
                            // 当前channel处于关闭状态：通知promise 写入失败 但不触发channelWritabilityChanged事件
                            outboundBuffer.failFlushed(newClosedChannelException(initialCloseCause, "flush0()"), false);
                        }
                    }
                } finally {
                    inFlush0 = false;
                }
                return;
            }

            try {
                // 写入Socket
                doWrite(outboundBuffer);
            } catch (Throwable t) {
                handleWriteError(t);
            } finally {
                inFlush0 = false;
            }
        }

        /**
         * 创建接收数据Buffer分配器（用于分配容量大小合适的byteBuffer用来容纳接收数据）
         * 在接收连接的场景中，这里的allocHandle只是用于控制read loop的循环读取创建连接的次数。
         */
        @Override
        public RecvByteBufAllocator.Handle recvBufAllocHandle() {
            if (recvHandle == null) {
                recvHandle = config().getRecvByteBufAllocator().newHandle();
            }
            return recvHandle;
        }

        /**
         * 关闭 channel 的核心逻辑，可分为主动关闭和被动关闭：客户端主动调用 ctx.channel().close() 发起关闭流程为主动关闭方，而服务端则是被动关闭方。
         * 1）服务端作为被动关闭方，这里传入的 ChannelPromise 类型为 VoidChannelPromise ，表示调用方对处理结果并不关心，VoidChannelPromise 不可添加 Listener ，不可修改操作结果状态。
         * 2）客户端作为主动关闭方，则需要监听 Channel 关闭的结果，所以这里传递的 ChannelPromise 参数为 DefaultChannelPromise 。
         */
        @Override
        public void close(final ChannelPromise promise) {
            assertEventLoop();
            ClosedChannelException closedChannelException =
                    StacklessClosedChannelException.newInstance(AbstractChannel.class, "close(ChannelPromise)");
            close(promise, closedChannelException, closedChannelException, false);
        }

        @Override
        public final ChannelPromise voidPromise() {
            assertEventLoop();
            return unsafeVoidPromise;
        }

        /**
         * 关闭channel
         * @param cause 当 Channel 关闭之后，需要清理 Channel 写入缓冲队列 ChannelOutboundBuffer 中的待发送数据，
         *              这里会将异常 cause 传递给用户的 writePromise ，通知用户 Channel 已经关闭，write 操作失败。
         * @param closeCause 与 cause 参数的作用差不多，都是用于在连接关闭的时候如果此时还有待发送数据未发送。就通知用户这里在参数中指定的异常。
         *                   唯一不同的是 Throwable cause 负责通知给 Channel 发送数据缓冲队列 ChannelOutboundBuffer 中的 flushedEntry 队列。
         *                   ClosedChannelException closeCause 负责通知给 ChannelOutboundBuffer 中的 unflushedEntry 队列。
         * @param notify 是否触发 ChannelWritabilityChanged 事件，由于当前是关闭操作，则不需要触发
         */
        private void close(final ChannelPromise promise, final Throwable cause,
                           final ClosedChannelException closeCause, final boolean notify) {

            // 若关闭操作被取消，则则直接返回
            if (!promise.setUncancellable()) {
                return;
            }

            // 若当前Channel的关闭流程是否已经开始，则判断是否已经关闭，确定设置promise为success 或注册回调
            if (closeInitiated) {
                if (closeFuture.isDone()) {
                    // Closed already.
                    safeSetSuccess(promise);
                } else if (!(promise instanceof VoidChannelPromise)) { // Only needed if no VoidChannelPromise.
                    // This means close() was called before so we just register a listener and return
                    closeFuture.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            promise.setSuccess();
                        }
                    });
                }
                // 直接返回，防止重复关闭
                return;
            }

            // 设置当前Channel的关闭流程已经开始
            closeInitiated = true;

            // 关闭当前Channel
            final boolean wasActive = isActive(); // 当前channel是否active，这里肯定是active的
            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            this.outboundBuffer = null; // 将channel对应的写缓冲区channelOutboundBuffer设置为null，表示channel要关闭了，不允许继续发送数据
            Executor closeExecutor = prepareToClose(); // 如果开启了SO_LINGER，则需要先将channel从reactor中取消掉。避免reactor线程空转浪费cpu
            if (closeExecutor != null) {
                // 在GlobalEventExecutor中执行channel的关闭任务
                closeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Execute the close.
                            doClose0(promise);
                        } finally {
                            // 在Reactor线程中执行：Call invokeLater so closeAndDeregister is executed in the EventLoop again!
                            invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    if (outboundBuffer != null) {
                                        // cause = closeCause = ClosedChannelException, notify = false
                                        // 此时channel已经关闭，需要清理对应channelOutboundBuffer中的待发送数据flushedEntry
                                        outboundBuffer.failFlushed(cause, notify);
                                        // 循环清理channelOutboundBuffer中的unflushedEntry
                                        outboundBuffer.close(closeCause);
                                    }
                                    // 关闭channel后，会将channel从reactor中注销，首先触发ChannelInactive事件，然后触发ChannelUnregistered
                                    fireChannelInactiveAndDeregister(wasActive);
                                }
                            });
                        }
                    }
                });
            } else {
                // 在Reactor中执行channel的关闭任务
                try {
                    // Close the channel and fail the queued messages in all cases.
                    doClose0(promise);
                } finally {
                    if (outboundBuffer != null) {
                        // Fail all the queued messages.
                        outboundBuffer.failFlushed(cause, notify);
                        outboundBuffer.close(closeCause);
                    }
                }
                if (inFlush0) {
                    invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            fireChannelInactiveAndDeregister(wasActive);
                        }
                    });
                } else {
                    fireChannelInactiveAndDeregister(wasActive);
                }
            }
        }

        /**
         * Prepares to close the {@link Channel}. If this method returns an {@link Executor}, the
         * caller must call the {@link Executor#execute(Runnable)} method with a task that calls
         * {@link #doClose()} on the returned {@link Executor}. If this method returns {@code null},
         * {@link #doClose()} must be called from the caller thread. (i.e. {@link EventLoop})
         *  @return 用于执行真正的Channel关闭任务的Executor，默认采用 Reactor 线程来执行 Channel 的关闭，除非设置 SO_LINGER 选项
         */
        protected Executor prepareToClose() {
            return null;
        }

        /**
         * 真正关闭channel
         */
        private void doClose0(ChannelPromise promise) {
            try {
                // 关闭channel，此时服务端向客户端发送FIN2，服务端进入last_ack状态，客户端收到fin2进入time_wait状态
                doClose();
                // 设置closeFuture的状态为success，表示channel已经关闭，调用shutdownOutput则不会通知closeFuture
                closeFuture.setClosed();
                // 通知用户promise success，关闭操作已经完成
                safeSetSuccess(promise);
            } catch (Throwable t) {
                closeFuture.setClosed();
                // 通知用户线程关闭失败
                safeSetFailure(promise, t);
            }
        }

        /**
         * 触发 ChannelInactive 事件和 ChannelUnregistered 事件
         */
        private void fireChannelInactiveAndDeregister(final boolean wasActive) {
            deregister(voidPromise(), wasActive && !isActive());
        }

        private void deregister(final ChannelPromise promise, final boolean fireChannelInactive) {
            if (!promise.setUncancellable()) {
                return;
            }

            if (!registered) {
                safeSetSuccess(promise);
                return;
            }

            invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 将channel从reactor中注销，reactor不在监听channel上的事件
                        doDeregister();
                    } catch (Throwable t) {
                        logger.warn("Unexpected exception occurred while deregistering a channel.", t);
                    } finally {
                        if (fireChannelInactive) {
                            // 当channel被关闭后，触发ChannelInactive事件
                            pipeline.fireChannelInactive();
                        }
                        // Some transports like local and AIO does not allow the deregistration of
                        // an open channel.  Their doDeregister() calls close(). Consequently,
                        // close() calls deregister() again - no need to fire channelUnregistered, so check
                        // if it was registered.
                        if (registered) {
                            // 触发ChannelUnregistered
                            registered = false;
                            pipeline.fireChannelUnregistered();
                        }
                        // 通知deRegisterPromise
                        safeSetSuccess(promise);
                    }
                }
            });
        }

        /**
         * Shutdown the output portion of the corresponding {@link Channel}.
         * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
         */
        @UnstableApi
        public final void shutdownOutput(final ChannelPromise promise) {
            assertEventLoop();
            shutdownOutput(promise, null);
        }

        /**
         * Shutdown the output portion of the corresponding {@link Channel}.
         * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
         * @param cause The cause which may provide rational for the shutdown.
         */
        private void shutdownOutput(final ChannelPromise promise, Throwable cause) {
            if (!promise.setUncancellable()) {
                return;
            }
            // 如果Channel已经close了，直接返回
            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                promise.setFailure(new ClosedChannelException());
                return;
            }

            // 半关闭状态下，不允许继续写入数据到Socket
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.

            final Throwable shutdownCause = cause == null ?
                    new ChannelOutputShutdownException("Channel output shutdown") :
                    new ChannelOutputShutdownException("Channel output shutdown", cause);
            Executor closeExecutor = prepareToClose();
            if (closeExecutor != null) {
                closeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // 将JDK NIO 底层的Socket shutdown
                            doShutdownOutput();
                            promise.setSuccess();
                        } catch (Throwable err) {
                            promise.setFailure(err);
                        } finally {
                            // Dispatch to the EventLoop
                            eventLoop().execute(new Runnable() {
                                @Override
                                public void run() {
                                    // 清理ChannelOutboundBuffer，并触发ChannelOutputShutdownEvent事件
                                    closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause);
                                }
                            });
                        }
                    }
                });
            } else {
                try {
                    // 在 Reactor 线程中执行
                    doShutdownOutput();
                    promise.setSuccess();
                } catch (Throwable err) {
                    promise.setFailure(err);
                } finally {
                    closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause);
                }
            }
        }

        private void closeOutboundBufferForShutdown(
                ChannelPipeline pipeline, ChannelOutboundBuffer buffer, Throwable cause) {
            buffer.failFlushed(cause, false);
            buffer.close(cause, true);
            pipeline.fireUserEventTriggered(ChannelOutputShutdownEvent.INSTANCE);
        }












        @Override
        public final ChannelOutboundBuffer outboundBuffer() {
            return outboundBuffer;
        }

        @Override
        public final SocketAddress localAddress() {
            return localAddress0();
        }

        @Override
        public final SocketAddress remoteAddress() {
            return remoteAddress0();
        }

        @Override
        public final void disconnect(final ChannelPromise promise) {
            assertEventLoop();

            if (!promise.setUncancellable()) {
                return;
            }

            boolean wasActive = isActive();
            try {
                doDisconnect();
                // Reset remoteAddress and localAddress
                remoteAddress = null;
                localAddress = null;
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            if (wasActive && !isActive()) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelInactive();
                    }
                });
            }

            safeSetSuccess(promise);
            closeIfClosed(); // doDisconnect() might have closed the channel
        }







        @Override
        public final void closeForcibly() {
            assertEventLoop();

            try {
                doClose();
            } catch (Exception e) {
                logger.warn("Failed to close a channel.", e);
            }
        }

        @Override
        public final void deregister(final ChannelPromise promise) {
            assertEventLoop();
            deregister(promise, false);
        }


















        protected final void handleWriteError(Throwable t) {
            if (t instanceof IOException && config().isAutoClose()) {
                /**
                 * Just call {@link #close(ChannelPromise, Throwable, boolean)} here which will take care of
                 * failing all flushed messages and also ensure the actual close of the underlying transport
                 * will happen before the promises are notified.
                 *
                 * This is needed as otherwise {@link #isActive()} , {@link #isOpen()} and {@link #isWritable()}
                 * may still return {@code true} even if the channel should be closed as result of the exception.
                 */
                initialCloseCause = t;
                close(voidPromise(), t, newClosedChannelException(t, "flush0()"), false);
            } else {
                try {
                    shutdownOutput(voidPromise(), t);
                } catch (Throwable t2) {
                    initialCloseCause = t;
                    close(voidPromise(), t2, newClosedChannelException(t, "flush0()"), false);
                }
            }
        }

        private ClosedChannelException newClosedChannelException(Throwable cause, String method) {
            ClosedChannelException exception =
                    StacklessClosedChannelException.newInstance(AbstractChannel.AbstractUnsafe.class, method);
            if (cause != null) {
                exception.initCause(cause);
            }
            return exception;
        }



        protected final boolean ensureOpen(ChannelPromise promise) {
            if (isOpen()) {
                return true;
            }

            safeSetFailure(promise, newClosedChannelException(initialCloseCause, "ensureOpen(ChannelPromise)"));
            return false;
        }

        /**
         * Marks the specified {@code promise} as failure.  If the {@code promise} is done already, log a message.
         */
        protected final void safeSetFailure(ChannelPromise promise, Throwable cause) {
            if (!(promise instanceof VoidChannelPromise) && !promise.tryFailure(cause)) {
                logger.warn("Failed to mark a promise as failure because it's done already: {}", promise, cause);
            }
        }

        protected final void closeIfClosed() {
            if (isOpen()) {
                return;
            }
            close(voidPromise());
        }

        /**
         * Appends the remote address to the message of the exceptions caused by connection attempt failure.
         */
        protected final Throwable annotateConnectException(Throwable cause, SocketAddress remoteAddress) {
            if (cause instanceof ConnectException) {
                return new AnnotatedConnectException((ConnectException) cause, remoteAddress);
            }
            if (cause instanceof NoRouteToHostException) {
                return new AnnotatedNoRouteToHostException((NoRouteToHostException) cause, remoteAddress);
            }
            if (cause instanceof SocketException) {
                return new AnnotatedSocketException((SocketException) cause, remoteAddress);
            }

            return cause;
        }


    }

















    @Override
    public final ChannelId id() {
        return id;
    }

    @Override
    public boolean isWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        return buf != null && buf.isWritable();
    }

    @Override
    public long bytesBeforeUnwritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        // isWritable() is currently assuming if there is no outboundBuffer then the channel is not writable.
        // We should be consistent with that here.
        return buf != null ? buf.bytesBeforeUnwritable() : 0;
    }

    @Override
    public long bytesBeforeWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        // isWritable() is currently assuming if there is no outboundBuffer then the channel is not writable.
        // We should be consistent with that here.
        return buf != null ? buf.bytesBeforeWritable() : Long.MAX_VALUE;
    }

    @Override
    public Channel parent() {
        return parent;
    }

    @Override
    public ByteBufAllocator alloc() {
        return config().getAllocator();
    }

    @Override
    public EventLoop eventLoop() {
        EventLoop eventLoop = this.eventLoop;
        if (eventLoop == null) {
            throw new IllegalStateException("channel not registered to an event loop");
        }
        return eventLoop;
    }

    @Override
    public SocketAddress localAddress() {
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            try {
                this.localAddress = localAddress = unsafe().localAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return localAddress;
    }

    /**
     * @deprecated no use-case for this.
     */
    @Deprecated
    protected void invalidateLocalAddress() {
        localAddress = null;
    }

    @Override
    public SocketAddress remoteAddress() {
        SocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            try {
                this.remoteAddress = remoteAddress = unsafe().remoteAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return remoteAddress;
    }

    @Deprecated
    protected void invalidateRemoteAddress() {
        remoteAddress = null;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return pipeline.bind(localAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return pipeline.connect(remoteAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return pipeline.connect(remoteAddress, localAddress);
    }

    @Override
    public ChannelFuture disconnect() {
        return pipeline.disconnect();
    }

    @Override
    public ChannelFuture close() {
        return pipeline.close();
    }

    @Override
    public ChannelFuture deregister() {
        return pipeline.deregister();
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public ChannelFuture disconnect(ChannelPromise promise) {
        return pipeline.disconnect(promise);
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        return pipeline.close(promise);
    }

    @Override
    public ChannelFuture deregister(ChannelPromise promise) {
        return pipeline.deregister(promise);
    }

    @Override
    public ChannelPromise newPromise() {
        return pipeline.newPromise();
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return pipeline.newProgressivePromise();
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        return pipeline.newSucceededFuture();
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return pipeline.newFailedFuture(cause);
    }

    @Override
    public ChannelFuture closeFuture() {
        return closeFuture;
    }

    @Override
    public Unsafe unsafe() {
        return unsafe;
    }


    /**
     * Returns the ID of this channel.
     */
    @Override
    public final int hashCode() {
        return id.hashCode();
    }

    /**
     * Returns {@code true} if and only if the specified object is identical
     * with this channel (i.e: {@code this == o}).
     */
    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    @Override
    public final int compareTo(Channel o) {
        if (this == o) {
            return 0;
        }

        return id().compareTo(o.id());
    }

    /**
     * Returns the {@link String} representation of this channel.  The returned
     * string contains the {@linkplain #hashCode() ID}, {@linkplain #localAddress() local address},
     * and {@linkplain #remoteAddress() remote address} of this channel for
     * easier identification.
     */
    @Override
    public String toString() {
        boolean active = isActive();
        if (strValActive == active && strVal != null) {
            return strVal;
        }

        SocketAddress remoteAddr = remoteAddress();
        SocketAddress localAddr = localAddress();
        if (remoteAddr != null) {
            StringBuilder buf = new StringBuilder(96)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(active? " - " : " ! ")
                .append("R:")
                .append(remoteAddr)
                .append(']');
            strVal = buf.toString();
        } else if (localAddr != null) {
            StringBuilder buf = new StringBuilder(64)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(']');
            strVal = buf.toString();
        } else {
            StringBuilder buf = new StringBuilder(16)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(']');
            strVal = buf.toString();
        }

        strValActive = active;
        return strVal;
    }

    @Override
    public final ChannelPromise voidPromise() {
        return pipeline.voidPromise();
    }




    /**
     * Returns the {@link SocketAddress} which is bound locally.
     */
    protected abstract SocketAddress localAddress0();

    /**
     * Return the {@link SocketAddress} which the {@link Channel} is connected to.
     */
    protected abstract SocketAddress remoteAddress0();

    /**
     * Bind the {@link Channel} to the {@link SocketAddress}
     */
    protected abstract void doBind(SocketAddress localAddress) throws Exception;

    /**
     * Disconnect this {@link Channel} from its remote peer
     */
    protected abstract void doDisconnect() throws Exception;

    /**
     * Close the {@link Channel}
     */
    protected abstract void doClose() throws Exception;

    /**
     * Called when conditions justify shutting down the output portion of the channel. This may happen if a write
     * operation throws an exception.
     */
    @UnstableApi
    protected void doShutdownOutput() throws Exception {
        doClose();
    }

    /**
     * Deregister the {@link Channel} from its {@link EventLoop}.
     *
     * Sub-classes may override this method
     */
    protected void doDeregister() throws Exception {
        // NOOP
    }

    /**
     * Flush the content of the given buffer to the remote peer.
     */
    protected abstract void doWrite(ChannelOutboundBuffer in) throws Exception;

    /**
     * Invoked when a new message is added to a {@link ChannelOutboundBuffer} of this {@link AbstractChannel}, so that
     * the {@link Channel} implementation converts the message to another. (e.g. heap buffer -> direct buffer)
     */
    protected Object filterOutboundMessage(Object msg) throws Exception {
        return msg;
    }

    protected void validateFileRegion(DefaultFileRegion region, long position) throws IOException {
        DefaultFileRegion.validate(region, position);
    }

    static final class CloseFuture extends DefaultChannelPromise {

        CloseFuture(AbstractChannel ch) {
            super(ch);
        }

        @Override
        public ChannelPromise setSuccess() {
            throw new IllegalStateException();
        }

        @Override
        public ChannelPromise setFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        @Override
        public boolean trySuccess() {
            throw new IllegalStateException();
        }

        @Override
        public boolean tryFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        boolean setClosed() {
            return super.trySuccess();
        }
    }

    private static final class AnnotatedConnectException extends ConnectException {

        private static final long serialVersionUID = 3901958112696433556L;

        AnnotatedConnectException(ConnectException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }

    private static final class AnnotatedNoRouteToHostException extends NoRouteToHostException {

        private static final long serialVersionUID = -6801433937592080623L;

        AnnotatedNoRouteToHostException(NoRouteToHostException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }

    private static final class AnnotatedSocketException extends SocketException {

        private static final long serialVersionUID = 3896743275010454039L;

        AnnotatedSocketException(SocketException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }
}
