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

import io.netty.util.IntSupplier;

/**
 * Select轮询策略接口
 */
public interface SelectStrategy {

    /**
     * 没有任何异步任务需要执行，Reactor线程可以安心的阻塞在Selector上等待IO就绪事件的来临
     */
    int SELECT = -1;
    /**
     * 重新开启一轮IO轮询
     */
    int CONTINUE = -2;
    /**
     * Reactor线程进行自旋轮询，由于NIO 不支持自旋操作，所以这里直接跳到SelectStrategy.SELECT策略
     */
    int BUSY_WAIT = -3;

    /**
     * 计算Select轮询策略
     */
    int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception;

}
