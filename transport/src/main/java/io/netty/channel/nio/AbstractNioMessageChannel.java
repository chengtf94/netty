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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.ServerChannel;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * AbstractNioMessageChannel：主要是对NioServerSocketChannel底层读写行为的封装和定义，例如accept接收客户端连接
 *  服务端NioServerSocketChannel主要负责处理OP_ACCEPT事件，创建用于通信的客户端NioSocketChannel。这时候客户端与服务端还没开始通信，
 *  所以主Reactor线程从NioServerSocketChannel的读取对象为Message。
 */
public abstract class AbstractNioMessageChannel extends AbstractNioChannel {
    boolean inputShutdown;

    /**
     * 构造方法
     */
    protected AbstractNioMessageChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent, ch, readInterestOp);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioMessageUnsafe();
    }

    /**
     * NioMessageUnsafe：NioServerSocketChannel中对底层JDK NIO ServerSocketChannel的Unsafe底层操作类
     * Unsafe接口是Netty对Channel底层操作行为的封装，例如NioServerSocketChannel的底层Unsafe操作类干的事情就是绑定端口地址，处理OP_ACCEPT事件。
     */
    private final class NioMessageUnsafe extends AbstractNioUnsafe {

        /**
         * 存放连接建立后，创建的客户端SocketChannel
         */
        private final List<Object> readBuf = new ArrayList<Object>();

        /**
         * 处理客户端连接事件OP_ACCEPT
         */
        @Override
        public void read() {

            // 必须在Main Reactor线程中执行
            assert eventLoop().inEventLoop();

            // 服务端ServerSocketChannel中的config和pipeline
            final ChannelConfig config = config();
            final ChannelPipeline pipeline = pipeline();

            // 创建接收数据Buffer分配器（用于分配容量大小合适的byteBuffer用来容纳接收数据）
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
            allocHandle.reset(config);

            // 接收客户端连接SocketChannel
            boolean closed = false;
            Throwable exception = null;
            try {
                try {
                    do {
                        // 底层调用NioServerSocketChannel->doReadMessages，接收客户端连接SocketChannel
                        int localRead = doReadMessages(readBuf);
                        // 已无新的连接可接收，则退出read loop
                        if (localRead == 0) {
                            break;
                        }
                        if (localRead < 0) {
                            closed = true;
                            break;
                        }
                        // 统计在当前事件循环中已经读取到得Message数量（创建连接的个数）
                        allocHandle.incMessagesRead(localRead);
                    } while (allocHandle.continueReading()); // 判断是否已经读满16次
                } catch (Throwable t) {
                    exception = t;
                }

                // 在NioServerSocketChannel对应的pipeline中传播ChannelRead事件：初始化客户端连接SocketChannel，并将其绑定到从Reactor线程组中的一个Reactor上
                int size = readBuf.size();
                for (int i = 0; i < size; i ++) {
                    readPending = false;
                    pipeline.fireChannelRead(readBuf.get(i));
                }

                // 清除本次accept 创建的客户端连接SocketChannel集合
                readBuf.clear();
                allocHandle.readComplete();

                // 触发readComplete事件传播
                pipeline.fireChannelReadComplete();

                if (exception != null) {
                    closed = closeOnReadError(exception);
                    pipeline.fireExceptionCaught(exception);
                }
                if (closed) {
                    inputShutdown = true;
                    if (isOpen()) {
                        close(voidPromise());
                    }
                }
            } finally {
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    /**
     * doReadMessages函数：接收完成三次握手的客户端连接，底层会调用到JDK NIO ServerSocketChannel的accept方法，从内核全连接队列中取出客户端连接
     */
    protected abstract int doReadMessages(List<Object> buf) throws Exception;








    @Override
    protected void doBeginRead() throws Exception {
        if (inputShutdown) {
            return;
        }
        super.doBeginRead();
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();

        for (;;) {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                    key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
                }
                break;
            }
            try {
                boolean done = false;
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
                    if (doWriteMessage(msg, in)) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    in.remove();
                } else {
                    // Did not write all messages.
                    if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                        key.interestOps(interestOps | SelectionKey.OP_WRITE);
                    }
                    break;
                }
            } catch (Exception e) {
                if (continueOnWriteError()) {
                    in.remove(e);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Returns {@code true} if we should continue the write loop on a write error.
     */
    protected boolean continueOnWriteError() {
        return false;
    }

    protected boolean closeOnReadError(Throwable cause) {
        if (!isActive()) {
            // If the channel is not active anymore for whatever reason we should not try to continue reading.
            return true;
        }
        if (cause instanceof PortUnreachableException) {
            return false;
        }
        if (cause instanceof IOException) {
            // ServerChannel should not be closed even on IOException because it can often continue
            // accepting incoming connections. (e.g. too many open files)
            return !(this instanceof ServerChannel);
        }
        return true;
    }

    /**
     * Write a message to the underlying {@link java.nio.channels.Channel}.
     *
     * @return {@code true} if and only if the message has been written
     */
    protected abstract boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception;
}
