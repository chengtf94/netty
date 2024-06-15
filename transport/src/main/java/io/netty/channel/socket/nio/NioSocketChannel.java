package io.netty.channel.socket.nio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.nio.AbstractNioByteChannel;
import io.netty.channel.socket.DefaultSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.SuppressJava6Requirement;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Map;
import java.util.concurrent.Executor;

import static io.netty.channel.internal.ChannelUtils.MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD;

/**
 * 基于NIO模型的SocketChannel：对应客户端连接Socket，当客户端完成三次握手后，由系统调用accept函数根据监听Socket创建。
 */
public class NioSocketChannel extends AbstractNioByteChannel implements io.netty.channel.socket.SocketChannel {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioSocketChannel.class);

    /**
     * SocketChannel配置
     */
    private final SocketChannelConfig config;

    /**
     * 构造方法
     */
    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();
    public NioSocketChannel() {
        this(DEFAULT_SELECTOR_PROVIDER);
    }

    public NioSocketChannel(SelectorProvider provider) {
        this(newSocket(provider));
    }

    private static SocketChannel newSocket(SelectorProvider provider) {
        try {
            return provider.openSocketChannel();
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    public NioSocketChannel(SocketChannel socket) {
        this(null, socket);
    }

    public NioSocketChannel(Channel parent, SocketChannel socket) {
        super(parent, socket);
        config = new NioSocketChannelConfig(this, socket.socket());
    }

    @Override
    public SocketChannelConfig config() {
        return config;
    }

    /**
     * 读取数据
     * @throws Exception 这里会有两种情况抛出异常：
     * ● 此时Socket接收缓冲区中只有 RST 包，并没有其他正常数据。
     * ● Socket 接收缓冲区有正常的数据，OP_READ 事件活跃，当调用 doReadBytes 方法从 Channel 中读取数据的过程中，对端发送 RST 强制关闭连接，这时会在读取的过程中抛出 IOException 异常。
     */
    @Override
    protected int doReadBytes(ByteBuf byteBuf) throws Exception {
        // 直接调用底层JDK NIO的SocketChannel#read方法将数据读取到DirectByteBuffer中。
        // 读取数据大小为本次分配的DirectByteBuffer容量，初始为2048
        final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
        allocHandle.attemptedBytesRead(byteBuf.writableBytes());
        // 当服务端的内核协议栈接收到来自客户端的 FIN 包后，内核协议栈会向 Socket 的接收缓冲区插入文件结束符 EOF ，表示客户端已经主动发起了关闭连接流程，
        // 这时 NioSocketChannel 上的 OP_READ 事件活跃，随即 Reactor 线程会在 AbstractNioByteChannel#read 方法中处理 OP_READ 事件。
        // 读到EOF后，这里会返回-1，表示客户端已经发起了连接关闭流程，此时服务端连接状态为 CLOSE_WAIT ，客户端连接状态为 FIN_WAIT2 。
        return byteBuf.writeBytes(javaChannel(), allocHandle.attemptedBytesRead());
    }

    @Override
    public boolean isActive() {
        SocketChannel ch = javaChannel();
        return ch.isOpen() && ch.isConnected();
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        SocketChannel ch = javaChannel();

        // 最大写入次数 默认为16 目的是为了保证SubReactor可以平均的处理注册其上的所有Channel
        int writeSpinCount = config().getWriteSpinCount();
        do {

            // 如果全部数据已经写完 则移除OP_WRITE事件并直接退出writeLoop
            if (in.isEmpty()) {
                clearOpWrite();
                return;
            }

            // 获取本次write loop 最大允许发送字节数：初始值为 SO_SNDBUF设置的发送缓冲区大小 * 2 作为 最大写入字节数  293976 = 146988 << 1
            int maxBytesPerGatheringWrite = ((NioSocketChannelConfig) config).getMaxBytesPerGatheringWrite();

            // 将ChannelOutboundBuffer中缓存的DirectBuffer转换成JDK NIO 的 ByteBuffer
            ByteBuffer[] nioBuffers = in.nioBuffers(1024, maxBytesPerGatheringWrite);

            // 本次write loop中需要发送的 JDK ByteBuffer个数
            int nioBufferCnt = in.nioBufferCount();

            // Always use nioBuffers() to workaround data-corruption.
            // See https://github.com/netty/netty/issues/2761
            switch (nioBufferCnt) {
                case 0:
                    // 针对 网络传输文件数据 的处理 FileRegion
                    // ByteBuf 类型用于装载普通的发送数据，而 FileRegion 类型用于通过零拷贝的方式网络传输文件
                    writeSpinCount -= doWrite0(in);
                    break;
                case 1: {
                    // 处理单个NioByteBuffer发送的情况
                    ByteBuffer buffer = nioBuffers[0];
                    int attemptedBytes = buffer.remaining();
                    final int localWrittenBytes = ch.write(buffer);
                    if (localWrittenBytes <= 0) {
                        // 如果当前Socket发送缓冲区满了写不进去了，则注册OP_WRITE事件，等待Socket发送缓冲区可写时 再写
                        // SubReactor在处理OP_WRITE事件时，直接调用flush方法
                        incompleteWrite(true);
                        return;
                    }
                    // 根据当前实际写入情况调整 maxBytesPerGatheringWrite数值
                    adjustMaxBytesPerGatheringWrite(attemptedBytes, localWrittenBytes, maxBytesPerGatheringWrite);
                    // 如果ChannelOutboundBuffer中的某个Entry被全部写入 则删除该Entry
                    // 如果Entry被写入了一部分 还有一部分未写入  则更新Entry中的readIndex 等待下次writeLoop继续写入
                    in.removeBytes(localWrittenBytes);
                    --writeSpinCount;
                    break;
                }
                default: {
                    // 批量处理多个NioByteBuffers发送的情况

                    // 获取本次 write loop 需要发送的字节总量 attemptedBytes
                    // nioBufferSize 是在 ChannelOutboundBuffer#nioBuffers 方法转换 JDK NIO ByteBuffer 类型时被计算出来的
                    long attemptedBytes = in.nioBufferSize();

                    // 调用 JDK NIO 原生 SocketChannel 批量发送 nioBuffers 中的数据，并获取到本次 write loop 一共批量发送了多少字节 localWrittenBytes
                    final long localWrittenBytes = ch.write(nioBuffers, 0, nioBufferCnt);
                    if (localWrittenBytes <= 0) {
                        // localWrittenBytes <= 0 表示当前 Socket 的写缓存区 SEND_BUF 已满，写不进数据了。
                        // 那么就需要向当前 NioSocketChannel 对应的 Reactor 注册 OP_WRITE 事件，并停止当前 flush 流程。
                        // 当 Socket 的写缓冲区有容量可写时，epoll 会通知 reactor 线程继续写入。
                        incompleteWrite(true);
                        return;
                    }

                    // 根据实际写入情况调整下一次写入数据大小的最大值
                    // 根据本次 write loop 向 Socket 写缓冲区写入数据的情况，来调整下次 write loop 最大写入字节数。
                    // maxBytesPerGatheringWrite 决定每次 write loop 可以从 channelOutboundBuffer 中最多获取多少发送数据。
                    // 初始值为 SO_SNDBUF大小 * 2 = 293976 = 146988 << 1，最小值为 2048
                    adjustMaxBytesPerGatheringWrite((int) attemptedBytes, (int) localWrittenBytes,
                            maxBytesPerGatheringWrite);

                    // 移除全部写完的Buffer，如果只写了部分数据则更新buffer的readerIndex，下一个writeLoop写入
                    in.removeBytes(localWrittenBytes);
                    --writeSpinCount;
                    break;
                }
            }
        } while (writeSpinCount > 0);

        // 处理本轮write loop未写完的情况
        incompleteWrite(writeSpinCount < 0);

    }

    /**
     * 零拷贝的方式传输文件
     */
    @Override
    protected long doWriteFileRegion(FileRegion region) throws Exception {
        final long position = region.transferred();
        // 通过 FileChannel#transferTo 方法底层用到的系统调用为 sendFile 实现零拷贝网络文件的传输
        return region.transferTo(javaChannel(), position);
    }

    private void adjustMaxBytesPerGatheringWrite(int attempted, int written, int oldMaxBytesPerGatheringWrite) {
        // attempted == written 表示本次 write loop 尝试写入的数据能全部写入到 Socket 的写缓冲区中，那么下次 write loop 就应该尝试去写入更多的数据。
        if (attempted == written) {
            // 将本次写入的数据量 written 扩大两倍
            if (attempted << 1 > oldMaxBytesPerGatheringWrite) {
                ((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted << 1);
            }
        } else if (attempted > MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD && written < attempted >>> 1) {
            // 将下次 write loop 要写入的数据减小为 attempted 的1 / 2
            ((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted >>> 1);
        }
    }

    @Override
    protected boolean isInputShutdown0() {
        return isInputShutdown();
    }

    @Override
    public boolean isInputShutdown() {
        return javaChannel().socket().isInputShutdown() || !isActive();
    }

    @Override
    protected void doClose() throws Exception {
        super.doClose();
        javaChannel().close();
    }

    /**
     * 在 TCP 半关闭的场景下，主动关闭方需要调用 shutdownOutput 方法向被动关闭方发送 FIN包 开始 TCP 半关闭流程。
     */
    @Override
    public ChannelFuture shutdownOutput() {
        return shutdownOutput(newPromise());
    }

    @Override
    public ChannelFuture shutdownOutput(final ChannelPromise promise) {
        // 必须在 Reactor 线程中完成
        final EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
                }
            });
        }
        return promise;
    }

    @SuppressJava6Requirement(reason = "Usage guarded by java version check")
    @UnstableApi
    @Override
    protected final void doShutdownOutput() throws Exception {
        // 关闭底层 JDK NIO SocketChannel 的写通道，此时内核协议栈会向对端发送 FIN 发起 TCP 半关闭流程。
        if (PlatformDependent.javaVersion() >= 7) {
            javaChannel().shutdownOutput();
        } else {
            javaChannel().socket().shutdownOutput();
        }
    }








    @Override
    public ServerSocketChannel parent() {
        return (ServerSocketChannel) super.parent();
    }

    @Override
    protected SocketChannel javaChannel() {
        return (SocketChannel) super.javaChannel();
    }

    @Override
    public boolean isOutputShutdown() {
        return javaChannel().socket().isOutputShutdown() || !isActive();
    }



    @Override
    public boolean isShutdown() {
        Socket socket = javaChannel().socket();
        return socket.isInputShutdown() && socket.isOutputShutdown() || !isActive();
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }





    @Override
    public ChannelFuture shutdownInput() {
        return shutdownInput(newPromise());
    }



    @Override
    public ChannelFuture shutdownInput(final ChannelPromise promise) {
        EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            shutdownInput0(promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    shutdownInput0(promise);
                }
            });
        }
        return promise;
    }

    @Override
    public ChannelFuture shutdown() {
        return shutdown(newPromise());
    }

    @Override
    public ChannelFuture shutdown(final ChannelPromise promise) {
        ChannelFuture shutdownOutputFuture = shutdownOutput();
        if (shutdownOutputFuture.isDone()) {
            shutdownOutputDone(shutdownOutputFuture, promise);
        } else {
            shutdownOutputFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture shutdownOutputFuture) throws Exception {
                    shutdownOutputDone(shutdownOutputFuture, promise);
                }
            });
        }
        return promise;
    }

    private void shutdownOutputDone(final ChannelFuture shutdownOutputFuture, final ChannelPromise promise) {
        ChannelFuture shutdownInputFuture = shutdownInput();
        if (shutdownInputFuture.isDone()) {
            shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
        } else {
            shutdownInputFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture shutdownInputFuture) throws Exception {
                    shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
                }
            });
        }
    }

    private static void shutdownDone(ChannelFuture shutdownOutputFuture,
                                     ChannelFuture shutdownInputFuture,
                                     ChannelPromise promise) {
        Throwable shutdownOutputCause = shutdownOutputFuture.cause();
        Throwable shutdownInputCause = shutdownInputFuture.cause();
        if (shutdownOutputCause != null) {
            if (shutdownInputCause != null) {
                logger.debug("Exception suppressed because a previous exception occurred.",
                        shutdownInputCause);
            }
            promise.setFailure(shutdownOutputCause);
        } else if (shutdownInputCause != null) {
            promise.setFailure(shutdownInputCause);
        } else {
            promise.setSuccess();
        }
    }
    private void shutdownInput0(final ChannelPromise promise) {
        try {
            shutdownInput0();
            promise.setSuccess();
        } catch (Throwable t) {
            promise.setFailure(t);
        }
    }

    @SuppressJava6Requirement(reason = "Usage guarded by java version check")
    private void shutdownInput0() throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            javaChannel().shutdownInput();
        } else {
            javaChannel().socket().shutdownInput();
        }
    }

    @Override
    protected SocketAddress localAddress0() {
        return javaChannel().socket().getLocalSocketAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return javaChannel().socket().getRemoteSocketAddress();
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        doBind0(localAddress);
    }

    private void doBind0(SocketAddress localAddress) throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            SocketUtils.bind(javaChannel(), localAddress);
        } else {
            SocketUtils.bind(javaChannel().socket(), localAddress);
        }
    }

    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        if (localAddress != null) {
            doBind0(localAddress);
        }

        boolean success = false;
        try {
            boolean connected = SocketUtils.connect(javaChannel(), remoteAddress);
            if (!connected) {
                selectionKey().interestOps(SelectionKey.OP_CONNECT);
            }
            success = true;
            return connected;
        } finally {
            if (!success) {
                doClose();
            }
        }
    }

    @Override
    protected void doFinishConnect() throws Exception {
        if (!javaChannel().finishConnect()) {
            throw new Error();
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }



    @Override
    protected int doWriteBytes(ByteBuf buf) throws Exception {
        final int expectedWrittenBytes = buf.readableBytes();
        return buf.readBytes(javaChannel(), expectedWrittenBytes);
    }





    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioSocketChannelUnsafe();
    }

    private final class NioSocketChannelUnsafe extends NioByteUnsafe {
        @Override
        protected Executor prepareToClose() {
            try {
                if (javaChannel().isOpen() && config().getSoLinger() > 0) {
                    // 在设置SO_LINGER后，channel会延时关闭，在延时期间我们仍然可以进行读写，会导致eventLoop线程不断的循环浪费CPU资源，所以需要在延时关闭期间 将channel注册的事件全部取消。
                    doDeregister();
                    // 在设置了SO_LINGER，不管是阻塞socket还是非阻塞socket，在关闭的时候都会发生阻塞，所以这里不能使用Reactor线程来执行关闭任务，否则Reactor线程就会被阻塞。
                    return GlobalEventExecutor.INSTANCE;
                }
            } catch (Throwable ignore) {
            }
            // 在没有设置SO_LINGER的情况下，可以使用Reactor线程来执行关闭任务
            return null;
        }
    }

    /**
     * NioSocketChannel配置类
     */
    private final class NioSocketChannelConfig extends DefaultSocketChannelConfig {
        private volatile int maxBytesPerGatheringWrite = Integer.MAX_VALUE;
        private NioSocketChannelConfig(NioSocketChannel channel, Socket javaSocket) {
            super(channel, javaSocket);
            calculateMaxBytesPerGatheringWrite();
        }

        private void calculateMaxBytesPerGatheringWrite() {
            // 293976 = 146988 << 1
            // SO_SNDBUF设置的发送缓冲区大小 * 2 作为 最大写入字节数
            int newSendBufferSize = getSendBufferSize() << 1;
            if (newSendBufferSize > 0) {
                setMaxBytesPerGatheringWrite(newSendBufferSize);
            }
        }

        @Override
        protected void autoReadCleared() {
            clearReadPending();
        }

        @Override
        public NioSocketChannelConfig setSendBufferSize(int sendBufferSize) {
            super.setSendBufferSize(sendBufferSize);
            calculateMaxBytesPerGatheringWrite();
            return this;
        }

        @Override
        public <T> boolean setOption(ChannelOption<T> option, T value) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.setOption(jdkChannel(), (NioChannelOption<T>) option, value);
            }
            return super.setOption(option, value);
        }

        @Override
        public <T> T getOption(ChannelOption<T> option) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.getOption(jdkChannel(), (NioChannelOption<T>) option);
            }
            return super.getOption(option);
        }

        @Override
        public Map<ChannelOption<?>, Object> getOptions() {
            if (PlatformDependent.javaVersion() >= 7) {
                return getOptions(super.getOptions(), NioChannelOption.getOptions(jdkChannel()));
            }
            return super.getOptions();
        }

        void setMaxBytesPerGatheringWrite(int maxBytesPerGatheringWrite) {
            this.maxBytesPerGatheringWrite = maxBytesPerGatheringWrite;
        }

        int getMaxBytesPerGatheringWrite() {
            return maxBytesPerGatheringWrite;
        }

        private SocketChannel jdkChannel() {
            return ((NioSocketChannel) channel).javaChannel();
        }
    }
}
