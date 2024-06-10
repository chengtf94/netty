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

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * AdaptiveRecvByteBufAllocator：为接收数据的ByteBuffer进行扩容缩容，根据Channel上每次到来的IO数据大小来自适应动态调整ByteBuffer的容量
 * 1）对于服务端NioServerSocketChannel来说，它上边的IO数据就是客户端的连接，它的长度和类型都是固定的，所以在接收客户端连接的时候并不需要这样的一个ByteBuffer来接收，我们会将接收到的客户端连接存放在List<Object> readBuf集合中
 * 2）对于客户端NioSocketChannel来说，它上边的IO数据时客户端发送来的网络数据，长度是不定的，所以才会需要这样一个可以根据每次IO数据的大小来自适应动态调整容量的ByteBuffer来接收。
 * 注意：
 * AdaptiveRecvByteBufAllocator并不会真正的去分配ByteBuffer，它只是负责动态调整分配ByteBuffer的大小。
 * 而真正具体执行内存分配动作的是这里的ByteBufAllocator类型为PooledByteBufAllocator。它会根据AdaptiveRecvByteBufAllocator动态调整出来的大小去真正的申请内存分配ByteBuffer。
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    /**
     * 扩容步长
     */
    private static final int INDEX_INCREMENT = 4;
    /**
     * 缩容步长
     */
    private static final int INDEX_DECREMENT = 1;
    /**
     * RecvBuf分配容量表（扩缩容索引表）：按照表中记录的容量大小进行扩缩容
     */
    private static final int[] SIZE_TABLE;
    static {
        // 初始化RecvBuf容量分配表：当分配容量小于512时，扩容单位为16递增
        List<Integer> sizeTable = new ArrayList<Integer>();
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }
        // 当分配容量大于512时，扩容单位为一倍
        for (int i = 512; i > 0; i <<= 1) { // lgtm[java/constant-comparison]
            sizeTable.add(i);
        }
        // 初始化RecbBuf扩缩容索引表
        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * ByteBuffer最小的容量、初始化容量、最大容量
     */
    static final int DEFAULT_MINIMUM = 64;
    // Use an initial value that is bigger than the common MTU of 1500
    static final int DEFAULT_INITIAL = 2048;
    static final int DEFAULT_MAXIMUM = 65536;
    private final int minIndex;
    private final int initial;
    private final int maxIndex;

    /**
     * 构造方法
     */
    public AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        // 计算minIndex、maxIndex
        // 在SIZE_TABLE中二分查找最小 >= minimum的容量索引 ：minIndex = 3
        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        // 在SIZE_TABLE中二分查找最大 <= maximum的容量索引 ：maxIndex = 38
        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    /**
     * 二分查找容量索引下标
     */
    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }
            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    /**
     * HandleImpl：专门用于统计read loop中接收客户端连接的次数，以及判断是否该结束read loop转去执行异步任务。
     */
    private final class HandleImpl extends MaxMessageHandle {
        /**
         * 最小容量在扩缩容索引表中的index
         */
        private final int minIndex;
        /**
         * 最大容量在扩缩容索引表中的index
         */
        private final int maxIndex;
        /**
         * 当前容量在扩缩容索引表中的index：初始为33，对应容量为2048
         */
        private int index;
        /**
         * 预计下一次分配buffer的容量：初始为2048
         */
        private int nextReceiveBufferSize;
        /**
         * 缩容标识
         */
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;
            // 在扩缩容索引表中二分查找到最小大于等于initial 的容量：初始为33，对应容量为2048
            index = getSizeTableIndex(initial);
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public void lastBytesRead(int bytes) {
            if (bytes == attemptedBytesRead()) {
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            // 预计下一次分配buffer的容量：初始为2048
            return nextReceiveBufferSize;
        }

        /**
         * 据在allocHandle中统计的在本轮read loop中读取字节总大小，来决定在下一轮read loop中是否对DirectByteBuffer进行扩容或者缩容
         */
        @Override
        public void readComplete() {
            // 是否对recvbuf进行扩容缩容
            record(totalBytesRead());
        }

        private void record(int actualReadBytes) {
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                if (decreaseNow) {
                    index = max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
