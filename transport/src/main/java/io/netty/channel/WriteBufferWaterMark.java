/*
 * Copyright 2016 The Netty Project
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

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

/**
 * WriteBufferWaterMark：ChannelOutboundBuffer中的高低水位线
 * WriteBufferWaterMark is used to set low water mark and high water mark for the write buffer.
 */
public final class WriteBufferWaterMark {

    /**
     * 高水位线设置的大小为 64 KB，低水位线设置的是 32 KB
     * 当每个 Channel 中的待发送数据如果超过 64 KB 时，则Channel 的状态就会变为不可写状态。
     * 当内存占用量低于 32 KB时，Channel 的状态会再次变为可写状态
     */
    private static final int DEFAULT_LOW_WATER_MARK = 32 * 1024;
    private static final int DEFAULT_HIGH_WATER_MARK = 64 * 1024;
    public static final WriteBufferWaterMark DEFAULT =
            new WriteBufferWaterMark(DEFAULT_LOW_WATER_MARK, DEFAULT_HIGH_WATER_MARK, false);
    /**
     * 高低水位
     */
    private final int low;
    private final int high;

    /**
     * 构造方法
     */
    public WriteBufferWaterMark(int low, int high) {
        this(low, high, true);
    }

    WriteBufferWaterMark(int low, int high, boolean validate) {
        if (validate) {
            checkPositiveOrZero(low, "low");
            if (high < low) {
                throw new IllegalArgumentException(
                        "write buffer's high water mark cannot be less than " +
                                " low water mark (" + low + "): " +
                                high);
            }
        }
        this.low = low;
        this.high = high;
    }

    public int low() {
        return low;
    }

    public int high() {
        return high;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(55)
                .append("WriteBufferWaterMark(low: ")
                .append(low)
                .append(", high: ")
                .append(high)
                .append(")");
        return builder.toString();
    }

}
