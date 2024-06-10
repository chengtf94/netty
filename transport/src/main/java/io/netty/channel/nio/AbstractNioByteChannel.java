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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * AbstractNioByteChannel：客户端NioSocketChannel主要处理的是服务端与客户端的通信，这里涉及到接收客户端发送来的数据，
 * 而从Reactor线程从NioSocketChannel中读取的正是网络通信数据单位为Byte。
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    /**
     * flush任务
     */
    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
            // meantime.
            ((AbstractNioUnsafe) unsafe()).flush0();
        }
    };
    private boolean inputClosedSeenErrorOnRead;

    /**
     * 构造方法
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        // 客户端NioSocketChannel向从Reactor注册的是SelectionKey.OP_READ事件
        super(parent, ch, SelectionKey.OP_READ);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    /**
     * 从NioSocketChannel中读取数据
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * NioByteUnsafe：NioSocketChannel中对底层JDK NIO SocketChannel的Unsafe底层操作类
     */
    protected class NioByteUnsafe extends AbstractNioUnsafe {

        /**
         * 处理客户端读事件OP_READ
         */
        @Override
        public final void read() {

            // 处理连接半关闭
            final ChannelConfig config = config();
            if (shouldBreakReadReady(config)) {
                clearReadPending();
                return;
            }

            // 获取NioSocketChannel的pipeline
            final ChannelPipeline pipeline = pipeline();

            // 获取PooledByteBufAllocator：具体用于实际分配ByteBuf的分配器
            final ByteBufAllocator allocator = config.getAllocator();

            // 获取自适应ByteBuf分配器AdaptiveRecvByteBufAllocator ：用于动态调节ByteBuf容量，需要与具体的ByteBuf分配器配合使用 例如PooledByteBufAllocator
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();

            // allocHandler用于统计每次读取数据的大小，方便下次分配合适大小的ByteBuf
            // 重置清除上次的统计指标
            allocHandle.reset(config);

            ByteBuf byteBuf = null;
            boolean close = false;
            try {
                do {
                    // 利用PooledByteBufAllocator分配合适大小的byteBuf 初始大小为2048
                    byteBuf = allocHandle.allocate(allocator);

                    // 记录本次读取了多少字节数
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));

                    // 如果本次没有读取到任何字节，则退出循环 进行下一轮事件轮询
                    if (allocHandle.lastBytesRead() <= 0) {
                        // nothing was read. release the buffer.
                        byteBuf.release();
                        byteBuf = null;
                        close = allocHandle.lastBytesRead() < 0;
                        if (close) {
                            // There is nothing left to read as we received an EOF.
                            readPending = false;
                        }
                        break;
                    }

                    // read loop读取数据次数+1
                    allocHandle.incMessagesRead(1);
                    readPending = false;

                    // 客户端NioSocketChannel的pipeline中触发ChannelRead事件
                    pipeline.fireChannelRead(byteBuf);

                    // 解除本次读取数据分配的ByteBuffer引用，方便下一轮read loop分配
                    byteBuf = null;

                } while (allocHandle.continueReading());

                // 根据本次read loop总共读取的字节数，决定下次是否扩容或者缩容
                allocHandle.readComplete();

                // 在NioSocketChannel的pipeline中触发ChannelReadComplete事件，表示一次read事件处理完毕
                // 但这并不表示 客户端发送来的数据已经全部读完，因为如果数据太多的话，这里只会读取16次，剩下的会等到下次read事件到来后在处理
                pipeline.fireChannelReadComplete();

                if (close) {
                    closeOnRead(pipeline);
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
            } finally {
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }

        private void closeOnRead(ChannelPipeline pipeline) {
            if (!isInputShutdown0()) {
                if (isAllowHalfClosure(config())) {
                    shutdownInput();
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(voidPromise());
                }
            } else {
                inputClosedSeenErrorOnRead = true;
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) {
                if (byteBuf.isReadable()) {
                    readPending = false;
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    byteBuf.release();
                }
            }
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(cause);

            // If oom will close the read event, release connection.
            // See https://github.com/netty/netty/issues/10434
            if (close || cause instanceof OutOfMemoryError || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

    }

    @Override
    protected final Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            // Netty为了减少数据从 堆内内存 到 堆外内存 的拷贝以及缓解GC的压力，所以这里必须采用 DirectByteBuffer 使用堆外内存来存放网络发送数据
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isDirect()) {
                return msg;
            }
            return newDirectBuffer(buf);
        }
        if (msg instanceof FileRegion) {
            return msg;
        }
        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }

    /**
     * Write objects to the OS.
     */
    protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        if (msg == null) {
            return 0;
        }
        return doWriteInternal(in, in.current());
    }

    private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                in.remove();
                return 0;
            }

            final int localFlushedAmount = doWriteBytes(buf);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (!buf.isReadable()) {
                    in.remove();
                }
                return 1;
            }
        } else if (msg instanceof FileRegion) {
            // 若文件已经传输完毕，则返回0
            FileRegion region = (FileRegion) msg;
            if (region.transferred() >= region.count()) {
                in.remove();
                return 0;
            }
            // 零拷贝的方式传输文件，返回1
            long localFlushedAmount = doWriteFileRegion(region);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (region.transferred() >= region.count()) {
                    in.remove();
                }
                return 1;
            }
        } else {
            // Should not reach here.
            throw new Error();
        }
        // 走到这里表示 此时Socket已经写不进去了 退出writeLoop，注册OP_WRITE事件，返回Integer.MAX_VALUE
        return WRITE_STATUS_SNDBUF_FULL;
    }


    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
        if (setOpWrite) {
            // 这里处理还没写满16次 但是socket缓冲区已满写不进去的情况 注册write事件：什么时候socket可写了， epoll会通知reactor线程继续写
            setOpWrite();
        } else {
            // 必须清除OP_WRITE事件，此时Socket对应的缓冲区依然是可写的，只不过当前channel写够了16次，被SubReactor限制了。
            // 这样SubReactor可以腾出手来处理其他channel上的IO事件。这里如果不清除OP_WRITE事件，则会一直被通知。
            clearOpWrite();
            // 如果本次writeLoop还没写完，则提交flushTask到reactor
            // Netty 会将 channel 中剩下的待写数据的 flush 操作封装程 flushTask，丢进 reactor 的普通任务队列中，
            // 等待 reactor 执行完其他 channel 上的 io 操作后在回过头来执行未写完的 flush 任务
            eventLoop().execute(flushTask);
        }
    }

    /**
     * 向 Reactor 注册 OP_WRITE 事件
     */
    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }


















    /**
     * Shutdown the input side of the channel.
     */
    protected abstract ChannelFuture shutdownInput();

    protected boolean isInputShutdown0() {
        return false;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    final boolean shouldBreakReadReady(ChannelConfig config) {
        return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }



    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = config().getWriteSpinCount();
        do {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }
            writeSpinCount -= doWriteInternal(in, msg);
        } while (writeSpinCount > 0);

        incompleteWrite(writeSpinCount < 0);
    }



    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;




}
