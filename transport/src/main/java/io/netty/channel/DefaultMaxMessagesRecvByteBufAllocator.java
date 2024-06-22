/*
 * Copyright 2015 The Netty Project
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

import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;

/**
 * Default implementation of {@link MaxMessagesRecvByteBufAllocator} which respects {@link ChannelConfig#isAutoRead()}
 * and also prevents overflow.
 */
public abstract class DefaultMaxMessagesRecvByteBufAllocator implements MaxMessagesRecvByteBufAllocator {
    private volatile int maxMessagesPerRead;
    private volatile boolean respectMaybeMoreData = true;

    /**
     * 构造方法
     */
    public DefaultMaxMessagesRecvByteBufAllocator() {
        this(1);
    }

    public DefaultMaxMessagesRecvByteBufAllocator(int maxMessagesPerRead) {
        maxMessagesPerRead(maxMessagesPerRead);
    }

    @Override
    public MaxMessagesRecvByteBufAllocator maxMessagesPerRead(int maxMessagesPerRead) {
        checkPositive(maxMessagesPerRead, "maxMessagesPerRead");
        this.maxMessagesPerRead = maxMessagesPerRead;
        return this;
    }

    @Override
    public int maxMessagesPerRead() {
        return maxMessagesPerRead;
    }

    public DefaultMaxMessagesRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        this.respectMaybeMoreData = respectMaybeMoreData;
        return this;
    }

    public final boolean respectMaybeMoreData() {
        return respectMaybeMoreData;
    }

    /**
     * Focuses on enforcing the maximum messages per read condition for {@link #continueReading()}.
     */
    public abstract class MaxMessageHandle implements ExtendedHandle {
        /**
         * Channel配置
         */
        private ChannelConfig config;
        /**
         * 每次事件轮询时的最大可以循环读取的次数：默认为16次，可在启动配置类ServerBootstrap中通过ChannelOption.MAX_MESSAGES_PER_READ选项设置
         */
        private int maxMessagePerRead;
        /**
         * 本次事件轮询总共读取的接收连接数量：每次read loop循环后会调用allocHandle.incMessagesRead增加记录接收到的连接个数
         */
        private int totalMessages;
        /**
         * 本次事件轮询中某一次读取的字节数
         * 1）lastBytesRead < 0：表示客户端主动发起了连接关闭流程，Netty开始连接关闭处理流程
         * 2）lastBytesRead = 0：表示当前NioSocketChannel上的数据已经全部读取完毕，没有数据可读了。本次OP_READ事件圆满处理完毕，可以开开心心的退出read loop
         * 3）lastBytesRead > 0：表示在本次read loop中从NioSocketChannel中读取到了数据，会在NioSocketChannel的pipeline中触发ChannelRead事件；
         * 进而在pipeline中负责IO处理的ChannelHandler中响应，处理网络请求
         */
        private int lastBytesRead;
        /**
         * 本次事件轮询总共读取的字节数：主要用于从reactor在接收客户端NioSocketChannel上的网络数据用的
         */
        private int totalBytesRead;
        /**
         * 本次事件轮询中某一次尝试读取的字节数：byteBuffer剩余可写的字节数，即当前ByteBuffer预计尝试要写入的字节数
         */
        private int attemptedBytesRead;
        /**
         * respectMaybeMoreData = true表示要对可能还有更多数据进行处理的这种情况要respect认真对待，
         * 如果本次循环读取到的数据已经装满ByteBuffer，表示后面可能还有数据，那么就要进行读取。
         * 如果ByteBuffer还没装满表示已经没有数据可读了那么就退出循环。
         * respectMaybeMoreData = false表示对可能还有更多数据的这种情况不认真对待 not respect。
         * 不管本次循环读取数据ByteBuffer是否满载而归，都要继续进行读取，直到读取不到数据在退出循环，属于无脑读取。
         */
        private final boolean respectMaybeMoreData = DefaultMaxMessagesRecvByteBufAllocator.this.respectMaybeMoreData;
        /**
         * 判断本次读取byteBuffer是否满载而归
         */
        private final UncheckedBooleanSupplier defaultMaybeMoreSupplier = new UncheckedBooleanSupplier() {
            @Override
            public boolean get() {
                // 判断本次读取byteBuffer是否满载而归，如果是表明可能NioSocketChannel里还有数据，否则表明NioSocketChannel里已经没有数据了
                return attemptedBytesRead == lastBytesRead;
            }
        };

        @Override
        public void reset(ChannelConfig config) {
            this.config = config;
            // 默认每次最多读取16次
            maxMessagePerRead = maxMessagesPerRead();
            totalMessages = totalBytesRead = 0;
        }

        @Override
        public final void incMessagesRead(int amt) {
            totalMessages += amt;
        }

        @Override
        public boolean continueReading() {
            return continueReading(defaultMaybeMoreSupplier);
        }

        @Override
        public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) {
            // !respectMaybeMoreData || maybeMoreDataSupplier.get() 这个条件比较复杂：其实就是通过respectMaybeMoreData字段来控制NioSocketChannel中可能还有数据可读的情况下该如何处理
            // totalMessages < maxMessagePerRead：在接收客户端连接场景中，用于判断主reactor线程在read loop中的读取次数是否超过了16次。如果超过16次就会返回false，主reactor线程退出循环。
            // totalBytesRead > 0：用于判断当客户端NioSocketChannel上的OP_READ事件活跃时，从reactor线程在read loop中是否读取到了网络数据。
            // totalBytesRead > 0：此处有bug，在4.1.69.final中被修复，Issue#11708：https://github.com/netty/netty/issues/11708
            return config.isAutoRead() &&
                    (!respectMaybeMoreData || maybeMoreDataSupplier.get()) &&
                    totalMessages < maxMessagePerRead &&
                    totalBytesRead > 0;
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            return alloc.ioBuffer(guess());
        }

        @Override
        public void lastBytesRead(int bytes) {
            lastBytesRead = bytes;
            if (bytes > 0) {
                totalBytesRead += bytes;
            }
        }

        @Override
        public final int lastBytesRead() {
            return lastBytesRead;
        }

        @Override
        public void readComplete() {
        }

        @Override
        public int attemptedBytesRead() {
            return attemptedBytesRead;
        }

        @Override
        public void attemptedBytesRead(int bytes) {
            attemptedBytesRead = bytes;
        }

        protected final int totalBytesRead() {
            return totalBytesRead < 0 ? Integer.MAX_VALUE : totalBytesRead;
        }
    }
}
